package org.ergoutree.compress.domain

import kotlinx.serialization.Serializable

@Serializable enum class EstimateBasis { UNKNOWN, BITRATE_BUDGET, VIDEO_SAMPLES, RUNNING_PROGRESS, ACTUAL }
/** Byte ranges are heuristic bounds, never a promise or a statistical confidence interval. */
@Serializable data class SizeEstimate(val basis: EstimateBasis, val expectedBytes: Long? = null, val lowerBytes: Long? = null, val upperBytes: Long? = null, val explanation: String) {
    init {
        require(expectedBytes == null || expectedBytes >= 0)
        require(lowerBytes == null || lowerBytes >= 0)
        require(upperBytes == null || upperBytes >= 0)
        require(lowerBytes == null || upperBytes == null || lowerBytes <= upperBytes)
    }
}
@Serializable data class SampleBudget(val count: Int = 3, val segmentUs: Long = 8_000_000) {
    init { require(count in 1..5); require(segmentUs in 500_000..12_000_000) }
}
@Serializable data class SampleWindow(val startUs: Long, val durationUs: Long)
@Serializable data class VideoSample(val window: SampleWindow, val sizeBytes: Long, val durationUs: Long, val elapsedMillis: Long = 0, val fileSizeBytes: Long = sizeBytes) {
    init { require(sizeBytes > 0); require(durationUs > 0) }
    val bitrateBps: Double get() = sizeBytes * 8_000_000.0 / durationUs
}
@Serializable enum class StaticClassification { CANDIDATE, MOTION_OBSERVED, UNKNOWN }
@Serializable data class StaticHint(val classification: StaticClassification, val positionsUs: List<Long>, val normalizedDifferences: List<Double>, val explanation: String)
@Serializable data class Recommendation(val code: String, val explanation: String)
@Serializable data class AnalysisReport(val analysisVersion: Int = 1, val cacheKey: String, val source: ContentEvidence, val estimate: SizeEstimate, val samples: List<VideoSample>, val staticHint: StaticHint?, val recommendations: List<Recommendation>)
@Serializable data class AnalysisOptions(val sample: Boolean = false, val staticHint: Boolean = false, val budget: SampleBudget = SampleBudget())
