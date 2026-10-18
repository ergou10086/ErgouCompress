package org.ergoutree.compress.domain

import kotlinx.serialization.Serializable

/** 记录可恢复提交阶段，终结状态必须通过实物核对。 */
@Serializable enum class CommitPhase { PREPARED, PUBLISH_INTENT, PUBLISHED, RETIRE_INTENT, RETIRED, COMMITTED, NEEDS_ATTENTION, CANCELLED }
/** 描述真实卷身份及事务发布能力边界。 */
@Serializable data class VolumeInfo(val id: String, val root: LocalPath, val fileSystem: String, val local: Boolean)
/** 将登记目录绑定卷身份，避免盘符变化时误用。 */
@Serializable data class Workspace(val id: String, val root: LocalPath, val volumeId: String)
/** 保存时间、权限和命名数据流的核对证据。 */
@Serializable data class FileMetadata(val modifiedTime: String, val createdTime: String, val dacl: String, val daclHash: String, val protectedDacl: Boolean, val adsHashes: Map<String, String>)
/** 保存提交意图与媒体强证据，备份释放和迁移身份单独记录。 */
@Serializable data class CommitRecord(
    val schemaVersion: Int = 1, val id: String, val itemId: String, val attemptId: String?, val restoreOf: String? = null,
    val plan: ResolvedPlan, val receipt: ValidationReceipt?, val source: ContentEvidence, val candidate: ContentEvidence,
    val staged: ContentEvidence, val backup: LocalPath?, val workspace: Workspace,
    val sourceMetadata: FileMetadata, val targetMetadata: FileMetadata,
    val phase: CommitPhase = CommitPhase.PREPARED, val revision: Long = 0,
    val output: ContentEvidence? = null, val lastError: Int = 0,
    val backupReleased: Boolean = false, val backupEvidence: ContentEvidence? = null,
)
/** 保留原生调用返回值，最终结果仍由实物证据判断。 */
data class NativeResult(val success: Boolean, val error: Int = 0)
