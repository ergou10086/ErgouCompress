package org.ergoutree.compress.infrastructure

import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.ffmpeg.*
import org.ergoutree.compress.ports.*
import org.ergoutree.compress.testing.*
import kotlin.test.*

class FfmpegContractTest {
    private val unusedRunner = object : ProcessRunner { override suspend fun run(request: ProcessRequest, stop: StopSignal, onStdout: (String) -> Unit): ProcessResult = error("not invoked") }
    @Test fun jsonAcceptsNumericStringsUnknownFieldsAndMissingDuration() {
        val raw = """{"format":{"format_name":"matroska","duration":"N/A","unknown":1},"streams":[{"index":0,"codec_type":"video","codec_name":"h264","avg_frame_rate":"30000/1001","pix_fmt":"yuv420p","width":1920,"height":1080,"disposition":{"attached_pic":0}}]}"""
        val media = FfprobeAdapter(unusedRunner).parse(raw)
        assertNull(media.durationUs); assertEquals(Rational(30000, 1001), media.streams.single().frameRate)
    }
    @Test fun progressEndIsOnlyASnapshotAndLegacyMillisecondsAreIgnored() {
        val parser = ProgressParser()
        assertNull(parser.accept("out_time_ms=3000000"))
        assertNull(parser.accept("speed=N/A"))
        val unknown = assertNotNull(parser.accept("progress=end"))
        assertTrue(unknown.ended); assertNull(unknown.outTimeUs); assertNull(unknown.speed)
        parser.accept("out_time=00:01:02.123456")
        parser.accept("speed=2.5x")
        val progress = assertNotNull(parser.accept("progress=continue"))
        assertEquals(62_123_456, progress.outTimeUs); assertEquals(2.5, progress.speed)
        assertEquals(1.0, progress.fraction(1_000_000))
    }
    @Test fun commandMapsAllStreamsWithoutSeekingShorteningOrShellInterpolation() {
        val plan = samplePlan().copy(source = samplePlan().source.copy(path = LocalPath("D:/中文 空格/$(literal).mp4")))
        val args = CommandPlanner().encode(plan, LocalPath("D:/work/candidate.partial.mp4"))
        assertEquals(plan.source.path.value, args[args.indexOf("-i") + 1])
        assertTrue("0:0" in args && "0:1" in args)
        assertFalse(args.any { it in setOf("-ss", "-shortest", "-t", "-y", "cmd", "-nostdin") })
        assertEquals("copy", args[args.indexOf("-c:a") + 1])
    }
    @Test fun validatorRejectsMissingAudioWrongDurationAndColorChange() {
        val validator = FullMediaValidator(unusedRunner, FfprobeAdapter(unusedRunner), FakeEnvironment())
        val plan = samplePlan()
        validator.compare(plan, plan.media)
        assertFailsWith<CompressException> { validator.compare(plan, plan.media.copy(streams = plan.media.streams.take(1))) }
        assertFailsWith<CompressException> { validator.compare(plan, plan.media.copy(durationUs = 100_000)) }
        assertFailsWith<CompressException> { validator.compare(plan, plan.media.copy(streams = plan.media.streams.map { if (it.kind == StreamKind.VIDEO) it.copy(colorTransfer = "smpte2084") else it })) }
    }
}
