package org.ergoutree.compress.ports

import kotlinx.coroutines.flow.Flow
import org.ergoutree.compress.application.*
import org.ergoutree.compress.domain.*

data class SavedToolPaths(val ffmpeg: String = "", val ffprobe: String = "")

/** UI-facing facade. The composition root supplies platform adapters behind these use cases. */
interface DesktopWorkflow {
    val dataRoot: String
    val startupNotice: String?
    val savedTools: SavedToolPaths
    suspend fun validateTools(ffmpeg: String, ffprobe: String, stop: StopSignal): Toolchain
    fun scan(roots: List<String>, depth: Int?, minimumBytes: Long, output: String, tools: Toolchain, stop: StopSignal): Flow<ScanUpdate>
    suspend fun freeze(selection: List<PlanningSelection>, tools: Toolchain, settings: EncodeSettings, output: String): FrozenJob
    suspend fun run(plan: ResolvedPlan, stop: StopSignal, emit: (AttemptEvent) -> Unit): AttemptResult
}
