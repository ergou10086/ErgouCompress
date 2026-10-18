package org.ergoutree.compress

import com.sun.jna.platform.win32.Kernel32
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.*
import org.ergoutree.compress.bootstrap.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.infrastructure.storage.*
import org.ergoutree.compress.platform.windows.*
import org.ergoutree.compress.ports.*
import java.nio.file.*
import java.util.UUID

/** 独立字节夹具只证明存储协议；真实媒体验证由 MediaIntegrationTest 覆盖。 */
class StorageDesktopFixture(val base: Path) : AutoCloseable {
    val root = base.resolve("data")
    private val session = DesktopBootstrap.open(root.toString())
    val environment = NioEnvironment(WindowsFileIdentity::key)
    val store = session.own(SqlDelightJobStore(root.resolve("catalog/catalog.sqlite")))
    val rootId = Files.readString(root.resolve("config/root-id")).trim()
    val paths = ManagedPaths { path -> Kernel32.INSTANCE.GetFileAttributes(WindowsFileIdentity.extendedPath(path)).let { it != -1 && it and 0x400 != 0 } }
    val native = WindowsCommitFiles(root.local(), environment)
    val registry = RegisteredWorkspaces(root.local(), store, native)
    val index = ManagedArtifactIndex(root.local(), rootId, store, store, environment, paths)
    val indexed = IndexedCommitFiles(native, index, root.local())
    val gate = MaintenanceGate()
    val clock = Clock { System.currentTimeMillis() + 100L * 86_400_000 }
    val ids = IdGenerator { UUID.randomUUID().toString() }
    val retention = JsonRetentionRepository(root.resolve("config/retention.json"))
    val managed = NioManagedStorage(root.local(), rootId, store, environment, paths, native::volume, WindowsManagedDeletion::delete)
    val cleaner = ArtifactCleanup(store, managed, retention, gate, clock, ids)
    val coordinator = CommitCoordinator(store, store, indexed, environment, registry, clock, ids)
    suspend fun completed(inputAgeDays: Int = 0): CommitRecord {
        val source = Files.write(base.resolve("原始 视频.mp4"), ByteArray(65536) { (it % 127).toByte() }).local()
        Files.setLastModifiedTime(source.nio(), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - inputAgeDays * 86_400_000L))
        WindowsDataStreams.create(WindowsFileIdentity.extendedPath(source.nio()) + ":test-note").use { it.write("original ADS".toByteArray()) }
        val tool = Files.writeString(base.resolve("external-tool.fixture"), "测试专用工具指纹，不可执行").local()
        val directory = Files.createDirectories(root.resolve("jobs/attempt"))
        val candidate = Files.write(directory.resolve("encoded.partial.mp4"), ByteArray(4096) { (it % 53).toByte() }).local()
        val binary = ToolBinary(tool, environment.evidence(tool).sha256, "test-only")
        val plan = ResolvedPlan(schemaVersion = 3, plannerVersion = 3, source = environment.identity(source),
            media = MediaInfo(format = "mp4", durationUs = 1_000_000, streams = listOf(MediaStream(0, StreamKind.VIDEO, "h264", width = 320, height = 240, pixelFormat = "yuv420p", colorTransfer = "bt709"))),
            tools = Toolchain(binary, binary, listOf(BackendCheck(Backend.X264, true, "fixture")), "fixture"), settings = EncodeSettings(), container = Container.MP4,
            destination = source, outputMode = OutputMode.REPLACE)
        val job = FrozenJob(id = "storage-job", plans = listOf(plan), planHash = environment.sha256(PlanEncoding.canonicalList(listOf(plan))))
        store.enqueue(job, 1); store.resume(job.id, 2); val claim = store.claim(job.id, "test", "attempt", 3)!!
        val receipt = ValidationReceipt(planHash = environment.sha256(PlanEncoding.canonical(plan)), source = environment.evidence(source), candidate = environment.evidence(candidate),
            outputMedia = plan.media, audioPacketDigests = emptyList(), fullDecodePassed = true)
        store.workspace(claim, WorkspaceRecord("attempt", directory.local(), candidate, receipt.source, receipt.planHash), 4)
        store.validated(claim, receipt, 5)
        index.file(candidate, ArtifactKind.CANDIDATE)
        val cachedProbe = Files.writeString(root.resolve("tmp/probe-fixture.json"), "{}")
        index.file(cachedProbe.local(), ArtifactKind.CACHE)
        Files.writeString(directory.resolve("validation.json"), SnapshotJson.encodeToString(receipt)); index.file(directory.resolve("validation.json").local(), ArtifactKind.METADATA)
        val prepared = coordinator.prepare(claim, plan, receipt, StopSignal())
        store.publishing(claim, 6); val target = coordinator.publish(prepared.id)
        store.published(claim, environment.evidence(target), 7); store.finish(claim, AttemptResult(AttemptStage.SUCCEEDED, target, message = "fixture"), 8)
        return store.commit(prepared.id)
    }
    fun migration(checkpoint: MigrationCheckpoint = MigrationCheckpoint { }) = DataRootMigration(root.local(), rootId, store, store, environment, native, paths, gate, checkpoint)
    override fun close() = session.close()
}
