package org.ergoutree.compress.application

import kotlinx.coroutines.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

/** 编码前核对按卷预算，运行中低空间先持久暂停再发送停止信号。 */
class SpaceAwareExecutor(private val delegate: ItemExecutor, private val admission: SpaceAdmission, private val pollingMillis: Long = 500) : ItemExecutor {
    init { require(pollingMillis > 0) }
    override suspend fun execute(plan: ResolvedPlan, stop: StopSignal, lifecycle: AttemptLifecycle, emit: (AttemptEvent) -> Unit): AttemptResult = coroutineScope {
        val requirements = admission.requirements(plan)
        if (!admission.enough(requirements)) {
            lifecycle.resourcePause("按卷预算不足"); stop.request()
            return@coroutineScope AttemptResult(AttemptStage.INTERRUPTED, error = ErrorCode.INSUFFICIENT_SPACE, message = "空间预算不足，任务已暂停，尚未启动编码")
        }
        var lowSpace = false
        val monitor = launch {
            while (isActive) {
                delay(pollingMillis)
                // 执行中只检查安全余量，已写出的候选不能再被当作完整未来预算扣除。
                if (!admission.enough(requirements.map { it.copy(additionalBytes = 0) })) {
                    lowSpace = true; lifecycle.resourcePause("运行中安全余量不足"); stop.request(); break
                }
            }
        }
        try {
            val result = delegate.execute(plan, stop, lifecycle, emit)
            if (lowSpace && result.stage != AttemptStage.SUCCEEDED) result.copy(error = ErrorCode.INSUFFICIENT_SPACE, message = "运行中磁盘空间不足，当前候选保留，释放空间后重新执行当前文件") else result
        } finally { monitor.cancelAndJoin() }
    }
}
