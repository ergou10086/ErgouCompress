package org.ergoutree.compress.domain

import kotlinx.serialization.Serializable
import kotlin.math.floor
import kotlin.math.min

@Serializable enum class MediaOperation { ENCODE, REMUX }
@Serializable enum class StreamAction { COPY, ENCODE, DROP }
@Serializable enum class RateControl { QUALITY, BITRATE }
@Serializable data class AacSettings(val bitrateKbps: Int = 192, val sampleRate: Int? = null, val channels: Int? = null) {
    init {
        require(bitrateKbps in 16..512)
        require(sampleRate == null || sampleRate in setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000))
        require(channels == null || channels in 1..6)
    }
}
@Serializable data class StreamPolicy(val sourceIndex: Int, val action: StreamAction, val aac: AacSettings? = null, val defaultDisposition: Boolean? = null) {
    init { require(sourceIndex >= 0); require(aac == null || action == StreamAction.ENCODE) }
}
data class StreamMapping(val source: MediaStream, val action: StreamAction, val aac: AacSettings? = null, val dispositions: Map<String, Int> = source.dispositions)
data class VideoDimensions(val width: Int, val height: Int)

/** Container compatibility and all lossy decisions are owned by the common core. */
object MediaStrategy {
    fun resolve(media: MediaInfo, settings: EncodeSettings, container: Container): List<StreamMapping> {
        fun reject(reason: String): Nothing = throw CompressException(ErrorCode.UNSUPPORTED_MEDIA, reason)
        if (media.streams.map { it.index }.distinct().size != media.streams.size) reject("源流索引重复")
        if (settings.streams.any { policy -> media.streams.none { it.index == policy.sourceIndex } }) reject("策略引用不存在的源流")
        if (settings.operation == MediaOperation.REMUX && (settings.maxWidth != null || settings.maxHeight != null || settings.frameRate != null || settings.rateControl != RateControl.QUALITY)) reject("转封装不能使用视频变换或码率控制")
        val mapping = media.streams.map { stream ->
            val policy = settings.streams.singleOrNull { it.sourceIndex == stream.index }
            val action = policy?.action ?: if (settings.operation == MediaOperation.ENCODE && stream.kind == StreamKind.VIDEO && !stream.attachedPicture) StreamAction.ENCODE else StreamAction.COPY
            StreamMapping(stream, action, policy?.aac, stream.dispositions + (policy?.defaultDisposition?.let { mapOf("default" to if (it) 1 else 0) } ?: emptyMap()))
        }.filter { it.action != StreamAction.DROP }
        val video = mapping.filter { it.source.kind == StreamKind.VIDEO && !it.source.attachedPicture }
        if (video.size != 1) reject("需保留一个真实主视频；多视频需明确逐轨选择")
        MediaPolicy.rejection(media.copy(streams = listOf(video.single().source)), container)?.let { reject(it) }
        if (video.single().action == StreamAction.COPY && (settings.maxWidth != null || settings.maxHeight != null || settings.frameRate != null || settings.rateControl != RateControl.QUALITY)) reject("复制视频不能同时缩放、改帧率或改码率")
        for (m in mapping) {
            val s = m.source
            if (settings.operation == MediaOperation.REMUX && m.action != StreamAction.COPY) reject("转封装只接受 Copy/Drop")
            if (m.aac != null && s.kind != StreamKind.AUDIO) reject("AAC 参数只能用于音频流")
            when (s.kind) {
                StreamKind.VIDEO -> if (s.attachedPicture || m.action == StreamAction.COPY && s.codec !in setOf("h264", "hevc")) reject("封面或该视频复制组合尚未验证，请明确 Drop 或重新规划")
                StreamKind.AUDIO -> {
                    if (m.action == StreamAction.ENCODE) {
                        val aac = m.aac ?: AacSettings()
                        if ((aac.sampleRate ?: s.sampleRate) !in setOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000) || (aac.channels ?: s.channels) !in 1..6) reject("AAC 需要可验证的采样率和 1–6 声道；不会隐式降混")
                    } else {
                        val allowed = if (container == Container.MP4) setOf("aac", "mp3", "ac3", "eac3", "alac") else setOf("aac", "mp3", "ac3", "eac3", "flac", "opus", "vorbis", "pcm_s16le", "pcm_s24le")
                        if (s.codec !in allowed) reject("音轨 ${s.index} 不兼容目标容器，请明确 AAC Encode、Drop 或改容器")
                    }
                }
                StreamKind.SUBTITLE -> {
                    val allowed = if (container == Container.MKV) setOf("subrip", "ass", "ssa", "webvtt", "hdmv_pgs_subtitle", "dvd_subtitle") else setOf("mov_text")
                    if (m.action != StreamAction.COPY || s.codec !in allowed) reject("字幕 ${s.index} 不能无损复制到目标容器；请明确 Drop 或改容器")
                }
                StreamKind.ATTACHMENT -> if (container != Container.MKV || m.action != StreamAction.COPY || s.codec !in setOf("ttf", "otf")) reject("仅支持 MKV 字体附件复制；其他附件需明确 Drop")
                else -> reject("未知/数据流 ${s.index} 必须人工处理或明确 Drop")
            }
        }
        if (container == Container.MP4) for (kind in listOf(StreamKind.VIDEO, StreamKind.AUDIO)) {
            val selected = mapping.filter { it.source.kind == kind }
            if (selected.isNotEmpty() && selected.all { it.source.dispositions.containsKey("default") } && selected.none { it.dispositions["default"] == 1 }) reject("MP4 会提升首个 $kind 为默认轨，请为保留流明确 defaultDisposition=true 后重新规划")
        }
        return mapping
    }

    fun rejection(media: MediaInfo, settings: EncodeSettings, container: Container): String? = try { resolve(media, settings, container); null } catch (e: CompressException) { e.message }

    /** Bound both axes without upscaling; FFmpeg adjusts SAR to retain display aspect. */
    fun dimensions(video: MediaStream, settings: EncodeSettings): VideoDimensions {
        val w = requireNotNull(video.width); val h = requireNotNull(video.height)
        val ratio = min(1.0, min((settings.maxWidth ?: w).toDouble() / w, (settings.maxHeight ?: h).toDouble() / h))
        return VideoDimensions((floor(w * ratio / 2).toInt() * 2).coerceAtLeast(2), (floor(h * ratio / 2).toInt() * 2).coerceAtLeast(2))
    }
}
