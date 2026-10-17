package org.ergoutree.compress.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*

/** S2 is deliberately a single-file use case. Persistent claiming and resume belong to S3. */
class RunCopy(
    private val environment: PlanEnvironment,
    private val encoder: EncoderAdapter,
    private val validator: CandidateValidator,
    private val artifacts: CopyArtifacts,
) {
    suspend fun execute(plan: ResolvedPlan, stop: StopSignal, emit: (AttemptEvent) -> Unit = {}): AttemptResult {
        var work: AttemptWorkspace? = null
        try {
            emit(AttemptEvent(AttemptStage.PREPARING, "核对工具、源文件与工作空间"))
            stop.checkRunning()
            val hash = environment.sha256(SnapshotJson.encodeToString(plan))
            if (environment.identity(plan.source.path) != plan.source) throw CompressException(ErrorCode.INPUT_CHANGED, "源文件已变化，请重新扫描规划")
            for (tool in listOf(plan.tools.ffmpeg, plan.tools.ffprobe)) {
                if (environment.evidence(tool.path).sha256 != tool.sha256) throw CompressException(ErrorCode.TOOLCHAIN_INVALID, "工具二进制已变化，请重新自检")
            }
            val source = environment.evidence(plan.source.path)
            work = artifacts.create(plan, hash)
            emit(AttemptEvent(AttemptStage.ENCODING, "编码到独立候选，不修改源文件"))
            encoder.encode(plan, work.candidate, work.root, stop) { emit(AttemptEvent(AttemptStage.ENCODING, "编码中", it)) }
            stop.checkRunning()
            emit(AttemptEvent(AttemptStage.VALIDATING, "完整解码、流与音频包校验"))
            val receipt = validator.validate(plan, hash, source, work.candidate, work.root, stop)
            artifacts.record(work, "validation.json", SnapshotJson.encodeToString(receipt))
            stop.checkRunning()
            emit(AttemptEvent(AttemptStage.PUBLISHING, "准备目标卷副本，可停止；最终发布后核对 SHA-256"))
            val prepared = artifacts.preparePublication(plan, receipt, work, stop)
            stop.checkRunning()
            val destination = withContext(NonCancellable) {
                stop.publish { artifacts.publish(prepared) }
            }
            val result = AttemptResult(AttemptStage.SUCCEEDED, destination, message = "副本已发布，源文件保留；档案：${work.root.value}")
            emit(AttemptEvent(result.stage, result.message))
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val code = (failure as? CompressException)?.code ?: ErrorCode.PROCESS_FAILED
            val stage = if (code == ErrorCode.USER_INTERRUPTED) AttemptStage.INTERRUPTED else AttemptStage.FAILED
            val message = "${failure.message ?: code.name}${work?.let { "；候选与诊断保留于 ${it.root.value}" } ?: ""}"
            work?.let { current ->
                // Recording failure is best effort; never conceals the original error.
                try { artifacts.record(current, "failure.json", SnapshotJson.encodeToString(mapOf("code" to code.name, "message" to message))) } catch (_: Exception) { }
            }
            emit(AttemptEvent(stage, message))
            return AttemptResult(stage, error = code, message = message)
        }
    }
}
