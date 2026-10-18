package org.ergoutree.compress.application

import kotlinx.serialization.encodeToString
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

/** Pure orchestration: identity, typed cache contract and bounded adapters are independently replaceable. */
class AnalyzeMedia(private val files: PlanEnvironment, private val sampler: MediaSampler, private val cache: AnalysisCache) {
    suspend fun execute(plan: ResolvedPlan, options: AnalysisOptions = AnalysisOptions(), stop: StopSignal = StopSignal()): AnalysisReport {
        stop.checkRunning()
        MediaStrategy.resolve(plan.media, plan.settings, plan.container)
        val source = files.evidence(plan.source.path)
        if (source.identity != plan.source) throw CompressException(ErrorCode.INPUT_CHANGED, "分析前源文件变化，请重新规划")
        suspend fun verifyTools() {
            for (tool in listOf(plan.tools.ffmpeg, plan.tools.ffprobe)) if (files.evidence(tool.path).sha256 != tool.sha256) throw CompressException(ErrorCode.TOOLCHAIN_INVALID, "分析工具已更换，请重新自检")
        }
        verifyTools()
        val key = files.sha256("analysis:1\n" + SnapshotJson.encodeToString(source) + "\n" + PlanEncoding.canonical(plan) + "\n" + SnapshotJson.encodeToString(options))
        cache.get(key)?.takeIf { it.analysisVersion == 1 && it.cacheKey == key && it.source == source }?.let {
            stop.checkRunning()
            if (files.evidence(plan.source.path) != source) throw CompressException(ErrorCode.INPUT_CHANGED, "读取缓存期间源文件变化")
            verifyTools()
            return it
        }
        val video = MediaStrategy.resolve(plan.media, plan.settings, plan.container).single { it.source.kind == StreamKind.VIDEO }
        val samples = if (options.sample && video.action == StreamAction.ENCODE) sampler.video(plan, SizeEstimation.schedule(requireNotNull(plan.media.durationUs), options.budget), stop) else emptyList()
        val hint = if (options.staticHint) sampler.staticHint(plan, stop) else null
        val recommendations = MediaRecommendations.forPlan(plan)
        stop.checkRunning()
        if (files.evidence(plan.source.path) != source) throw CompressException(ErrorCode.INPUT_CHANGED, "分析期间源文件变化，结果不会缓存")
        verifyTools()
        val report = AnalysisReport(cacheKey = key, source = source, estimate = SizeEstimation.estimate(plan, samples), samples = samples, staticHint = hint, recommendations = recommendations)
        cache.put(report)
        return report
    }
}
