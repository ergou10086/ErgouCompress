package org.ergoutree.compress.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

/** 扫描资格表示可进入任务审核，逐轨与容器冲突仍需在冻结前解决。 */
data class ScannedMedia(val file: FileIdentity, val media: MediaInfo?, val eligible: Boolean, val reason: String?)
/** 分离逐项发现、警告与完整性汇总，界面不可把部分扫描当作完成。 */
sealed interface ScanUpdate {
    /** 携带真实探测结果，不由界面生成占位媒体。 */
    data class Entry(val item: ScannedMedia) : ScanUpdate
    /** 提示扫描中的非致命问题，最终完整性仍以汇总为准。 */
    data class Warning(val message: String) : ScanUpdate
    /** 汇总发现数量和容量，并保留停止或失败造成的不完整状态。 */
    data class Summary(val files: Long, val bytes: Long, val complete: Boolean) : ScanUpdate
}

/** 两个固定探测工作者配合有界通道，避免按文件创建无界进程或协程。 */
class ScanInputs(private val scanner: InputScanner, private val probe: MediaProbe, private val environment: PlanEnvironment) {
    fun execute(request: ScanRequest, tools: Toolchain, work: LocalPath, stop: StopSignal): Flow<ScanUpdate> = channelFlow {
        val candidates = Channel<ScanEvent.Found>(32)
        var summary: ScanEvent.Finished? = null
        val workers = List(2) {
            launch {
                for (event in candidates) {
                    if (stop.requested) continue
                    if (!event.candidate || !event.aboveThreshold) {
                        send(ScanUpdate.Entry(ScannedMedia(event.file, null, false, if (!event.candidate) "非视频候选" else "低于体积阈值")))
                        continue
                    }
                    try {
                        val media = probe.inspect(event.file.path, tools, work, stop)
                        if (environment.identity(event.file.path) != event.file) throw CompressException(ErrorCode.INPUT_CHANGED, "探测期间源文件变化")
                        // 扫描只判断主视频的安全资格；逐流和容器兼容性在任务参数与冻结时审核。
                        val videos = media.streams.filter { it.kind == StreamKind.VIDEO && !it.attachedPicture }
                        val reasons = videos.map { MediaPolicy.rejection(media.copy(streams = listOf(it)), Container.MKV) }
                        // 多视频允许进入逐轨审核；必须至少存在一条安全主视频，冻结仍要求明确保留唯一主视频。
                        val reason = if (reasons.isEmpty()) "没有可审核的主视频" else if (reasons.any { it == null }) null else reasons.first()
                        send(ScanUpdate.Entry(ScannedMedia(event.file, media, reason == null, reason)))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { if (!stop.requested) send(ScanUpdate.Entry(ScannedMedia(event.file, null, false, failure.message ?: "探测失败"))) }
                }
            }
        }
        try {
            scanner.scan(request, stop).collect { event ->
                when (event) {
                    is ScanEvent.Found -> candidates.send(event)
                    is ScanEvent.Issue -> send(ScanUpdate.Warning("${event.path.value}：${event.reason}"))
                    is ScanEvent.Finished -> summary = event
                }
            }
        } finally { candidates.close() }
        workers.forEach { it.join() }
        val end = summary
        send(ScanUpdate.Summary(end?.files ?: 0, end?.totalBytes ?: 0, end?.complete == true && !stop.requested))
    }
}
