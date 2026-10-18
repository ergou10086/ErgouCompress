package org.ergoutree.compress.infrastructure.ffmpeg

import kotlinx.serialization.json.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*
import java.math.BigDecimal
import java.math.RoundingMode

/** 保留有界原始探测结果，并映射为通用媒体快照。 */
class FfprobeAdapter(private val runner: ProcessRunner, private val recorder: ArtifactRecorder = NoopArtifactRecorder) : MediaProbe {
    override suspend fun inspect(path: LocalPath, tools: Toolchain, work: LocalPath, stop: StopSignal): MediaInfo {
        val result = runner.run(ProcessRequest(listOf(tools.ffprobe.path.value, "-v", "error", "-protocol_whitelist", "file,pipe", "-show_data_hash", "sha256", "-show_format", "-show_streams", "-show_chapters", "-of", "json", path.value), work), stop)
        requireProcess(result, ErrorCode.UNSUPPORTED_MEDIA, "媒体探测失败")
        if (result.stdoutTruncated) throw CompressException(ErrorCode.UNSUPPORTED_MEDIA, "ffprobe 输出超过上限")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val rawPath = java.nio.file.Path.of(work.value, "probe-${java.util.UUID.randomUUID()}.json")
            java.nio.file.Files.writeString(rawPath, result.stdout)
            recorder.file(LocalPath(rawPath.toString()), ArtifactKind.CACHE)
        }
        return parse(result.stdout)
    }

    fun parse(raw: String): MediaInfo {
        val root = Json.parseToJsonElement(raw).jsonObject
        val format = root["format"]?.jsonObject ?: throw CompressException(ErrorCode.UNSUPPORTED_MEDIA, "媒体缺少容器信息")
        return MediaInfo(
            format = format.text("format_name") ?: "unknown", durationUs = secondsUs(format.text("duration")), startUs = secondsUs(format.text("start_time")),
            streams = root["streams"]?.jsonArray.orEmpty().map { node ->
                val s = node.jsonObject
                val dispositions = s["disposition"]?.jsonObject?.mapValues { it.value.jsonPrimitive.intOrNull ?: 0 }.orEmpty()
                MediaStream(
                    index = s["index"]?.jsonPrimitive?.intOrNull ?: throw CompressException(ErrorCode.UNSUPPORTED_MEDIA, "流缺少索引"),
                    kind = when (s.text("codec_type")) { "video" -> StreamKind.VIDEO; "audio" -> StreamKind.AUDIO; "subtitle" -> StreamKind.SUBTITLE; "attachment" -> StreamKind.ATTACHMENT; "data" -> StreamKind.DATA; else -> StreamKind.UNKNOWN },
                    codec = s.text("codec_name") ?: "unknown", attachedPicture = dispositions["attached_pic"] == 1,
                    width = s.int("width"), height = s.int("height"), pixelFormat = s.text("pix_fmt"), frameRate = Rational.parse(s.text("avg_frame_rate")),
                    sampleRate = s.int("sample_rate"), channels = s.int("channels"), channelLayout = s.text("channel_layout"),
                    startUs = secondsUs(s.text("start_time")), durationUs = secondsUs(s.text("duration")), colorTransfer = s.text("color_transfer"),
                    colorPrimaries = s.text("color_primaries"), colorSpace = s.text("color_space"), colorRange = s.text("color_range"), fieldOrder = s.text("field_order"),
                    sideDataTypes = s["side_data_list"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject.text("side_data_type") },
                    tags = s.tags(), dispositions = dispositions,
                    bitrateBps = s.text("bit_rate")?.toLongOrNull()?.takeIf { it > 0 },
                    sampleAspectRatio = Rational.parse(s.text("sample_aspect_ratio")?.replace(':', '/')),
                    extradataHash = s.text("extradata_hash"),
                )
            },
            chapters = root["chapters"]?.jsonArray.orEmpty().map {
                val c = it.jsonObject
                Chapter(secondsUs(c.text("start_time")) ?: 0, secondsUs(c.text("end_time")) ?: 0, c.tags())
            }, tags = format.tags(),
        )
    }
}

internal fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = text(key)?.toIntOrNull()
internal fun JsonObject.tags(): Map<String, String> = get("tags")?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
internal fun secondsUs(value: String?): Long? = try {
    value?.let { BigDecimal(it).multiply(BigDecimal(1_000_000)).setScale(0, RoundingMode.HALF_UP).longValueExact() }
} catch (_: NumberFormatException) { null } catch (_: ArithmeticException) { null }

internal fun requireProcess(result: ProcessResult, code: ErrorCode, message: String) {
    if (result.stopped) throw CompressException(ErrorCode.USER_INTERRUPTED, "用户停止了处理")
    if (result.exitCode != 0 || result.timedOut) throw CompressException(code, "$message${if (result.timedOut) "（超时）" else ""}：${result.stderrTail.takeLast(1200)}")
}
