package org.ergoutree.compress.infrastructure.ffmpeg

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.ports.*
import java.security.MessageDigest
import kotlin.math.abs

class FullMediaValidator(private val runner: ProcessRunner, private val probe: MediaProbe, private val files: PlanEnvironment) : CandidateValidator {
    override suspend fun validate(plan: ResolvedPlan, planHash: String, source: ContentEvidence, candidate: LocalPath, work: LocalPath, stop: StopSignal): ValidationReceipt = withContext(Dispatchers.IO) {
        stop.checkRunning()
        val output = probe.inspect(candidate, plan.tools, work, stop)
        compare(plan, output)
        val decode = runner.run(ProcessRequest(listOf(plan.tools.ffmpeg.path.value, "-hide_banner", "-nostdin", "-v", "error", "-xerror", "-err_detect", "explode",
            "-protocol_whitelist", "file,pipe", "-i", candidate.value, "-map", "0:v", "-map", "0:a?", "-fps_mode:v", "passthrough", "-enc_time_base:v", "demux", "-f", "null", "-"), work,
            timeoutMillis = 7L * 24 * 60 * 60 * 1000, logPrefix = work.nio().resolve("decode").local(), captureLimit = 0), stop)
        requireProcess(decode, ErrorCode.VALIDATION_FAILED, "候选完整解码失败")
        if (decode.stderrTail.isNotBlank()) throw CompressException(ErrorCode.VALIDATION_FAILED, "完整解码报告错误：${decode.stderrTail.takeLast(1000)}")
        val mapping = mapping(plan)
        val videoIndex = mapping.indexOfFirst { it.source.kind == StreamKind.VIDEO }
        val fps = plan.settings.frameRate
        val timeline = work.nio().resolve("source-video-timestamps.bin")
        val inputFrames = java.io.DataOutputStream(java.nio.file.Files.newOutputStream(timeline)).use { stream ->
            videoFrames(plan.source.path, mapping[videoIndex].source.index, plan.tools, work, stop) { stream.writeLong(it) }
        }
        var timelineMatches = true
        var referenceFirst: Long? = null
        var outputFirst: Long? = null
        val outputFrames = java.io.DataInputStream(java.nio.file.Files.newInputStream(timeline)).use { stream ->
            videoFrames(candidate, output.streams[videoIndex].index, plan.tools, work, stop) { time ->
                if (fps == null) {
                    val reference = try { stream.readLong() } catch (_: java.io.EOFException) { timelineMatches = false; 0L }
                    if (referenceFirst == null) { referenceFirst = reference; outputFirst = time }
                    if (abs((reference - referenceFirst) - (time - outputFirst!!)) > 2_000) timelineMatches = false
                }
            }
        }
        val frameTolerance = if (fps == null) 100_000L else maxOf(100_000L, (1_000_000.0 * fps.denominator / fps.numerator).toLong())
        val countMatches = if (fps == null) inputFrames.count == outputFrames.count else {
            val sourceFps = mapping[videoIndex].source.frameRate
            val interval = sourceFps?.let { 1_000_000.0 * it.denominator / it.numerator } ?: 0.0
            val span = (inputFrames.lastUs ?: 0) - (inputFrames.firstUs ?: 0) + interval
            abs(outputFrames.count - span * fps.numerator / fps.denominator / 1_000_000.0) <= 2.0
        }
        if (inputFrames.count == 0L || !countMatches || !timelineMatches || !near(inputFrames.firstUs, outputFrames.firstUs, frameTolerance) || !near(inputFrames.lastUs, outputFrames.lastUs, frameTolerance))
            throw CompressException(ErrorCode.VALIDATION_FAILED, "视频解码帧数量或首尾时间线不符")
        val audioDigests = mutableListOf<String>()
        mapping.forEachIndexed { index, m ->
            if (m.action == StreamAction.COPY && m.source.kind != StreamKind.ATTACHMENT) {
                val before = packets(plan.source.path, m.source.index, plan.tools, work, stop)
                val after = packets(candidate, output.streams[index].index, plan.tools, work, stop)
                val needsPackets = m.source.kind in setOf(StreamKind.AUDIO, StreamKind.VIDEO)
                if (needsPackets && before.count == 0L || before.digest != after.digest || before.count != after.count ||
                    !near(before.firstUs, after.firstUs, 30_000) || !near(before.lastUs, after.lastUs, 30_000))
                    throw CompressException(ErrorCode.VALIDATION_FAILED, "复制流 ${m.source.index} 的包内容、数量或首尾时间线发生变化")
                if (m.source.kind == StreamKind.AUDIO) audioDigests += before.digest
            } else if (m.source.kind == StreamKind.AUDIO) {
                val before = packets(plan.source.path, m.source.index, plan.tools, work, stop)
                val after = packets(candidate, output.streams[index].index, plan.tools, work, stop)
                if (after.count == 0L || !near(before.firstUs, after.firstUs, 100_000) || !near(before.lastUs, after.lastUs, 100_000))
                    throw CompressException(ErrorCode.VALIDATION_FAILED, "AAC 流 ${m.source.index} 首尾时间线不完整")
            }
        }
        stop.checkRunning()
        if (files.evidence(plan.source.path) != source) throw CompressException(ErrorCode.INPUT_CHANGED, "编码/校验期间源文件已变化")
        val evidence = files.evidence(candidate)
        if (evidence.identity.sizeBytes <= 0) throw CompressException(ErrorCode.VALIDATION_FAILED, "候选为空")
        ValidationReceipt(planHash = planHash, source = source, candidate = evidence, outputMedia = output, audioPacketDigests = audioDigests.toList(), fullDecodePassed = true)
    }

    private fun mapping(plan: ResolvedPlan): List<StreamMapping> = if (plan.plannerVersion >= 3) MediaStrategy.resolve(plan.media, plan.settings, plan.container)
        else plan.media.streams.map { StreamMapping(it, if (it.kind == StreamKind.VIDEO) StreamAction.ENCODE else StreamAction.COPY) }

    fun compare(plan: ResolvedPlan, output: MediaInfo) {
        fun check(ok: Boolean, reason: String) { if (!ok) throw CompressException(ErrorCode.VALIDATION_FAILED, reason) }
        val mapping = mapping(plan)
        check(output.format.split(',').contains(if (plan.container == Container.MKV) "matroska" else "mp4"), "输出容器与冻结计划不符")
        val chapterTrack = plan.plannerVersion >= 3 && plan.container == Container.MP4 && plan.settings.preserveChapters && plan.media.chapters.isNotEmpty()
        check(output.streams.size == mapping.size + if (chapterTrack) 1 else 0, "输出流数量与冻结计划不符")
        if (chapterTrack) {
            val helper = output.streams.last()
            check(helper.kind == StreamKind.DATA && helper.codec == "bin_data" && helper.tags.entries.any { it.key.equals("handler_name", true) && it.value == "SubtitleHandler" } && helper.dispositions.values.all { it == 0 } && helper.extradataHash != null, "MP4 生成的章节轨不符合已知布局")
        }
        check(near(plan.media.durationUs, output.durationUs, 500_000), "输出时长不符或缺失")
        check(near(plan.media.startUs, output.startUs, 100_000), "媒体起始时间不符")
        check(if (plan.settings.preserveMetadata) userTagsMatch(plan.media.tags, output.tags) else removedTags(plan.media.tags, output.tags), "文件标签与保留策略不符")
        if (plan.settings.preserveChapters) {
            check(plan.media.chapters.size == output.chapters.size, "章节数量不符")
            plan.media.chapters.zip(output.chapters).forEach { (a, b) -> check(near(a.startUs, b.startUs, 1000) && near(a.endUs, b.endUs, 1000) && userTagsMatch(a.tags, b.tags), "章节内容不符") }
        } else check(output.chapters.isEmpty(), "章节应已明确删除")
        mapping.zip(output.streams).forEach { (mapped, b) ->
            val a = mapped.source
            check(a.kind == b.kind, "流类型不符")
            check(if (plan.settings.preserveMetadata) userTagsMatch(a.tags, b.tags) else removedTags(a.tags.filterKeys { a.kind != StreamKind.ATTACHMENT || it.lowercase() !in setOf("mimetype", "filename") }, b.tags), "流 ${a.index} 标签与保留策略不符：${a.tags} → ${b.tags}")
            check(mapped.dispositions.filterValues { it != 0 } == b.dispositions.filterValues { it != 0 }, "流 disposition 变化")
            check(near(a.startUs, b.startUs, 100_000), "流起始时间偏移")
            if (mapped.action == StreamAction.COPY) {
                check(a.codec == b.codec, "复制流编码变化")
                if (plan.plannerVersion >= 3) check(a.extradataHash == b.extradataHash, "复制流 extradata 变化")
            }
            when (a.kind) {
                StreamKind.VIDEO -> {
                    val encoded = mapped.action == StreamAction.ENCODE
                    check(b.codec == if (encoded) { if (plan.settings.backend == Backend.X264) "h264" else "hevc" } else a.codec, "输出视频编码不符")
                    val size = if (encoded) MediaStrategy.dimensions(a, plan.settings) else VideoDimensions(a.width!!, a.height!!)
                    check(size.width == b.width && size.height == b.height && a.pixelFormat == b.pixelFormat, "分辨率/像素格式不符")
                    val expectedFps = plan.settings.frameRate // Preserved VFR is proven by every decoded frame timestamp, not container avg_frame_rate.
                    if (expectedFps != null) check(b.frameRate != null && abs(expectedFps.numerator.toDouble() / expectedFps.denominator - b.frameRate.numerator.toDouble() / b.frameRate.denominator) < 0.02, "平均帧率不符或缺失")
                    check(a.sideDataTypes == b.sideDataTypes, "输出视频出现未规划的侧数据")
                    check(a.colorTransfer == b.colorTransfer && a.colorPrimaries == b.colorPrimaries && a.colorSpace == b.colorSpace && a.colorRange == b.colorRange, "色彩标记变化")
                    if (plan.plannerVersion >= 3) {
                        fun aspect(s: MediaStream): Double = s.width!!.toDouble() / s.height!! * (s.sampleAspectRatio?.let { it.numerator.toDouble() / it.denominator } ?: 1.0)
                        check(abs(aspect(a) - aspect(b)) < 0.001, "显示宽高比变化")
                    }
                }
                StreamKind.AUDIO -> {
                    if (mapped.action == StreamAction.COPY) check(a.sampleRate == b.sampleRate && a.channels == b.channels && a.channelLayout == b.channelLayout, "复制音轨规格变化")
                    else {
                        val aac = mapped.aac ?: AacSettings()
                        check(b.codec == "aac" && b.sampleRate == (aac.sampleRate ?: a.sampleRate) && b.channels == (aac.channels ?: a.channels), "AAC 规格不符")
                        if (aac.channels == null && a.channelLayout != null) check(a.channelLayout == b.channelLayout, "AAC 隐式改变声道布局")
                    }
                }
                StreamKind.SUBTITLE -> Unit // Copy packets and extradata are checked independently.
                StreamKind.ATTACHMENT -> check(a.extradataHash != null && a.extradataHash == b.extradataHash, "附件内容证据不符或缺失")
                else -> check(false, "不支持的输出流")
            }
        }
    }

    private data class PacketEvidence(val count: Long, val digest: String, val firstUs: Long?, val lastUs: Long?)
    private data class FrameEvidence(val count: Long, val firstUs: Long?, val lastUs: Long?)
    private suspend fun videoFrames(path: LocalPath, streamIndex: Int, tools: Toolchain, work: LocalPath, stop: StopSignal, onFrame: (Long) -> Unit = {}): FrameEvidence {
        var count = 0L
        var first: Long? = null
        var last: Long? = null
        val result = runner.run(ProcessRequest(listOf(tools.ffprobe.path.value, "-v", "error", "-protocol_whitelist", "file,pipe", "-select_streams", streamIndex.toString(), "-show_frames",
            "-show_entries", "frame=best_effort_timestamp_time", "-of", "compact", path.value), work,
            timeoutMillis = 7L * 24 * 60 * 60 * 1000, captureLimit = 0), stop) { line ->
            val timestamp = line.split('|').firstOrNull { it.startsWith("best_effort_timestamp_time=") }?.substringAfter('=')
            secondsUs(timestamp)?.let { time -> if (first == null) first = time; last = time; count++; onFrame(time) }
        }
        requireProcess(result, ErrorCode.VALIDATION_FAILED, "视频帧证据读取失败")
        if (result.stderrTail.isNotBlank()) throw CompressException(ErrorCode.VALIDATION_FAILED, "视频帧探测存在解码错误：${result.stderrTail.takeLast(500)}")
        return FrameEvidence(count, first, last)
    }
    private suspend fun packets(path: LocalPath, streamIndex: Int, tools: Toolchain, work: LocalPath, stop: StopSignal): PacketEvidence {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        var first: Long? = null
        var last: Long? = null
        val result = runner.run(ProcessRequest(listOf(tools.ffprobe.path.value, "-v", "error", "-protocol_whitelist", "file,pipe", "-select_streams", streamIndex.toString(),
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
    private val generatedTags = setOf("encoder", "duration", "major_brand", "minor_version", "compatible_brands", "handler_name", "vendor_id")
    private fun removedTags(source: Map<String, String>, target: Map<String, String>): Boolean = source.keys.filter { it.lowercase() !in generatedTags && !it.equals("language", true) }.none { key -> target.keys.any { it.equals(key, true) } }
    private fun userTagsMatch(source: Map<String, String>, target: Map<String, String>): Boolean {
        val generated = generatedTags
        return source.filterKeys { it.lowercase() !in generated }.all { (key, value) ->
            val actual = target.entries.firstOrNull { it.key.equals(key, true) }?.value
            actual == value || (key.equals("language", true) && value == "und" && actual == null)
        }
    }
}
