package org.ergoutree.compress.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import org.ergoutree.compress.domain.*
import kotlin.test.*

/** 验证导入草稿、冻结任务和关闭操作的契约，防止跨窗口意图破坏正在运行的任务。 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkspaceControllerTest {
    @Test fun importValidatesAutomaticallyAndDeduplicatesOverlappingSources() = runTest {
        val f = WorkspaceFixture(); val c = WorkspaceController(f, this)
        c.importPaths(listOf("/input/a.mp4")); c.initialize(); advanceUntilIdle()
        assertEquals(1, f.validations); assertTrue(c.state.value.canStart)
        c.importPaths(listOf("/input/a.mp4", "/input/b.mp4")); advanceUntilIdle()
        assertEquals(1, f.validations); assertEquals(2, c.state.value.rows.size); assertEquals(2000L, c.state.value.scannedBytes)
    }
    @Test fun runningSnapshotSurvivesNewImportsAndDraftEdits() = runTest {
        val f = WorkspaceFixture().apply { runGate = CompletableDeferred() }; val c = WorkspaceController(f, this)
        c.initialize(); c.importPaths(listOf("/input/a.mp4")); runCurrent()
        c.startTask(); runCurrent(); assertNotNull(c.state.value.active)
        c.importPaths(listOf("/input/b.mp4")); runCurrent(); c.editDraft { it.copy(quality = "30") }
        assertEquals("/input/b.mp4", c.state.value.rows.single().media.file.path.value)
        assertEquals(23, f.records.single().snapshot.plans.single().settings.quality)
        assertEquals(listOf("/input/a.mp4"), f.audit?.roots)
        c.startTask(); runCurrent(); assertEquals(2, f.records.size)
        f.runGate!!.complete(Unit); advanceUntilIdle(); assertEquals(listOf("job-0", "job-1"), f.executed)
        assertNull(c.state.value.active)
    }
    @Test fun failedDurableSubmissionKeepsDraftForRetry() = runTest {
        val f = WorkspaceFixture().apply { submitFailure = true }; val c = WorkspaceController(f, this)
        c.initialize(); c.importPaths(listOf("/input/a.mp4")); advanceUntilIdle(); c.startTask(); advanceUntilIdle()
        assertEquals(1, c.state.value.rows.size); assertTrue(f.executed.isEmpty()); assertEquals("数据库写入失败", c.state.value.error)
        f.submitFailure = false; c.startTask(); advanceUntilIdle(); assertEquals(1, f.records.size); assertTrue(c.state.value.rows.isEmpty())
    }
    @Test fun incompleteScanAndUnacknowledgedReplacementCannotStart() = runTest {
        val f = WorkspaceFixture().apply { complete = false }; val c = WorkspaceController(f, this)
        c.initialize(); c.importPaths(listOf("/input/a.mp4")); advanceUntilIdle(); c.startTask(); advanceUntilIdle()
        assertFalse(c.state.value.canStart); assertTrue(f.records.isEmpty())
        f.complete = true; c.rescan(); advanceUntilIdle(); c.editDraft { it.copy(mode = OutputMode.REPLACE) }; c.startTask(); advanceUntilIdle()
        assertTrue(f.records.isEmpty()); assertTrue(c.state.value.error!!.contains("确认"))
    }
    @Test fun closingWaitsForPersistentSubmissionAndDoesNotDispatchNewWork() = runTest {
        val f = WorkspaceFixture().apply { submitGate = CompletableDeferred() }; val c = WorkspaceController(f, this)
        c.initialize(); c.importPaths(listOf("/input/a.mp4")); runCurrent(); c.startTask(); runCurrent()
        c.requestClose(); runCurrent(); assertFalse(c.state.value.canExit); assertTrue(c.state.value.closing)
        f.submitGate!!.complete(Unit); advanceUntilIdle(); assertTrue(c.state.value.canExit); assertEquals(1, f.records.size); assertTrue(f.executed.isEmpty())
    }
    @Test fun savingDefaultsDoesNotReplaceAnExistingDraftOrFrozenJob() = runTest {
        val f = WorkspaceFixture(); val c = WorkspaceController(f, this)
        c.initialize(); c.importPaths(listOf("/input/a.mp4")); advanceUntilIdle()
        c.savePreferences(f.preferences.copy(defaults = EncodeSettings(quality = 31))); advanceUntilIdle()
        assertEquals("23", c.state.value.draft.quality); assertEquals(31, f.preferences.defaults.quality)
        c.startTask(); advanceUntilIdle(); assertEquals(23, f.records.single().snapshot.plans.single().settings.quality)
    }
    @Test fun maintenanceRetainsNewImportsAndDefersScanningUntilItFinishes() = runTest {
        val f = WorkspaceFixture().apply { storageGate = CompletableDeferred() }; val c = WorkspaceController(f, this)
        c.initialize(); runCurrent(); c.refreshStorage(); runCurrent()
        assertTrue(c.state.value.maintenanceBusy)
        c.importPaths(listOf("/input/a.mp4")); runCurrent(); assertEquals(0, f.scans)
        assertEquals(listOf("/input/a.mp4"), c.state.value.roots)
        f.storageGate!!.complete(Unit); advanceUntilIdle()
        assertEquals(1, f.scans); assertTrue(c.state.value.scanComplete)
    }
}
