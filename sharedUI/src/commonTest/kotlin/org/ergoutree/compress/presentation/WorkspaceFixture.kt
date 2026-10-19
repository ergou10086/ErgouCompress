package org.ergoutree.compress.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import org.ergoutree.compress.application.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

/** 用可控闸门模拟扫描、持久入队与执行，验证窗口并发意图的边界。 */
class WorkspaceFixture : DesktopWorkspaceWorkflow {
    override val dataRoot = "/data"
    override val startupNotice: String? = null
    override val savedTools = SavedToolPaths("/tools/ffmpeg", "/tools/ffprobe")
    val binary = ToolBinary(LocalPath("/tools/ffmpeg"), "sha", "version")
    val tools = Toolchain(binary, binary.copy(path = LocalPath("/tools/ffprobe")), listOf(BackendCheck(Backend.X264, true, "ok")), "build")
    val media = MediaInfo(format = "mp4", durationUs = 10_000_000, streams = listOf(MediaStream(0, StreamKind.VIDEO, "h264", width = 128, height = 96,
        pixelFormat = "yuv420p", frameRate = Rational(24, 1), colorTransfer = "bt709")))
    var preferences = DesktopPreferences(outputDirectory = "/out")
    var validations = 0
    var scans = 0
    var complete = true
    var submitFailure = false
    var submitGate: CompletableDeferred<Unit>? = null
    var runGate: CompletableDeferred<Unit>? = null
    var freezeGate: CompletableDeferred<Unit>? = null
    var storageGate: CompletableDeferred<Unit>? = null
    var audit: TaskImportAudit? = null
    val records = mutableListOf<JobRecord>()
    val executed = mutableListOf<String>()
    override val queue = object : DesktopTaskQueue {
        override suspend fun submit(job: FrozenJob) {
            submitGate?.await(); check(!submitFailure) { "数据库写入失败" }
            records += JobRecord(job.id, job, JobState.READY, 0, 1)
        }
        override suspend fun recover() = Unit
        override suspend fun run(jobId: String, emit: (AttemptEvent) -> Unit): List<AttemptResult> {
            executed += jobId; emit(AttemptEvent(AttemptStage.ENCODING, "执行", source = records.first { it.id == jobId }.snapshot.plans.first().source.path))
            runGate?.await()
            val index = records.indexOfFirst { it.id == jobId }; records[index] = records[index].copy(state = JobState.COMPLETED)
            return emptyList()
        }
        override suspend fun pause(jobId: String, immediate: Boolean) { runGate?.complete(Unit) }
        override suspend fun cancel(jobId: String) { runGate?.complete(Unit) }
        override suspend fun retry(itemId: String) = Unit
        override suspend fun hide(jobId: String) { records.removeAll { it.id == jobId } }
        override suspend fun history() = records.toList()
        override suspend fun items(jobId: String) = records.first { it.id == jobId }.snapshot.plans.mapIndexed { i, p -> ItemRecord("$jobId-$i", jobId, i.toLong(), p, ItemState.READY, 0, 0) }
        override suspend fun attempts(itemId: String) = emptyList<AttemptRecord>()
        override suspend fun events(jobId: String) = emptyList<DurableEvent>()
    }
    override suspend fun validateTools(ffmpeg: String, ffprobe: String, stop: StopSignal): Toolchain { validations++; return tools }
    override fun scan(roots: List<String>, depth: Int?, minimumBytes: Long, output: String, tools: Toolchain, stop: StopSignal) = flow {
        scans++
        roots.forEach { path -> emit(ScanUpdate.Entry(ScannedMedia(FileIdentity(LocalPath(path), 1000, 1, path), media, true, null))) }
        emit(ScanUpdate.Summary(roots.size.toLong(), roots.size * 1000L, complete))
    }
    override suspend fun loadPreferences() = preferences
    override suspend fun savePreferences(preferences: DesktopPreferences) { this.preferences = preferences }
    override suspend fun freezeTask(selection: List<PlanningSelection>, tools: Toolchain, settings: EncodeSettings, output: String, mode: OutputMode, onlyIfSmaller: Boolean, audit: TaskImportAudit): FrozenJob {
        freezeGate?.await(); this.audit = audit
        return FrozenJob(id = "job-${records.size}", plans = selection.map { ResolvedPlan(plannerVersion = 3, source = it.file, media = it.media,
            tools = tools, settings = it.override ?: settings, container = Container.MP4, destination = LocalPath("$output/${it.file.path.value.substringAfterLast('/')}"), outputMode = mode, onlyIfSmaller = onlyIfSmaller) }, planHash = "hash")
    }
    override suspend fun freeze(selection: List<PlanningSelection>, tools: Toolchain, settings: EncodeSettings, output: String): FrozenJob = error("不能调用旧冻结入口")
    override suspend fun run(plan: ResolvedPlan, stop: StopSignal, emit: (AttemptEvent) -> Unit): AttemptResult = error("必须通过持久队列执行")
    override suspend fun analyze(plan: ResolvedPlan, options: AnalysisOptions, stop: StopSignal): AnalysisReport = error("未使用")
    override suspend fun estimateSelection(selection: PlanningSelection, tools: Toolchain, settings: EncodeSettings, options: AnalysisOptions, stop: StopSignal): AnalysisReport = error("未使用")
    override suspend fun readTaskLogs(jobId: String, cursor: TaskLogCursor?) = TaskLogPage(TaskLogCursor(jobId), emptyList())
    override suspend fun commitHistory() = emptyList<CommitRecord>()
    override suspend fun restoreCommit(id: String): CommitRecord = error("未使用")
    override suspend fun loadRetention() = RetentionPolicy()
    override suspend fun saveRetention(policy: RetentionPolicy) = Unit
    override suspend fun storageReport(): StorageReport { storageGate?.await(); return StorageReport(emptyList(), 0, "统计") }
    override suspend fun cleanupPreview(options: CleanupOptions): CleanupPreview = error("未使用")
    override suspend fun cleanupExecute(id: String, confirmation: String, acknowledgeBackupLoss: Boolean): CleanupResult = error("未使用")
    override suspend fun exportSupport(destination: LocalPath) = destination
    override suspend fun migrateDataRoot(destination: LocalPath, stop: StopSignal): MigrationResult = error("未使用")
    override suspend fun registerWorkspace(root: LocalPath): Workspace = error("未使用")
}
