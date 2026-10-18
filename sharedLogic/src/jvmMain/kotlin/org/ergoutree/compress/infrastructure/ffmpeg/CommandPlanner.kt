package org.ergoutree.compress.infrastructure.ffmpeg

import org.ergoutree.compress.domain.*

/** Typed plan -> argv; this class never emits shell source or user-controlled filter expressions. */
class CommandPlanner {
    fun encode(plan: ResolvedPlan, candidate: LocalPath): List<String> {
        require(candidate != plan.source.path && candidate != plan.destination)
        if (plan.plannerVersion >= 3) return planned(plan, candidate)
        MediaPolicy.rejection(plan.media, plan.container)?.let { throw CompressException(ErrorCode.UNSUPPORTED_MEDIA, it) }
        val args = mutableListOf(plan.tools.ffmpeg.path.value, "-hide_banner", "-n", "-xerror", "-nostats", "-stats_period", "0.5", "-progress", "pipe:1",
            "-protocol_whitelist", "file,pipe", "-noautorotate", "-i", plan.source.path.value)
        plan.media.streams.forEach { args += listOf("-map", "0:${it.index}") }
        args += listOf("-map_metadata", "0", "-map_chapters", "0", "-c:a", "copy", "-fps_mode", "passthrough")
        args += videoOptions(plan.settings)
        val video = plan.media.streams.single { it.kind == StreamKind.VIDEO }
        args += listOf("-pix_fmt", video.pixelFormat!!)
        val frameColors = listOf("color_trc" to video.colorTransfer, "color_primaries" to video.colorPrimaries, "colorspace" to video.colorSpace,
            "range" to when (video.colorRange) { "tv" -> "limited"; "pc" -> "full"; else -> null })
            .filter { it.second != null && it.second != "unknown" }.joinToString(":") { "${it.first}=${it.second}" }
        args += listOf("-vf", "setparams=$frameColors")
        listOf("-color_trc" to video.colorTransfer, "-color_primaries" to video.colorPrimaries, "-colorspace" to video.colorSpace, "-color_range" to video.colorRange)
            .filter { it.second != null && it.second != "unknown" }.forEach { args += listOf(it.first, it.second!!) }
        plan.media.streams.forEachIndexed { outputIndex, stream ->
            val flags = stream.dispositions.filter { it.value == 1 }.keys.sorted().joinToString("+").ifEmpty { "0" }
            args += listOf("-disposition:$outputIndex", flags)
        }
        if (plan.container == Container.MP4) args += listOf("-movflags", "+faststart")
        args += listOf("-f", plan.container.muxer, candidate.value)
        return args.toList()
    }

    private fun planned(plan: ResolvedPlan, candidate: LocalPath): List<String> {
        val mapping = MediaStrategy.resolve(plan.media, plan.settings, plan.container)
        val args = mutableListOf(plan.tools.ffmpeg.path.value, "-hide_banner", "-n", "-xerror", "-nostats", "-stats_period", "0.5", "-progress", "pipe:1",
            "-protocol_whitelist", "file,pipe", "-noautorotate", "-i", plan.source.path.value)
        mapping.forEach { args += listOf("-map", "0:${it.source.index}") }
        args += listOf("-map_metadata", if (plan.settings.preserveMetadata) "0" else "-1", "-map_chapters", if (plan.settings.preserveChapters) "0" else "-1", "-c", "copy")
        mapping.forEachIndexed { index, mapped ->
            val stream = mapped.source
            if (mapped.action == StreamAction.ENCODE && stream.kind == StreamKind.VIDEO) {
                args += videoOptions(plan.settings)
                args += listOf("-pix_fmt", stream.pixelFormat!!, "-fps_mode:v", if (plan.settings.frameRate == null) "passthrough" else "cfr")
                if (plan.settings.frameRate == null) args += listOf("-enc_time_base:v", "demux")
                val filters = mutableListOf<String>()
                if (plan.settings.maxWidth != null || plan.settings.maxHeight != null) {
                    val size = MediaStrategy.dimensions(stream, plan.settings)
                    filters += "scale=${size.width}:${size.height}"
                }
                plan.settings.frameRate?.let { filters += "fps=${it.numerator}/${it.denominator}" }
                val colors = listOf("color_trc" to stream.colorTransfer, "color_primaries" to stream.colorPrimaries, "colorspace" to stream.colorSpace,
                    "range" to when (stream.colorRange) { "tv" -> "limited"; "pc" -> "full"; else -> null })
                    .filter { it.second != null && it.second != "unknown" }.joinToString(":") { "${it.first}=${it.second}" }
                if (colors.isNotEmpty()) filters += "setparams=$colors"
                if (filters.isNotEmpty()) args += listOf("-vf", filters.joinToString(","))
                listOf("-color_trc" to stream.colorTransfer, "-color_primaries" to stream.colorPrimaries, "-colorspace" to stream.colorSpace, "-color_range" to stream.colorRange)
                    .filter { it.second != null && it.second != "unknown" }.forEach { args += listOf(it.first, it.second!!) }
            }
            if (mapped.action == StreamAction.ENCODE && stream.kind == StreamKind.AUDIO) {
                val aac = mapped.aac ?: AacSettings()
                args += listOf("-c:$index", "aac", "-b:$index", "${aac.bitrateKbps}k")
                aac.sampleRate?.let { args += listOf("-ar:$index", it.toString()) }
                aac.channels?.let { args += listOf("-ac:$index", it.toString()) }
            }
            args += listOf("-map_metadata:s:$index", if (plan.settings.preserveMetadata) "0:s:${stream.index}" else "-1")
            if (!plan.settings.preserveMetadata && stream.kind == StreamKind.ATTACHMENT) stream.tags.filterKeys { it.lowercase() in setOf("mimetype", "filename") }.forEach { (key, value) -> args += listOf("-metadata:s:$index", "$key=$value") }
            args += listOf("-disposition:$index", mapped.dispositions.filterValues { it == 1 }.keys.sorted().joinToString("+").ifEmpty { "0" })
        }
        if (plan.container == Container.MP4) args += listOf("-movflags", "+faststart")
        return args + listOf("-f", plan.container.muxer, candidate.value)
    }

    fun videoOptions(settings: EncodeSettings): List<String> {
        if (settings.rateControl == RateControl.BITRATE) return when (settings.backend) {
            Backend.X264 -> listOf("-c:v", "libx264", "-preset", if (settings.speed == Speed.FAST) "veryfast" else "medium", "-b:v", "${settings.videoBitrateKbps}k")
            Backend.NVENC -> listOf("-c:v", "hevc_nvenc", "-preset", if (settings.speed == Speed.FAST) "p4" else "p5", "-tune", "hq", "-rc", "vbr", "-b:v", "${settings.videoBitrateKbps}k")
        }
        return when (settings.backend) {
        Backend.X264 -> listOf("-c:v", "libx264", "-preset", if (settings.speed == Speed.FAST) "veryfast" else "medium", "-crf", settings.quality.toString())
        Backend.NVENC -> listOf("-c:v", "hevc_nvenc", "-preset", if (settings.speed == Speed.FAST) "p4" else "p5", "-tune", "hq", "-rc", "vbr", "-cq", settings.quality.toString(), "-b:v", "0")
        }
    }
}
