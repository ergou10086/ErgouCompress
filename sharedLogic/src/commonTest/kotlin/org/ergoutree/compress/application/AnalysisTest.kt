package org.ergoutree.compress.application

import kotlinx.coroutines.test.runTest
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*
import org.ergoutree.compress.testing.*
import kotlin.test.*

class AnalysisTest {
    private fun plan() = samplePlan().copy(schemaVersion = 3, plannerVersion = 3, media = sampleMedia().copy(streams = sampleMedia().streams.map { if (it.kind == StreamKind.AUDIO) it.copy(bitrateBps = 128_000) else it }))
    @Test fun bitrateEstimationUsesBitsPerSecondAndNeverCountsSourceTotalTwice() {
        val p = plan().copy(settings = EncodeSettings(rateControl = RateControl.BITRATE, videoBitrateKbps = 1000))
        val estimate = SizeEstimation.estimate(p)
        assertEquals(EstimateBasis.BITRATE_BUDGET, estimate.basis)
        assertEquals(501_226, estimate.expectedBytes) // 3 seconds * (1000000+128000) / 8 * 1.03 + 65536.
        assertTrue(estimate.lowerBytes!! < estimate.expectedBytes!! && estimate.expectedBytes < estimate.upperBytes!!)
        assertEquals(EstimateBasis.UNKNOWN, SizeEstimation.estimate(p.copy(media = sampleMedia())).basis)
        assertEquals(EstimateBasis.UNKNOWN, SizeEstimation.estimate(plan()).basis)
    }
    @Test fun samplesHaveAnObservedRangeAndAudioIsAddedOnce() {
        val samples = listOf(VideoSample(SampleWindow(0, 1_000_000), 100_000, 1_000_000), VideoSample(SampleWindow(2_000_000, 1_000_000), 200_000, 1_000_000))
        val estimate = SizeEstimation.estimate(plan(), samples)
        assertEquals(EstimateBasis.VIDEO_SAMPLES, estimate.basis)
        assertTrue(estimate.lowerBytes!! < estimate.expectedBytes!! && estimate.expectedBytes < estimate.upperBytes!!)
        assertTrue(estimate.explanation.contains("不保证"))
    }
    @Test fun scheduleIsBoundedAndIncludesHeadAndTailWithoutDuplicatingShortClips() {
        assertEquals(listOf(SampleWindow(0, 3_000_000)), SizeEstimation.schedule(3_000_000, SampleBudget()))
        val schedule = SizeEstimation.schedule(600_000_000, SampleBudget())
        assertEquals(3, schedule.size); assertEquals(0, schedule.first().startUs)
        assertEquals(600_000_000, schedule.last().startUs + schedule.last().durationUs)
        assertEquals(24_000_000, schedule.sumOf { it.durationUs })
    }
    @Test fun runningPredictionRejectsMissingAndRegressingProgressAndNeverClaimsActual() {
        val predictor = DynamicSizePredictor(10_000_000)
        assertEquals(EstimateBasis.UNKNOWN, predictor.update(Progress(1_000_000, 1000, null, false)).basis)
        assertEquals(5000, predictor.update(Progress(2_000_000, 1000, null, false)).expectedBytes)
        assertEquals(EstimateBasis.UNKNOWN, predictor.update(Progress(1_900_000, 2000, null, false)).basis)
        assertEquals(EstimateBasis.UNKNOWN, predictor.update(Progress(3_000_000, null, null, false)).basis)
        assertEquals(EstimateBasis.RUNNING_PROGRESS, predictor.update(Progress(10_000_000, 7000, null, true)).basis)
    }
    @Test fun cachedSamplesInvalidateForSourceSettingsToolAndBudgetAndChangedToolsAreBlocked() = runTest {
        var contentHash = "source1"; var toolHash = "tools"
        val base = FakeEnvironment()
        val files = object : PlanEnvironment by base {
            override suspend fun evidence(path: LocalPath) = ContentEvidence(base.identity(path), if (path.value.startsWith("/tools")) toolHash else contentHash)
        }
        var calls = 0
        val sampler = object : MediaSampler {
            override suspend fun video(plan: ResolvedPlan, windows: List<SampleWindow>, stop: StopSignal): List<VideoSample> { calls++; return windows.map { VideoSample(it, 1000, it.durationUs) } }
            override suspend fun staticHint(plan: ResolvedPlan, stop: StopSignal) = StaticHint(StaticClassification.UNKNOWN, emptyList(), emptyList(), "提示")
        }
        val entries = mutableMapOf<String, AnalysisReport>()
        val cache = object : AnalysisCache {
            override suspend fun get(key: String) = entries[key]
            override suspend fun put(report: AnalysisReport) { entries[report.cacheKey] = report }
        }
        val analyze = AnalyzeMedia(files, sampler, cache)
        val options = AnalysisOptions(sample = true)
        val first = analyze.execute(plan(), options)
        assertEquals(first, analyze.execute(plan(), options)); assertEquals(1, calls)
        assertNotEquals(first.cacheKey, analyze.execute(plan().copy(settings = EncodeSettings(quality = 24)), options).cacheKey)
        contentHash = "source2"
        assertNotEquals(first.cacheKey, analyze.execute(plan(), options).cacheKey)
        assertNotEquals(first.cacheKey, analyze.execute(plan(), options.copy(budget = SampleBudget(2, 1_000_000))).cacheKey)
        toolHash = "changed"
        assertEquals(ErrorCode.TOOLCHAIN_INVALID, assertFailsWith<CompressException> { analyze.execute(plan(), options) }.code)
        val changedTools = sampleTools().copy(ffmpeg = sampleTools().ffmpeg.copy(sha256 = toolHash), ffprobe = sampleTools().ffprobe.copy(sha256 = toolHash))
        assertNotEquals(first.cacheKey, analyze.execute(plan().copy(tools = changedTools), options).cacheKey)
        assertEquals(5, calls)
    }
    @Test fun historicalCanonicalFieldsStayStableButV3IncludesAnalysisInputs() {
        val old = samplePlan()
        val extra = old.copy(media = old.media.copy(streams = old.media.streams.map { it.copy(bitrateBps = 100, sampleAspectRatio = Rational(1, 1), extradataHash = "sha") }), tools = old.tools.copy(aacAvailable = true))
        for (version in 1..2) {
            assertEquals(PlanEncoding.canonical(old.copy(plannerVersion = version)), PlanEncoding.canonical(extra.copy(plannerVersion = version)))
            assertFalse(PlanEncoding.canonical(extra.copy(plannerVersion = version)).contains("aacAvailable"))
        }
        assertNotEquals(PlanEncoding.canonical(old.copy(plannerVersion = 3)), PlanEncoding.canonical(extra.copy(plannerVersion = 3)))
    }
    @Test fun analysisMutationAndCancellationDoNotWriteCache() = runTest {
        val base = FakeEnvironment(); var modified = false; var writes = 0
        val files = object : PlanEnvironment by base {
            override suspend fun evidence(path: LocalPath) = ContentEvidence(base.identity(path), if (path.value.startsWith("/tools")) "tools" else if (modified) "changed" else "original")
        }
        val sampler = object : MediaSampler {
            override suspend fun video(plan: ResolvedPlan, windows: List<SampleWindow>, stop: StopSignal): List<VideoSample> { modified = true; return windows.map { VideoSample(it, 1000, it.durationUs) } }
            override suspend fun staticHint(plan: ResolvedPlan, stop: StopSignal) = error("not requested")
        }
        val cache = object : AnalysisCache {
            override suspend fun get(key: String): AnalysisReport? = null
            override suspend fun put(report: AnalysisReport) { writes++ }
        }
        val analyze = AnalyzeMedia(files, sampler, cache)
        assertEquals(ErrorCode.INPUT_CHANGED, assertFailsWith<CompressException> { analyze.execute(plan(), AnalysisOptions(sample = true)) }.code)
        val stopped = StopSignal().apply { request() }
        assertEquals(ErrorCode.USER_INTERRUPTED, assertFailsWith<CompressException> { analyze.execute(plan(), stop = stopped) }.code)
        assertEquals(0, writes)
    }
}
