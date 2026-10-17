package org.ergoutree.compress.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.ergoutree.compress.application.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DesktopControllerTest {
    private class Workflow : DesktopWorkflow {
        override val dataRoot = "/data"
        override val startupNotice: String? = null
        override val savedTools = SavedToolPaths("/tools/ffmpeg", "/tools/ffprobe")
        var validations = 0
        var complete = true
        val wait = CompletableDeferred<Unit>()
        val tool = ToolBinary(LocalPath("/tools/ffmpeg"), "sha", "version")
        val tools = Toolchain(tool, tool.copy(path = LocalPath("/tools/ffprobe")), listOf(BackendCheck(Backend.X264, true, "ok")), "build")
        override suspend fun validateTools(ffmpeg: String, ffprobe: String, stop: StopSignal): Toolchain { validations++; wait.await(); return tools }
        override fun scan(roots: List<String>, depth: Int?, minimumBytes: Long, output: String, tools: Toolchain, stop: StopSignal): Flow<ScanUpdate> = flow { emit(ScanUpdate.Summary(0, 0, complete)) }
        override suspend fun freeze(selection: List<PlanningSelection>, tools: Toolchain, settings: EncodeSettings, output: String): FrozenJob = error("no selected rows")
        override suspend fun run(plan: ResolvedPlan, stop: StopSignal, emit: (AttemptEvent) -> Unit): AttemptResult = error("not needed")
    }
    @Test fun duplicateActionDoesNotStartConcurrentOperations() = runTest {
        val workflow = Workflow(); val c = DesktopController(workflow, this)
        c.validateTools(); c.validateTools(); runCurrent()
        assertEquals(1, workflow.validations); assertTrue(c.state.value.busy)
        workflow.wait.complete(Unit); advanceUntilIdle()
        assertFalse(c.state.value.busy); assertNotNull(c.state.value.tools)
    }
    @Test fun toolPathChangeInvalidatesValidatedCapabilities() = runTest {
        val workflow = Workflow().apply { wait.complete(Unit) }; val c = DesktopController(workflow, this)
        c.validateTools(); advanceUntilIdle()
        c.edit { it.copy(ffmpeg = "/other/ffmpeg") }
        assertNull(c.state.value.tools); assertNull(c.state.value.frozen)
    }
    @Test fun incompleteScanCannotBeFrozenAndInputEditInvalidatesScan() = runTest {
        val workflow = Workflow().apply { complete = false; wait.complete(Unit) }; val c = DesktopController(workflow, this)
        c.edit { it.copy(roots = "/input") }; c.validateTools(); advanceUntilIdle(); c.scan(); advanceUntilIdle()
        assertFalse(c.state.value.scanComplete)
        c.freeze(); advanceUntilIdle(); assertNull(c.state.value.frozen)
        assertTrue(c.state.value.status.contains("完整扫描"))
        workflow.complete = true; c.scan(); advanceUntilIdle(); assertTrue(c.state.value.scanComplete)
        c.edit { it.copy(depth = "1") }; assertFalse(c.state.value.scanComplete)
    }
}
