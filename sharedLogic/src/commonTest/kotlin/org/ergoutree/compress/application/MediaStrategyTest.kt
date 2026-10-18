package org.ergoutree.compress.application

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.testing.*
import kotlin.test.*

class MediaStrategyTest {
    @Test fun copyPreservesAudioAndUnsupportedStreamsRequireAnExplicitDecision() {
        val source = sampleMedia().copy(streams = sampleMedia().streams + MediaStream(7, StreamKind.UNKNOWN, "unknown"))
        assertNotNull(MediaStrategy.rejection(source, EncodeSettings(), Container.MKV))
        val resolved = MediaStrategy.resolve(source, EncodeSettings(streams = listOf(StreamPolicy(7, StreamAction.DROP))), Container.MKV)
        assertEquals(listOf(0, 1), resolved.map { it.source.index })
        assertEquals(StreamAction.COPY, resolved[1].action)
    }
    @Test fun subtitlesAndFontsMustMatchContainerAndCannotBeSilentlyConverted() {
        val source = sampleMedia().copy(streams = sampleMedia().streams + MediaStream(3, StreamKind.SUBTITLE, "ass") + MediaStream(4, StreamKind.ATTACHMENT, "ttf"))
        assertEquals(4, MediaStrategy.resolve(source, EncodeSettings(), Container.MKV).size)
        assertNotNull(MediaStrategy.rejection(source, EncodeSettings(), Container.MP4))
        assertNotNull(MediaStrategy.rejection(source, EncodeSettings(streams = listOf(StreamPolicy(3, StreamAction.ENCODE))), Container.MKV))
    }
    @Test fun remuxRejectsTransformsAndAudioEncodingButDoesNotNeedVideoEncoder() = runTest {
        val source = sampleMedia()
        assertNotNull(MediaStrategy.rejection(source, EncodeSettings(operation = MediaOperation.REMUX, maxWidth = 100), Container.MKV))
        assertNotNull(MediaStrategy.rejection(source, EncodeSettings(operation = MediaOperation.REMUX, streams = listOf(StreamPolicy(1, StreamAction.ENCODE))), Container.MKV))
        val files = FakeEnvironment()
        val job = FreezeJob(files).execute("remux", listOf(PlanningSelection(files.plan.source, source)), sampleTools().copy(checks = emptyList()), EncodeSettings(operation = MediaOperation.REMUX), LocalPath("/output"))
        assertEquals(3, job.plans.single().plannerVersion)
    }
    @Test fun aacRequiresSelfTestAndPreservesUnspecifiedChannelsAndSampleRate() = runTest {
        val files = FakeEnvironment()
        val settings = EncodeSettings(streams = listOf(StreamPolicy(1, StreamAction.ENCODE, AacSettings(128))))
        assertEquals(ErrorCode.TOOLCHAIN_INVALID, assertFailsWith<CompressException> { FreezeJob(files).execute("aac", listOf(PlanningSelection(files.plan.source, sampleMedia())), sampleTools(), settings, LocalPath("/output")) }.code)
        val mapping = MediaStrategy.resolve(sampleMedia(), settings, Container.MP4)
        assertNull(mapping[1].aac!!.channels); assertNull(mapping[1].aac!!.sampleRate)
        assertNotNull(MediaStrategy.rejection(sampleMedia().copy(streams = sampleMedia().streams.map { if (it.kind == StreamKind.AUDIO) it.copy(channels = 8) else it }), settings, Container.MP4))
    }
    @Test fun hdrUnknownColorAndMultipleVideosStayBehindReviewGate() {
        val source = sampleMedia()
        for (settings in listOf(EncodeSettings(), EncodeSettings(operation = MediaOperation.REMUX))) {
            assertNotNull(MediaStrategy.rejection(source.copy(streams = source.streams.map { if (it.kind == StreamKind.VIDEO) it.copy(colorTransfer = "smpte2084") else it }), settings, Container.MKV))
            assertNotNull(MediaStrategy.rejection(source.copy(streams = source.streams + source.streams[0].copy(index = 5)), settings, Container.MKV))
        }
    }
    @Test fun dimensionsNeverUpscaleAndLegacyPlansCannotHideNewParameters() {
        val video = sampleMedia().streams[0]
        assertEquals(VideoDimensions(128, 96), MediaStrategy.dimensions(video, EncodeSettings(maxWidth = 1920)))
        assertEquals(VideoDimensions(64, 48), MediaStrategy.dimensions(video, EncodeSettings(maxWidth = 64, maxHeight = 64)))
        assertFailsWith<IllegalArgumentException> { EncodeSettings(videoBitrateKbps = 1000) }
        assertFailsWith<IllegalArgumentException> { EncodeSettings(streams = listOf(StreamPolicy(1, StreamAction.COPY), StreamPolicy(1, StreamAction.DROP))) }
        assertFailsWith<IllegalArgumentException> { samplePlan().copy(settings = EncodeSettings(maxWidth = 64)) }
    }
    @Test fun droppingDefaultAudioRequiresAnExplicitNewMp4Default() {
        val base = sampleMedia()
        val source = base.copy(streams = listOf(base.streams[0].copy(dispositions = mapOf("default" to 1)), base.streams[1].copy(dispositions = mapOf("default" to 1)), base.streams[1].copy(index = 2, dispositions = mapOf("default" to 0))))
        val drop = EncodeSettings(streams = listOf(StreamPolicy(1, StreamAction.DROP)))
        assertNotNull(MediaStrategy.rejection(source, drop, Container.MP4))
        val resolved = MediaStrategy.resolve(source, drop.copy(streams = drop.streams + StreamPolicy(2, StreamAction.COPY, defaultDisposition = true)), Container.MP4)
        assertEquals(1, resolved.last().dispositions["default"])
        assertEquals(0, resolved.last().source.dispositions["default"])
    }
    @Test fun everySerializedSettingHasPlatformIndependentHelp() {
        val keys = SnapshotJson.parseToJsonElement(SnapshotJson.encodeToString(EncodeSettings())).let { (it as kotlinx.serialization.json.JsonObject).keys }
        assertEquals(keys, ParameterHelp.settings.map { it.id }.toSet())
        assertTrue(ParameterHelp.settings.all { it.explanation.isNotBlank() })
    }
}
