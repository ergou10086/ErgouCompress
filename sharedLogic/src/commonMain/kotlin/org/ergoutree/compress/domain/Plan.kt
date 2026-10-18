package org.ergoutree.compress.domain

import kotlinx.serialization.Serializable

@Serializable
enum class Backend { X264, NVENC }
@Serializable
enum class Speed { FAST, BALANCED }
@Serializable
enum class Container(val extension: String, val muxer: String) { MP4("mp4", "mp4"), MKV("mkv", "matroska") }
@Serializable enum class OutputMode { COPY, REPLACE, CHANGE_EXTENSION }

@Serializable
data class EncodeSettings(
    val backend: Backend = Backend.X264,
    val quality: Int = 23,
    val speed: Speed = Speed.BALANCED,
    val container: Container? = null,
    val operation: MediaOperation = MediaOperation.ENCODE,
    val streams: List<StreamPolicy> = emptyList(),
    val maxWidth: Int? = null,
    val maxHeight: Int? = null,
    val frameRate: Rational? = null,
    val rateControl: RateControl = RateControl.QUALITY,
    val videoBitrateKbps: Int? = null,
    val preserveMetadata: Boolean = true,
    val preserveChapters: Boolean = true,
) {
    init {
        require(quality in 0..51) { "质量值必须为 0–51" }
        require(maxWidth == null || maxWidth in 2..16384 && maxWidth % 2 == 0)
        require(maxHeight == null || maxHeight in 2..16384 && maxHeight % 2 == 0)
        require(frameRate == null || frameRate.numerator.toDouble() / frameRate.denominator in 1.0..240.0)
        require((rateControl == RateControl.BITRATE) == (videoBitrateKbps != null)) { "码率模式需要明确视频码率；质量模式不接受码率覆盖" }
        require(videoBitrateKbps == null || videoBitrateKbps in 16..200_000)
        require(streams.map { it.sourceIndex }.distinct().size == streams.size) { "每条流只能指定一次策略" }
    }
}

@Serializable
data class ToolBinary(val path: LocalPath, val sha256: String, val version: String)

@Serializable
data class BackendCheck(val backend: Backend, val available: Boolean, val diagnostic: String)

@Serializable
data class Toolchain(
    val ffmpeg: ToolBinary,
    val ffprobe: ToolBinary,
    val checks: List<BackendCheck>,
    val buildConfiguration: String,
    val aacAvailable: Boolean = false,
) {
    fun supports(backend: Backend): Boolean = checks.any { it.backend == backend && it.available }
}

/** All collections are copied at the application boundary before this snapshot is created. */
@Serializable
data class ResolvedPlan(
    val schemaVersion: Int = 1,
    val plannerVersion: Int = 1,
    val source: FileIdentity,
    val media: MediaInfo,
    val tools: Toolchain,
    val settings: EncodeSettings,
    val container: Container,
    val destination: LocalPath,
    val outputMode: OutputMode = OutputMode.COPY,
    val onlyIfSmaller: Boolean = true,
    val preserveSourceTime: Boolean = true,
) {
    init {
        require(outputMode == OutputMode.COPY || plannerVersion >= 2) { "原地计划需要 plannerVersion >= 2" }
        require(plannerVersion >= 3 || settings == EncodeSettings(settings.backend, settings.quality, settings.speed, settings.container)) { "扩展媒体参数需要 plannerVersion >= 3" }
    }
}

@Serializable
data class FrozenJob(val schemaVersion: Int = 1, val id: String, val plans: List<ResolvedPlan>, val planHash: String)

@Serializable
data class ValidationReceipt(
    val policyVersion: Int = 1,
    val planHash: String,
    val source: ContentEvidence,
    val candidate: ContentEvidence,
    val outputMedia: MediaInfo,
    val audioPacketDigests: List<String>,
    val fullDecodePassed: Boolean,
)

@Serializable
enum class AttemptStage { PREPARING, ENCODING, VALIDATING, PUBLISHING, SUCCEEDED, INTERRUPTED, FAILED, SKIPPED }

@Serializable
data class Progress(val outTimeUs: Long?, val totalBytes: Long?, val speed: Double?, val ended: Boolean) {
    fun fraction(durationUs: Long?): Double? =
        if (outTimeUs != null && durationUs != null && durationUs > 0) (outTimeUs.toDouble() / durationUs).coerceIn(0.0, 1.0) else null
}

data class AttemptEvent(val stage: AttemptStage, val message: String, val progress: Progress? = null, val sizeEstimate: SizeEstimate? = null)
data class AttemptResult(val stage: AttemptStage, val destination: LocalPath? = null, val error: ErrorCode? = null, val message: String)

object MediaPolicy {
    /** Conservative S2 path: every input stream is retained; complex streams require S5. */
    fun rejection(media: MediaInfo, container: Container): String? {
        val videos = media.streams.filter { it.kind == StreamKind.VIDEO && !it.attachedPicture }
        if (videos.size != 1) return "需要一个真实主视频；封面或多视频轨需要人工审核"
        if (media.streams.any { it.kind != StreamKind.AUDIO && it != videos.single() }) return "字幕、附件、封面和数据流暂待后续逐流策略，禁止静默丢弃"
        val video = videos.single()
        if (video.pixelFormat !in setOf("yuv420p", "yuvj420p")) return "当前基础路径仅支持已验证的 8bit 4:2:0 SDR"
        if (video.colorTransfer !in setOf("bt709", "smpte170m", "bt470bg")) return "HDR 或未确认的色彩传递需要审核"
        if (listOf(video.colorPrimaries, video.colorSpace).any { it !in setOf(null, "unknown", "bt709", "smpte170m", "bt470bg", "bt470m", "smpte240m") } || video.colorRange !in setOf(null, "unknown", "tv", "pc")) return "色彩属性组合尚未验证"
        if (video.sideDataTypes.isNotEmpty() || video.tags.keys.any { it.equals("rotate", true) }) return "旋转/HDR/额外视频侧数据需要审核"
        if (video.fieldOrder !in setOf(null, "unknown", "progressive")) return "交错视频暂不自动处理"
        if (video.width == null || video.height == null || video.width % 2 != 0 || video.height % 2 != 0) return "视频尺寸必须为已知偶数"
        if (media.durationUs == null || media.durationUs <= 0) return "无法确认完整媒体时长"
        val allowedAudio = when (container) {
            Container.MP4 -> setOf("aac", "mp3", "ac3", "eac3", "alac")
            Container.MKV -> setOf("aac", "mp3", "ac3", "eac3", "flac", "opus", "vorbis", "pcm_s16le", "pcm_s24le")
        }
        if (media.streams.any { it.kind == StreamKind.AUDIO && it.codec !in allowedAudio }) return "音频与目标容器组合尚未验证，请更换容器"
        return null
    }
}
