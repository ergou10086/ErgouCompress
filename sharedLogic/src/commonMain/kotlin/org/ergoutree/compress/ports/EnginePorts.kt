package org.ergoutree.compress.ports

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.ergoutree.compress.domain.*

/** Cancellation is recorded before signalling q. Publication has a short, non-cancellable gate. */
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

data class ScanRequest(
    val roots: List<LocalPath>, val maxDirectoryDepth: Int? = 4,
    val excludedRoots: List<LocalPath> = emptyList(), val minimumBytes: Long = 0,
) {
    init { require(maxDirectoryDepth == null || maxDirectoryDepth >= 0); require(minimumBytes >= 0) }
}
sealed interface ScanEvent {
    data class Found(val file: FileIdentity, val candidate: Boolean, val aboveThreshold: Boolean) : ScanEvent
    data class Issue(val path: LocalPath, val reason: String) : ScanEvent
    data class Finished(val files: Long, val totalBytes: Long, val complete: Boolean) : ScanEvent
}
interface InputScanner { fun scan(request: ScanRequest, stop: StopSignal): Flow<ScanEvent> }
interface MediaProbe { suspend fun inspect(path: LocalPath, tools: Toolchain, work: LocalPath, stop: StopSignal): MediaInfo }
interface PlanEnvironment {
    suspend fun identity(path: LocalPath): FileIdentity
    suspend fun evidence(path: LocalPath): ContentEvidence
    suspend fun sha256(value: String): String
    suspend fun target(directory: LocalPath, source: LocalPath, container: Container): LocalPath
    suspend fun availableBytes(path: LocalPath): Long
    suspend fun exists(path: LocalPath): Boolean
}

data class ProcessRequest(
    val argv: List<String>, val directory: LocalPath, val timeoutMillis: Long = 30_000,
    val allowQuit: Boolean = false, val logPrefix: LocalPath? = null, val captureLimit: Int = 2_000_000,
)
data class ProcessResult(val exitCode: Int, val stdout: String, val stderrTail: String, val stopped: Boolean, val timedOut: Boolean, val stdoutTruncated: Boolean)
interface ProcessRunner {
    suspend fun run(request: ProcessRequest, stop: StopSignal = StopSignal(), onStdout: (String) -> Unit = {}): ProcessResult
}
interface EncoderAdapter {
    suspend fun encode(plan: ResolvedPlan, candidate: LocalPath, work: LocalPath, stop: StopSignal, onProgress: (Progress) -> Unit)
}
interface CandidateValidator {
    suspend fun validate(plan: ResolvedPlan, planHash: String, source: ContentEvidence, candidate: LocalPath, work: LocalPath, stop: StopSignal): ValidationReceipt
}
data class AttemptWorkspace(val id: String, val root: LocalPath, val candidate: LocalPath)
data class PreparedCopy(val plan: ResolvedPlan, val receipt: ValidationReceipt, val work: AttemptWorkspace, val staged: ContentEvidence)
interface CopyArtifacts {
    suspend fun create(plan: ResolvedPlan, planHash: String): AttemptWorkspace
    suspend fun record(work: AttemptWorkspace, name: String, json: String)
    suspend fun preparePublication(plan: ResolvedPlan, receipt: ValidationReceipt, work: AttemptWorkspace, stop: StopSignal): PreparedCopy
    suspend fun publish(prepared: PreparedCopy): LocalPath
}
