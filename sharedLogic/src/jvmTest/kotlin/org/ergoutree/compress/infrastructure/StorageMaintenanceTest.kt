package org.ergoutree.compress.infrastructure

import kotlinx.coroutines.runBlocking
import org.ergoutree.compress.application.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.infrastructure.storage.*
import org.ergoutree.compress.ports.*
import org.ergoutree.compress.testing.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.*
import java.util.zip.ZipFile
import kotlin.test.*

/** 使用真实文件和 SQLite 验证预览、删除、故障补记及脱敏导出。 */
class StorageMaintenanceTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun fixture() = StorageFixture(temporary.newFolder().toPath())
    @Test fun cleanupDeletesOnlySelectedRegisteredArtifactsAndLeavesTombstones(): Unit = runBlocking {
        fixture().use { f ->
            val cache = f.artifact("analysis/artifacts/test/sample.bin")
            val proof = f.artifact("jobs/test/validation.json", ArtifactKind.METADATA)
            val unregistered = Files.writeString(f.root.resolve("user-file.txt"), "不得清理")
            val cleaner = f.cleanup(); val preview = cleaner.preview()
            assertTrue(preview.decisions.single { it.artifact.id == cache.id }.eligible)
            assertFalse(preview.decisions.single { it.artifact.id == proof.id }.eligible)
            val result = cleaner.execute(preview.id, preview.confirmation)
            assertEquals(listOf(cache.id), result.deleted); assertTrue(result.deletedLogicalBytes > 0)
            assertTrue(Files.exists(unregistered)); assertTrue(Files.exists(proof.evidence.identity.path.nio()))
            assertEquals(ArtifactState.DELETED, f.store.maintenanceView().artifacts.single { it.id == cache.id }.state)
            assertFailsWith<IllegalStateException> { cleaner.execute(preview.id, preview.confirmation) }
        }
    }
    @Test fun changedFileExpiredPreviewAndChangedPolicyAllPreventDeletion(): Unit = runBlocking {
        for (case in listOf("file", "age", "policy", "token")) fixture().use { f ->
            val cache = f.artifact("analysis/artifacts/test/cache.bin"); val cleaner = f.cleanup(); val preview = cleaner.preview()
            when (case) {
                "file" -> Files.writeString(cache.evidence.identity.path.nio(), "external")
                "age" -> f.now += 900_001
                "policy" -> f.retention.save(RetentionPolicy(logDays = 31))
            }
            assertFailsWith<Exception> { cleaner.execute(preview.id, if (case == "token") "bad" else preview.confirmation) }
            assertTrue(Files.exists(cache.evidence.identity.path.nio()))
        }
    }
    @Test fun interruptedDeletionIsReconciledAfterDatabaseReopen(): Unit = runBlocking {
        fixture().use { f ->
            val cache = f.artifact("analysis/artifacts/test/cache.bin")
            val failing = object : MaintenanceStore by f.store { override suspend fun completeDeletion(artifactId: String) { throw java.io.IOException("模拟墓碑写入失败") } }
            val cleaner = f.cleanup(failing); val preview = cleaner.preview(); val result = cleaner.execute(preview.id, preview.confirmation)
            assertTrue(result.failed.containsKey(cache.id)); assertFalse(Files.exists(cache.evidence.identity.path.nio()))
            assertEquals(ArtifactState.MARKED, f.store.maintenanceView().artifacts.single().state)
            f.reopen(); f.cleanup().reconcile()
            assertEquals(ArtifactState.DELETED, f.store.maintenanceView().artifacts.single().state)
        }
    }
    @Test fun deleteFailureKeepsArtifactAndAllowsNewPreview(): Unit = runBlocking {
        fixture().use { f ->
            val cache = f.artifact("analysis/artifacts/test/cache.bin")
            val failing = object : ManagedStorageFiles by f.files { override suspend fun delete(artifact: ManagedArtifact) { throw java.io.IOException("模拟独占锁") } }
            val cleaner = f.cleanup(storage = failing); val preview = cleaner.preview()
            assertEquals(1, cleaner.execute(preview.id, preview.confirmation).failed.size)
            assertTrue(Files.exists(cache.evidence.identity.path.nio())); assertEquals(ArtifactState.LIVE, f.store.maintenanceView().artifacts.single().state)
            assertTrue(f.cleanup().preview().decisions.single().eligible)
        }
    }
    @Test fun traversalAndChangedRootMarkerAreRejected(): Unit = runBlocking {
        fixture().use { f ->
            val cache = f.artifact("analysis/artifacts/test/cache.bin")
            assertFailsWith<IllegalArgumentException> { f.files.resolve(cache.copy(relativePath = "../outside.bin")) }
            Files.writeString(f.root.resolve("config/root-id"), "other-root")
            assertFalse(f.cleanup().preview().decisions.single().eligible)
            assertTrue(Files.exists(cache.evidence.identity.path.nio()))
        }
    }
    @Test fun changedRootDuringReconciliationIsNotMistakenForDeletedFile(): Unit = runBlocking {
        fixture().use { f ->
            val cache = f.artifact("analysis/artifacts/test/cache.bin")
            val preview = f.cleanup().preview()
            f.store.claimPreview(preview.id, preview.confirmation)
            Files.writeString(f.root.resolve("config/root-id"), "unavailable-root")
            f.cleanup().reconcile()
            assertEquals(ArtifactState.LIVE, f.store.maintenanceView().artifacts.single().state)
            assertTrue(Files.exists(cache.evidence.identity.path.nio()))
        }
    }
    @Test fun activeOrNeedsAttentionItemsBlockCleanupEvenWhenHistoryIsHidden(): Unit = runBlocking {
        fixture().use { f ->
            val job = QueueFixture(f.root.parent).job("job", 1)
            f.store.enqueue(job, 1); f.store.resume(job.id, 2); val claim = f.store.claim(job.id, "worker", "attempt", 3)!!
            f.artifact("analysis/artifacts/test/cache.bin")
            assertFailsWith<IllegalStateException> { f.cleanup().preview() }
            f.store.recovered(claim.item.id, ItemState.NEEDS_ATTENTION, null, "待核对", 4)
            assertFailsWith<IllegalStateException> { f.cleanup().preview() }
        }
        fixture().use { f ->
            val job = QueueFixture(f.root.parent).job("hidden", 1)
            f.store.enqueue(job, 1); f.store.cancel(job.id, 2); f.store.hide(job.id)
            assertTrue(f.store.history().isEmpty()); assertEquals(1, f.store.maintenanceView().jobs.size)
        }
    }
    @Test fun cacheBudgetEvictsOldestWithoutPretendingMetadataIsDisposable(): Unit = runBlocking {
        fixture().use { f ->
            f.retention.save(RetentionPolicy(cacheDays = 3650, cacheBudgetBytes = 1))
            f.artifact("analysis/artifacts/old/cache.bin"); f.artifact("analysis/artifacts/new/cache.bin")
            assertTrue(f.cleanup().preview().decisions.all { it.eligible })
            val report = StorageOverview(f.store, f.files, f.root.local()).report()
            assertEquals(1, report.volumes.size); assertTrue(report.volumes.single().temporaryBytes > 0)
        }
    }
    @Test fun supportZipUsesAnonymousNamesAndOmitsMediaAndDatabase(): Unit = runBlocking {
        fixture().use { f ->
            val artifact = f.artifact("jobs/test/encode.stderr.log", ArtifactKind.LOG)
            Files.writeString(artifact.evidence.identity.path.nio(), "打开 D:/Private/家庭 片段.mp4\nuser@example.com\nAuthorization: Bearer super-secret\nframe=42 speed=1.2x")
            f.index.file(artifact.evidence.identity.path, ArtifactKind.LOG)
            val destination = f.root.parent.resolve("support-${f.root.fileName}.zip")
            SupportExporter(f.store, f.files, f.gate).export(destination.local())
            ZipFile(destination.toFile()).use { zip ->
                val names = zip.entries().toList().map { it.name }
                assertEquals(setOf("summary.json", "logs/1.txt"), names.toSet())
                val text = names.joinToString { zip.getInputStream(zip.getEntry(it)).bufferedReader().use { reader -> reader.readText() } }
                for (value in listOf("Private", "家庭", "片段.mp4", "example.com", "super-secret", f.root.toString())) assertFalse(text.contains(value), text)
                assertTrue(text.contains("frame=42"))
            }
            assertFailsWith<IllegalArgumentException> { SupportExporter(f.store, f.files, f.gate).export(destination.local()) }
        }
    }
    @Test fun retentionRoundTripAndReparseDetectorProtectTheRoot(): Unit = runBlocking {
        fixture().use { f ->
            val policy = RetentionPolicy(1, 0, 90, 123456, 10); f.retention.save(policy); assertEquals(policy, f.retention.load())
            val cache = f.artifact("analysis/artifacts/test/cache.bin")
            assertFailsWith<IllegalArgumentException> { ManagedPaths { it.fileName?.toString() == "artifacts" }.resolve(cache.root, cache.relativePath) }
        }
    }
}
