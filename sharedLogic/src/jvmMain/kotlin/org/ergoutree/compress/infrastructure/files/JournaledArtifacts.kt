package org.ergoutree.compress.infrastructure.files

import org.ergoutree.compress.application.CommitCoordinator
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

/** Each claimed attempt has its own adapter; no shared mutable current-item context. */
class JournaledArtifacts(private val archive: CopyArtifacts, private val commits: CommitCoordinator, private val claim: ClaimedItem) : CopyArtifacts {
    private var record: CommitRecord? = null
    override suspend fun create(plan: ResolvedPlan, planHash: String) = archive.create(plan, planHash)
    override suspend fun record(work: AttemptWorkspace, name: String, json: String) = archive.record(work, name, json)
    override suspend fun preparePublication(plan: ResolvedPlan, receipt: ValidationReceipt, work: AttemptWorkspace, stop: StopSignal): PreparedCopy {
        val prepared = commits.prepare(claim, plan, receipt, stop)
        record = prepared
        return PreparedCopy(plan, receipt, work, prepared.staged)
    }
    override suspend fun publish(prepared: PreparedCopy) = commits.publish(requireNotNull(record).id)
}
