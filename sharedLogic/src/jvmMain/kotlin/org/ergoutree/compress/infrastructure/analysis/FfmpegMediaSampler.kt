package org.ergoutree.compress.infrastructure.analysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.ffmpeg.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.ports.*
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/** Bounded optional analysis. Generated samples are never accepted as full encode candidates. */
class FfmpegMediaSampler(private val runner: ProcessRunner, private val probe: MediaProbe, private val root: LocalPath) : MediaSampler {
    private suspend fun workspace(): LocalPath = withContext(Dispatchers.IO) { Files.createDirectories(root.nio().resolve(UUID.randomUUID().toString())).local() }
    override suspend fun video(plan: ResolvedPlan, windows: List<SampleWindow>, stop: StopSignal): List<VideoSample> {
        require(windows.isNotEmpty() && windows.size <= 5 && windows.all { it.startUs >= 0 && it.durationUs in 1..12_000_000 })
        val video = MediaStrategy.resolve(plan.media, plan.settings, plan.container).single { it.source.kind == StreamKind.VIDEO }
        require(video.action == StreamAction.ENCODE)
        val work = workspace()
        val samplePlan = plan.copy(schemaVersion = 3, plannerVersion = 3, media = plan.media.copy(streams = listOf(video.source), chapters = emptyList(), tags = emptyMap()),
            settings = plan.settings.copy(streams = emptyList(), preserveMetadata = false, preserveChapters = false))
        return windows.mapIndexed { index, window ->
            stop.checkRunning()
            require(window.startUs + window.durationUs <= requireNotNull(plan.media.durationUs))
            val candidate = work.nio().resolve("sample-$index.${plan.container.extension}").local()
            val args = CommandPlanner().encode(samplePlan, candidate).toMutableList()
            args.addAll(args.indexOf("-i"), listOf("-ss", seconds(window.startUs)))
            args.addAll(args.lastIndex - 2, listOf("-t", seconds(window.durationUs)))
            val started = System.nanoTime()
            val run = runner.run(ProcessRequest(args, work, timeoutMillis = 180_000, logPrefix = work.nio().resolve("sample-$index").local()), stop)
            requireProcess(run, ErrorCode.PROCESS_FAILED, "视频采样失败")
            val output = probe.inspect(candidate, plan.tools, work, stop)
            val duration = output.streams.single { it.kind == StreamKind.VIDEO }.durationUs ?: output.durationUs
            if (duration == null || duration <= 0 || abs(duration - window.durationUs) > 500_000) throw CompressException(ErrorCode.VALIDATION_FAILED, "采样输出时长不完整")
            var packetBytes = 0L
            val packets = runner.run(ProcessRequest(listOf(plan.tools.ffprobe.path.value, "-v", "error", "-select_streams", "v:0", "-show_packets", "-show_entries", "packet=size", "-of", "csv=p=0", candidate.value), work, captureLimit = 0), stop) { line ->
                line.substringBefore(',').toLongOrNull()?.let { require(it > 0 && packetBytes <= Long.MAX_VALUE - it); packetBytes += it }
            }
            requireProcess(packets, ErrorCode.PROCESS_FAILED, "采样包大小读取失败")
            val fileBytes = withContext(Dispatchers.IO) { Files.size(candidate.nio()) }
            require(packetBytes in 1..fileBytes) { "采样包证据异常" }
            VideoSample(window, packetBytes, duration, (System.nanoTime() - started) / 1_000_000, fileBytes)
        }
    }
    override suspend fun staticHint(plan: ResolvedPlan, stop: StopSignal): StaticHint {
        val duration = requireNotNull(plan.media.durationUs)
        val video = MediaStrategy.resolve(plan.media, plan.settings, plan.container).single { it.source.kind == StreamKind.VIDEO }.source
        val work = workspace()
        val positions = (0..4).map { duration / 5 * it + duration % 5 * it / 5 }
        val frames = positions.mapIndexed { index, time ->
            stop.checkRunning()
            val target = work.nio().resolve("static-$index.pgm")
            val run = runner.run(ProcessRequest(listOf(plan.tools.ffmpeg.path.value, "-hide_banner", "-nostdin", "-n", "-v", "error", "-protocol_whitelist", "file,pipe", "-ss", seconds(time),
                "-noautorotate", "-i", plan.source.path.value, "-map", "0:${video.index}", "-frames:v", "1", "-vf", "scale=32:32,format=gray", "-c:v", "pgm", "-f", "image2", target.toString()), work,
                logPrefix = work.nio().resolve("static-$index").local()), stop)
            requireProcess(run, ErrorCode.PROCESS_FAILED, "静态候选帧提取失败")
            withContext(Dispatchers.IO) { readPgm(Files.readAllBytes(target)) }
        }
        val differences = frames.zipWithNext().map { (a, b) -> a.indices.sumOf { abs((a[it].toInt() and 255) - (b[it].toInt() and 255)) }.toDouble() / (a.size * 255) }
        return StaticHint(if (differences.all { it < 0.012 }) StaticClassification.CANDIDATE else StaticClassification.MOTION_OBSERVED, positions, differences,
            "5 个均匀位置的 32×32 灰度帧平均差异；未检查位置、字幕和细微运动可能遗漏。只作提示，不改变冻结计划或自动优化")
    }
    private fun seconds(us: Long) = String.format(Locale.ROOT, "%.6f", us / 1_000_000.0)
    internal fun readPgm(bytes: ByteArray): ByteArray {
        val header = Regex("^P5\\s+32\\s+32\\s+255(?:\\r?\\n| )").find(bytes.take(64).toByteArray().toString(Charsets.US_ASCII))
            ?: throw CompressException(ErrorCode.PROCESS_FAILED, "分析 PGM 格式不符")
        val start = header.value.length // Consume exactly the delimiter, never binary whitespace-valued pixels.
        if (bytes.size != start + 1024) throw CompressException(ErrorCode.PROCESS_FAILED, "分析帧大小不符")
        return bytes.copyOfRange(start, bytes.size)
    }
}
