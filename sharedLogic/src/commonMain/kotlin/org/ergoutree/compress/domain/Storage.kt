package org.ergoutree.compress.domain

import kotlinx.serialization.Serializable

/** 区分可重建临时文件、详细日志和需要明确授权的原始备份。 */
@Serializable enum class ArtifactKind { CACHE, LOG, CANDIDATE, STAGING, ORIGINAL_BACKUP, METADATA }
/** 删除采用持久标记与墓碑；丢失文件不能直接从索引消失。 */
@Serializable enum class ArtifactState { LIVE, MARKED, DELETED }
/** 文件必须绑定登记根和强证据，不能仅凭文件名认定归应用所有。 */
@Serializable data class ManagedArtifact(val id: String, val rootId: String, val root: LocalPath, val relativePath: String,
    val kind: ArtifactKind, val evidence: ContentEvidence, val createdAt: Long, val state: ArtifactState = ArtifactState.LIVE,
    val revision: Long = 0, val commitId: String? = null, val cleanupId: String? = null)
/** 自动保留规则只覆盖可再生内容；原始备份没有自动删除开关。 */
@Serializable data class RetentionPolicy(val schemaVersion: Int = 1, val cacheDays: Int = 30, val logDays: Int = 14,
    val cacheBudgetBytes: Long = 512L * 1024 * 1024, val minimumBackupDays: Int = 30) {
    init { require(schemaVersion == 1); require(cacheDays in 0..3650 && logDays in 0..3650 && minimumBackupDays in 0..3650); require(cacheBudgetBytes >= 0) }
}
/** 清理范围由调用方明确选择，覆盖年龄仅适用于可再生缓存。 */
@Serializable data class CleanupOptions(val caches: Boolean = true, val logs: Boolean = true, val candidates: Boolean = false,
    val originalBackups: Boolean = false, val clearCacheNow: Boolean = false)
/** 每个预览项同时保留允许或拒绝的原因，供任何平台界面直接呈现。 */
@Serializable data class CleanupDecision(val artifact: ManagedArtifact, val eligible: Boolean, val reason: String)
/** 确认令牌绑定保存的预览，执行时仍核对状态、引用与文件强证据。 */
@Serializable data class CleanupPreview(val id: String, val confirmation: String, val options: CleanupOptions,
    val createdAt: Long, val decisions: List<CleanupDecision>, val retention: RetentionPolicy = RetentionPolicy())
/** 逻辑删除量不能冒充磁盘实际回收量，尤其存在硬链接时。 */
@Serializable data class CleanupResult(val previewId: String, val deletedLogicalBytes: Long, val deleted: List<String>, val failed: Map<String, String>)
/** 一致性引用视图也包含隐藏任务，隐藏历史不解除媒体保护。 */
data class MaintenanceView(val jobs: List<JobRecord>, val items: List<ItemRecord>, val attempts: List<AttemptRecord>, val commits: List<CommitRecord>, val artifacts: List<ManagedArtifact>)
/** 按真实卷展示剩余容量和应用逻辑占用，保留量与媒体减少量分别统计。 */
@Serializable data class VolumeStorage(val volume: VolumeInfo, val usableBytes: Long, val managedLogicalBytes: Long, val backupBytesRetained: Long, val temporaryBytes: Long)
/** 媒体缩小不等于已释放磁盘空间，因此不提供虚构的精确回收数字。 */
@Serializable data class StorageReport(val volumes: List<VolumeStorage>, val mediaBytesReduced: Long, val explanation: String)
/** 调度预算按卷合并；安全余量以字节表达，避免 MB/MiB 混用。 */
data class SpaceRequirement(val path: LocalPath, val additionalBytes: Long)
/** 迁移结果明确要求重启，当前会话不能继续写入旧目录。 */
@Serializable data class MigrationResult(val id: String, val source: LocalPath, val destination: LocalPath, val rootId: String, val filesVerified: Int, val restartRequired: Boolean = true)
