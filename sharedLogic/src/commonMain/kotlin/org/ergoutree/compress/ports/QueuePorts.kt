package org.ergoutree.compress.ports

import org.ergoutree.compress.domain.*

/** 注入毫秒时钟，使保留期和状态测试可重复。 */
fun interface Clock { fun nowMillis(): Long }
/** 注入独立标识生成能力，测试不依赖随机结果。 */
fun interface IdGenerator { fun next(): String }
/** 以持久事务保存队列状态、租约和恢复凭据。 */
interface JobStore {
    suspend fun enqueue(job: FrozenJob, now: Long)
    suspend fun job(id: String): JobRecord
    suspend fun history(): List<JobRecord>
    suspend fun items(jobId: String): List<ItemRecord>
    suspend fun attempts(itemId: String): List<AttemptRecord>
    suspend fun events(jobId: String): List<DurableEvent>
    suspend fun resume(jobId: String, now: Long)
    suspend fun claim(jobId: String, owner: String, attemptId: String, now: Long): ClaimedItem?
    suspend fun requestPause(jobId: String, immediate: Boolean, now: Long)
    suspend fun cancel(jobId: String, now: Long)
    suspend fun retry(itemId: String, now: Long)
    suspend fun hide(jobId: String)
    suspend fun workspace(claim: ClaimedItem, work: WorkspaceRecord, now: Long)
    suspend fun validated(claim: ClaimedItem, receipt: ValidationReceipt, now: Long)
    suspend fun publishing(claim: ClaimedItem, now: Long)
    suspend fun published(claim: ClaimedItem, output: ContentEvidence, now: Long)
    suspend fun finish(claim: ClaimedItem, result: AttemptResult, now: Long)
    suspend fun recoveryItems(): List<RecoveryItem>
    suspend fun recovered(itemId: String, state: ItemState, output: ContentEvidence?, diagnostic: String, now: Long)
}
/** 在执行关键边界持久化状态，资源暂停先于停止信号。 */
interface AttemptLifecycle {
    val claim: ClaimedItem? get() = null
    suspend fun resourcePause(reason: String) {}
    suspend fun created(work: AttemptWorkspace, source: ContentEvidence, planHash: String) {}
    suspend fun validated(receipt: ValidationReceipt) {}
    suspend fun publishing(prepared: PreparedCopy) {}
    suspend fun published(output: ContentEvidence) {}
}
/** 启动时根据提交意图和实物证据核对结果。 */
fun interface TransactionRecovery { suspend fun recoverTransactions() }
/** 兼容不接入持久队列的独立执行入口。 */
object NoopAttemptLifecycle : AttemptLifecycle
/** 执行单项冻结计划，供队列和资源装饰器组合。 */
fun interface ItemExecutor {
    suspend fun execute(plan: ResolvedPlan, stop: StopSignal, lifecycle: AttemptLifecycle, emit: (AttemptEvent) -> Unit): AttemptResult
}
