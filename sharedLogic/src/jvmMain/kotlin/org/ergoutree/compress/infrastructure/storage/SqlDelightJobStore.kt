package org.ergoutree.compress.infrastructure.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.application.PlanEncoding
import org.ergoutree.compress.catalog.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.domain.Workspace
import org.ergoutree.compress.ports.JobStore
import org.ergoutree.compress.ports.CommitStore
import org.ergoutree.compress.ports.MaintenanceStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

/** 串行化短数据库事务，统一保存任务、提交与清理意图，媒体 I/O 始终在事务外。 */
class SqlDelightJobStore(path: Path) : JobStore, CommitStore, MaintenanceStore, AutoCloseable {
    private val gate = Mutex()
    private val driver: JdbcSqliteDriver
    private val database: CatalogDatabase
    private val q get() = database.catalogQueries
    private val commits get() = database.commitLogQueries
    private val maintenance get() = database.maintenanceQueries
    init {
        Files.createDirectories(path.toAbsolutePath().parent)
        driver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}", Properties().apply {
            setProperty("foreign_keys", "true"); setProperty("journal_mode", "DELETE"); setProperty("synchronous", "FULL"); setProperty("busy_timeout", "5000")
        })
        try {
            val version = driver.executeQuery(null, "PRAGMA user_version", { cursor ->
                cursor.next(); app.cash.sqldelight.db.QueryResult.Value(cursor.getLong(0)!!)
            }, 0).value
            if (version == 0L) {
                CatalogDatabase.Schema.create(driver)
                driver.execute(null, "PRAGMA user_version=${CatalogDatabase.Schema.version}", 0)
            } else if (version < CatalogDatabase.Schema.version) {
                val backupRoot = Files.createDirectories(path.toAbsolutePath().parent.resolve("catalog-backups"))
                val backup = backupRoot.resolve("v$version-${java.util.UUID.randomUUID()}.sqlite")
                driver.execute(null, "VACUUM INTO ?", 1) { bindString(0, backup.toString()) }
                java.nio.channels.FileChannel.open(backup, java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
                CatalogDatabase(driver).transaction {
                    CatalogDatabase.Schema.migrate(driver, version, CatalogDatabase.Schema.version)
                    driver.execute(null, "PRAGMA user_version=${CatalogDatabase.Schema.version}", 0)
                }
            } else require(version == CatalogDatabase.Schema.version) { "数据库版本 $version 高于当前程序；禁止降级打开" }
            database = CatalogDatabase(driver)
        } catch (failure: Throwable) { driver.close(); throw failure }
    }
    private suspend fun <T> read(block: () -> T): T = withContext(Dispatchers.IO) { gate.withLock { block() } }
    private suspend fun <T> write(block: () -> T): T = read { database.transactionWithResult { block() } }
    private fun jobRow(id: String) = q.findJob(id).executeAsOne()
    private fun itemRow(id: String) = q.findItem(id).executeAsOne()
    private fun event(jobId: String, itemId: String?, type: String, payload: String, now: Long) {
        q.bumpSequence(jobId)
        q.insertEvent(jobId, jobRow(jobId).next_sequence, itemId, type, payload, now)
    }
    private fun Job.record() = JobRecord(id, SnapshotJson.decodeFromString(snapshot), JobState.valueOf(state), revision, created_at)
    private fun Item.record() = ItemRecord(id, job_id, ordinal, SnapshotJson.decodeFromString(plan), ItemState.valueOf(state), revision, attempt_sequence)
    private fun Attempt.record() = AttemptRecord(id, item_id, sequence, ExecutionState.valueOf(state), stop_requested != 0L,
        workspace?.let { SnapshotJson.decodeFromString(it) }, validation?.let { SnapshotJson.decodeFromString(it) }, output?.let { SnapshotJson.decodeFromString(it) }, diagnostic)
    private fun checked(claim: ClaimedItem): Item {
        val row = itemRow(claim.item.id)
        check(row.state == "RUNNING" && row.active_attempt == claim.attemptId && row.lease_owner == claim.owner && row.lease_epoch == claim.epoch) { "执行令牌失效，禁止过期执行器写状态" }
        return row
    }
    private fun aggregate(jobId: String, now: Long) {
        val job = jobRow(jobId)
        if (job.state == "CANCELLED") return
        val states = q.listItems(jobId).executeAsList().map { ItemState.valueOf(it.state) }
        val next = when {
            ItemState.RUNNING in states -> if (job.state == "PAUSE_REQUESTED") JobState.PAUSE_REQUESTED else JobState.RUNNING
            ItemState.NEEDS_ATTENTION in states -> JobState.NEEDS_ATTENTION
            states.any { it == ItemState.READY || it == ItemState.INTERRUPTED } -> if (job.state == "PAUSE_REQUESTED" || job.state == "PAUSED") JobState.PAUSED else JobState.RUNNING
            ItemState.FAILED in states -> JobState.COMPLETED_WITH_ERRORS
            else -> JobState.COMPLETED
        }
        if (job.state != next.name) { q.updateJob(next.name, jobId); event(jobId, null, "JobState", next.name, now) }
    }
    override suspend fun enqueue(job: FrozenJob, now: Long) = write {
        require(job.schemaVersion == 1 && job.plans.isNotEmpty())
        val canonical = PlanEncoding.canonicalList(job.plans)
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        require(hash == job.planHash) { "冻结任务 hash 不符" }
        q.findJob(job.id).executeAsOneOrNull()?.let {
            require(SnapshotJson.decodeFromString<FrozenJob>(it.snapshot) == job) { "任务 ID 已用于其他快照" }
            return@write
        }
        q.insertJob(job.id, SnapshotJson.encodeToString(job), JobState.READY.name, now)
        job.plans.forEachIndexed { index, plan ->
            require(plan.schemaVersion in 1..3 && plan.plannerVersion in 1..3) { "不支持的计划版本" }
            val itemId = "${job.id}:$index"
            q.insertItem(itemId, job.id, index.toLong(), SnapshotJson.encodeToString(plan), ItemState.READY.name)
            val target = Path.of(plan.destination.value).toAbsolutePath().normalize().toString().lowercase()
            if (q.findReservation(target).executeAsOneOrNull() != null) throw CompressException(ErrorCode.DESTINATION_CONFLICT, "目标已由另一未完成任务预留")
            q.reserveTarget(target, itemId)
        }
        event(job.id, null, "Enqueued", job.planHash, now)
    }
    override suspend fun job(id: String) = read { jobRow(id).record() }
    override suspend fun history() = read { q.listJobs().executeAsList().map { it.record() } }
    override suspend fun items(jobId: String) = read { q.listItems(jobId).executeAsList().map { it.record() } }
    override suspend fun attempts(itemId: String) = read { q.listAttempts(itemId).executeAsList().map { it.record() } }
    override suspend fun events(jobId: String) = read { q.listEvents(jobId).executeAsList().map { DurableEvent(it.sequence, it.item_id, it.type, it.payload, it.created_at) } }
    override suspend fun resume(jobId: String, now: Long) = write {
        val row = jobRow(jobId)
        require(row.state !in listOf("CANCELLED", "COMPLETED", "COMPLETED_WITH_ERRORS", "NEEDS_ATTENTION")) { "任务不处于可继续状态；失败项需显式重试" }
        if (row.state != "RUNNING") { q.updateJob("RUNNING", jobId); event(jobId, null, "Resumed", "", now) }
    }
    override suspend fun claim(jobId: String, owner: String, attemptId: String, now: Long): ClaimedItem? = write {
        if (jobRow(jobId).state != "RUNNING") return@write null
        if (q.listItems(jobId).executeAsList().any { it.state == "RUNNING" }) return@write null
        val row = q.nextReady(jobId).executeAsOneOrNull() ?: return@write null
        q.claimItem(owner, attemptId, row.id)
        val claimed = itemRow(row.id)
        q.insertAttempt(attemptId, row.id, claimed.attempt_sequence, now)
        event(jobId, row.id, "Claimed", attemptId, now)
        ClaimedItem(claimed.record(), attemptId, owner, claimed.lease_epoch)
    }
    override suspend fun requestPause(jobId: String, immediate: Boolean, now: Long) = write {
        val job = jobRow(jobId)
        if (job.state !in listOf("READY", "RUNNING", "PAUSE_REQUESTED", "PAUSED")) return@write
        q.updateJob("PAUSE_REQUESTED", jobId)
        if (immediate) q.listItems(jobId).executeAsList().filter { it.state == "RUNNING" }.forEach { item ->
            val attempt = q.findAttempt(item.active_attempt!!).executeAsOne()
            // 发布意图持久化后不可中途取消；暂停仅影响后续派发。
            if (attempt.state !in listOf("PUBLISHING", "PUBLISHED")) q.stopAttempt(attempt.id)
        }
        event(jobId, null, if (immediate) "StopRequested" else "PauseRequested", "", now)
        aggregate(jobId, now)
    }
    override suspend fun cancel(jobId: String, now: Long) = write {
        val state = jobRow(jobId).state
        if (state in listOf("COMPLETED", "COMPLETED_WITH_ERRORS", "CANCELLED")) return@write
        require(state != "NEEDS_ATTENTION") { "待对账任务不能取消并丢弃恢复状态" }
        q.updateJob("CANCELLED", jobId)
        q.listItems(jobId).executeAsList().forEach { item ->
            if (item.state == "RUNNING") {
                val attempt = q.findAttempt(item.active_attempt!!).executeAsOne()
                if (attempt.state !in listOf("PUBLISHING", "PUBLISHED")) q.stopAttempt(attempt.id)
            } else if (item.state !in listOf("SUCCEEDED", "NEEDS_ATTENTION")) { q.setItemState("CANCELLED", item.id); q.releaseTarget(item.id) }
        }
        event(jobId, null, "Cancelled", "", now)
    }
    override suspend fun retry(itemId: String, now: Long) = write {
        val item = itemRow(itemId)
        require(item.state in listOf("FAILED", "INTERRUPTED")) { "该项不可重试" }
        require(jobRow(item.job_id).state != "CANCELLED")
        require(jobRow(item.job_id).hidden == 0L) { "已隐藏历史不可重试" }
        q.setItemState("READY", itemId); q.updateJob("PAUSED", item.job_id)
        event(item.job_id, item.id, "RetryRequested", "", now)
    }
    override suspend fun hide(jobId: String) = write {
        require(jobRow(jobId).state in listOf("COMPLETED", "COMPLETED_WITH_ERRORS", "CANCELLED")) { "活动或待对账任务不能隐藏" }
        q.hideJob(jobId)
        q.listItems(jobId).executeAsList().forEach { q.releaseTarget(it.id) }
        Unit
    }
    override suspend fun workspace(claim: ClaimedItem, work: WorkspaceRecord, now: Long) = write {
        checked(claim); q.setWorkspace(SnapshotJson.encodeToString(work), claim.attemptId); event(claim.item.jobId, claim.item.id, "WorkspaceCreated", work.id, now)
    }
    override suspend fun validated(claim: ClaimedItem, receipt: ValidationReceipt, now: Long) = write {
        checked(claim); require(receipt.fullDecodePassed)
        val work = SnapshotJson.decodeFromString<WorkspaceRecord>(q.findAttempt(claim.attemptId).executeAsOne().workspace ?: error("没有工作区证据"))
        require(receipt.planHash == work.planHash && receipt.source == work.source && receipt.candidate.identity.path == work.candidate) { "校验凭据未绑定本次计划/源/候选" }
        q.setValidation(SnapshotJson.encodeToString(receipt), claim.attemptId); event(claim.item.jobId, claim.item.id, "Validated", receipt.planHash, now)
    }
    override suspend fun publishing(claim: ClaimedItem, now: Long) = write {
        checked(claim)
        val attempt = q.findAttempt(claim.attemptId).executeAsOne()
        if (attempt.stop_requested != 0L || jobRow(claim.item.jobId).state == "CANCELLED") throw CompressException(ErrorCode.USER_INTERRUPTED, "停止意图已持久化，禁止发布")
        require(attempt.validation != null && attempt.state == "VALIDATED")
        q.setPublishing(claim.attemptId); event(claim.item.jobId, claim.item.id, "PublishIntent", "", now)
    }
    override suspend fun published(claim: ClaimedItem, output: ContentEvidence, now: Long) = write {
        checked(claim); require(q.findAttempt(claim.attemptId).executeAsOne().state == "PUBLISHING")
        q.setPublished(SnapshotJson.encodeToString(output), claim.attemptId); event(claim.item.jobId, claim.item.id, "Published", output.sha256, now)
    }
    override suspend fun finish(claim: ClaimedItem, result: AttemptResult, now: Long) = write {
        checked(claim)
        val attempt = q.findAttempt(claim.attemptId).executeAsOne()
        val state = when {
            result.stage == AttemptStage.SUCCEEDED -> {
                require(attempt.validation != null && attempt.output != null && attempt.state == "PUBLISHED") { "没有权威验证和发布证据，不能完成" }; ItemState.SUCCEEDED
            }
            result.stage == AttemptStage.SKIPPED -> ItemState.SKIPPED
            attempt.state in listOf("PUBLISHING", "PUBLISHED") -> ItemState.NEEDS_ATTENTION
            jobRow(claim.item.jobId).state == "CANCELLED" -> ItemState.CANCELLED
            attempt.stop_requested != 0L || result.stage == AttemptStage.INTERRUPTED -> ItemState.INTERRUPTED
            else -> ItemState.FAILED
        }
        q.endAttempt(state.name, now, result.message, claim.attemptId); q.setItemState(state.name, claim.item.id)
        if (state in listOf(ItemState.SUCCEEDED, ItemState.CANCELLED, ItemState.SKIPPED)) q.releaseTarget(claim.item.id)
        event(claim.item.jobId, claim.item.id, state.name, result.message, now); aggregate(claim.item.jobId, now)
    }
    override suspend fun recoveryItems() = read {
        val journaled = commits.listCommits().executeAsList().mapNotNull { it.attempt_id }.toSet()
        q.allItems().executeAsList().map {
            val attempt = q.latestAttempt(it.id).executeAsOneOrNull()?.record()
            RecoveryItem(it.record(), attempt, it.superseded != 0L, attempt?.id in journaled)
        }
    }
    override suspend fun recovered(itemId: String, state: ItemState, output: ContentEvidence?, diagnostic: String, now: Long) = write {
        val item = itemRow(itemId)
        if (item.active_attempt != null) {
            if (output != null) q.setPublished(SnapshotJson.encodeToString(output), item.active_attempt)
            q.endAttempt(state.name, now, diagnostic, item.active_attempt)
        }
        q.setItemState(state.name, itemId)
        if (state == ItemState.SUCCEEDED) q.releaseTarget(itemId)
        if (jobRow(item.job_id).state != "CANCELLED") q.updateJob("PAUSED", item.job_id)
        event(item.job_id, itemId, "Recovered", diagnostic, now); aggregate(item.job_id, now)
    }
    override fun close() = driver.close()

    override suspend fun createCommit(record: CommitRecord, claim: ClaimedItem?, now: Long) = write {
        if (record.restoreOf == null) {
            val lease = requireNotNull(claim); checked(lease)
            val attempt = q.findAttempt(lease.attemptId).executeAsOne()
            require(attempt.stop_requested == 0L && attempt.state in listOf("VALIDATED", "PUBLISHING"))
            require(record.attemptId == lease.attemptId && record.itemId == lease.item.id)
            require(record.receipt == attempt.validation?.let { SnapshotJson.decodeFromString<ValidationReceipt>(it) }) { "没有本次执行的权威媒体验证" }
        } else {
            val parent = SnapshotJson.decodeFromString<CommitRecord>(commits.findCommit(record.restoreOf).executeAsOne().payload)
            require(parent.phase == CommitPhase.COMMITTED && itemRow(parent.itemId).state == "SUCCEEDED") { "仅完成且未在执行的版本可恢复" }
            val expectedTarget = if (parent.plan.outputMode == OutputMode.CHANGE_EXTENSION) parent.source.identity.path else parent.plan.destination
            require(record.source == parent.output && record.candidate.sha256 == parent.source.sha256 && record.plan.destination == expectedTarget)
            require(record.plan.source.path == parent.plan.destination)
            val target = Path.of(record.plan.destination.value).toAbsolutePath().normalize().toString().lowercase()
            require(q.findReservation(target).executeAsOneOrNull() == null) { "目标正由其他任务预留" }
            q.reserveTarget(target, record.itemId)
        }
        commits.insertCommit(record.id, record.itemId, record.attemptId, record.restoreOf, record.phase.name, SnapshotJson.encodeToString(record))
        event(itemRow(record.itemId).job_id, record.itemId, "CommitPrepared", record.id, now)
    }
    override suspend fun saveCommit(record: CommitRecord, now: Long): CommitRecord = write {
        val updated = record.copy(revision = record.revision + 1)
        check(commits.updateCommit(updated.phase.name, SnapshotJson.encodeToString(updated), record.id, record.revision).value == 1L) { "提交 revision 过期" }
        if (updated.phase == CommitPhase.CANCELLED && updated.restoreOf != null) q.releaseTarget(updated.itemId)
        if (updated.phase == CommitPhase.COMMITTED && updated.restoreOf != null) { q.markSuperseded(updated.itemId); q.releaseTarget(updated.itemId) }
        event(itemRow(updated.itemId).job_id, updated.itemId, "Commit${updated.phase}", updated.id, now)
        updated
    }
    override suspend fun commits() = read { commits.listCommits().executeAsList().map { SnapshotJson.decodeFromString<CommitRecord>(it.payload) } }
    override suspend fun commit(id: String) = read { SnapshotJson.decodeFromString<CommitRecord>(commits.findCommit(id).executeAsOne().payload) }
    override suspend fun registerWorkspace(workspace: Workspace) = write {
        val existing = commits.listWorkspaces().executeAsList().firstOrNull { it.volume_id == workspace.volumeId }
        require(existing == null || (existing.id == workspace.id && existing.root == workspace.root.value)) { "该卷已登记其他工作区" }
        if (existing == null) commits.insertWorkspace(workspace.id, workspace.volumeId, workspace.root.value)
        Unit
    }
    override suspend fun workspaces() = read { commits.listWorkspaces().executeAsList().map { Workspace(it.id, LocalPath(it.root), it.volume_id) } }
    override suspend fun canResumeCommit(record: CommitRecord) = read {
        record.phase != CommitPhase.CANCELLED && (record.attemptId == null || q.findAttempt(record.attemptId).executeAsOne().let {
            it.stop_requested == 0L && (jobRow(itemRow(record.itemId).job_id).state != "CANCELLED" || it.state in listOf("PUBLISHING", "PUBLISHED", "SUCCEEDED"))
        })
    }

    override suspend fun maintenanceView() = read {
        MaintenanceView(q.allJobs().executeAsList().map { it.record() }, q.allItems().executeAsList().map { it.record() },
            q.allAttempts().executeAsList().map { it.record() }, commits.listCommits().executeAsList().map { SnapshotJson.decodeFromString<CommitRecord>(it.payload) },
            maintenance.artifacts().executeAsList().map { SnapshotJson.decodeFromString<ManagedArtifact>(it.payload) })
    }
    override suspend fun registerArtifact(artifact: ManagedArtifact): ManagedArtifact = write {
        val row = maintenance.artifactByPath(artifact.evidence.identity.path.value).executeAsOneOrNull()
        if (row == null) {
            maintenance.insertArtifact(artifact.id, artifact.evidence.identity.path.value, SnapshotJson.encodeToString(artifact), artifact.state.name, artifact.revision)
            artifact
        } else {
            val old = SnapshotJson.decodeFromString<ManagedArtifact>(row.payload)
            check(old.state != ArtifactState.MARKED) { "清理标记中的文件禁止重新认领" }
            if (old.evidence == artifact.evidence && old.kind == artifact.kind && old.state == ArtifactState.LIVE && old.commitId == artifact.commitId) old
            else artifact.copy(id = old.id, revision = old.revision + 1).also { updateArtifact(it, old.revision) }
        }
    }
    private fun updateArtifact(artifact: ManagedArtifact, previousRevision: Long) {
        check(maintenance.updateArtifact(SnapshotJson.encodeToString(artifact), artifact.state.name, artifact.revision, artifact.id, previousRevision).value == 1L) { "产物 revision 已过期" }
    }
    override suspend fun savePreview(preview: CleanupPreview): Unit = write { maintenance.insertPreview(preview.id, SnapshotJson.encodeToString(preview)) }
    override suspend fun preview(id: String) = read { SnapshotJson.decodeFromString<CleanupPreview>(maintenance.findPreview(id).executeAsOne().payload) }
    override suspend fun claimPreview(id: String, confirmation: String): CleanupPreview = write {
        val row = maintenance.findPreview(id).executeAsOne()
        val preview = SnapshotJson.decodeFromString<CleanupPreview>(row.payload)
        require(row.consumed == 0L && preview.confirmation == confirmation) { "预览已消费或令牌不匹配" }
        check(maintenance.consumePreview(id).value == 1L)
        preview.decisions.filter { it.eligible }.forEach {
            val old = SnapshotJson.decodeFromString<ManagedArtifact>(maintenance.artifactById(it.artifact.id).executeAsOne().payload)
            check(old == it.artifact && old.state == ArtifactState.LIVE) { "预览后的产物状态发生变化" }
            updateArtifact(old.copy(state = ArtifactState.MARKED, cleanupId = id, revision = old.revision + 1), old.revision)
        }
        preview
    }
    override suspend fun completeDeletion(artifactId: String) = write {
        val old = SnapshotJson.decodeFromString<ManagedArtifact>(maintenance.artifactById(artifactId).executeAsOne().payload)
        check(old.state == ArtifactState.MARKED && old.cleanupId != null)
        if (old.kind == ArtifactKind.ORIGINAL_BACKUP) {
            val parent = SnapshotJson.decodeFromString<CommitRecord>(commits.findCommit(requireNotNull(old.commitId)).executeAsOne().payload)
            check(parent.phase == CommitPhase.COMMITTED && parent.backup?.sameLocation(old.evidence.identity.path) == true)
            val released = parent.copy(backupReleased = true, revision = parent.revision + 1)
            check(commits.updateCommit(released.phase.name, SnapshotJson.encodeToString(released), parent.id, parent.revision).value == 1L)
            event(parent.plan.let { itemRow(parent.itemId).job_id }, parent.itemId, "OriginalBackupReleased", old.cleanupId, System.currentTimeMillis())
        }
        updateArtifact(old.copy(state = ArtifactState.DELETED, revision = old.revision + 1), old.revision)
    }
    override suspend fun undoDeletionMark(artifactId: String) = write {
        val old = SnapshotJson.decodeFromString<ManagedArtifact>(maintenance.artifactById(artifactId).executeAsOne().payload)
        check(old.state == ArtifactState.MARKED)
        updateArtifact(old.copy(state = ArtifactState.LIVE, cleanupId = null, revision = old.revision + 1), old.revision)
    }
    override suspend fun consumePublishedStaging(commitId: String): Unit = write {
        val record = SnapshotJson.decodeFromString<CommitRecord>(commits.findCommit(commitId).executeAsOne().payload)
        check(record.phase == CommitPhase.COMMITTED && record.output?.identity?.fileKey == record.staged.identity.fileKey)
        val row = maintenance.artifactByPath(record.staged.identity.path.value).executeAsOneOrNull()
        if (row != null) {
            val old = SnapshotJson.decodeFromString<ManagedArtifact>(row.payload)
            check(old.kind == ArtifactKind.STAGING && old.commitId == record.id)
            if (old.state == ArtifactState.LIVE) updateArtifact(old.copy(state = ArtifactState.DELETED, revision = old.revision + 1), old.revision)
        }
    }
    override suspend fun backupDatabase(path: LocalPath) = read {
        driver.execute(null, "VACUUM INTO ?", 1) { bindString(0, path.value) }
        java.nio.channels.FileChannel.open(Path.of(path.value), java.nio.file.StandardOpenOption.WRITE).use { it.force(true) }
    }
}
