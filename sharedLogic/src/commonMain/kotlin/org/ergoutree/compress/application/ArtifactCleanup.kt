package org.ergoutree.compress.application

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

/** 将清理拆为预览、引用复核、持久标记与删除，原始素材和审计元数据不可选。 */
class ArtifactCleanup(private val store: MaintenanceStore, private val files: ManagedStorageFiles,
    private val retention: RetentionRepository, private val gate: MaintenanceGate, private val clock: Clock, private val ids: IdGenerator) {
    private fun requireSettled(view: MaintenanceView) {
        check(view.items.none { it.state in setOf(ItemState.RUNNING, ItemState.NEEDS_ATTENTION) }) { "活动或待核对任务禁止清理" }
        check(view.commits.all { it.phase in setOf(CommitPhase.COMMITTED, CommitPhase.CANCELLED) }) { "存在未决提交，禁止清理" }
    }
    private suspend fun decisions(view: MaintenanceView, options: CleanupOptions, policy: RetentionPolicy): List<CleanupDecision> {
        val protectedPaths = view.items.flatMap { listOf(it.plan.source.path, it.plan.destination, it.plan.tools.ffmpeg.path, it.plan.tools.ffprobe.path) }.toSet()
        val protectedRoots = view.attempts.filter { attempt -> view.items.any { it.id == attempt.itemId && it.state !in setOf(ItemState.SUCCEEDED, ItemState.FAILED, ItemState.SKIPPED, ItemState.CANCELLED) } }.mapNotNull { it.workspace?.root }
        val cacheTotal = view.artifacts.filter { it.kind == ArtifactKind.CACHE && it.state != ArtifactState.DELETED }.sumOf { it.evidence.identity.sizeBytes }
        var cacheToEvict = (cacheTotal - policy.cacheBudgetBytes).coerceAtLeast(0)
        return view.artifacts.filter { it.state == ArtifactState.LIVE }.sortedBy { it.createdAt }.map { artifact ->
            val path = artifact.evidence.identity.path
            val age = (clock.nowMillis() - artifact.createdAt).coerceAtLeast(0)
            val protectedOwner = protectedRoots.any { path.value.replace('\\', '/').startsWith(it.value.replace('\\', '/').trimEnd('/') + "/", true) }
            val related = view.commits.filter { it.id == artifact.commitId || listOfNotNull(it.candidate.identity.path, it.staged.identity.path, it.backup).any(path::sameLocation) }
            val reason = when {
                artifact.kind == ArtifactKind.METADATA -> "审计证据长期保留"
                protectedPaths.any(path::sameLocation) -> "源文件、最终输出与工具不可清理"
                protectedOwner -> "未终结任务仍引用该工作目录"
                files.evidence(artifact) != artifact.evidence -> "文件缺失、身份改变或路径不安全"
                artifact.kind == ArtifactKind.ORIGINAL_BACKUP && !options.originalBackups -> "默认保留原始备份"
                artifact.kind == ArtifactKind.ORIGINAL_BACKUP && (related.count { it.backup?.sameLocation(path) == true } != 1 || related.single { it.backup?.sameLocation(path) == true }.backupReleased) -> "原始备份引用不明确"
                artifact.kind == ArtifactKind.ORIGINAL_BACKUP && age < policy.minimumBackupDays * 86_400_000L -> "尚未达到原始备份最短保留期"
                artifact.kind == ArtifactKind.CACHE && !options.caches -> "未选择缓存"
                artifact.kind == ArtifactKind.CACHE && !options.clearCacheNow && age < policy.cacheDays * 86_400_000L && cacheToEvict <= 0 -> "缓存未过期且未超过容量预算"
                artifact.kind == ArtifactKind.LOG && (!options.logs || age < policy.logDays * 86_400_000L) -> "详细日志尚未到清理期或未选择"
                artifact.kind in setOf(ArtifactKind.CANDIDATE, ArtifactKind.STAGING) && !options.candidates -> "未选择候选与 staging"
                related.any { record ->
                    if (record.phase == CommitPhase.CANCELLED) files.evidence(record.source.identity.path) != record.source || files.evidence(record.candidate.identity.path) != record.candidate
                    else CommitLineage.current(record, view.commits).let { it.output == null || files.evidence(it.plan.destination) != it.output }
                } -> "最终输出尚未通过实物复核"
                related.isEmpty() && artifact.kind in setOf(ArtifactKind.CANDIDATE, ArtifactKind.STAGING) && view.attempts.none { a -> a.workspace?.let { it.candidate == path && it.source == files.evidence(it.source.identity.path) } == true } -> "无法证明候选可从原始输入重建"
                else -> null
            }
            if (reason == null && artifact.kind == ArtifactKind.CACHE) cacheToEvict -= artifact.evidence.identity.sizeBytes
            CleanupDecision(artifact, reason == null, reason ?: if (artifact.kind == ArtifactKind.ORIGINAL_BACKUP) "明确确认后将失去此原始版本恢复能力" else "可清理；逻辑字节不等于实际回收")
        }
    }
    suspend fun preview(options: CleanupOptions = CleanupOptions()): CleanupPreview = gate.exclusive {
        val view = store.maintenanceView(); requireSettled(view)
        val policy = retention.load()
        CleanupPreview(ids.next(), ids.next(), options, clock.nowMillis(), decisions(view, options, policy), policy).also { store.savePreview(it) }
    }
    suspend fun execute(id: String, confirmation: String, acknowledgeBackupLoss: Boolean = false): CleanupResult = gate.exclusive {
        val preview = store.preview(id)
        require(preview.confirmation == confirmation) { "预览确认令牌不符" }
        require(clock.nowMillis() - preview.createdAt in 0..900_000) { "预览已过期，请重新预览" }
        require(!preview.options.originalBackups || acknowledgeBackupLoss) { "原始备份删除必须明确确认无法恢复的后果" }
        val view = store.maintenanceView(); requireSettled(view)
        val policy = retention.load()
        check(policy == preview.retention) { "保留策略变化，请重新预览" }
        val current = decisions(view, preview.options, policy).associateBy { it.artifact.id }
        preview.decisions.filter { it.eligible }.forEach { old ->
            val fresh = current[old.artifact.id]
            check(fresh?.eligible == true && fresh.artifact == old.artifact) { "预览后文件、引用或保留策略发生变化，请重新预览" }
        }
        val claimed = store.claimPreview(id, confirmation)
        val deleted = mutableListOf<String>(); val failures = mutableMapOf<String, String>(); var bytes = 0L
        // 文件删除与墓碑落库属于短关键区；取消不能留下“已删但未知”的正常返回。
        withContext(NonCancellable) {
            for (decision in claimed.decisions.filter { it.eligible }) {
                val artifact = decision.artifact
                try {
                    check(files.evidence(artifact) == artifact.evidence) { "删除前文件强证据变化" }
                    files.delete(artifact)
                    store.completeDeletion(artifact.id)
                    deleted += artifact.id; bytes += artifact.evidence.identity.sizeBytes
                } catch (failure: Exception) {
                    failures[artifact.id] = failure.message ?: "删除失败"
                    // 若文件已消失而落库失败，保留 MARKED，交由启动对账补记。
                    if (!files.missing(artifact)) store.undoDeletionMark(artifact.id)
                }
            }
        }
        CleanupResult(id, bytes, deleted, failures)
    }
    suspend fun reconcile() = gate.exclusive {
        for (artifact in store.maintenanceView().artifacts.filter { it.state == ArtifactState.MARKED }) {
            // 根离线或标记变化只能撤回删除意图，不能误记墓碑并释放备份引用。
            if (files.missing(artifact)) store.completeDeletion(artifact.id) else store.undoDeletionMark(artifact.id)
        }
    }
}
