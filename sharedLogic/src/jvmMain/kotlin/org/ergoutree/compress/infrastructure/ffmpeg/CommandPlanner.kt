package org.ergoutree.compress.infrastructure.ffmpeg

import org.ergoutree.compress.domain.*

/** Typed plan -> argv; this class never emits shell source or user-controlled filter expressions. */
class CommandPlanner {
    fun encode(plan: ResolvedPlan, candidate: LocalPath): List<String> {
        require(candidate != plan.source.path && candidate != plan.destination)
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

    fun videoOptions(settings: EncodeSettings): List<String> = when (settings.backend) {
        Backend.X264 -> listOf("-c:v", "libx264", "-preset", if (settings.speed == Speed.FAST) "veryfast" else "medium", "-crf", settings.quality.toString())
        Backend.NVENC -> listOf("-c:v", "hevc_nvenc", "-preset", if (settings.speed == Speed.FAST) "p4" else "p5", "-tune", "hq", "-rc", "vbr", "-cq", settings.quality.toString(), "-b:v", "0")
    }
}
