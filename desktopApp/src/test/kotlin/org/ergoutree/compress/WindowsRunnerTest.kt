package org.ergoutree.compress

import kotlinx.coroutines.*
import org.ergoutree.compress.infrastructure.files.local
import org.ergoutree.compress.infrastructure.process.JvmProcessRunner
import org.ergoutree.compress.platform.windows.WindowsProcessLauncher
import org.ergoutree.compress.ports.*
import org.junit.Rule
import org.junit.Before
import org.junit.Assume
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class WindowsRunnerTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun windowsOnly() { Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows")) }
    private fun runner() = JvmProcessRunner(WindowsProcessLauncher())
    @Test fun independentPipesDrainUnderLoadAndCaptureStaysBounded() = runBlocking {
        val result = runner().run(ProcessRequest(ProcessChild.javaCommand("flood"), temporary.newFolder("中文 工作区").toPath().local(), captureLimit = 1000))
        assertEquals(0, result.exitCode); assertEquals(1000, result.stdout.length)
        assertTrue(result.stdoutTruncated); assertTrue(result.stderrTail.contains("err-19999")); assertTrue(result.stderrTail.length <= 16384)
    }
    @Test fun unicodeQuotesEmptyArgumentsAndTrailingBackslashesRoundTrip() = runBlocking {
        val values = listOf("", "中文 空格", "a\"b", "C:\\path with spaces\\", "\\\\", "$(literal)", "plain")
        val result = runner().run(ProcessRequest(ProcessChild.javaCommand("argv", *values.toTypedArray()), temporary.newFolder().toPath().local()))
        assertEquals(0, result.exitCode)
        assertEquals(values, result.stdout.lines().filter { it.startsWith("ARG:") }.map { String(java.util.Base64.getDecoder().decode(it.removePrefix("ARG:")), Charsets.UTF_8) })
    }
    @Test fun qReturningZeroIsStillStopped() = runBlocking {
        val stop = StopSignal()
        val request = ProcessRequest(ProcessChild.javaCommand("quit"), temporary.newFolder().toPath().local(), allowQuit = true)
        val task = async { runner().run(request, stop) { if (it == "ready") launch { stop.request() } } }
        val result = withTimeout(15_000) { task.await() }
        assertEquals(0, result.exitCode); assertTrue(result.stopped)
    }
    @Test fun timeoutKillsChildAndItsDescendant() = runBlocking {
        var pid: Long? = null
        val result = runner().run(ProcessRequest(ProcessChild.javaCommand("spawn"), temporary.newFolder().toPath().local(), timeoutMillis = 2000)) { pid = it.toLongOrNull() }
        assertTrue(result.timedOut)
        val child = assertNotNull(pid)
        withTimeout(10_000) { while (ProcessHandle.of(child).map { it.isAlive }.orElse(false)) delay(25) }
    }
    @Test fun cancellationDoesNotHangReaders() = runBlocking {
        withTimeout(10_000) {
            val task = launch { runner().run(ProcessRequest(ProcessChild.javaCommand("sleep"), temporary.newFolder().toPath().local())) }
            delay(500); task.cancelAndJoin()
        }
    }
    @Test fun ownerProcessCrashClosesJobAndKillsNativeChild() = runBlocking {
        val base = ProcessChild.javaCommand()
        val classes = listOf(NativeJobCrashChild::class.java, WindowsProcessLauncher::class.java, ProcessRequest::class.java,
            com.sun.jna.Native::class.java, com.sun.jna.platform.win32.Kernel32::class.java, kotlin.Unit::class.java)
        val classpath = classes.map { java.nio.file.Path.of(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct().joinToString(java.io.File.pathSeparator)
        val owner = ProcessBuilder(listOf(base.first(), "-cp", classpath, NativeJobCrashChild::class.java.name, temporary.newFolder().absolutePath)).redirectErrorStream(true).start()
        try {
            val text = withContext(Dispatchers.IO) { owner.inputStream.bufferedReader().readText() }
            assertTrue(owner.waitFor(15, java.util.concurrent.TimeUnit.SECONDS), text)
            assertEquals(83, owner.exitValue(), text)
            val pid = text.lineSequence().mapNotNull { it.toLongOrNull() }.first()
            withTimeout(10_000) { while (ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) delay(25) }
        } finally { if (owner.isAlive) owner.destroyForcibly() }
    }
}
