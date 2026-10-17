package org.ergoutree.compress.presentation

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.ergoutree.compress.application.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

data class InputForm(
    val ffmpeg: String = "", val ffprobe: String = "", val roots: String = "", val output: String = "",
    val depth: String = "4", val minimumMb: String = "0", val quality: String = "23",
    val backend: Backend = Backend.X264, val speed: Speed = Speed.BALANCED, val container: Container? = null,
)
data class MediaRow(val media: ScannedMedia, val selected: Boolean, val override: EncodeSettings? = null)
data class DesktopState(
    val form: InputForm = InputForm(), val tools: Toolchain? = null, val rows: List<MediaRow> = emptyList(),
    val busy: Boolean = false, val status: String = "请先设置 FFmpeg，完成工具自检", val scanComplete: Boolean = false,
    val fileCount: Long = 0, val totalBytes: Long = 0, val frozen: FrozenJob? = null,
    val attempt: AttemptEvent? = null, val completed: Set<LocalPath> = emptySet(),
    val activeDurationUs: Long? = null,
)

class DesktopController(val workflow: DesktopWorkflow, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(DesktopState(form = InputForm(ffmpeg = workflow.savedTools.ffmpeg, ffprobe = workflow.savedTools.ffprobe)))
    val state: StateFlow<DesktopState> = mutable.asStateFlow()
    private var stop = StopSignal()

    fun edit(change: (InputForm) -> InputForm) {
        if (state.value.busy) return
        mutable.update { old ->
            val form = change(old.form)
            val toolsChanged = form.ffmpeg != old.form.ffmpeg || form.ffprobe != old.form.ffprobe
            val scanChanged = toolsChanged || form.roots != old.form.roots || form.depth != old.form.depth || form.minimumMb != old.form.minimumMb || form.output != old.form.output
            old.copy(form = form, tools = if (toolsChanged) null else old.tools, frozen = null,
                scanComplete = if (scanChanged) false else old.scanComplete, rows = if (scanChanged) emptyList() else old.rows)
        }
    }
    fun toggle(path: LocalPath) {
        if (state.value.busy) return
        mutable.update { old -> old.copy(frozen = null, rows = old.rows.map { if (it.media.file.path == path && it.media.eligible) it.copy(selected = !it.selected) else it }) }
    }
    fun selectAll(selected: Boolean) {
        if (state.value.busy) return
        mutable.update { it.copy(frozen = null, rows = it.rows.map { row -> row.copy(selected = selected && row.media.eligible) }) }
    }
    fun overrideFromCurrent(path: LocalPath, clear: Boolean = false) {
        if (state.value.busy) return
        val form = state.value.form
        val quality = form.quality.toIntOrNull()?.takeIf { it in 0..51 }
        if (!clear && quality == null) { mutable.update { it.copy(status = "质量需要 0–51 的整数") }; return }
        val settings = if (clear) null else EncodeSettings(form.backend, quality!!, form.speed, form.container)
        mutable.update { old -> old.copy(frozen = null, rows = old.rows.map { if (it.media.file.path == path) it.copy(override = settings) else it }) }
    }
    fun stop() {
        val requestedOperation = stop
        scope.launch {
            requestedOperation.request()
            mutable.update { if (it.busy && stop === requestedOperation) it.copy(status = "已请求停止；未发布候选将保留，当前文件下次需重新编码") else it }
        }
    }

    private fun operation(block: suspend () -> Unit) {
        if (state.value.busy) return
        stop = StopSignal()
        mutable.update { it.copy(busy = true) }
        scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(status = failure.message ?: "操作失败") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun validateTools() = operation {
        val form = state.value.form
        mutable.update { it.copy(status = "正在实际编码短片自检 CPU / NVIDIA…", tools = null, frozen = null, rows = emptyList(), scanComplete = false, fileCount = 0, totalBytes = 0) }
        val tools = workflow.validateTools(form.ffmpeg, form.ffprobe, stop)
        mutable.update { it.copy(tools = tools, form = it.form.copy(ffmpeg = tools.ffmpeg.path.value, ffprobe = tools.ffprobe.path.value), status = tools.checks.joinToString("；") { c -> "${c.backend}：${c.diagnostic}" }) }
    }
    fun scan() = operation {
        val current = state.value
        val tools = requireNotNull(current.tools) { "请先自检工具" }
        val depth = if (current.form.depth.trim() == "无限") null else current.form.depth.toIntOrNull()?.takeIf { it >= 0 } ?: error("深度输入非负整数或“无限”")
        val minMb = current.form.minimumMb.toLongOrNull()?.takeIf { it >= 0 && it <= Long.MAX_VALUE / 1_000_000 } ?: error("阈值需要非负整数 MB")
        val roots = current.form.roots.lines().map(String::trim).filter(String::isNotEmpty)
        require(roots.isNotEmpty()) { "请添加文件或文件夹" }
        mutable.update { it.copy(rows = emptyList(), frozen = null, scanComplete = false, fileCount = 0, totalBytes = 0, status = "扫描和限流探测中…") }
        workflow.scan(roots, depth, minMb * 1_000_000, current.form.output, tools, stop).collect { update ->
            mutable.update { old -> when (update) {
                is ScanUpdate.Entry -> old.copy(rows = old.rows + MediaRow(update.item, update.item.eligible))
                is ScanUpdate.Warning -> old.copy(status = update.message)
                is ScanUpdate.Summary -> old.copy(scanComplete = update.complete, fileCount = update.files, totalBytes = update.bytes,
                    status = if (update.complete) "扫描完成；合格项已默认全选，请审核并冻结计划" else "扫描不完整（停止或目录不可读），请处理后重新扫描")
            } }
        }
    }
    fun freeze() = operation {
        val current = state.value
        require(current.scanComplete) { "仅完整扫描可以冻结计划" }
        val tools = requireNotNull(current.tools)
        val settings = EncodeSettings(current.form.backend, current.form.quality.toIntOrNull() ?: error("质量需要整数"), current.form.speed, current.form.container)
        val job = workflow.freeze(current.rows.filter { it.selected }.map { PlanningSelection(it.media.file, requireNotNull(it.media.media), it.override) }, tools, settings, current.form.output)
        mutable.update { it.copy(frozen = job, completed = emptySet(), attempt = null, activeDurationUs = null, status = "计划已冻结：${job.plans.size} 项；核对目标后逐项运行。档案保存在数据根") }
    }
    fun run(plan: ResolvedPlan) = operation {
        require(state.value.frozen?.plans?.contains(plan) == true) { "计划已经失效，请重新冻结" }
        mutable.update { it.copy(attempt = null, activeDurationUs = plan.media.durationUs) }
        val result = workflow.run(plan, stop) { event -> mutable.update { it.copy(attempt = event, status = event.message) } }
        mutable.update { it.copy(status = result.message, completed = if (result.stage == AttemptStage.SUCCEEDED) it.completed + plan.destination else it.completed) }
    }
}
