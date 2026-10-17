package org.ergoutree.compress.application

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.testing.*
import kotlin.test.*

class PlanningTest {
    @Test fun freezeOwnsCollectionsAndRoundTripsLargeNumbersAndRationalFps() = runTest {
        val files = FakeEnvironment()
        val streams = sampleMedia().streams.toMutableList()
        val selected = mutableListOf(PlanningSelection(files.plan.source, sampleMedia().copy(streams = streams)))
        val job = FreezeJob(files).execute("job", selected, sampleTools(), EncodeSettings(), LocalPath("/output"))
        streams.clear(); selected.clear()
        assertEquals(2, job.plans.single().media.streams.size)
        assertEquals(job, SnapshotJson.decodeFromString<FrozenJob>(SnapshotJson.encodeToString(job)))
        val big = files.plan.source.copy(sizeBytes = 9_007_199_254_740_993L)
        assertEquals(big, SnapshotJson.decodeFromString<FileIdentity>(SnapshotJson.encodeToString(big)))
        assertEquals(Rational(30000, 1001), Rational.parse("30000/1001"))
        assertNull(Rational.parse("0/0"))
    }
    @Test fun duplicateTargetsFailBeforeExecution() = runTest {
        val files = FakeEnvironment()
        val selection = PlanningSelection(files.plan.source, sampleMedia())
        val error = assertFailsWith<CompressException> { FreezeJob(files).execute("job", listOf(selection, selection), sampleTools(), EncodeSettings(), LocalPath("/output")) }
        assertEquals(ErrorCode.DESTINATION_CONFLICT, error.code)
    }
    @Test fun existingTargetIsNotOverwritten() = runTest {
        val files = FakeEnvironment().apply { conflict = true }
        val error = assertFailsWith<CompressException> { FreezeJob(files).execute("job", listOf(PlanningSelection(files.plan.source, sampleMedia())), sampleTools(), EncodeSettings(), LocalPath("/output")) }
        assertEquals(ErrorCode.DESTINATION_CONFLICT, error.code)
    }
    @Test fun insufficientBudgetAndUnavailableGpuAreBlocked() = runTest {
        val files = FakeEnvironment().apply { space = 1 }
        assertEquals(ErrorCode.INSUFFICIENT_SPACE, assertFailsWith<CompressException> { FreezeJob(files).execute("job", listOf(PlanningSelection(files.plan.source, sampleMedia())), sampleTools(), EncodeSettings(), LocalPath("/output")) }.code)
        assertEquals(ErrorCode.TOOLCHAIN_INVALID, assertFailsWith<CompressException> { FreezeJob(files).execute("job", listOf(PlanningSelection(files.plan.source, sampleMedia())), sampleTools(), EncodeSettings(Backend.NVENC, 28), LocalPath("/output")) }.code)
    }
    @Test fun complexMediaIsRejectedRatherThanDroppingStreams() {
        val media = sampleMedia()
        assertNotNull(MediaPolicy.rejection(media.copy(streams = media.streams + MediaStream(2, StreamKind.SUBTITLE, "ass")), Container.MP4))
        assertNotNull(MediaPolicy.rejection(media.copy(streams = media.streams.map { if (it.kind == StreamKind.VIDEO) it.copy(colorTransfer = "smpte2084") else it }), Container.MP4))
        assertNull(MediaPolicy.rejection(media, Container.MP4))
    }
}
