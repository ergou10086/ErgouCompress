package org.ergoutree.compress

import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.infrastructure.storage.SqlDelightJobStore
import org.ergoutree.compress.platform.windows.*
import org.ergoutree.compress.ports.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Byte fixtures exercise the file protocol; the real media receipt chain has separate integration tests. */
class CommitFixture(val root: Path) : AutoCloseable {
    val data = Files.createDirectories(root.resolve("data"))
    val environment = NioEnvironment(WindowsFileIdentity::key)
    val store = SqlDelightJobStore(data.resolve("catalog.sqlite"))
    val native = WindowsCommitFiles(data.local(), environment)
    val registry = RegisteredWorkspaces(data.local(), store, native)
    val clock = Clock { System.currentTimeMillis() }
    val ids = IdGenerator { UUID.randomUUID().toString() }
    fun coordinator(files: CommitFiles = native, checkpoint: CommitCheckpoint = CommitCheckpoint { _, _ -> }) = CommitCoordinator(store, store, files, environment, registry, clock, ids, checkpoint)
    suspend fun input(mode: OutputMode = OutputMode.REPLACE, candidateBytes: Int = 4096, sourceDirectory: Path = root.resolve("inputs")): Input {
        Files.createDirectories(sourceDirectory)
        val source = Files.write(sourceDirectory.resolve("中文 视频.mp4"), ByteArray(65536) { (it % 127).toByte() }).local()
        WindowsDataStreams.create(WindowsFileIdentity.extendedPath(source.nio()) + ":ergou-note").use { it.write("source ADS".toByteArray()) }
        val candidateDir = Files.createDirectories(data.resolve("jobs/fixture"))
        val candidate = Files.write(candidateDir.resolve("candidate.mp4"), ByteArray(candidateBytes) { (it % 53).toByte() }).local()
        val tool = ToolBinary(candidate, environment.evidence(candidate).sha256, "test-only")
        val media = MediaInfo(format = "mp4", durationUs = 1_000_000, streams = listOf(MediaStream(0, StreamKind.VIDEO, "h264", width = 320, height = 240, pixelFormat = "yuv420p", colorTransfer = "bt709")))
        val destination = when (mode) {
            OutputMode.REPLACE -> source
            OutputMode.CHANGE_EXTENSION -> sourceDirectory.resolve("中文 视频.mkv").local()
            OutputMode.COPY -> Files.createDirectories(root.resolve("output")).resolve("中文 视频.mp4").local()
        }
        val plan = ResolvedPlan(schemaVersion = 2, plannerVersion = 2, source = environment.identity(source), media = media,
            tools = Toolchain(tool, tool, listOf(BackendCheck(Backend.X264, true, "test-only")), "fixture"), settings = EncodeSettings(),
            container = if (mode == OutputMode.CHANGE_EXTENSION) Container.MKV else Container.MP4, destination = destination, outputMode = mode)
        val job = FrozenJob(id = "fixture", plans = listOf(plan), planHash = environment.sha256(PlanEncoding.canonicalList(listOf(plan))))
        store.enqueue(job, 1); store.resume(job.id, 2)
        val claim = store.claim(job.id, "worker", "attempt", 3)!!
        val receipt = ValidationReceipt(planHash = environment.sha256(PlanEncoding.canonical(plan)), source = environment.evidence(source), candidate = environment.evidence(candidate),
            outputMedia = media, audioPacketDigests = emptyList(), fullDecodePassed = true)
        store.workspace(claim, WorkspaceRecord("fixture", candidateDir.local(), candidate, receipt.source, receipt.planHash), 4)
        store.validated(claim, receipt, 5)
        return Input(plan, claim, receipt)
    }
    data class Input(val plan: ResolvedPlan, val claim: ClaimedItem, val receipt: ValidationReceipt)
    suspend fun publish(coordinator: CommitCoordinator, record: CommitRecord, claim: ClaimedItem): LocalPath {
        store.publishing(claim, 10)
        val target = coordinator.publish(record.id)
        store.published(claim, environment.evidence(target), 11)
        store.finish(claim, AttemptResult(AttemptStage.SUCCEEDED, target, message = "published"), 12)
        return target
    }
    override fun close() = store.close()
}
