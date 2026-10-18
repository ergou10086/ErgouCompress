package org.ergoutree.compress.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

/** The owning desktop session holds the OS file lock throughout recovery and dispatch. */
class QueueCoordinator(private val store: JobStore, private val executor: ItemExecutor, private val files: PlanEnvironment, private val clock: Clock, private val ids: IdGenerator) {
    private val worker = Mutex()
    private val control = Mutex()
    private var active: Pair<ClaimedItem, StopSignal>? = null
    private val recovered = MutableStateFlow(false)
    suspend fun submit(job: FrozenJob) = store.enqueue(job, clock.nowMillis())
    suspend fun history() = store.history()
    suspend fun items(jobId: String) = store.items(jobId)
    suspend fun attempts(itemId: String) = store.attempts(itemId)
    suspend fun events(jobId: String) = store.events(jobId)
    suspend fun retry(itemId: String) = store.retry(itemId, clock.nowMillis())
    suspend fun hide(jobId: String) = store.hide(jobId)
    suspend fun pause(jobId: String, immediate: Boolean = false) {
        store.requestPause(jobId, immediate, clock.nowMillis()) // Durable before q.
        if (immediate) control.withLock { active?.takeIf { it.first.item.jobId == jobId }?.second }?.request()
    }
    suspend fun cancel(jobId: String) {
        store.cancel(jobId, clock.nowMillis())
        control.withLock { active?.takeIf { it.first.item.jobId == jobId }?.second }?.request()
    }
    suspend fun recover() = worker.withLock {
        recovered.value = false
        check(active == null)
        for ((item, attempt) in store.recoveryItems()) {
            if (attempt == null) continue
            if (item.state == ItemState.SUCCEEDED || attempt.state in listOf(ExecutionState.PUBLISHING, ExecutionState.PUBLISHED, ExecutionState.NEEDS_ATTENTION)) {
                val receipt = attempt.validation
                val output = try { files.evidence(item.plan.destination) } catch (_: Exception) { null }
                if (receipt != null && output?.sha256 == receipt.candidate.sha256 && (attempt.output == null || attempt.output == output)) {
                    if (item.state != ItemState.SUCCEEDED) store.recovered(item.id, ItemState.SUCCEEDED, output, "根据持久发布意图及完整目标哈希补记完成", clock.nowMillis())
                } else if (item.state == ItemState.SUCCEEDED || output != null) {
                    store.recovered(item.id, ItemState.NEEDS_ATTENTION, null, "输出缺失/改变或发布证据不一致，禁止自动重跑", clock.nowMillis())
                } else {
                    store.recovered(item.id, ItemState.INTERRUPTED, null, "未发现已发布输出；等待明确继续后重跑", clock.nowMillis())
                }
            } else if (item.state == ItemState.RUNNING) {
                store.recovered(item.id, ItemState.INTERRUPTED, null, "上次执行中断，当前文件将重新编码", clock.nowMillis())
            }
        }
        recovered.value = true
    }
    suspend fun run(jobId: String, emit: (AttemptEvent) -> Unit = {}): List<AttemptResult> {
        check(recovered.value) { "必须先完成启动对账" }
        if (!worker.tryLock()) return emptyList() // Repeated resume never starts a second worker.
        try {
            store.resume(jobId, clock.nowMillis())
            val results = mutableListOf<AttemptResult>()
            while (true) {
                val claim = store.claim(jobId, "desktop", ids.next(), clock.nowMillis()) ?: break
                val stop = StopSignal()
                control.withLock { active = claim to stop }
                if (store.attempts(claim.item.id).last().stopRequested) stop.request()
                val lifecycle = object : AttemptLifecycle {
                    override suspend fun created(work: AttemptWorkspace, source: ContentEvidence, planHash: String) = store.workspace(claim, WorkspaceRecord(work.id, work.root, work.candidate, source, planHash), clock.nowMillis())
                    override suspend fun validated(receipt: ValidationReceipt) = store.validated(claim, receipt, clock.nowMillis())
                    override suspend fun publishing(prepared: PreparedCopy) = store.publishing(claim, clock.nowMillis())
                    override suspend fun published(output: ContentEvidence) = store.published(claim, output, clock.nowMillis())
                }
                try {
                    val result = executor.execute(claim.item.plan, stop, lifecycle, emit)
                    withContext(NonCancellable) { store.finish(claim, result, clock.nowMillis()) }
                    results += result
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { store.requestPause(jobId, true, clock.nowMillis()); stop.request() }
                    throw cancelled
                } catch (failure: Exception) {
                    val result = AttemptResult(AttemptStage.FAILED, error = (failure as? CompressException)?.code ?: ErrorCode.PROCESS_FAILED, message = failure.message ?: "执行失败")
                    store.finish(claim, result, clock.nowMillis()); results += result
                } finally { control.withLock { active = null } }
            }
            return results
        } finally { worker.unlock() }
    }
}
