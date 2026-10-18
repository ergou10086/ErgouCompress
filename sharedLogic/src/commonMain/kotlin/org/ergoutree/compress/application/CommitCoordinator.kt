package org.ergoutree.compress.application

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

/** Database intent precedes every namespace mutation. Native return values are never completion proof. */
class CommitCoordinator(private val store: CommitStore, private val jobs: JobStore, private val files: CommitFiles,
    private val environment: PlanEnvironment, private val registry: WorkspaceRegistry, private val clock: Clock, private val ids: IdGenerator,
    private val checkpoint: CommitCheckpoint = CommitCheckpoint { _, _ -> }) : TransactionRecovery {
    private val gate = Mutex()
    private fun child(root: LocalPath, name: String) = LocalPath(root.value.trimEnd('/', '\\') + "/" + name)
    private suspend fun durable(record: CommitRecord): CommitRecord {
        val saved = store.saveCommit(record, clock.nowMillis())
        checkpoint.reached("After${saved.phase}Db", saved)
        files.mirror(saved)
        checkpoint.reached("After${saved.phase}Mirror", saved)
        return saved
    }
    suspend fun prepare(claim: ClaimedItem, plan: ResolvedPlan, receipt: ValidationReceipt, stop: StopSignal): CommitRecord {
        stop.checkRunning()
        require(receipt.fullDecodePassed && receipt.planHash == environment.sha256(PlanEncoding.canonical(plan))) { "验证凭据与冻结计划不符" }
        require(receipt.source.identity == plan.source && receipt.candidate.identity.sizeBytes > 0)
        if (files.evidence(plan.source.path) != receipt.source) throw CompressException(ErrorCode.INPUT_CHANGED, "提交准备前源内容已变化")
        if (files.evidence(receipt.candidate.identity.path) != receipt.candidate) throw CompressException(ErrorCode.VALIDATION_FAILED, "候选验证后发生变化")
        if (receipt.source.identity.fileKey.isNotEmpty() && receipt.source.identity.fileKey == receipt.candidate.identity.fileKey) throw CompressException(ErrorCode.VALIDATION_FAILED, "候选不能是源的硬链接")
        if (plan.outputMode != OutputMode.COPY && plan.onlyIfSmaller && receipt.candidate.identity.sizeBytes >= receipt.source.identity.sizeBytes)
            throw CompressException(ErrorCode.NO_BENEFIT, "候选未变小，按冻结策略保留源文件")
        if (plan.outputMode == OutputMode.REPLACE) require(plan.destination == plan.source.path)
        if (plan.outputMode != OutputMode.REPLACE && files.evidence(plan.destination) != null) throw CompressException(ErrorCode.DESTINATION_CONFLICT, "目标已存在")
        val volume = files.volume(if (plan.outputMode == OutputMode.COPY) plan.destination else plan.source.path)
        require(volume.local && volume.fileSystem.equals("NTFS", true)) { "当前事务发布仅支持本地 NTFS" }
        val workspace = registry.forVolume(volume)
        require(files.volume(workspace.root).id == workspace.volumeId && workspace.volumeId == volume.id) { "登记工作区卷身份发生变化" }
        val id = ids.next()
        val dir = files.prepareDirectory(workspace.root, id)
        val staged = child(dir, "ready.${plan.container.extension}")
        files.copy(receipt.candidate.identity.path, staged, stop)
        val metadata = files.metadata(plan.source.path)
        files.prepareMetadata(plan.source.path, staged, metadata)
        val stagedEvidence = requireNotNull(files.evidence(staged))
        require(stagedEvidence.sha256 == receipt.candidate.sha256)
        stop.checkRunning()
        val record = CommitRecord(id = id, itemId = claim.item.id, attemptId = claim.attemptId, plan = plan, receipt = receipt,
            source = receipt.source, candidate = receipt.candidate, staged = stagedEvidence,
            backup = if (plan.outputMode == OutputMode.COPY) null else child(dir, "original.backup"), workspace = workspace, sourceMetadata = metadata, targetMetadata = metadata)
        store.createCommit(record, claim, clock.nowMillis())
        checkpoint.reached("AfterPREPAREDDb", record); files.mirror(record); checkpoint.reached("AfterPREPAREDMirror", record)
        return record
    }
    private suspend fun unchanged(record: CommitRecord): Boolean = files.evidence(record.source.identity.path) == record.source && files.metadata(record.source.identity.path) == record.sourceMetadata
    private fun matches(actual: ContentEvidence?, expected: ContentEvidence) = actual != null && actual.sha256 == expected.sha256 &&
        (expected.identity.fileKey.isEmpty() || actual.identity.fileKey == expected.identity.fileKey)
    private suspend fun finalize(record: CommitRecord): CommitRecord {
        val target = record.plan.destination
        files.applyMetadata(target, record.targetMetadata, record.plan.preserveSourceTime)
        val output = requireNotNull(files.evidence(target))
        require(matches(output, record.staged)) { "最终目标内容或文件身份不符" }
        if (record.backup != null) {
            require(matches(files.evidence(record.backup), record.source)) { "原始备份内容或身份不符" }
            val metadata = files.metadata(record.backup)
            require(metadata.daclHash == record.sourceMetadata.daclHash && metadata.adsHashes == record.sourceMetadata.adsHashes && metadata.modifiedTime == record.sourceMetadata.modifiedTime) { "原始备份元数据不符" }
        }
        return durable(record.copy(phase = CommitPhase.COMMITTED, output = output))
    }
    private suspend fun attention(record: CommitRecord, reason: String): Nothing {
        durable(record.copy(phase = CommitPhase.NEEDS_ATTENTION))
        throw CompressException(ErrorCode.COMMIT_AMBIGUOUS, reason)
    }
    private suspend fun advance(initial: CommitRecord, continuePrepared: Boolean): CommitRecord {
        var record = initial
        require(files.volume(record.workspace.root).id == record.workspace.volumeId) { "工作区离线或换卷" }
        val target = files.evidence(record.plan.destination)
        val backup = record.backup?.let { files.evidence(it) }
        if (record.phase == CommitPhase.COMMITTED) {
            if (target != record.output || (record.backup != null && !matches(backup, record.source))) attention(record, "已完成目标/备份发生变化，禁止自动改写")
            val metadata = files.metadata(record.plan.destination)
            if (metadata.daclHash != record.targetMetadata.daclHash || metadata.adsHashes != record.targetMetadata.adsHashes) attention(record, "已完成目标权限/命名流变化，需要人工确认")
            return record
        }
        if (matches(target, record.staged)) {
            if (record.plan.outputMode == OutputMode.REPLACE) {
                if (!matches(backup, record.source)) attention(record, "目标像候选但原始备份不符")
                if (record.phase != CommitPhase.COMMITTED) record = durable(record.copy(phase = CommitPhase.PUBLISHED))
                return finalize(record)
            }
            if (record.plan.outputMode == OutputMode.CHANGE_EXTENSION) {
                if (!matches(backup, record.source)) {
                    if (!unchanged(record) || backup != null) attention(record, "输出已就位但源/备份不符，保留全部文件")
                    record = durable(record.copy(phase = CommitPhase.RETIRE_INTENT))
                    checkpoint.reached("BeforeRetire", record)
                    if (!unchanged(record)) attention(record, "归档前源内容变化")
                    val retired = files.retire(record.source.identity.path, record.backup!!)
                    checkpoint.reached("AfterRetire", record)
                    record = record.copy(lastError = retired.error)
                    if (!matches(files.evidence(record.backup!!), record.source)) attention(record, "源归档失败，输出和原片保留")
                }
                record = durable(record.copy(phase = CommitPhase.RETIRED))
                return finalize(record)
            }
            if (record.phase != CommitPhase.COMMITTED && !unchanged(record)) attention(record, "副本就位后源身份不符")
            return finalize(record)
        }
        if (target != null && !(record.plan.outputMode == OutputMode.REPLACE && matches(target, record.source))) attention(record, "最终目标存在非本次输出，禁止覆盖")
        if (!matches(files.evidence(record.staged.identity.path), record.staged)) attention(record, "staging 证据不符，禁止提交")
        if (files.evidence(record.candidate.identity.path) != record.candidate) attention(record, "已验证候选/原始备份变化")
        if (record.plan.outputMode == OutputMode.REPLACE && target == null && matches(backup, record.source)) {
            if (!continuePrepared) attention(record, "原始文件已在备份，等待启动对账完成发布")
            record = durable(record.copy(phase = CommitPhase.PUBLISH_INTENT))
            val result = files.publishNew(record.staged.identity.path, record.plan.destination)
            record = record.copy(lastError = result.error)
            return advance(record, false)
        }
        if (!unchanged(record) || backup != null) attention(record, "提交前源/备份内容变化")
        if (!continuePrepared) {
            durable(record.copy(phase = CommitPhase.NEEDS_ATTENTION))
            throw CompressException(ErrorCode.COMMIT_BLOCKED, "系统调用未发布结果（错误 ${record.lastError}）；原片保留，可解决后对账重试")
        }
        record = durable(record.copy(phase = CommitPhase.PUBLISH_INTENT))
        checkpoint.reached("BeforeNative", record)
        if (!unchanged(record) || !matches(files.evidence(record.staged.identity.path), record.staged) || record.backup?.let { files.evidence(it) } != null)
            attention(record, "最后调用边界的源/候选/备份变化")
        val result = if (record.plan.outputMode == OutputMode.REPLACE) files.replace(record.source.identity.path, record.staged.identity.path, record.backup!!)
            else files.publishNew(record.staged.identity.path, record.plan.destination)
        checkpoint.reached("AfterNative", record)
        record = record.copy(lastError = result.error)
        return advance(record, false)
    }
    suspend fun publish(id: String): LocalPath = gate.withLock {
        val record = store.commit(id)
        if (!store.canResumeCommit(record)) throw CompressException(ErrorCode.USER_INTERRUPTED, "停止意图已持久化，禁止提交")
        advance(record, true).plan.destination
    }
    suspend fun restore(id: String, stop: StopSignal = StopSignal()): CommitRecord {
        val original = store.commit(id)
        require(original.phase == CommitPhase.COMMITTED && original.backup != null) { "没有已核对的原始备份" }
        require(files.evidence(original.plan.destination) == original.output) { "当前版本已变化，不能恢复覆盖" }
        val backup = requireNotNull(files.evidence(original.backup))
        require(matches(backup, original.source)) { "原始备份已变化" }
        val workspace = registry.forVolume(files.volume(original.plan.destination))
        val newId = ids.next(); val dir = files.prepareDirectory(workspace.root, newId)
        val staged = child(dir, "restore.${original.plan.container.extension}")
        files.copy(original.backup, staged, stop) // Never consume the only original backup.
        files.prepareMetadata(original.backup, staged, original.sourceMetadata)
        val current = requireNotNull(files.evidence(original.plan.destination))
        val plan = original.plan.copy(schemaVersion = 2, plannerVersion = 2, source = current.identity, destination = current.identity.path, outputMode = OutputMode.REPLACE, onlyIfSmaller = false)
        val record = CommitRecord(id = newId, itemId = original.itemId, attemptId = null, restoreOf = original.id, plan = plan, receipt = null,
            source = current, candidate = backup, staged = requireNotNull(files.evidence(staged)), backup = child(dir, "previous.backup"), workspace = workspace,
            sourceMetadata = files.metadata(current.identity.path), targetMetadata = original.sourceMetadata)
        store.createCommit(record, null, clock.nowMillis()); files.mirror(record)
        stop.checkRunning()
        return stop.publish { gate.withLock { advance(record, true) } }
    }
    override suspend fun recoverTransactions() = gate.withLock {
        val records = store.commits()
        val superseded = records.filter { it.phase == CommitPhase.COMMITTED }.mapNotNull { it.restoreOf }.toSet()
        for (record in records) {
            if (record.id in superseded) continue
            if (record.phase != CommitPhase.COMMITTED && !store.canResumeCommit(record)) {
                if (record.attemptId != null) jobs.recovered(record.itemId, ItemState.INTERRUPTED, null, "停止请求保留；旧提交候选不会自动发布", clock.nowMillis())
                continue
            }
            try {
                val completed = advance(record, true)
                if (record.attemptId != null) jobs.recovered(record.itemId, ItemState.SUCCEEDED, completed.output, "提交日志与目标/备份强证据完成对账", clock.nowMillis())
            } catch (failure: Exception) {
                if (record.attemptId != null) jobs.recovered(record.itemId, ItemState.NEEDS_ATTENTION, null, failure.message ?: "提交对账失败", clock.nowMillis())
            }
        }
    }
}
