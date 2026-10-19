package org.ergoutree.compress.infrastructure.process

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.infrastructure.files.nio
import org.ergoutree.compress.ports.*
import java.io.*
import java.nio.file.Files
import java.util.concurrent.TimeUnit

interface ManagedProcess : AutoCloseable {
    val stdout: InputStream
    val stderr: InputStream
    fun quit()
    fun alive(): Boolean
    fun exitCode(): Int
    fun terminate()
}
fun interface ProcessLauncher { fun launch(request: ProcessRequest): ManagedProcess }

class PortableProcessLauncher : ProcessLauncher {
    override fun launch(request: ProcessRequest): ManagedProcess {
        val builder = ProcessBuilder(request.argv).directory(request.directory.nio().toFile())
        builder.environment()["TEMP"] = request.directory.value
        builder.environment()["TMP"] = request.directory.value
        val p = builder.start()
        return object : ManagedProcess {
            override val stdout = p.inputStream
            override val stderr = p.errorStream
            override fun quit() { p.outputStream.write("q\n".toByteArray()); p.outputStream.flush() }
            override fun alive() = p.isAlive
            override fun exitCode() = p.exitValue()
            override fun terminate() { p.descendants().forEach { it.destroyForcibly() }; p.destroyForcibly(); p.waitFor(5, TimeUnit.SECONDS) }
            override fun close() { if (p.isAlive) terminate(); stdout.close(); stderr.close(); p.outputStream.close() }
        }
    }
}

/** 并发排空子进程管道并记录有界诊断，活动日志定期刷新供桌面增量读取。 */
class JvmProcessRunner(private val launcher: ProcessLauncher = PortableProcessLauncher()) : ProcessRunner {
    override suspend fun run(request: ProcessRequest, stop: StopSignal, onStdout: (String) -> Unit): ProcessResult = withContext(Dispatchers.IO) {
        require(request.argv.isNotEmpty() && request.timeoutMillis > 0 && request.captureLimit >= 0)
        stop.checkRunning()
        request.logPrefix?.let { Files.writeString(java.nio.file.Path.of(it.value + ".argv.json"), SnapshotJson.encodeToString(request.argv)) }
        val process = launcher.launch(request)
        try {
            coroutineScope {
                val stdout = async(Dispatchers.IO) {
                    try { drain(process.stdout, request.captureLimit, request.logPrefix?.value?.plus(".stdout.log"), onStdout, false) }
                    catch (e: Exception) { process.terminate(); throw e }
                }
                val stderr = async(Dispatchers.IO) {
                    try { drain(process.stderr, 16_384, request.logPrefix?.value?.plus(".stderr.log"), {}, true) }
                    catch (e: Exception) { process.terminate(); throw e }
                }
                val start = System.nanoTime()
                var stopped = false
                var timedOut = false
                var quitAt: Long? = null
                try { while (process.alive()) {
                    ensureActive()
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    if (stop.requested && !stopped) {
                        stopped = true
                        quitAt = elapsed
                        if (request.allowQuit) runCatching { process.quit() } else process.terminate()
                    }
                    if (elapsed >= request.timeoutMillis) { timedOut = true; process.terminate() }
                    if (quitAt != null && elapsed - quitAt >= 2_000) process.terminate()
                    delay(25)
                } } finally { process.terminate() } // Close descendants' inherited pipes before awaiting EOF.
                val out = stdout.await()
                val err = stderr.await()
                val result = ProcessResult(process.exitCode(), out.first, err.first, stopped || stop.requested, timedOut, out.second)
                request.logPrefix?.let {
                    Files.writeString(java.nio.file.Path.of(it.value + ".process.json"), SnapshotJson.encodeToString(mapOf(
                        "exitCode" to result.exitCode.toString(), "stopped" to result.stopped.toString(), "timedOut" to result.timedOut.toString())))
                }
                result
            }
        } finally {
            // Synchronous native termination also unblocks both readers on cancellation/reader failure.
            process.close()
        }
    }

    private fun drain(input: InputStream, limit: Int, log: String?, onLine: (String) -> Unit, tail: Boolean): Pair<String, Boolean> {
        val captured = StringBuilder()
        var truncated = false
        var logged = 0L
        var flushedAt = System.nanoTime()
        val writer = log?.let { Files.newBufferedWriter(java.nio.file.Path.of(it)) }
        try {
            val line = StringBuilder()
            input.reader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                fun consume() {
                    val text = line.toString().trimEnd('\r')
                    onLine(text)
                    if (captured.length + text.length + 1 > limit) truncated = true
                    if (tail) {
                        captured.append(text).append('\n')
                        if (captured.length > limit) captured.delete(0, captured.length - limit)
                    } else if (captured.length < limit) captured.append((text + "\n").take(limit - captured.length))
                    // Cap disk diagnostics per process. Draining continues beyond the cap.
                    if (logged < 16L * 1024 * 1024) { writer?.append(text)?.append('\n'); logged += text.length + 1 }
                    // 定期刷新活动日志，使独立日志窗口无需等待编码结束。
                    if (System.nanoTime() - flushedAt >= 200_000_000L) { writer?.flush(); flushedAt = System.nanoTime() }
                    line.clear()
                }
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    for (i in 0 until n) {
                        val c = buffer[i]
                        if (c == '\n') consume() else {
                            if (line.length >= 256 * 1024) throw IOException("子进程单行超过 256 KiB 限制")
                            line.append(c)
                        }
                    }
                }
                if (line.isNotEmpty()) consume()
            }
        } finally { writer?.close() }
        return captured.toString() to truncated
    }
}
