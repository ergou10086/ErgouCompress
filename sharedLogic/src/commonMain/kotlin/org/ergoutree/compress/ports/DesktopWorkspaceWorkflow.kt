package org.ergoutree.compress.ports

import org.ergoutree.compress.application.PlanningSelection
import org.ergoutree.compress.domain.*

/** 桌面只通过持久队列控制任务，不直接调用单文件发布器。 */
interface DesktopTaskQueue {
    suspend fun submit(job: FrozenJob)
    suspend fun recover()
    suspend fun run(jobId: String, emit: (AttemptEvent) -> Unit = {}): List<AttemptResult>
    suspend fun pause(jobId: String, immediate: Boolean = false)
    suspend fun cancel(jobId: String)
    suspend fun retry(itemId: String)
    suspend fun hide(jobId: String)
    suspend fun history(): List<JobRecord>
    suspend fun items(jobId: String): List<ItemRecord>
    suspend fun attempts(itemId: String): List<AttemptRecord>
    suspend fun events(jobId: String): List<DurableEvent>
}
/** 为桌面工作台聚合共享用例，窗口和文件选择器始终由平台层负责。 */
interface DesktopWorkspaceWorkflow : DesktopWorkflow {
    val queue: DesktopTaskQueue
    suspend fun loadPreferences(): DesktopPreferences
    suspend fun savePreferences(preferences: DesktopPreferences)
    suspend fun freezeTask(selection: List<PlanningSelection>, tools: Toolchain, settings: EncodeSettings,
        output: String, mode: OutputMode, onlyIfSmaller: Boolean, audit: TaskImportAudit): FrozenJob
    suspend fun analyze(plan: ResolvedPlan, options: AnalysisOptions = AnalysisOptions(), stop: StopSignal = StopSignal()): AnalysisReport
    suspend fun estimateSelection(selection: PlanningSelection, tools: Toolchain, settings: EncodeSettings, options: AnalysisOptions, stop: StopSignal): AnalysisReport
    suspend fun readTaskLogs(jobId: String, cursor: TaskLogCursor? = null): TaskLogPage
    suspend fun commitHistory(): List<CommitRecord>
    suspend fun restoreCommit(id: String): CommitRecord
    suspend fun loadRetention(): RetentionPolicy
    suspend fun saveRetention(policy: RetentionPolicy)
    suspend fun storageReport(): StorageReport
    suspend fun cleanupPreview(options: CleanupOptions = CleanupOptions()): CleanupPreview
    suspend fun cleanupExecute(id: String, confirmation: String, acknowledgeBackupLoss: Boolean = false): CleanupResult
    suspend fun exportSupport(destination: LocalPath): LocalPath
    suspend fun migrateDataRoot(destination: LocalPath, stop: StopSignal = StopSignal()): MigrationResult
    suspend fun registerWorkspace(root: LocalPath): Workspace
}
