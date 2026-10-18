package org.ergoutree.compress.ports

import org.ergoutree.compress.domain.*

/** 为清理提供事务化引用视图、一次性预览和持久删除意图。 */
interface MaintenanceStore {
    suspend fun maintenanceView(): MaintenanceView
    suspend fun registerArtifact(artifact: ManagedArtifact): ManagedArtifact
    suspend fun savePreview(preview: CleanupPreview)
    suspend fun preview(id: String): CleanupPreview
    suspend fun claimPreview(id: String, confirmation: String): CleanupPreview
    suspend fun completeDeletion(artifactId: String)
    suspend fun undoDeletionMark(artifactId: String)
    suspend fun consumePublishedStaging(commitId: String)
    suspend fun backupDatabase(path: LocalPath)
}
/** 文件实现必须验证登记根、相对路径和重解析点，删除只接受完整登记项。 */
interface ManagedStorageFiles {
    suspend fun evidence(artifact: ManagedArtifact): ContentEvidence?
    /** 仅在登记根可验证且文件确定不存在时返回 true，离线和拒绝访问不等于已删除。 */
    suspend fun missing(artifact: ManagedArtifact): Boolean
    suspend fun delete(artifact: ManagedArtifact)
    suspend fun volume(path: LocalPath): VolumeInfo
    suspend fun usableBytes(path: LocalPath): Long
    suspend fun evidence(path: LocalPath): ContentEvidence?
}
/** 生产者只登记自己创建的文件或独占工作目录，不扫描用户素材认领所有权。 */
interface ArtifactRecorder {
    suspend fun file(path: LocalPath, kind: ArtifactKind, commitId: String? = null)
    suspend fun directory(path: LocalPath)
    suspend fun consumedStaging(commitId: String) {}
}
/** 默认空实现保留既有适配器的构造兼容，正式组合根必须注入登记器。 */
object NoopArtifactRecorder : ArtifactRecorder {
    override suspend fun file(path: LocalPath, kind: ArtifactKind, commitId: String?) {}
    override suspend fun directory(path: LocalPath) {}
}
/** 保留设置由平台持久化，通用清理规则无需知道 JSON 文件位置。 */
interface RetentionRepository { suspend fun load(): RetentionPolicy; suspend fun save(policy: RetentionPolicy) }
/** 编码前预算与运行中低空间保护复用同一个卷检查策略。 */
interface SpaceAdmission { suspend fun requirements(plan: ResolvedPlan): List<SpaceRequirement>; suspend fun enough(requirements: List<SpaceRequirement>): Boolean }
