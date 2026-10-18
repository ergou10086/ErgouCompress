package org.ergoutree.compress.platform.windows

import com.sun.jna.*
import com.sun.jna.platform.win32.*
import com.sun.jna.ptr.*
import com.sun.jna.win32.StdCallLibrary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.ports.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

private interface FileApi : StdCallLibrary {
    fun ReplaceFileW(source: WString, candidate: WString, backup: WString, flags: Int, exclude: Pointer?, reserved: Pointer?): Boolean
    fun MoveFileExW(source: WString, target: WString, flags: Int): Boolean
    fun FindFirstStreamW(path: WString, level: Int, data: Pointer, flags: Int): WinNT.HANDLE
    fun FindNextStreamW(handle: WinNT.HANDLE, data: Pointer): Boolean
}
private interface SecurityApi : StdCallLibrary {
    fun GetFileSecurityW(path: WString, info: Int, descriptor: Pointer?, length: Int, required: IntByReference): Boolean
    fun SetFileSecurityW(path: WString, info: Int, descriptor: Pointer): Boolean
    fun GetSecurityDescriptorDacl(descriptor: Pointer, present: IntByReference, acl: PointerByReference, defaulted: IntByReference): Boolean
    fun GetSecurityDescriptorControl(descriptor: Pointer, control: ShortByReference, revision: IntByReference): Boolean
}
@Structure.FieldOrder("size", "name")
class NamedStreamData : Structure() { @JvmField var size: Long = 0; @JvmField var name: CharArray = CharArray(296) }

class WindowsCommitFiles(private val archive: LocalPath, private val environment: PlanEnvironment) : CommitFiles {
    private val kernel = Kernel32.INSTANCE
    private val api = Native.load("kernel32", FileApi::class.java)
    private val security = Native.load("advapi32", SecurityApi::class.java)
    private fun win(path: LocalPath) = WString(WindowsFileIdentity.extendedPath(path.nio()))
    private fun check(ok: Boolean) { if (!ok) throw java.io.IOException("Win32 文件操作失败：${kernel.GetLastError()}") }
    private fun checkPath(path: Path) {
        generateSequence(path.toAbsolutePath().normalize()) { it.parent }.forEach { current ->
            val a = kernel.GetFileAttributes(WindowsFileIdentity.extendedPath(current))
            if (a != -1) require(a and 0x400 == 0) { "工作路径不能包含重解析点：$current" }
        }
    }
    override suspend fun volume(path: LocalPath): VolumeInfo = withContext(Dispatchers.IO) {
        checkPath(path.nio())
        val root = CharArray(32768)
        check(kernel.GetVolumePathName(WindowsFileIdentity.extendedPath(path.nio()), root, root.size))
        val name = Native.toString(root)
        val guid = CharArray(1024); check(kernel.GetVolumeNameForVolumeMountPoint(name, guid, guid.size))
        val fs = CharArray(128); val serial = IntByReference()
        check(kernel.GetVolumeInformation(name, null, 0, serial, IntByReference(), IntByReference(), fs, fs.size))
        VolumeInfo(Native.toString(guid) + ":" + Integer.toUnsignedString(serial.value, 16), LocalPath(name), Native.toString(fs), kernel.GetDriveType(name) in listOf(2, 3))
    }
    override suspend fun evidence(path: LocalPath): ContentEvidence? = withContext(Dispatchers.IO) {
        if (Files.notExists(path.nio(), LinkOption.NOFOLLOW_LINKS)) null else environment.evidence(path)
    }
    override suspend fun copy(source: LocalPath, destination: LocalPath, stop: StopSignal): Unit = withContext(Dispatchers.IO) {
        checkPath(destination.nio())
        Files.newInputStream(source.nio(), LinkOption.NOFOLLOW_LINKS).use { input ->
            Files.newOutputStream(destination.nio(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                val buffer = ByteArray(128 * 1024)
                while (true) { stop.checkRunning(); val n = input.read(buffer); if (n < 0) break; output.write(buffer, 0, n) }
            }
        }
        FileChannel.open(destination.nio(), StandardOpenOption.WRITE).use { it.force(true) }
    }
    override suspend fun prepareDirectory(root: LocalPath, id: String): LocalPath = withContext(Dispatchers.IO) {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        checkPath(root.nio())
        val parent = Files.createDirectories(root.nio().resolve("transactions"))
        checkPath(parent)
        Files.createDirectory(parent.resolve(id)).local()
    }
    private fun daclFacts(bytes: ByteArray): Pair<String, Boolean> {
        val buffer = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        val acl = PointerByReference(); val control = ShortByReference()
        check(security.GetSecurityDescriptorDacl(buffer, IntByReference(), acl, IntByReference()))
        check(security.GetSecurityDescriptorControl(buffer, control, IntByReference()))
        val protected = control.value.toInt() and 0x1000 != 0
        val raw = acl.value?.let { it.getByteArray(0, it.getShort(2).toInt() and 0xffff) } ?: byteArrayOf()
        val hash = MessageDigest.getInstance("SHA-256").digest(raw + byteArrayOf(if (protected) 1 else 0)).hex()
        return hash to protected
    }
    private fun streamNames(path: LocalPath): List<String> {
        val data = NamedStreamData()
        val handle = api.FindFirstStreamW(win(path), 0, data.pointer, 0)
        if (handle == WinBase.INVALID_HANDLE_VALUE) {
            val error = kernel.GetLastError(); if (error == 38 || error == 2) return emptyList()
            throw java.io.IOException("枚举 ADS 失败：$error")
        }
        try {
            val names = mutableListOf<String>()
            do {
                data.read(); val name = Native.toString(data.name)
                if (name != "::\$DATA") names += name.removeSuffix(":\$DATA")
                require(names.size < 4096) { "命名流过多，需要人工审核" }
            } while (api.FindNextStreamW(handle, data.pointer))
            check(kernel.GetLastError() == 38)
            return names
        } finally { kernel.FindClose(handle) }
    }
    private fun adsHash(path: String): String = WindowsDataStreams.input(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        digest.digest().hex()
    }
    override suspend fun metadata(path: LocalPath): FileMetadata = withContext(Dispatchers.IO) {
        val size = IntByReference(); security.GetFileSecurityW(win(path), 4, null, 0, size)
        require(size.value in 1..1_048_576)
        val buffer = Memory(size.value.toLong()); check(security.GetFileSecurityW(win(path), 4, buffer, size.value, size))
        val bytes = buffer.getByteArray(0, size.value); val facts = daclFacts(bytes)
        val attr = Files.readAttributes(path.nio(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val attributes = kernel.GetFileAttributes(WindowsFileIdentity.extendedPath(path.nio()))
        require(attributes != -1 && attributes and (0x4000 or 0x800 or 0x200) == 0) { "加密/压缩/稀疏文件需要独立策略验收" }
        FileMetadata(attr.lastModifiedTime().toInstant().toString(), attr.creationTime().toInstant().toString(), Base64.getEncoder().encodeToString(bytes), facts.first, facts.second,
            streamNames(path).associateWith { adsHash(WindowsFileIdentity.extendedPath(path.nio()) + it) })
    }
    private fun applyDacl(path: LocalPath, metadata: FileMetadata) {
        val bytes = Base64.getDecoder().decode(metadata.dacl); val buffer = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
        check(security.SetFileSecurityW(win(path), 4 or if (metadata.protectedDacl) Int.MIN_VALUE else 0x20000000, buffer))
    }
    override suspend fun prepareMetadata(source: LocalPath, staged: LocalPath, metadata: FileMetadata): Unit = withContext(Dispatchers.IO) {
        applyDacl(staged, metadata)
        metadata.adsHashes.forEach { (name, expected) ->
            WindowsDataStreams.input(WindowsFileIdentity.extendedPath(source.nio()) + name).use { input ->
                WindowsDataStreams.create(WindowsFileIdentity.extendedPath(staged.nio()) + name).use { input.copyTo(it) }
            }
            require(adsHash(WindowsFileIdentity.extendedPath(staged.nio()) + name) == expected)
        }
    }
    override suspend fun applyMetadata(target: LocalPath, metadata: FileMetadata, preserveTime: Boolean): Unit = withContext(Dispatchers.IO) {
        applyDacl(target, metadata)
        if (preserveTime) Files.setLastModifiedTime(target.nio(), FileTime.from(Instant.parse(metadata.modifiedTime)))
        Files.setAttribute(target.nio(), "basic:creationTime", FileTime.from(Instant.parse(metadata.createdTime)), LinkOption.NOFOLLOW_LINKS)
        val after = this@WindowsCommitFiles.metadata(target)
        require(after.daclHash == metadata.daclHash && after.protectedDacl == metadata.protectedDacl && after.createdTime == metadata.createdTime)
        if (preserveTime) require(after.modifiedTime == metadata.modifiedTime)
        require(metadata.adsHashes.all { (key, value) -> after.adsHashes[key] == value }) { "目标 ADS 与冻结策略不符" }
    }
    override suspend fun replace(source: LocalPath, staged: LocalPath, backup: LocalPath) = withContext(Dispatchers.IO) {
        val ok = api.ReplaceFileW(win(source), win(staged), win(backup), 0, null, null); NativeResult(ok, if (ok) 0 else kernel.GetLastError())
    }
    override suspend fun publishNew(staged: LocalPath, target: LocalPath): NativeResult = withContext(Dispatchers.IO) {
        try { Files.createLink(target.nio(), staged.nio()); NativeResult(true) }
        catch (e: FileAlreadyExistsException) { NativeResult(false, 183) }
        catch (e: java.io.IOException) { NativeResult(false, 5) }
    }
    override suspend fun retire(source: LocalPath, backup: LocalPath) = withContext(Dispatchers.IO) {
        val ok = api.MoveFileExW(win(source), win(backup), 8); NativeResult(ok, if (ok) 0 else kernel.GetLastError())
    }
    override suspend fun mirror(record: CommitRecord): Unit = withContext(Dispatchers.IO) {
        val folder = Files.createDirectories(archive.nio().resolve("commits"))
        val target = folder.resolve("${record.id}.json"); val temporary = folder.resolve("${record.id}.writing")
        FileChannel.open(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(SnapshotJson.encodeToString(record).toByteArray())
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
