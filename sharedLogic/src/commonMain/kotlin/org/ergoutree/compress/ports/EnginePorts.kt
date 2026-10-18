package org.ergoutree.compress.ports

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ergoutree.compress.domain.*

/** 停止意图先持久化再发送 q；最终发布通过短暂互斥门完成。 */
class StopSignal {
    private val gate = Mutex()
    private val flag = MutableStateFlow(false)
    val requested: Boolean get() = flag.value
    suspend fun request() = gate.withLock { flag.value = true }
    suspend fun <T> publish(block: suspend () -> T): T = gate.withLock {
        checkRunning()
        block()
    }
    fun checkRunning() { if (requested) throw CompressException(ErrorCode.USER_INTERRUPTED, "用户已停止，本次候选不会发布") }
}

/** 声明扫描根、排除路径与深度阈值，扫描器不能隐式扩大范围。 */
data class ScanRequest(
    val roots: List<LocalPath>, val maxDirectoryDepth: Int? = 4,
    val excludedRoots: List<LocalPath> = emptyList(), val minimumBytes: Long = 0,
) {
    init { require(maxDirectoryDepth == null || maxDirectoryDepth >= 0); require(minimumBytes >= 0) }
}
/** 以流式事件报告扫描结果、异常路径和完整性。 */
sealed interface ScanEvent {
    /** 记录发现文件及候选资格，阈值判定与类型判定分别保留。 */
    data class Found(val file: FileIdentity, val candidate: Boolean, val aboveThreshold: Boolean) : ScanEvent
    /** 记录无法完整扫描的路径及原因。 */
    data class Issue(val path: LocalPath, val reason: String) : ScanEvent
    /** 汇总已发现容量，并明确扫描是否完整。 */
    data class Finished(val files: Long, val totalBytes: Long, val complete: Boolean) : ScanEvent
}
/** 扫描平台文件系统并逐项输出领域事件。 */
interface InputScanner { fun scan(request: ScanRequest, stop: StopSignal): Flow<ScanEvent> }
/** 将外部工具探测结果转换为平台无关媒体信息。 */
interface MediaProbe { suspend fun inspect(path: LocalPath, tools: Toolchain, work: LocalPath, stop: StopSignal): MediaInfo }
/** 提供身份、哈希、容量和目标路径等规划所需能力。 */
interface PlanEnvironment {
    suspend fun identity(path: LocalPath): FileIdentity
    suspend fun evidence(path: LocalPath): ContentEvidence
    suspend fun sha256(value: String): String
    suspend fun target(directory: LocalPath, source: LocalPath, container: Container): LocalPath
    suspend fun availableBytes(path: LocalPath): Long
    suspend fun exists(path: LocalPath): Boolean
}

/** 声明进程参数、工作目录、日志和有界输出限制。 */
data class ProcessRequest(
    val argv: List<String>, val directory: LocalPath, val timeoutMillis: Long = 30_000,
    val allowQuit: Boolean = false, val logPrefix: LocalPath? = null, val captureLimit: Int = 2_000_000,
)
/** 保留退出、停止、超时与截断证据，不能仅看退出码。 */
data class ProcessResult(val exitCode: Int, val stdout: String, val stderrTail: String, val stopped: Boolean, val timedOut: Boolean, val stdoutTruncated: Boolean)
/** 运行外部进程，隔离平台启动与终止细节。 */
interface ProcessRunner {
    suspend fun run(request: ProcessRequest, stop: StopSignal = StopSignal(), onStdout: (String) -> Unit = {}): ProcessResult
}
/** 按冻结计划编码独立候选并上报进度。 */
interface EncoderAdapter {
    suspend fun encode(plan: ResolvedPlan, candidate: LocalPath, work: LocalPath, stop: StopSignal, onProgress: (Progress) -> Unit)
}
/** 验证候选完整性并生成可追溯的提交凭据。 */
interface CandidateValidator {
    suspend fun validate(plan: ResolvedPlan, planHash: String, source: ContentEvidence, candidate: LocalPath, work: LocalPath, stop: StopSignal): ValidationReceipt
}
/** 绑定一次执行的独占目录与候选路径。 */
data class AttemptWorkspace(val id: String, val root: LocalPath, val candidate: LocalPath)
/** 携带已验证候选和目标卷 staging，供最终发布门使用。 */
data class PreparedCopy(val plan: ResolvedPlan, val receipt: ValidationReceipt, val work: AttemptWorkspace, val staged: ContentEvidence)
/** 隔离工作区、审计记录和副本发布操作。 */
interface CopyArtifacts {
    suspend fun create(plan: ResolvedPlan, planHash: String): AttemptWorkspace
    suspend fun record(work: AttemptWorkspace, name: String, json: String)
    suspend fun preparePublication(plan: ResolvedPlan, receipt: ValidationReceipt, work: AttemptWorkspace, stop: StopSignal): PreparedCopy
    suspend fun publish(prepared: PreparedCopy): LocalPath
    suspend fun capture(work: AttemptWorkspace) {}
}
