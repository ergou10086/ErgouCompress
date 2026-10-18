package org.ergoutree.compress

import kotlinx.coroutines.runBlocking
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.ports.*
import org.junit.Assume
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*

object CommitCrashChild {
    @JvmStatic fun main(args: Array<String>): Unit = runBlocking {
        CommitFixture(Path.of(args[0])).use { f ->
            val input = f.input()
            val coordinator = f.coordinator(checkpoint = CommitCheckpoint { point, _ -> if (point == args[1]) Runtime.getRuntime().halt(83) })
            val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal())
            f.publish(coordinator, record, input.claim)
        }
        error("Checkpoint not reached")
    }
}
class CommitCrashTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun eightRealOwnerCrashBoundariesRecoverWithoutReencodingOrLosingOriginal(): Unit = runBlocking {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val classpath = System.getProperty("ergou.test.runtimeClasspath")
        require(classpath.isNotBlank())
        val executable = Path.of(System.getProperty("java.home"), "bin/java.exe").toString()
        for (point in listOf("AfterPREPAREDDb", "AfterPREPAREDMirror", "AfterPUBLISH_INTENTDb", "AfterPUBLISH_INTENTMirror", "BeforeNative", "AfterNative", "AfterPUBLISHEDDb", "AfterCOMMITTEDDb")) {
            val root = temporary.newFolder(point).toPath()
            val process = ProcessBuilder(executable, "-cp", classpath, CommitCrashChild::class.java.name, root.toString(), point).redirectErrorStream(true).start()
            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "checkpoint $point timed out")
                val diagnostic = process.inputStream.bufferedReader().readText()
                assertEquals(83, process.exitValue(), "$point: $diagnostic")
                CommitFixture(root).use { f ->
                    val before = f.store.commits().single()
                    f.coordinator().recoverTransactions()
                    val after = f.store.commit(before.id)
                    assertEquals(CommitPhase.COMMITTED, after.phase, point)
                    assertEquals(before.candidate.sha256, f.environment.evidence(after.plan.destination).sha256, point)
                    assertEquals(before.source.sha256, f.environment.evidence(after.backup!!).sha256, point)
                    assertEquals(ItemState.SUCCEEDED, f.store.items("fixture").single().state, point)
                }
            } finally { if (process.isAlive) process.destroyForcibly() }
        }
    }
}
