package org.ergoutree.compress.presentation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.time.TimeSource
import org.ergoutree.compress.application.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*
import org.ergoutree.compress.workspace.fileName

/** 独立编排导入与持久队列，所有窗口消费同一状态，不在 Composable 中执行媒体 I/O。 */
class WorkspaceController(val workflow: DesktopWorkspaceWorkflow, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(WorkspaceState(ffmpeg = workflow.savedTools.ffmpeg, ffprobe = workflow.savedTools.ffprobe))
    val state = mutable.asStateFlow()
    private val pendingImports = ArrayDeque<List<String>>()
    private val requestedJobs = ArrayDeque<String>()
    private var intake: Job? = null
    private var execution: Job? = null
    private var initialization: Job? = null
    private var intakeStop = StopSignal()
    private var logCursor: TaskLogCursor? = null
    private var readingLogs = false
    private var detailsVersion = 0L
    private val operations = mutableSetOf<Job>()
    private var toolStop = StopSignal()
    private fun launchOperation(block: suspend CoroutineScope.() -> Unit): Job {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        operations += job
        job.invokeOnCompletion { operations -= job }
        job.start()
        return job
    }
    private fun fail(error: Exception) { mutable.update { it.copy(error = error.message ?: "操作失败", message = error.message ?: "操作失败") } }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    fun initialize() {
        if (initialization != null) return
        initialization = launchOperation {
            try {
                val preferences = workflow.loadPreferences()
                workflow.queue.recover()
                val retention = workflow.loadRetention()
                mutable.update { it.copy(initialized = true, preferences = preferences, draft = TaskDraft.from(preferences), retention = retention,
                    jobs = workflow.queue.history(), message = workflow.startupNotice ?: "拖入文件夹或视频，开始整理本次任务") }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
            finally { mutable.update { it.copy(startupBusy = false) }; drainImports() }
        }
    }
    fun editTools(ffmpeg: String, ffprobe: String) {
        if (state.value.intakeBusy || state.value.active != null || state.value.closing || state.value.maintenanceBusy || state.value.restartRequired) return
        mutable.update { it.copy(ffmpeg = ffmpeg, ffprobe = ffprobe, tools = null) }
    }
    fun validateTools() {
        if (state.value.intakeBusy || state.value.active != null || state.value.closing || state.value.maintenanceBusy || state.value.restartRequired) return
        toolStop = StopSignal()
        mutable.update { it.copy(toolBusy = true, error = null, message = "正在实际编码短片并验证 CPU / NVIDIA / AAC…") }
        launchOperation {
            try { val s = state.value; val tools = workflow.validateTools(s.ffmpeg, s.ffprobe, toolStop)
                mutable.update { it.copy(tools = tools, ffmpeg = tools.ffmpeg.path.value, ffprobe = tools.ffprobe.path.value, message = "工具自检通过，可以拖入文件") }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
            finally { mutable.update { it.copy(toolBusy = false) }; drainImports() }
        }
    }
    fun importPaths(paths: List<String>) {
        if (state.value.closing || state.value.restartRequired) return
        val values = paths.map(String::trim).filter(String::isNotEmpty).distinct()
        if (values.isEmpty()) return
        pendingImports.addLast(values)
        mutable.update { it.copy(roots = (it.roots + values).distinct(), error = null) }
        drainImports()
    }
    private fun drainImports() {
        if (!state.value.initialized || state.value.closing || state.value.restartRequired || state.value.maintenanceBusy || state.value.toolBusy || state.value.preparing || state.value.estimating || intake?.isActive == true || pendingImports.isEmpty()) return
        if (state.value.ffmpeg.isBlank()) { mutable.update { it.copy(message = "已保留导入路径，请在设置中选择 FFmpeg 并自检") }; return }
        intakeStop = StopSignal()
        mutable.update { it.copy(scanning = true, error = null) }
        intake = launchOperation {
            try {
                var tools = state.value.tools
                if (tools == null) {
                    mutable.update { it.copy(message = "首次导入：正在验证本机工具…") }
                    tools = workflow.validateTools(state.value.ffmpeg, state.value.ffprobe, intakeStop)
                    mutable.update { it.copy(tools = tools) }
                }
                var complete = state.value.scanComplete || state.value.rows.isEmpty()
                while (pendingImports.isNotEmpty() && !intakeStop.requested) {
                    val roots = pendingImports.removeFirst()
                    val preferences = state.value.preferences
                    var batchComplete = false
                    workflow.scan(roots, preferences.scanDepth, preferences.minimumBytes, state.value.draft.output, tools, intakeStop).collect { update ->
                        mutable.update { s -> when (update) {
                            is ScanUpdate.Entry -> {
                                val known = s.rows.any { it.media.file.path.sameLocation(update.item.file.path) }
                                s.copy(rows = if (known) s.rows else s.rows + WorkspaceRow(update.item, update.item.eligible),
                                    scannedFiles = if (known) s.scannedFiles else s.scannedFiles + 1,
                                    scannedBytes = if (known) s.scannedBytes else s.scannedBytes + update.item.file.sizeBytes,
                                    scanningPath = update.item.file.path.value, message = "正在扫描与探测文件…")
                            }
                            is ScanUpdate.Warning -> s.copy(message = update.message)
                            is ScanUpdate.Summary -> { batchComplete = update.complete; s }
                        } }
                    }
                    complete = complete && batchComplete
                    mutable.update { it.copy(scanComplete = complete) }
                }
                mutable.update { it.copy(message = if (complete && !intakeStop.requested) "扫描完成，已默认选中可处理项；可直接开始或先审核参数" else "扫描未完成，请重新扫描后再开始任务") }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { mutable.update { it.copy(scanComplete = false) }; fail(e) }
            finally { mutable.update { it.copy(scanning = false) } }
        }
    }
    fun stopScan() { scope.launch { intakeStop.request() } }
    fun clearDraft() {
        if (state.value.intakeBusy) return
        pendingImports.clear()
        mutable.update { it.copy(rows = emptyList(), roots = emptyList(), scanComplete = false, scannedFiles = 0, scannedBytes = 0, scanningPath = "") }
    }
    fun rescan() { if (state.value.intakeBusy) return; val roots = state.value.roots; clearDraft(); importPaths(roots) }
    fun editDraft(change: (TaskDraft) -> TaskDraft) {
        if (state.value.preparing || state.value.estimating || state.value.closing) return
        mutable.update { val draft = change(it.draft); it.copy(draft = draft, rows = it.rows.map { row -> row.copy(sampled = null) }) }
    }
    fun toggle(path: LocalPath) {
        if (state.value.intakeBusy) return
        mutable.update { it.copy(rows = it.rows.map { row -> if (row.media.file.path == path && row.media.eligible) row.copy(selected = !row.selected) else row }) }
    }
    fun selectAll(value: Boolean) { if (!state.value.intakeBusy) mutable.update { it.copy(rows = it.rows.map { row -> row.copy(selected = value && row.media.eligible) }) } }
    fun editStream(path: LocalPath, policy: StreamPolicy) {
        if (state.value.intakeBusy) return
        mutable.update { it.copy(rows = it.rows.map { row -> if (row.media.file.path == path) row.copy(streams = row.streams.filterNot { p -> p.sourceIndex == policy.sourceIndex } + policy, sampled = null) else row }) }
    }
    fun startTask() {
        val snapshot = state.value
        if (!snapshot.canStart) return
        mutable.update { it.copy(preparing = true, error = null) }
        launchOperation {
            try {
                val draft = snapshot.draft
                require(draft.mode == OutputMode.COPY || draft.acknowledgeInPlace) { "请确认原地处理的备份和替换行为" }
                val selected = snapshot.selected
                selected.firstNotNullOfOrNull { it.rejection(draft) }?.let { error(it) }
                val selections = selected.map { PlanningSelection(it.media.file, requireNotNull(it.media.media), it.settings(draft)) }
                val audit = TaskImportAudit(snapshot.roots, snapshot.rows.map { ImportAuditEntry(it.media.file.path, it.selected, it.rejection(draft)) })
                val job = workflow.freezeTask(selections, requireNotNull(snapshot.tools), draft.settings(), draft.output, draft.mode, draft.onlyIfSmaller, audit)
                workflow.queue.submit(job)
                // 只有冻结并持久入队成功才清空当前草稿；正在处理的任务完全独立。
                mutable.update { it.copy(rows = emptyList(), roots = pendingImports.flatten().distinct(), scannedFiles = 0, scannedBytes = 0,
                    scanComplete = false, scanningPath = "", activeItems = emptyList(), draft = it.draft.copy(acknowledgeInPlace = false),
                    selectedJobId = job.id, message = "任务已入队，可以继续添加文件") }
                requestedJobs.addLast(job.id)
                refreshHistory()
                pump()
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
            finally { mutable.update { it.copy(preparing = false) }; drainImports() }
        }
    }
    private fun pump() {
        if (execution?.isActive == true || state.value.closing) return
        execution = launchOperation {
            try {
                while (requestedJobs.isNotEmpty() && !state.value.closing) {
                    val id = requestedJobs.removeFirst()
                    val started = TimeSource.Monotonic.markNow()
                    val initialItems = runCatching { workflow.queue.items(id) }.getOrDefault(emptyList())
                    mutable.update { it.copy(active = TaskActivity(id, startedAt = started), activeItems = initialItems, activeItemsJobId = id, message = "正在处理任务 ${id.take(8)}") }
                    try {
                        workflow.queue.run(id) { event ->
                            mutable.update { s ->
                                // 阶段事件即到达终态时先更新本地项状态，让批量计数无需等待下一次数据库刷新。
                                val terminalState = when (event.stage) {
                                    AttemptStage.SUCCEEDED -> ItemState.SUCCEEDED
                                    AttemptStage.FAILED -> ItemState.FAILED
                                    AttemptStage.INTERRUPTED -> ItemState.INTERRUPTED
                                    AttemptStage.SKIPPED -> ItemState.SKIPPED
                                    else -> null
                                }
                                val items = if (terminalState == null) s.activeItems else s.activeItems.map { item ->
                                    if (item.plan.source.path == event.source) item.copy(state = terminalState) else item
                                }
                                s.copy(active = s.active?.copy(event = event) ?: TaskActivity(id, event, started), activeItems = items)
                            }
                        }
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
                    refreshHistory()
                    runCatching { workflow.queue.items(id) }.onSuccess { items -> mutable.update { it.copy(activeItems = items, activeItemsJobId = id) } }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
            finally { mutable.update { it.copy(active = null) } }
        }
    }
    fun resume(jobId: String) { if (state.value.closing || state.value.maintenanceBusy || state.value.restartRequired) return; if (requestedJobs.contains(jobId) || state.value.active?.id == jobId) return; requestedJobs.addLast(jobId); pump() }
    fun pause(jobId: String, immediate: Boolean) = action { workflow.queue.pause(jobId, immediate); requestedJobs.remove(jobId); refreshHistory() }
    fun cancel(jobId: String) = action { workflow.queue.cancel(jobId); requestedJobs.remove(jobId); refreshHistory() }
    fun retry(itemId: String, jobId: String) = action { workflow.queue.retry(itemId); refreshHistory(); resume(jobId) }
    fun hide(jobId: String) = action { workflow.queue.hide(jobId); refreshHistory() }
    private suspend fun refreshHistory() { mutable.update { it.copy(jobs = workflow.queue.history()) } }
    fun refresh() = action { refreshHistory(); state.value.selectedJobId?.let { loadDetails(it) } }
    fun selectJob(id: String) { mutable.update { it.copy(selectedJobId = id, detailItems = emptyList(), detailAttempts = emptyMap(), commits = emptyList(), detailsLoading = true) }; action { loadDetails(id) } }
    private suspend fun loadDetails(id: String) {
        val version = ++detailsVersion
        val items = workflow.queue.items(id); val commits = workflow.commitHistory()
        val attempts = items.associate { it.id to workflow.queue.attempts(it.id) }
        if (state.value.selectedJobId == id && version == detailsVersion) mutable.update { it.copy(detailItems = items, detailAttempts = attempts, detailsLoading = false, commits = commits.filter { record -> items.any { item -> item.id == record.itemId } }) }
    }
    fun readLogs(id: String) {
        if (readingLogs || state.value.closing || state.value.restartRequired) return
        readingLogs = true
        launchOperation {
            try {
                if (state.value.logJobId != id) { logCursor = null; mutable.update { it.copy(logJobId = id, logLines = emptyList()) } }
                val page = workflow.readTaskLogs(id, logCursor)
                logCursor = page.cursor
                mutable.update { it.copy(logLines = (it.logLines + page.lines).takeLast(it.preferences.logBufferLines)) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
            finally { readingLogs = false }
        }
    }
    fun estimateSelected() {
        val snapshot = state.value
        if (snapshot.intakeBusy || snapshot.maintenanceBusy || snapshot.closing || snapshot.restartRequired || snapshot.selected.isEmpty() || snapshot.tools == null) return
        val total = snapshot.selected.size
        mutable.update { it.copy(estimating = true, estimateTotal = total, estimateDone = 0, error = null) }
        intakeStop = StopSignal()
        launchOperation {
            try {
                snapshot.selected.forEachIndexed { index, row ->
                    if (intakeStop.requested) return@forEachIndexed
                    mutable.update { it.copy(message = "正在采样估算 ${index + 1}/$total · ${fileName(row.media.file.path.value)}") }
                    val report = workflow.estimateSelection(PlanningSelection(row.media.file, requireNotNull(row.media.media)), snapshot.tools,
                        row.settings(snapshot.draft), AnalysisOptions(sample = true, staticHint = true), intakeStop)
                    mutable.update {
                        it.copy(rows = it.rows.map { current -> if (current.media.file.path == row.media.file.path) current.copy(sampled = report.estimate) else current },
                            estimateDone = index + 1, message = report.estimate.explanation)
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) }
            finally { mutable.update { it.copy(estimating = false) }; drainImports() }
        }
    }
    fun savePreferences(value: DesktopPreferences) = action {
        workflow.savePreferences(value)
        mutable.update { it.copy(preferences = value, message = "工具设置已保存；默认参数用于下一次新建草稿") }
        if (state.value.rows.isEmpty() && !state.value.intakeBusy) mutable.update { it.copy(draft = TaskDraft.from(value)) }
    }
    fun saveTaskAsDefaults() = action {
        val s = state.value; val draft = s.draft
        val preferences = s.preferences.copy(defaults = draft.settings().copy(streams = emptyList()), outputDirectory = draft.output, outputMode = draft.mode, onlyIfSmaller = draft.onlyIfSmaller)
        workflow.savePreferences(preferences); mutable.update { it.copy(preferences = preferences, message = "已保存为默认参数；已有任务快照保持不变") }
    }
    fun saveRetention(policy: RetentionPolicy) = action { workflow.saveRetention(policy); mutable.update { it.copy(retention = policy, cleanup = null, message = "保留策略已保存，请重新预览清理") } }
    fun refreshStorage() = maintenance { val report = workflow.storageReport(); mutable.update { it.copy(storage = report) } }
    fun previewCleanup(options: CleanupOptions) = maintenance { val preview = workflow.cleanupPreview(options); mutable.update { it.copy(cleanup = preview) } }
    fun executeCleanup(acknowledge: Boolean) = maintenance {
        val preview = requireNotNull(state.value.cleanup)
        val result = workflow.cleanupExecute(preview.id, preview.confirmation, acknowledge)
        mutable.update { it.copy(cleanup = null, message = "已移除 ${result.deletedLogicalBytes} 逻辑字节；失败 ${result.failed.size} 项", storage = workflow.storageReport()) }
    }
    fun exportSupport(path: String) = maintenance { workflow.exportSupport(LocalPath(path)); mutable.update { it.copy(message = "诊断包已保存：$path") } }
    fun registerWorkspace(path: String) = maintenance { workflow.registerWorkspace(LocalPath(path)); mutable.update { it.copy(message = "已登记该卷工作区：$path") } }
    fun migrate(path: String) = maintenance { workflow.migrateDataRoot(LocalPath(path)); mutable.update { it.copy(restartRequired = true, message = "数据根迁移完成，请关闭并重新启动工具") } }
    fun restore(id: String) = maintenance { workflow.restoreCommit(id); refreshHistory(); state.value.selectedJobId?.let { loadDetails(it) } }
    private fun action(block: suspend () -> Unit) { if (state.value.closing) return; launchOperation { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) } } }
    private fun maintenance(block: suspend () -> Unit) {
        if (state.value.busy || state.value.closing || state.value.restartRequired) return
        mutable.update { it.copy(maintenanceBusy = true, error = null) }
        launchOperation { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(e) } finally { mutable.update { it.copy(maintenanceBusy = false) }; drainImports() } }
    }
    fun requestClose() {
        if (state.value.closing) return
        mutable.update { it.copy(closing = true, message = "正在安全停止，发布中的文件先完成核对…") }
        requestedJobs.clear(); pendingImports.clear()
        scope.launch {
            try {
                intakeStop.request(); toolStop.request()
                state.value.active?.id?.let { workflow.queue.pause(it, true) }
                // 等待所有已获准的 I/O 结束，尤其不能在冻结、迁移或发布期间关闭数据库。
                operations.toList().joinAll()
                mutable.update { it.copy(canExit = true) }
            } catch (e: Exception) { fail(e); mutable.update { it.copy(closing = false) } }
        }
    }
}
