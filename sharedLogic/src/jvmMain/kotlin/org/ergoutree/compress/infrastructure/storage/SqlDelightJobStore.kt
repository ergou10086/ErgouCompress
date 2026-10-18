package org.ergoutree.compress.infrastructure.storage

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.catalog.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.JobStore
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Properties

/** One serialized writer, short SQL transactions, no media I/O inside a transaction. */
class SqlDelightJobStore(path: Path) : JobStore, AutoCloseable {
    private val gate = Mutex()
    private val driver: JdbcSqliteDriver
    private val database: CatalogDatabase
    private val q get() = database.catalogQueries
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
                CatalogDatabase.Schema.migrate(driver, version, CatalogDatabase.Schema.version)
                driver.execute(null, "PRAGMA user_version=${CatalogDatabase.Schema.version}", 0)
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
        val canonical = SnapshotJson.encodeToString(job.plans)
        val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
        require(hash == job.planHash) { "冻结任务 hash 不符" }
        q.findJob(job.id).executeAsOneOrNull()?.let {
            require(it.snapshot == SnapshotJson.encodeToString(job)) { "任务 ID 已用于其他快照" }
            return@write
        }
        q.insertJob(job.id, SnapshotJson.encodeToString(job), JobState.READY.name, now)
        job.plans.forEachIndexed { index, plan ->
            require(plan.schemaVersion == 1 && plan.plannerVersion == 1) { "不支持的计划版本" }
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
            // Publication is a durable point of no cancellation. Pause only future dispatch.
            if (attempt.state !in listOf("PUBLISHING", "PUBLISHED")) q.stopAttempt(attempt.id)
        }
        event(jobId, null, if (immediate) "StopRequested" else "PauseRequested", "", now)
        aggregate(jobId, now)
    }
    override suspend fun cancel(jobId: String, now: Long) = write {
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
            attempt.state in listOf("PUBLISHING", "PUBLISHED") -> ItemState.NEEDS_ATTENTION
            jobRow(claim.item.jobId).state == "CANCELLED" -> ItemState.CANCELLED
            attempt.stop_requested != 0L || result.stage == AttemptStage.INTERRUPTED -> ItemState.INTERRUPTED
            else -> ItemState.FAILED
        }
        q.endAttempt(state.name, now, result.message, claim.attemptId); q.setItemState(state.name, claim.item.id)
        if (state in listOf(ItemState.SUCCEEDED, ItemState.CANCELLED)) q.releaseTarget(claim.item.id)
        event(claim.item.jobId, claim.item.id, state.name, result.message, now); aggregate(claim.item.jobId, now)
    }
    override suspend fun recoveryItems() = read { q.allItems().executeAsList().map { RecoveryItem(it.record(), q.latestAttempt(it.id).executeAsOneOrNull()?.record()) } }
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
}
