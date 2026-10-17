package org.ergoutree.compress.application

import kotlinx.coroutines.test.runTest
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*
import org.ergoutree.compress.testing.*
import kotlin.test.*

class CopySafetyTest {
    private class Encoder(private val action: suspend (StopSignal) -> Unit = {}) : EncoderAdapter {
        override suspend fun encode(plan: ResolvedPlan, candidate: LocalPath, work: LocalPath, stop: StopSignal, onProgress: (Progress) -> Unit) {
            onProgress(Progress(3_000_000, 500, 1.0, true))
            action(stop)
        }
    }
    private class Validator(private val fail: Boolean = false, private val action: suspend (StopSignal) -> Unit = {}) : CandidateValidator {
        var calls = 0
        override suspend fun validate(plan: ResolvedPlan, planHash: String, source: ContentEvidence, candidate: LocalPath, work: LocalPath, stop: StopSignal): ValidationReceipt {
            calls++; action(stop)
            if (fail) throw CompressException(ErrorCode.VALIDATION_FAILED, "truncated")
            return ValidationReceipt(planHash = planHash, source = source, candidate = source.copy(identity = source.identity.copy(path = candidate)), outputMedia = plan.media, audioPacketDigests = emptyList(), fullDecodePassed = true)
        }
    }
    @Test fun progressEndStillRequiresValidationBeforePublication() = runTest {
        val files = FakeEnvironment(); val artifacts = FakeArtifacts(); val validator = Validator()
        val events = mutableListOf<AttemptStage>()
        val result = RunCopy(files, Encoder(), validator, artifacts).execute(samplePlan(), StopSignal()) { events += it.stage }
        assertEquals(AttemptStage.SUCCEEDED, result.stage)
        assertEquals(1, validator.calls); assertEquals(1, artifacts.published)
        assertTrue(events.indexOf(AttemptStage.VALIDATING) < events.indexOf(AttemptStage.PUBLISHING))
        assertEquals("validation.json", artifacts.records.first())
    }
    @Test fun quitWithSuccessfulEncoderOutcomeDoesNotValidateOrPublish() = runTest {
        val artifacts = FakeArtifacts(); val validator = Validator()
        val result = RunCopy(FakeEnvironment(), Encoder { it.request() }, validator, artifacts).execute(samplePlan(), StopSignal())
        assertEquals(AttemptStage.INTERRUPTED, result.stage); assertEquals(0, validator.calls); assertEquals(0, artifacts.published)
    }
    @Test fun truncatedCandidateNeverPublishes() = runTest {
        val artifacts = FakeArtifacts()
        val result = RunCopy(FakeEnvironment(), Encoder(), Validator(fail = true), artifacts).execute(samplePlan(), StopSignal())
        assertEquals(ErrorCode.VALIDATION_FAILED, result.error); assertEquals(0, artifacts.published)
    }
    @Test fun stopDuringValidationNeverPublishes() = runTest {
        val artifacts = FakeArtifacts()
        val result = RunCopy(FakeEnvironment(), Encoder(), Validator { it.request() }, artifacts).execute(samplePlan(), StopSignal())
        assertEquals(AttemptStage.INTERRUPTED, result.stage); assertEquals(0, artifacts.published)
    }
    @Test fun changedSourcePreventsEncoder() = runTest {
        val files = FakeEnvironment().apply { changed = true }
        val artifacts = FakeArtifacts()
        val result = RunCopy(files, Encoder { error("must not encode") }, Validator(), artifacts).execute(samplePlan(), StopSignal())
        assertEquals(ErrorCode.INPUT_CHANGED, result.error); assertEquals(0, artifacts.published)
    }
    @Test fun stopDuringCopyPreparationNeverEntersPublication() = runTest {
        val artifacts = FakeArtifacts().apply { stopInPreparation = true }
        val result = RunCopy(FakeEnvironment(), Encoder(), Validator(), artifacts).execute(samplePlan(), StopSignal())
        assertEquals(AttemptStage.INTERRUPTED, result.stage); assertEquals(0, artifacts.published)
    }
}
