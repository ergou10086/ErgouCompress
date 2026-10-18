package org.ergoutree.compress.ports

import org.ergoutree.compress.domain.*

interface MediaSampler {
    suspend fun video(plan: ResolvedPlan, windows: List<SampleWindow>, stop: StopSignal): List<VideoSample>
    suspend fun staticHint(plan: ResolvedPlan, stop: StopSignal): StaticHint
}
interface AnalysisCache {
    suspend fun get(key: String): AnalysisReport?
    suspend fun put(report: AnalysisReport)
}
