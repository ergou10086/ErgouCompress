package org.ergoutree.compress.ports

import org.ergoutree.compress.domain.*

fun interface Clock { fun nowMillis(): Long }
fun interface IdGenerator { fun next(): String }
interface JobStore {
    suspend fun enqueue(job: FrozenJob, now: Long)
    suspend fun job(id: String): JobRecord
    suspend fun history(): List<JobRecord>
    suspend fun items(jobId: String): List<ItemRecord>
    suspend fun attempts(itemId: String): List<AttemptRecord>
    suspend fun events(jobId: String): List<DurableEvent>
    suspend fun resume(jobId: String, now: Long)
    suspend fun claim(jobId: String, owner: String, attemptId: String, now: Long): ClaimedItem?
    suspend fun requestPause(jobId: String, immediate: Boolean, now: Long)
    suspend fun cancel(jobId: String, now: Long)
    suspend fun retry(itemId: String, now: Long)
    suspend fun hide(jobId: String)
    suspend fun workspace(claim: ClaimedItem, work: WorkspaceRecord, now: Long)
    suspend fun validated(claim: ClaimedItem, receipt: ValidationReceipt, now: Long)
    suspend fun publishing(claim: ClaimedItem, now: Long)
    suspend fun published(claim: ClaimedItem, output: ContentEvidence, now: Long)
    suspend fun finish(claim: ClaimedItem, result: AttemptResult, now: Long)
    suspend fun recoveryItems(): List<RecoveryItem>
    suspend fun recovered(itemId: String, state: ItemState, output: ContentEvidence?, diagnostic: String, now: Long)
}
interface AttemptLifecycle {
    suspend fun created(work: AttemptWorkspace, source: ContentEvidence, planHash: String) {}
    suspend fun validated(receipt: ValidationReceipt) {}
    suspend fun publishing(prepared: PreparedCopy) {}
    suspend fun published(output: ContentEvidence) {}
}
object NoopAttemptLifecycle : AttemptLifecycle
fun interface ItemExecutor {
    suspend fun execute(plan: ResolvedPlan, stop: StopSignal, lifecycle: AttemptLifecycle, emit: (AttemptEvent) -> Unit): AttemptResult
}
