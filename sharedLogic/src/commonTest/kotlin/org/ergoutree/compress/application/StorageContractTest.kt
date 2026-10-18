package org.ergoutree.compress.application

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*
import org.ergoutree.compress.testing.samplePlan
import kotlin.test.*

/** 覆盖维护互斥、低空间暂停和脱敏的跨平台契约。 */
class StorageContractTest {
    @Test fun maintenanceRefusesActiveWorkAndCancellationReleasesGate() = runTest {
        val gate = MaintenanceGate()
        val entered = CompletableDeferred<Unit>()
        val worker = launch { gate.work { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        assertFailsWith<IllegalStateException> { gate.exclusive {} }
        worker.cancelAndJoin()
        gate.exclusive { assertFailsWith<IllegalStateException> { gate.work {} } }
        gate.work {}
        gate.exclusive { gate.seal() }
        assertFailsWith<IllegalStateException> { gate.work {} }
    }
    @Test fun lowSpaceBeforeEncodingPersistsPauseWithoutStartingEncoder() = runTest {
        var started = false; var paused = false
        val stop = StopSignal()
        val admission = object : SpaceAdmission {
            override suspend fun requirements(plan: ResolvedPlan) = listOf(SpaceRequirement(LocalPath("/scratch"), 1000))
            override suspend fun enough(requirements: List<SpaceRequirement>) = false
        }
        val executor = SpaceAwareExecutor(ItemExecutor { _, _, _, _ -> started = true; error("不应执行") }, admission)
        val lifecycle = object : AttemptLifecycle { override suspend fun resourcePause(reason: String) { assertFalse(stop.requested); paused = true } }
        val result = executor.execute(samplePlan(), stop, lifecycle) {}
        assertTrue(paused); assertFalse(started); assertTrue(stop.requested)
        assertEquals(ErrorCode.INSUFFICIENT_SPACE, result.error)
    }
    @Test fun runningLowSpacePreservesCandidateAndStopsFurtherDispatch() = runTest {
        var checks = 0; var paused = false
        val admission = object : SpaceAdmission {
            override suspend fun requirements(plan: ResolvedPlan) = listOf(SpaceRequirement(LocalPath("/scratch"), 1000))
            override suspend fun enough(requirements: List<SpaceRequirement>): Boolean { checks++; return checks == 1 }
        }
        val executor = SpaceAwareExecutor(ItemExecutor { _, stop, _, _ -> delay(30); assertTrue(stop.requested); AttemptResult(AttemptStage.INTERRUPTED, message = "停止") }, admission, 10)
        val result = executor.execute(samplePlan(), StopSignal(), object : AttemptLifecycle { override suspend fun resourcePause(reason: String) { paused = true } }) {}
        assertTrue(paused); assertEquals(ErrorCode.INSUFFICIENT_SPACE, result.error)
    }
    @Test fun redactorHidesPathsNamesCredentialsAndAddresses() {
        val redactor = DiagnosticRedactor(listOf("机密.mp4"))
        for (line in listOf("打开 D:\\用户\\私密 目录\\机密.mp4", "打开 \\\\nas\\共享\\家庭\\机密.mp4", "打开 /home/alice/private/机密.mp4", "https://host/private?token=abc", "user@example.com", "Authorization: Bearer abc-secret", "title : 用户备注")) {
            val safe = redactor.redact(line)
            for (value in listOf("用户", "私密", "共享", "家庭", "alice", "private", "机密.mp4", "abc-secret", "example.com")) assertFalse(safe.contains(value), safe)
        }
        assertEquals("frame=42 speed=1.2x", redactor.redact("frame=42 speed=1.2x"))
    }
}
