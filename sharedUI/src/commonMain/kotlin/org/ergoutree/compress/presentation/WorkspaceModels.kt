package org.ergoutree.compress.presentation

import kotlin.time.TimeSource
import org.ergoutree.compress.application.*
import org.ergoutree.compress.domain.*

/** 任务参数文本草稿允许用户暂时输入不完整数字，冻结时统一校验。 */
data class TaskDraft(
    val backend: Backend = Backend.X264, val quality: String = "23", val speed: Speed = Speed.BALANCED,
    val container: Container? = null, val operation: MediaOperation = MediaOperation.ENCODE,
    val rateControl: RateControl = RateControl.QUALITY, val bitrate: String = "2000",
    val width: String = "", val height: String = "", val fps: String = "",
    val output: String = "", val mode: OutputMode = OutputMode.COPY, val onlyIfSmaller: Boolean = true,
    val preserveMetadata: Boolean = true, val preserveChapters: Boolean = true, val acknowledgeInPlace: Boolean = false,
) {
    fun settings(): EncodeSettings = EncodeSettings(backend, quality.toIntOrNull() ?: error("质量需要整数"), speed, container,
        operation = operation, maxWidth = dimension(width), maxHeight = dimension(height), frameRate = fps.takeIf { it.isNotBlank() }?.let {
            Rational.parse(it.replace('：', ':')) ?: error("帧率使用整数或分数，如 30、30000/1001")
        }, rateControl = rateControl, videoBitrateKbps = if (rateControl == RateControl.BITRATE) bitrate.toIntOrNull() ?: error("视频码率需要整数") else null,
        preserveMetadata = preserveMetadata, preserveChapters = preserveChapters)
    private fun dimension(value: String) = if (value.isBlank()) null else value.toIntOrNull() ?: error("尺寸上限需要偶数整数")
    companion object {
        fun from(preferences: DesktopPreferences): TaskDraft = preferences.defaults.let { s -> TaskDraft(
            s.backend, s.quality.toString(), s.speed, s.container, s.operation, s.rateControl, s.videoBitrateKbps?.toString() ?: "2000",
            s.maxWidth?.toString().orEmpty(), s.maxHeight?.toString().orEmpty(), s.frameRate?.let { "${it.numerator}/${it.denominator}" }.orEmpty(),
            preferences.outputDirectory, preferences.outputMode, preferences.onlyIfSmaller, s.preserveMetadata, s.preserveChapters)
        }
    }
}
/** 草稿行保留原始探测信息与用户逐轨选择，处理中的冻结任务不引用此对象。 */
data class WorkspaceRow(val media: ScannedMedia, val selected: Boolean, val streams: List<StreamPolicy> = emptyList(), val sampled: SizeEstimate? = null) {
    fun settings(draft: TaskDraft) = draft.settings().copy(streams = streams)
    fun container(draft: TaskDraft) = draft.container ?: if (media.media?.format?.split(',')?.contains("matroska") == true) Container.MKV else Container.MP4
    fun rejection(draft: TaskDraft): String? {
        val info = media.media
        if (!media.eligible || info == null) return media.reason ?: "无法探测"
        return try { MediaStrategy.rejection(info, settings(draft), container(draft)) } catch (e: IllegalArgumentException) { e.message ?: "参数无效" } catch (e: IllegalStateException) { e.message ?: "参数未填写完整" }
    }
    fun estimate(draft: TaskDraft): SizeEstimate = sampled ?: try {
        media.media?.let { SizeEstimation.estimate(it, settings(draft), container(draft)) } ?: SizeEstimation.unknown("尚未完成探测")
    } catch (e: Exception) { SizeEstimation.unknown(e.message ?: "先解决媒体策略冲突") }
}
/** 处理任务的实时状态与导入草稿分离，新增文件不会改动进行中的快照。 */
data class TaskActivity(
    val id: String,
    val event: AttemptEvent? = null,
    /** 单调计时用于当前文件耗时和剩余时间，不参与持久化。 */
    val startedAt: TimeSource.Monotonic.ValueTimeMark? = null,
)
/** 多窗口共享单一状态源，界面只负责展示与发送意图。 */
data class WorkspaceState(
    val initialized: Boolean = false, val startupBusy: Boolean = true, val tools: Toolchain? = null,
    val ffmpeg: String = "", val ffprobe: String = "", val preferences: DesktopPreferences = DesktopPreferences(),
    val draft: TaskDraft = TaskDraft(), val rows: List<WorkspaceRow> = emptyList(), val roots: List<String> = emptyList(),
    val scanning: Boolean = false, val preparing: Boolean = false, val toolBusy: Boolean = false, val estimating: Boolean = false,
    val scanComplete: Boolean = false, val scannedFiles: Long = 0, val scannedBytes: Long = 0, val scanningPath: String = "",
    val estimateDone: Int = 0, val estimateTotal: Int = 0,
    val jobs: List<JobRecord> = emptyList(), val active: TaskActivity? = null,
    val activeItems: List<ItemRecord> = emptyList(), val activeItemsJobId: String? = null,
    val selectedJobId: String? = null,
    val detailItems: List<ItemRecord> = emptyList(), val commits: List<CommitRecord> = emptyList(),
    val detailAttempts: Map<String, List<AttemptRecord>> = emptyMap(), val detailsLoading: Boolean = false,
    val logLines: List<TaskLogLine> = emptyList(), val logJobId: String? = null,
    val storage: StorageReport? = null, val retention: RetentionPolicy = RetentionPolicy(), val cleanup: CleanupPreview? = null,
    val maintenanceBusy: Boolean = false, val restartRequired: Boolean = false,
    val message: String = "拖入文件夹或视频，开始整理本次任务", val error: String? = null,
    val closing: Boolean = false, val canExit: Boolean = false,
) {
    val intakeBusy get() = scanning || preparing || toolBusy || estimating || startupBusy
    val busy get() = intakeBusy || active != null || maintenanceBusy
    val selected get() = rows.filter { it.selected }
    val canStart get() = initialized && !intakeBusy && !maintenanceBusy && !closing && !restartRequired && scanComplete && tools != null && selected.isNotEmpty()
    val pendingJobs get() = jobs.count { it.state == JobState.READY || it.state == JobState.PAUSED || it.state == JobState.PAUSE_REQUESTED }
    val activeJob get() = jobs.firstOrNull { it.id == active?.id }
    val currentJob get() = activeJob ?: jobs.firstOrNull { it.state == JobState.READY || it.state == JobState.PAUSED }
    val activeCompleted get() = activeItems.count { it.state in setOf(ItemState.SUCCEEDED, ItemState.FAILED, ItemState.INTERRUPTED, ItemState.SKIPPED, ItemState.CANCELLED) }
}
