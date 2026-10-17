package org.ergoutree.compress.infrastructure

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.ports.*
import org.ergoutree.compress.testing.samplePlan
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.*

/** File protocol tests use byte fixtures; valid media/decoder evidence is tested separately. */
class CopyPublicationTest {
    @get:Rule val temporary = TemporaryFolder()
    private data class Fixture(val plan: ResolvedPlan, val files: NioEnvironment, val store: NioCopyArtifacts, val work: AttemptWorkspace, val receipt: ValidationReceipt)
    private suspend fun fixture(): Fixture {
        val root = temporary.newFolder().toPath()
        val source = Files.writeString(root.resolve("source.mp4"), "original").local()
        val output = Files.createDirectory(root.resolve("output"))
        val files = NioEnvironment()
        val plan = samplePlan().copy(source = files.identity(source), destination = output.resolve("source.mp4").local())
        val hash = files.sha256(SnapshotJson.encodeToString(plan))
        val store = NioCopyArtifacts(root.local(), files)
        val work = store.create(plan, hash)
        Files.writeString(work.candidate.nio(), "encoded-byte-fixture")
        val receipt = ValidationReceipt(planHash = hash, source = files.evidence(source), candidate = files.evidence(work.candidate), outputMedia = plan.media, audioPacketDigests = emptyList(), fullDecodePassed = true)
        return Fixture(plan, files, store, work, receipt)
    }
    @Test fun publicationRetainsIndependentCandidateAndOriginal() = runBlocking {
        val f = fixture()
        f.store.publish(f.store.preparePublication(f.plan, f.receipt, f.work, StopSignal()))
        assertEquals("original", Files.readString(f.plan.source.path.nio()))
        assertEquals("encoded-byte-fixture", Files.readString(f.plan.destination.nio()))
        assertFalse(Files.isSameFile(f.plan.destination.nio(), f.work.candidate.nio()))
        Files.writeString(f.plan.destination.nio(), "user edits output")
        assertEquals("encoded-byte-fixture", Files.readString(f.work.candidate.nio()))
    }
    @Test fun existingAndRepeatedPublicationNeverReplaceTarget() = runBlocking {
        val f = fixture()
        Files.writeString(f.plan.destination.nio(), "external")
        assertEquals(ErrorCode.DESTINATION_CONFLICT, assertFailsWith<CompressException> { f.store.preparePublication(f.plan, f.receipt, f.work, StopSignal()) }.code)
        assertEquals("external", Files.readString(f.plan.destination.nio()))
        assertEquals("original", Files.readString(f.plan.source.path.nio()))
        val race = fixture()
        val prepared = race.store.preparePublication(race.plan, race.receipt, race.work, StopSignal())
        Files.writeString(race.plan.destination.nio(), "arrived-after-preparation")
        assertEquals(ErrorCode.DESTINATION_CONFLICT, assertFailsWith<CompressException> { race.store.publish(prepared) }.code)
        assertEquals("arrived-after-preparation", Files.readString(race.plan.destination.nio()))
    }
    @Test fun sourceChangesAndCandidateChangesBlockPublication() = runBlocking {
        val first = fixture(); Files.writeString(first.plan.source.path.nio(), "changed")
        assertEquals(ErrorCode.INPUT_CHANGED, assertFailsWith<CompressException> { first.store.preparePublication(first.plan, first.receipt, first.work, StopSignal()) }.code)
        assertFalse(Files.exists(first.plan.destination.nio()))
        val second = fixture(); Files.writeString(second.work.candidate.nio(), "changed")
        assertEquals(ErrorCode.VALIDATION_FAILED, assertFailsWith<CompressException> { second.store.preparePublication(second.plan, second.receipt, second.work, StopSignal()) }.code)
        assertFalse(Files.exists(second.plan.destination.nio()))
        val afterPreparation = fixture()
        val prepared = afterPreparation.store.preparePublication(afterPreparation.plan, afterPreparation.receipt, afterPreparation.work, StopSignal())
        Files.writeString(prepared.staged.identity.path.nio(), "changed staging with a different size")
        assertEquals(ErrorCode.VALIDATION_FAILED, assertFailsWith<CompressException> { afterPreparation.store.publish(prepared) }.code)
        assertFalse(Files.exists(afterPreparation.plan.destination.nio()))
    }
    @Test fun receiptMustBindPlanCandidateAndFullDecode() = runBlocking {
        val f = fixture()
        for (receipt in listOf(f.receipt.copy(planHash = "wrong"), f.receipt.copy(fullDecodePassed = false), f.receipt.copy(candidate = f.receipt.source))) {
            assertEquals(ErrorCode.VALIDATION_FAILED, assertFailsWith<CompressException> { f.store.preparePublication(f.plan, receipt, f.work, StopSignal()) }.code)
        }
        assertFalse(Files.exists(f.plan.destination.nio()))
    }
}
