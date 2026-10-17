package org.ergoutree.compress.infrastructure.ffmpeg

import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.ports.*
import java.security.MessageDigest
import kotlin.math.abs

class FullMediaValidator(private val runner: ProcessRunner, private val probe: MediaProbe, private val files: PlanEnvironment) : CandidateValidator {
    override suspend fun validate(plan: ResolvedPlan, planHash: String, source: ContentEvidence, candidate: LocalPath, work: LocalPath, stop: StopSignal): ValidationReceipt {
        stop.checkRunning()
        val output = probe.inspect(candidate, plan.tools, work, stop)
        compare(plan, output)
        val decode = runner.run(ProcessRequest(listOf(plan.tools.ffmpeg.path.value, "-hide_banner", "-nostdin", "-v", "error", "-xerror", "-err_detect", "explode",
            "-protocol_whitelist", "file,pipe", "-i", candidate.value, "-map", "0:v", "-map", "0:a?", "-f", "null", "-"), work,
            timeoutMillis = 7L * 24 * 60 * 60 * 1000, logPrefix = work.nio().resolve("decode").local(), captureLimit = 0), stop)
        requireProcess(decode, ErrorCode.VALIDATION_FAILED, "候选完整解码失败")
        val inputFrames = videoFrames(plan.source.path, plan.tools, work, stop)
        val outputFrames = videoFrames(candidate, plan.tools, work, stop)
        if (inputFrames.count == 0L || inputFrames.count != outputFrames.count || !near(inputFrames.firstUs, outputFrames.firstUs, 100_000) || !near(inputFrames.lastUs, outputFrames.lastUs, 100_000))
            throw CompressException(ErrorCode.VALIDATION_FAILED, "视频解码帧数量或首尾时间线不符")
        val audioDigests = mutableListOf<String>()
        for (i in plan.media.streams.filter { it.kind == StreamKind.AUDIO }.indices) {
            val before = packets(plan.source.path, i, plan.tools, work, stop)
            val after = packets(candidate, i, plan.tools, work, stop)
            if (before.count == 0L || before.digest != after.digest || before.count != after.count ||
                !near(before.firstUs, after.firstUs, 30_000) || !near(before.lastUs, after.lastUs, 30_000))
                throw CompressException(ErrorCode.VALIDATION_FAILED, "音轨 $i 的包内容、数量或首尾时间线发生变化")
            audioDigests += before.digest
        }
        stop.checkRunning()
        if (files.evidence(plan.source.path) != source) throw CompressException(ErrorCode.INPUT_CHANGED, "编码/校验期间源文件已变化")
        val evidence = files.evidence(candidate)
        if (evidence.identity.sizeBytes <= 0) throw CompressException(ErrorCode.VALIDATION_FAILED, "候选为空")
        return ValidationReceipt(planHash = planHash, source = source, candidate = evidence, outputMedia = output, audioPacketDigests = audioDigests.toList(), fullDecodePassed = true)
    }

    fun compare(plan: ResolvedPlan, output: MediaInfo) {
        fun check(ok: Boolean, reason: String) { if (!ok) throw CompressException(ErrorCode.VALIDATION_FAILED, reason) }
        check(output.format.split(',').contains(if (plan.container == Container.MKV) "matroska" else "mp4"), "输出容器与冻结计划不符")
        check(output.streams.size == plan.media.streams.size, "输出流数量与冻结计划不符")
        check(near(plan.media.durationUs, output.durationUs, 500_000), "输出时长不符或缺失")
        check(near(plan.media.startUs, output.startUs, 100_000), "媒体起始时间不符")
        check(userTagsMatch(plan.media.tags, output.tags), "文件标题/标签未保留")
        check(plan.media.chapters.size == output.chapters.size, "章节数量不符")
        plan.media.chapters.zip(output.chapters).forEach { (a, b) -> check(near(a.startUs, b.startUs, 1000) && near(a.endUs, b.endUs, 1000) && userTagsMatch(a.tags, b.tags), "章节内容不符") }
        plan.media.streams.zip(output.streams).forEach { (a, b) ->
            check(a.kind == b.kind, "流类型不符")
            check(userTagsMatch(a.tags, b.tags), "流 ${a.index} 标签未保留：${a.tags} → ${b.tags}")
            check(a.dispositions.filterValues { it != 0 } == b.dispositions.filterValues { it != 0 }, "流 disposition 变化")
            check(near(a.startUs, b.startUs, 100_000), "流起始时间偏移")
            when (a.kind) {
                StreamKind.VIDEO -> {
                    check(b.codec == if (plan.settings.backend == Backend.X264) "h264" else "hevc", "输出视频编码不符")
                    check(a.width == b.width && a.height == b.height && a.pixelFormat == b.pixelFormat, "分辨率/像素格式变化")
                    if (a.frameRate != null && b.frameRate != null) check(abs(a.frameRate.numerator.toDouble() / a.frameRate.denominator - b.frameRate.numerator.toDouble() / b.frameRate.denominator) < 0.02, "平均帧率发生变化")
                    check(a.colorTransfer == b.colorTransfer && a.colorPrimaries == b.colorPrimaries && a.colorSpace == b.colorSpace && a.colorRange == b.colorRange, "色彩标记变化")
                }
                StreamKind.AUDIO -> check(a.codec == b.codec && a.sampleRate == b.sampleRate && a.channels == b.channels && a.channelLayout == b.channelLayout, "复制音轨规格变化")
                else -> check(false, "当前基础校验器不接受复杂流")
            }
        }
    }

    private data class PacketEvidence(val count: Long, val digest: String, val firstUs: Long?, val lastUs: Long?)
    private data class FrameEvidence(val count: Long, val firstUs: Long?, val lastUs: Long?)
    private suspend fun videoFrames(path: LocalPath, tools: Toolchain, work: LocalPath, stop: StopSignal): FrameEvidence {
        var count = 0L
        var first: Long? = null
        var last: Long? = null
        val result = runner.run(ProcessRequest(listOf(tools.ffprobe.path.value, "-v", "error", "-protocol_whitelist", "file,pipe", "-select_streams", "v:0", "-show_frames",
            "-show_entries", "frame=best_effort_timestamp_time", "-of", "compact", path.value), work,
            timeoutMillis = 7L * 24 * 60 * 60 * 1000, captureLimit = 0), stop) { line ->
            val timestamp = line.split('|').firstOrNull { it.startsWith("best_effort_timestamp_time=") }?.substringAfter('=')
            secondsUs(timestamp)?.let { time -> if (first == null) first = time; last = time; count++ }
        }
        requireProcess(result, ErrorCode.VALIDATION_FAILED, "视频帧证据读取失败")
        if (result.stderrTail.isNotBlank()) throw CompressException(ErrorCode.VALIDATION_FAILED, "视频帧探测存在解码错误：${result.stderrTail.takeLast(500)}")
        return FrameEvidence(count, first, last)
    }
    private suspend fun packets(path: LocalPath, audioIndex: Int, tools: Toolchain, work: LocalPath, stop: StopSignal): PacketEvidence {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        var first: Long? = null
        var last: Long? = null
        val result = runner.run(ProcessRequest(listOf(tools.ffprobe.path.value, "-v", "error", "-protocol_whitelist", "file,pipe", "-select_streams", "a:$audioIndex",
            "-show_packets", "-show_data_hash", "sha256", "-show_entries", "packet=pts_time,duration_time,data_hash", "-of", "compact", path.value), work,
            timeoutMillis = 7L * 24 * 60 * 60 * 1000, captureLimit = 0), stop) { line ->
            val fields = line.split('|').mapNotNull { field -> field.indexOf('=').takeIf { it > 0 }?.let { field.substring(0, it) to field.substring(it + 1) } }.toMap()
            fields["data_hash"]?.let { hash ->
                digest.update((hash + "\n").toByteArray(Charsets.UTF_8))
                count++
                val pts = secondsUs(fields["pts_time"])
                if (first == null) first = pts
                last = pts?.plus(secondsUs(fields["duration_time"]) ?: 0)
            }
        }
        requireProcess(result, ErrorCode.VALIDATION_FAILED, "音频包证据读取失败")
        return PacketEvidence(count, digest.digest().hex(), first, last)
    }

    private fun near(a: Long?, b: Long?, tolerance: Long): Boolean = when {
        a == null -> true // Unknown source time cannot establish an additional promise.
        b == null -> false
        else -> abs(a - b) <= tolerance
    }
    private fun userTagsMatch(source: Map<String, String>, target: Map<String, String>): Boolean {
        val generated = setOf("encoder", "duration", "major_brand", "minor_version", "compatible_brands", "handler_name", "vendor_id")
        return source.filterKeys { it.lowercase() !in generated }.all { (key, value) ->
            val actual = target.entries.firstOrNull { it.key.equals(key, true) }?.value
            actual == value || (key.equals("language", true) && value == "und" && actual == null)
        }
    }
}
