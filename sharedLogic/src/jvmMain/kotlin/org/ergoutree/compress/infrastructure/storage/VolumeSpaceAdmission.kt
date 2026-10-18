package org.ergoutree.compress.infrastructure.storage

import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

/** 将候选与目标 staging 的保守预算合并到真实卷，避免同卷重复扣安全余量。 */
class VolumeSpaceAdmission(private val files: ManagedStorageFiles, private val root: LocalPath, private val safetyBytes: Long = 64L * 1024 * 1024) : SpaceAdmission {
    override suspend fun requirements(plan: ResolvedPlan): List<SpaceRequirement> {
        require(plan.source.sizeBytes <= (Long.MAX_VALUE - safetyBytes) / 4) { "空间预算溢出" }
        val target = java.nio.file.Path.of(plan.destination.value).parent.toString()
        val routes = listOf(root, LocalPath(target))
        val grouped = routes.groupBy { files.volume(it).id }
        return grouped.values.map { paths -> SpaceRequirement(paths.first(), plan.source.sizeBytes * 2 * paths.size) }
    }
    override suspend fun enough(requirements: List<SpaceRequirement>): Boolean = requirements.all { files.usableBytes(it.path) >= it.additionalBytes + safetyBytes }
}
