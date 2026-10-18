package org.ergoutree.compress

import kotlinx.coroutines.runBlocking
import org.ergoutree.compress.application.*
import org.ergoutree.compress.bootstrap.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.infrastructure.storage.*
import org.ergoutree.compress.platform.windows.*
import org.ergoutree.compress.ports.*
import org.junit.Rule
import org.junit.Before
import org.junit.Assume
import org.junit.rules.TemporaryFolder
import java.nio.file.*
import kotlin.test.*

/** 验证真实 NTFS 删除、备份保留和完整数据根迁移，不依赖生产 UI。 */
class StorageDesktopTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun windowsOnly() = Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
    private fun fixture() = StorageDesktopFixture(temporary.newFolder().toPath())
    @Test fun originalBackupRequiresExplicitAcknowledgementAndReleaseDoesNotBreakRecovery(): Unit = runBlocking {
        fixture().use { f ->
            val record = f.completed()
            val default = f.cleaner.preview(CleanupOptions(candidates = true))
            assertFalse(default.decisions.single { it.artifact.kind == ArtifactKind.ORIGINAL_BACKUP }.eligible)
            val preview = f.cleaner.preview(CleanupOptions(candidates = true, originalBackups = true))
            val backupDecision = preview.decisions.single { it.artifact.kind == ArtifactKind.ORIGINAL_BACKUP }
            assertTrue(backupDecision.eligible, backupDecision.reason)
            assertFailsWith<IllegalArgumentException> { f.cleaner.execute(preview.id, preview.confirmation) }
            val result = f.cleaner.execute(preview.id, preview.confirmation, true)
            assertTrue(result.failed.isEmpty(), result.failed.toString())
            assertFalse(Files.exists(record.backup!!.nio()))
            assertTrue(f.store.commit(record.id).backupReleased)
            f.coordinator.recoverTransactions()
            assertEquals(ItemState.SUCCEEDED, f.store.items("storage-job").single().state)
            assertFailsWith<IllegalArgumentException> { f.coordinator.restore(record.id) }
            assertEquals(record.output!!.sha256, f.environment.evidence(record.plan.destination).sha256)
        }
    }
    @Test fun nativeDeletionCannotDeleteLockedOrChangedFiles(): Unit = runBlocking {
        fixture().use { f ->
            val path = Files.createDirectories(f.root.resolve("analysis/artifacts/test")).resolve("cache.bin")
            Files.writeString(path, "cache")
            f.index.file(path.local(), ArtifactKind.CACHE)
            val artifact = f.store.maintenanceView().artifacts.single()
            Files.writeString(path, "changed")
            assertFailsWith<IllegalStateException> { WindowsManagedDeletion.delete(path, artifact) }
            assertTrue(Files.exists(path))
            f.index.file(path.local(), ArtifactKind.CACHE)
            val current = f.store.maintenanceView().artifacts.single()
            val kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE
            val handle = kernel.CreateFile(path.toString(), Int.MIN_VALUE, 0, null, 3, 0, null)
            require(handle != com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE)
            try { assertFailsWith<IllegalStateException> { WindowsManagedDeletion.delete(path, current) } } finally { kernel.CloseHandle(handle) }
            f.managed.delete(current); assertFalse(Files.exists(path))
        }
    }
    @Test fun oldSourceModificationTimeDoesNotBypassMinimumBackupRetention(): Unit = runBlocking {
        fixture().use { f ->
            f.completed(inputAgeDays = 365)
            val cleaner = ArtifactCleanup(f.store, f.managed, f.retention, f.gate, Clock { System.currentTimeMillis() }, f.ids)
            val decision = cleaner.preview(CleanupOptions(originalBackups = true)).decisions.single { it.artifact.kind == ArtifactKind.ORIGINAL_BACKUP }
            assertFalse(decision.eligible)
            assertEquals("尚未达到原始备份最短保留期", decision.reason)
        }
    }
    @Test fun migrationRebasesEvidencePreservesRestoreAndBootstrapFollowsPointer(): Unit = runBlocking {
        val target = temporary.newFolder("migrated").toPath()
        var original: LocalPath? = null; lateinit var record: CommitRecord
        fixture().use { f ->
            original = f.root.local(); record = f.completed()
            val result = f.migration().migrate(target.local())
            assertTrue(result.restartRequired); assertTrue(result.filesVerified > 0)
            assertTrue(Files.exists(record.backup!!.nio())) // 源档案作为回退副本保留。
            assertFailsWith<IllegalStateException> { f.gate.work {} }
        }
        DesktopBootstrap.open(requireNotNull(original).value).use { session ->
            assertEquals(target.toRealPath(), session.location.root)
            val app = AppWiring(session); app.queue.recover()
            assertTrue(Files.exists(target.resolve("tmp/probe-fixture.json")))
            assertTrue(app.storageReport().volumes.sumOf { it.backupBytesRetained } > 0)
            assertEquals(ItemState.SUCCEEDED, app.queue.items("storage-job").single().state)
            val migrated = app.commitHistory().single()
            assertNotEquals(record.backup, migrated.backup); assertNotNull(migrated.backupEvidence)
            app.restoreCommit(migrated.id)
            assertEquals(record.source.sha256, NioEnvironment(WindowsFileIdentity::key).evidence(record.plan.destination).sha256)
        }
    }
    @Test fun migrationFailureAndStopNeverActivatePartialTargets(): Unit = runBlocking {
        for (point in listOf("AfterCopy", "AfterRebase", "BeforeSwitch", "BeforePointer")) fixture().use { f ->
            val target = temporary.newFolder().toPath(); val record = f.completed()
            assertFailsWith<IllegalStateException> { f.migration(MigrationCheckpoint { phase -> if (phase == point) error("模拟故障") }).migrate(target.local()) }
            assertFalse(Files.exists(f.root.resolve("config/current-root.json")))
            assertTrue(Files.exists(target.resolve("migration-pending.json")))
            assertEquals(record.output, f.environment.evidence(record.plan.destination))
            assertFailsWith<IllegalArgumentException> { DesktopBootstrap.open(target.toString()) }
        }
        fixture().use { f ->
            f.completed(); val stopped = StopSignal().apply { request() }
            assertFailsWith<CompressException> { f.migration().migrate(temporary.newFolder().toPath().local(), stopped) }
            assertFalse(Files.exists(f.root.resolve("config/current-root.json")))
        }
    }
    @Test fun migrationRefusesPendingJobsTargetsAndChangedManagedFiles(): Unit = runBlocking {
        fixture().use { f ->
            f.completed()
            assertFailsWith<IllegalArgumentException> { f.migration().migrate(f.root.local()) }
            val notEmpty = temporary.newFolder().toPath(); Files.writeString(notEmpty.resolve("existing.txt"), "user")
            assertFailsWith<IllegalArgumentException> { f.migration().migrate(notEmpty.local()) }
            val artifact = f.store.maintenanceView().artifacts.first { it.kind == ArtifactKind.CANDIDATE }
            Files.writeString(artifact.evidence.identity.path.nio(), "external")
            assertFailsWith<IllegalArgumentException> { f.migration().migrate(temporary.newFolder().toPath().local()) }
        }
        fixture().use { f ->
            val source = Files.writeString(f.base.resolve("source.mp4"), "source").local()
            val binary = ToolBinary(source, f.environment.evidence(source).sha256, "fixture")
            val plan = ResolvedPlan(source = f.environment.identity(source), media = MediaInfo(format = "mp4", durationUs = 1, streams = emptyList()), tools = Toolchain(binary, binary, emptyList(), "fixture"), settings = EncodeSettings(), container = Container.MP4, destination = f.base.resolve("out.mp4").local())
            val job = FrozenJob(id = "pending", plans = listOf(plan), planHash = f.environment.sha256(PlanEncoding.canonicalList(listOf(plan))))
            f.store.enqueue(job, 1)
            assertFailsWith<IllegalArgumentException> { f.migration().migrate(temporary.newFolder().toPath().local()) }
        }
    }
    @Test fun crossVolumeMigrationPreservesBackupMetadataAndCanRestoreToOriginalVolume(): Unit = runBlocking {
        OwnedTestDirectory("s6-cross").use { owned ->
            val target = owned.path
            var sourceRoot: LocalPath? = null; lateinit var record: CommitRecord
            fixture().use { f ->
                Assume.assumeTrue(f.native.volume(target.local()).id != f.native.volume(f.root.local()).id)
                sourceRoot = f.root.local(); record = f.completed()
                f.migration().migrate(target.local())
            }
            DesktopBootstrap.open(requireNotNull(sourceRoot).value).use { session ->
                val app = AppWiring(session); app.queue.recover()
                assertTrue(app.storageReport().volumes.sumOf { it.backupBytesRetained } > 0)
                val migrated = app.commitHistory().single()
                assertEquals(record.source.sha256, NioEnvironment(WindowsFileIdentity::key).evidence(migrated.backup!!).sha256)
                val native = WindowsCommitFiles(target.local(), NioEnvironment(WindowsFileIdentity::key))
                assertEquals(record.sourceMetadata.daclHash, native.metadata(migrated.backup!!).daclHash)
                assertEquals(record.sourceMetadata.adsHashes, native.metadata(migrated.backup!!).adsHashes)
                app.restoreCommit(migrated.id)
                assertEquals(record.source.sha256, NioEnvironment(WindowsFileIdentity::key).evidence(record.plan.destination).sha256)
            }
        }
    }
    @Test fun cliStorageRetentionAndCleanupPreviewWorkWithoutUi(): Unit = runBlocking {
        val root = temporary.newFolder().toPath()
        DesktopBootstrap.open(root.toString()).use { session ->
            val app = AppWiring(session)
            BackendCommandLine.execute(arrayOf("--storage"), app)
            assertTrue(Files.readString(root.resolve("diagnostics/backend-result.json")).contains("volumes"))
            BackendCommandLine.execute(arrayOf("--retention"), app)
            assertTrue(Files.readString(root.resolve("diagnostics/backend-result.json")).contains("minimumBackupDays"))
            BackendCommandLine.execute(arrayOf("--cleanup-preview"), app)
            val preview = SnapshotJson.decodeFromString<CleanupPreview>(Files.readString(root.resolve("diagnostics/backend-result.json")))
            BackendCommandLine.execute(arrayOf("--cleanup=${preview.id}", "--confirm=${preview.confirmation}"), app)
        }
    }
}
