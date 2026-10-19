package org.ergoutree.compress.application

import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*
import org.ergoutree.compress.testing.*
import kotlin.test.*

/** 验证扫描资格与任务资格的边界，允许人工解决逐轨冲突而不放宽安全限制。 */
class ScanReviewTest {
    @Test fun multipleVideosRemainReviewableButCannotFreezeUntilOneIsExplicitlyDropped() = runTest {
        val environment = FakeEnvironment()
        val media = sampleMedia().let { it.copy(streams = it.streams + it.streams.first().copy(index = 2)) }
        val row = scan(media, environment)
        assertTrue(row.eligible)
        assertNotNull(MediaStrategy.rejection(media, EncodeSettings(), Container.MP4))
        val settings = EncodeSettings(streams = listOf(StreamPolicy(2, StreamAction.DROP)))
        assertNull(MediaStrategy.rejection(media, settings, Container.MP4))
        val frozen = FreezeJob(environment).execute("reviewed", listOf(PlanningSelection(row.file, media, settings)), sampleTools(), settings, LocalPath("/out"))
        assertEquals(StreamAction.DROP, frozen.plans.single().settings.streams.single().action)
    }
    @Test fun unknownTransferOrMissingVideoCannotBecomeAnAutomaticSelection() = runTest {
        val environment = FakeEnvironment()
        val media = sampleMedia()
        assertFalse(scan(media.copy(streams = media.streams.map { it.copy(colorTransfer = null) }), environment).eligible)
        assertFalse(scan(media.copy(streams = media.streams.filter { it.kind == StreamKind.AUDIO }), environment).eligible)
    }
    private suspend fun scan(media: MediaInfo, environment: FakeEnvironment): ScannedMedia {
        val scanner = object : InputScanner { override fun scan(request: ScanRequest, stop: StopSignal) = flowOf(
            ScanEvent.Found(environment.plan.source, true, true), ScanEvent.Finished(1, 500, true)) }
        val probe = object : MediaProbe { override suspend fun inspect(path: LocalPath, tools: Toolchain, work: LocalPath, stop: StopSignal) = media }
        return ScanInputs(scanner, probe, environment).execute(ScanRequest(listOf(environment.plan.source.path)), sampleTools(), LocalPath("/work"), StopSignal()).toList().filterIsInstance<ScanUpdate.Entry>().single().item
    }
}
