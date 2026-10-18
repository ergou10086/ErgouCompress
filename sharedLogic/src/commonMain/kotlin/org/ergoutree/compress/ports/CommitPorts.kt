package org.ergoutree.compress.ports

import org.ergoutree.compress.domain.*

interface CommitFiles {
    suspend fun volume(path: LocalPath): VolumeInfo
    suspend fun evidence(path: LocalPath): ContentEvidence?
    suspend fun copy(source: LocalPath, destination: LocalPath, stop: StopSignal)
    suspend fun prepareDirectory(root: LocalPath, id: String): LocalPath
    suspend fun metadata(path: LocalPath): FileMetadata
    suspend fun prepareMetadata(source: LocalPath, staged: LocalPath, metadata: FileMetadata)
    suspend fun applyMetadata(target: LocalPath, metadata: FileMetadata, preserveTime: Boolean)
    suspend fun replace(source: LocalPath, staged: LocalPath, backup: LocalPath): NativeResult
    suspend fun publishNew(staged: LocalPath, target: LocalPath): NativeResult
    suspend fun retire(source: LocalPath, backup: LocalPath): NativeResult
    suspend fun mirror(record: CommitRecord)
}
interface CommitStore {
    suspend fun createCommit(record: CommitRecord, claim: ClaimedItem?, now: Long)
    suspend fun saveCommit(record: CommitRecord, now: Long): CommitRecord
    suspend fun commits(): List<CommitRecord>
    suspend fun commit(id: String): CommitRecord
    suspend fun registerWorkspace(workspace: Workspace)
    suspend fun workspaces(): List<Workspace>
    suspend fun canResumeCommit(record: CommitRecord): Boolean
}
interface WorkspaceRegistry {
    suspend fun forVolume(volume: VolumeInfo): Workspace
    suspend fun register(root: LocalPath): Workspace
}
fun interface CommitCheckpoint { fun reached(point: String, record: CommitRecord) }
