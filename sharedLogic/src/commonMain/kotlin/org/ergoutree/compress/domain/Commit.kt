package org.ergoutree.compress.domain

import kotlinx.serialization.Serializable

@Serializable enum class CommitPhase { PREPARED, PUBLISH_INTENT, PUBLISHED, RETIRE_INTENT, RETIRED, COMMITTED, NEEDS_ATTENTION, CANCELLED }
@Serializable data class VolumeInfo(val id: String, val root: LocalPath, val fileSystem: String, val local: Boolean)
@Serializable data class Workspace(val id: String, val root: LocalPath, val volumeId: String)
@Serializable data class FileMetadata(val modifiedTime: String, val createdTime: String, val dacl: String, val daclHash: String, val protectedDacl: Boolean, val adsHashes: Map<String, String>)
@Serializable data class CommitRecord(
    val schemaVersion: Int = 1, val id: String, val itemId: String, val attemptId: String?, val restoreOf: String? = null,
    val plan: ResolvedPlan, val receipt: ValidationReceipt?, val source: ContentEvidence, val candidate: ContentEvidence,
    val staged: ContentEvidence, val backup: LocalPath?, val workspace: Workspace,
    val sourceMetadata: FileMetadata, val targetMetadata: FileMetadata,
    val phase: CommitPhase = CommitPhase.PREPARED, val revision: Long = 0,
    val output: ContentEvidence? = null, val lastError: Int = 0,
)
data class NativeResult(val success: Boolean, val error: Int = 0)
