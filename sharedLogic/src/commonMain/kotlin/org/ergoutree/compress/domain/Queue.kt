package org.ergoutree.compress.domain

import kotlinx.serialization.Serializable

@Serializable enum class JobState { READY, RUNNING, PAUSE_REQUESTED, PAUSED, COMPLETED, COMPLETED_WITH_ERRORS, CANCELLED, NEEDS_ATTENTION }
@Serializable enum class ItemState { READY, RUNNING, INTERRUPTED, SUCCEEDED, FAILED, NEEDS_ATTENTION, CANCELLED, SKIPPED }
@Serializable enum class ExecutionState { RUNNING, VALIDATED, PUBLISHING, PUBLISHED, SUCCEEDED, FAILED, INTERRUPTED, NEEDS_ATTENTION, CANCELLED }
@Serializable data class JobRecord(val id: String, val snapshot: FrozenJob, val state: JobState, val revision: Long, val createdAt: Long)
@Serializable data class ItemRecord(val id: String, val jobId: String, val ordinal: Long, val plan: ResolvedPlan, val state: ItemState, val revision: Long, val attemptSequence: Long)
@Serializable data class AttemptRecord(val id: String, val itemId: String, val sequence: Long, val state: ExecutionState, val stopRequested: Boolean,
    val workspace: WorkspaceRecord?, val validation: ValidationReceipt?, val output: ContentEvidence?, val diagnostic: String?)
@Serializable data class WorkspaceRecord(val id: String, val root: LocalPath, val candidate: LocalPath, val source: ContentEvidence, val planHash: String)
@Serializable data class DurableEvent(val sequence: Long, val itemId: String?, val type: String, val payload: String, val createdAt: Long)
data class ClaimedItem(val item: ItemRecord, val attemptId: String, val owner: String, val epoch: Long)
data class RecoveryItem(val item: ItemRecord, val attempt: AttemptRecord?)
