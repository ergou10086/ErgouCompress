package org.ergoutree.compress.application

import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

/** 按卷汇总实际存在的登记产物，分别呈现媒体减少量和仍占用的备份。 */
class StorageOverview(private val store: MaintenanceStore, private val files: ManagedStorageFiles, private val dataRoot: LocalPath) {
    suspend fun report(): StorageReport {
        val view = store.maintenanceView()
        val roots = (listOf(dataRoot) + view.artifacts.map { it.root }).distinct()
        val entries = view.artifacts.filter { it.state != ArtifactState.DELETED }.mapNotNull { artifact ->
            files.evidence(artifact)?.let { Triple(files.volume(artifact.root), artifact.kind, it.identity.sizeBytes) }
        }
        val volumes = roots.map { files.volume(it) }.distinctBy { it.id }.map { volume ->
            val own = entries.filter { it.first.id == volume.id }
            VolumeStorage(volume, files.usableBytes(volume.root), own.sumOf { it.third }, own.filter { it.second == ArtifactKind.ORIGINAL_BACKUP }.sumOf { it.third },
                own.filter { it.second in setOf(ArtifactKind.CACHE, ArtifactKind.CANDIDATE, ArtifactKind.STAGING) }.sumOf { it.third })
        }
        val reduced = view.commits.filter { it.restoreOf == null && it.phase == CommitPhase.COMMITTED }.sumOf { (it.source.identity.sizeBytes - (CommitLineage.current(it, view.commits).output?.identity?.sizeBytes ?: it.source.identity.sizeBytes)).coerceAtLeast(0) }
        return StorageReport(volumes, reduced, "媒体逻辑减少量、备份保留量和临时文件分别统计；硬链接/文件系统分配及其他进程写入使逻辑删除量不等于精确磁盘回收量")
    }
}
