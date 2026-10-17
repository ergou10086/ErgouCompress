package org.ergoutree.compress.platform.windows

import com.sun.jna.*
import com.sun.jna.platform.win32.*
import com.sun.jna.platform.win32.WinNT.HANDLE
import com.sun.jna.platform.win32.WinNT.HANDLEByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.win32.StdCallLibrary
import org.ergoutree.compress.infrastructure.process.*
import org.ergoutree.compress.ports.ProcessRequest
import java.io.IOException
import java.io.InputStream

object WindowsArgv {
    // Windows CRT argv quoting. Even arguments without spaces are quoted for one consistent rule.
    fun quote(value: String): String {
        require('\u0000' !in value)
        val result = StringBuilder("\"")
        var slashes = 0
        value.forEach { c ->
            when (c) {
                '\\' -> slashes++
                '"' -> { result.append("\\".repeat(slashes * 2 + 1)).append(c); slashes = 0 }
                else -> { result.append("\\".repeat(slashes)).append(c); slashes = 0 }
            }
        }
        return result.append("\\".repeat(slashes * 2)).append('"').toString()
    }
}

private interface JobApi : StdCallLibrary {
    fun CreateJobObjectW(attributes: Pointer?, name: WString?): HANDLE?
    fun SetInformationJobObject(job: HANDLE, infoClass: Int, info: Pointer, length: Int): Boolean
    fun TerminateJobObject(job: HANDLE, exitCode: Int): Boolean
    fun InitializeProcThreadAttributeList(list: Pointer?, count: Int, flags: Int, size: LongByReference): Boolean
    fun UpdateProcThreadAttribute(list: Pointer, flags: Int, attribute: BaseTSD.ULONG_PTR, value: Pointer, size: BaseTSD.SIZE_T, previous: Pointer?, returned: Pointer?): Boolean
    fun DeleteProcThreadAttributeList(list: Pointer)
    fun CreateProcessW(application: WString, command: Pointer, processAttributes: Pointer?, threadAttributes: Pointer?, inherit: Boolean, flags: Int, environment: Pointer?, directory: WString, startup: Pointer, result: WinBase.PROCESS_INFORMATION): Boolean
}

@Structure.FieldOrder("startup", "attributes")
class StartupInfoEx : Structure() {
    @JvmField var startup = WinBase.STARTUPINFO()
    @JvmField var attributes: Pointer? = null
}

/** Windows 11 x64: the child is assigned to KILL_ON_JOB_CLOSE at creation, before any code runs. */
class WindowsProcessLauncher : ProcessLauncher {
    private val kernel = Kernel32.INSTANCE
    private val api = Native.load("kernel32", JobApi::class.java)

    override fun launch(request: ProcessRequest): ManagedProcess {
        check(Native.POINTER_SIZE == 8) { "当前仅支持 Windows x64" }
        val owned = mutableListOf<HANDLE>()
        var attrs: Memory? = null
        var initialized = false
        fun check(ok: Boolean) { if (!ok) throw IOException("Win32 进程操作失败，错误码 ${kernel.GetLastError()}") }
        fun pipe(parentReads: Boolean): Pair<HANDLE, HANDLE> {
            val read = HANDLEByReference()
            val write = HANDLEByReference()
            val security = WinBase.SECURITY_ATTRIBUTES().apply { bInheritHandle = true; dwLength = WinDef.DWORD(size().toLong()) }
            check(kernel.CreatePipe(read, write, security, 0))
            owned += read.value; owned += write.value
            val parent = if (parentReads) read.value else write.value
            val child = if (parentReads) write.value else read.value
            check(kernel.SetHandleInformation(parent, 1, 0))
            return parent to child
        }
        try {
            val job = api.CreateJobObjectW(null, null) ?: throw IOException("无法创建进程 Job")
            owned += job
            // Win64 JOBOBJECT_EXTENDED_LIMIT_INFORMATION: 64-byte BASIC_LIMIT + 48-byte
            // IO_COUNTERS + four SIZE_T fields. LimitFlags is DWORD at offset 16.
            val limits = Memory(144).apply { clear(); setInt(16, 0x2000) }
            check(api.SetInformationJobObject(job, 9, limits, 144))
            val (stdout, childOut) = pipe(true)
            val (stderr, childErr) = pipe(true)
            val (stdin, childIn) = pipe(false)
            val size = LongByReference()
            api.InitializeProcThreadAttributeList(null, 2, 0, size)
            attrs = Memory(size.value)
            check(api.InitializeProcThreadAttributeList(attrs, 2, 0, size))
            initialized = true
            val jobs = Memory(8).apply { setPointer(0, job.pointer) }
            check(api.UpdateProcThreadAttribute(attrs, 0, BaseTSD.ULONG_PTR(0x2000D), jobs, BaseTSD.SIZE_T(8), null, null))
            val handles = Memory(24).apply { setPointer(0, childIn.pointer); setPointer(8, childOut.pointer); setPointer(16, childErr.pointer) }
            check(api.UpdateProcThreadAttribute(attrs, 0, BaseTSD.ULONG_PTR(0x20002), handles, BaseTSD.SIZE_T(24), null, null))
            val startup = StartupInfoEx().apply {
                this.attributes = attrs
                this.startup.cb = WinDef.DWORD(size().toLong())
                this.startup.dwFlags = 0x100 // STARTF_USESTDHANDLES
                this.startup.hStdInput = childIn; this.startup.hStdOutput = childOut; this.startup.hStdError = childErr
                write()
            }
            val commandText = request.argv.joinToString(" ") { WindowsArgv.quote(it) }
            val command = Memory((commandText.length + 1L) * 2).apply { setWideString(0, commandText) }
            val envText = (System.getenv() + mapOf("TEMP" to request.directory.value, "TMP" to request.directory.value))
                .toSortedMap(String.CASE_INSENSITIVE_ORDER).entries.joinToString("\u0000") { "${it.key}=${it.value}" } + "\u0000\u0000"
            val environment = Memory(envText.length * 2L).apply { write(0, envText.toCharArray(), 0, envText.length) }
            val info = WinBase.PROCESS_INFORMATION()
            check(api.CreateProcessW(WString(request.argv.first()), command, null, null, true,
                0x08000000 or 0x00080000 or 0x00000400, environment, WString(request.directory.value), startup.pointer, info))
            java.lang.ref.Reference.reachabilityFence(jobs)
            java.lang.ref.Reference.reachabilityFence(handles)
            info.read()
            owned += info.hProcess
            kernel.CloseHandle(info.hThread)
            listOf(childIn, childOut, childErr).forEach { kernel.CloseHandle(it); owned.remove(it) }
            return NativeChild(kernel, api, job, info.hProcess, stdin, stdout, stderr).also { owned.clear() }
        } finally {
            if (initialized) api.DeleteProcThreadAttributeList(attrs!!)
            attrs?.close()
            owned.asReversed().forEach { kernel.CloseHandle(it) }
        }
    }
}

private class NativeChild(
    private val kernel: Kernel32, private val api: JobApi,
    private val job: HANDLE, private val process: HANDLE, private val stdin: HANDLE,
    private val out: HANDLE, private val err: HANDLE,
) : ManagedProcess {
    override val stdout = pipeStream(out)
    override val stderr = pipeStream(err)
    private var closed = false
    private fun pipeStream(handle: HANDLE) = object : InputStream() {
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 255 }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val buffer = ByteArray(minOf(len, 32 * 1024))
            val read = IntByReference()
            if (!kernel.ReadFile(handle, buffer, buffer.size, read, null)) {
                val error = kernel.GetLastError()
                if (error == 109 || error == 232) return -1
                throw IOException("读取进程管道失败：$error")
            }
            if (read.value == 0) return -1
            buffer.copyInto(b, off, 0, read.value)
            return read.value
        }
    }
    override fun quit() {
        val bytes = "q\n".toByteArray()
        if (!kernel.WriteFile(stdin, bytes, bytes.size, IntByReference(), null)) throw IOException("发送停止失败：${kernel.GetLastError()}")
    }
    override fun alive(): Boolean = kernel.WaitForSingleObject(process, 0) == 258
    override fun exitCode(): Int {
        val value = IntByReference()
        if (!kernel.GetExitCodeProcess(process, value)) throw IOException("读取进程退出码失败")
        return value.value
    }
    override fun terminate() { api.TerminateJobObject(job, 130); kernel.WaitForSingleObject(process, 5000) }
    override fun close() {
        if (closed) return
        closed = true
        terminate() // Also kills any descendants that outlived the main process.
        listOf(job, process, stdin, out, err).forEach { kernel.CloseHandle(it) }
    }
}
