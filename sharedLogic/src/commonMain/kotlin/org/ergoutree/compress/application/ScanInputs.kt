package org.ergoutree.compress.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

data class ScannedMedia(val file: FileIdentity, val media: MediaInfo?, val eligible: Boolean, val reason: String?)
sealed interface ScanUpdate {
    data class Entry(val item: ScannedMedia) : ScanUpdate
    data class Warning(val message: String) : ScanUpdate
    data class Summary(val files: Long, val bytes: Long, val complete: Boolean) : ScanUpdate
}

/** Two fixed workers and a bounded channel. No coroutine/process per file in the input tree. */
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
                        // The draft may still change container. Specific compatibility is checked on freeze.
                        val reason = MediaPolicy.rejection(media, Container.MKV)
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
