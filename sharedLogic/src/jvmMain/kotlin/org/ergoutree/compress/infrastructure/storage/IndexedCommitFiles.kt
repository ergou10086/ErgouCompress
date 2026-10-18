package org.ergoutree.compress.infrastructure.storage

import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

/** 提交日志落盘后登记候选、staging 与原始备份，最终输出始终不登记为垃圾。 */
class IndexedCommitFiles(private val delegate: CommitFiles, private val recorder: ArtifactRecorder, private val archive: LocalPath) : CommitFiles by delegate {
    override suspend fun mirror(record: CommitRecord) {
        delegate.mirror(record)
        if (record.restoreOf == null) recorder.file(record.candidate.identity.path, ArtifactKind.CANDIDATE)
        recorder.file(record.staged.identity.path, ArtifactKind.STAGING, record.id)
        if (record.phase == CommitPhase.COMMITTED && delegate.evidence(record.staged.identity.path) == null) recorder.consumedStaging(record.id)
        record.backup?.let { recorder.file(it, ArtifactKind.ORIGINAL_BACKUP, record.id) }
        recorder.file(LocalPath(archive.value.trimEnd('/', '\\') + "/commits/${record.id}.json"), ArtifactKind.METADATA)
    }
}
