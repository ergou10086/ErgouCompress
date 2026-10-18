package org.ergoutree.compress.testing

import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.application.PlanEncoding
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.ports.*
import java.nio.file.Files
import java.nio.file.Path

class QueueFixture(val root: Path) {
    val files = NioEnvironment()
    suspend fun job(id: String = "job", count: Int = 2): FrozenJob {
        val input = Files.createDirectories(root.resolve("inputs"))
        val output = Files.createDirectories(root.resolve("outputs"))
        val plans = (0 until count).map { index ->
            val source = input.resolve("$id-$index.mp4")
            if (!Files.exists(source)) Files.writeString(source, "source-$index")
            samplePlan().copy(source = files.identity(source.local()), destination = output.resolve("$id-$index.mp4").local())
        }
        return FrozenJob(id = id, plans = plans, planHash = files.sha256(PlanEncoding.canonicalList(plans)))
    }
    suspend fun workspace(plan: ResolvedPlan, id: String): Pair<AttemptWorkspace, ValidationReceipt> {
        val dir = Files.createDirectories(root.resolve("attempts/$id"))
        val candidate = Files.writeString(dir.resolve("candidate.mp4"), "encoded-${plan.source.path.value}").local()
        val receipt = ValidationReceipt(planHash = files.sha256(PlanEncoding.canonical(plan)), source = files.evidence(plan.source.path),
            candidate = files.evidence(candidate), outputMedia = plan.media, audioPacketDigests = emptyList(), fullDecodePassed = true)
        return AttemptWorkspace(id, dir.local(), candidate) to receipt
    }
    suspend fun complete(plan: ResolvedPlan, stop: StopSignal, lifecycle: AttemptLifecycle, id: String): AttemptResult {
        stop.checkRunning()
        val (work, receipt) = workspace(plan, id)
        lifecycle.created(work, receipt.source, receipt.planHash)
        lifecycle.validated(receipt)
        stop.publish {
            lifecycle.publishing(PreparedCopy(plan, receipt, work, receipt.candidate))
            Files.copy(work.candidate.nio(), plan.destination.nio())
            lifecycle.published(files.evidence(plan.destination))
        }
        return AttemptResult(AttemptStage.SUCCEEDED, plan.destination, message = "fixture completed")
    }
}
