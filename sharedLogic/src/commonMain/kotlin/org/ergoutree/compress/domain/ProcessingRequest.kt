package org.ergoutree.compress.domain

import kotlinx.serialization.Serializable

@Serializable data class ProcessingRequest(
    val inputs: List<String>, val ffmpeg: String = "", val ffprobe: String = "", val outputDirectory: String = "",
    val settings: EncodeSettings = EncodeSettings(), val mode: OutputMode = OutputMode.COPY, val onlyIfSmaller: Boolean = true,
    val maxDepth: Int? = 4, val minimumBytes: Long = 0,
)
