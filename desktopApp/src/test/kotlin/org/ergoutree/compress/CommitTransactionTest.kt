package org.ergoutree.compress

import kotlinx.coroutines.runBlocking
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.ports.*
import org.ergoutree.compress.platform.windows.WindowsDataStreams
import org.ergoutree.compress.platform.windows.WindowsFileIdentity
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.*

class CommitTransactionTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun windowsOnly() = Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
    private fun fixture() = CommitFixture(temporary.newFolder().toPath())
    @Test fun nativeReplaceHasBackupFileIdentityMetadataAndNoDuplicateMutation(): Unit = runBlocking {
        fixture().use { f ->
            val input = f.input()
            // Fixture creates ADS before freezing the input identity.
            var nativeCalls = 0
            val counted = object : CommitFiles by f.native { override suspend fun replace(source: LocalPath, staged: LocalPath, backup: LocalPath): NativeResult { nativeCalls++; return f.native.replace(source, staged, backup) } }
            val coordinator = f.coordinator(counted); val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal())
            val target = f.publish(coordinator, record, input.claim)
            assertEquals(input.receipt.candidate.sha256, f.environment.evidence(target).sha256)
            assertEquals(input.receipt.source.sha256, f.environment.evidence(record.backup!!).sha256)
            assertEquals(input.receipt.source.identity.fileKey, f.environment.evidence(record.backup!!).identity.fileKey)
            assertEquals("source ADS", WindowsDataStreams.input(WindowsFileIdentity.extendedPath(target.nio()) + ":ergou-note").bufferedReader().use { it.readText() })
            assertEquals(record.sourceMetadata.daclHash, f.native.metadata(target).daclHash)
            assertEquals(record.sourceMetadata.modifiedTime, f.native.metadata(target).modifiedTime)
            coordinator.publish(record.id); assertEquals(1, nativeCalls)
        }
    }
    @Test fun extensionChangePublishesBeforeArchivingOriginal(): Unit = runBlocking {
        fixture().use { f ->
            val input = f.input(OutputMode.CHANGE_EXTENSION); val coordinator = f.coordinator()
            val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal())
            val target = f.publish(coordinator, record, input.claim)
            assertFalse(Files.exists(input.plan.source.path.nio())); assertTrue(Files.exists(target.nio()))
            assertEquals(input.receipt.source.sha256, f.environment.evidence(record.backup!!).sha256)
            val types = f.store.events("fixture").map { it.type }
            assertTrue(types.indexOf("CommitRETIRE_INTENT") > types.indexOf("CommitPUBLISH_INTENT"))
        }
    }
    @Test fun copyKeepsOriginalAndRestoreNeverConsumesUniqueBackup(): Unit = runBlocking {
        fixture().use { f ->
            val input = f.input(); val coordinator = f.coordinator()
            val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal())
            f.publish(coordinator, record, input.claim)
            val restored = coordinator.restore(record.id)
            assertEquals(input.receipt.source.sha256, f.environment.evidence(input.plan.source.path).sha256)
            assertEquals(input.receipt.source.sha256, f.environment.evidence(record.backup!!).sha256)
            assertEquals(input.receipt.candidate.sha256, f.environment.evidence(restored.backup!!).sha256)
            assertTrue(f.store.recoveryItems().single().superseded)
        }
        fixture().use { f ->
            val input = f.input(OutputMode.COPY); val coordinator = f.coordinator()
            val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal()); f.publish(coordinator, record, input.claim)
            assertEquals(input.receipt.source, f.environment.evidence(input.plan.source.path))
        }
    }
    @Test fun sourceCandidateStageAndLastBoundaryChangesNeverOverwriteSource(): Unit = runBlocking {
        for (change in listOf("source", "candidate", "staged", "boundary")) fixture().use { f ->
            val input = f.input()
            val coordinator = f.coordinator(checkpoint = CommitCheckpoint { point, _ -> if (change == "boundary" && point == "BeforeNative") Files.writeString(input.plan.source.path.nio(), "external change") })
            val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal())
            when (change) { "source" -> Files.writeString(input.plan.source.path.nio(), "external change"); "candidate" -> Files.writeString(input.receipt.candidate.identity.path.nio(), "tamper"); "staged" -> Files.writeString(record.staged.identity.path.nio(), "tamper") }
            assertFailsWith<Exception> { f.publish(coordinator, record, input.claim) }
            assertFalse(Files.exists(record.backup!!.nio()))
            assertEquals(if (change in listOf("source", "boundary")) "external change" else null, if (change in listOf("source", "boundary")) Files.readString(input.plan.source.path.nio()) else null)
        }
    }
    @Test fun nativeErrorsAreReconciledRatherThanAssumedNoChange(): Unit = runBlocking {
        for (code in listOf(5, 32, 112, 1175, 1176)) fixture().use { f ->
            val input = f.input()
            val fault = object : CommitFiles by f.native { override suspend fun replace(source: LocalPath, staged: LocalPath, backup: LocalPath) = NativeResult(false, code) }
            val coordinator = f.coordinator(fault); val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal())
            assertFailsWith<CompressException> { f.publish(coordinator, record, input.claim) }
            assertEquals(input.receipt.source, f.environment.evidence(input.plan.source.path))
            assertFalse(Files.exists(record.backup!!.nio()))
            f.coordinator().recoverTransactions()
            assertEquals(input.receipt.candidate.sha256, f.environment.evidence(input.plan.source.path).sha256)
        }
    }
    @Test fun error1177KeepsBackupAndRecoveryFinishesWithoutEncoding(): Unit = runBlocking {
        fixture().use { f ->
            val input = f.input()
            val fault = object : CommitFiles by f.native { override suspend fun replace(source: LocalPath, staged: LocalPath, backup: LocalPath): NativeResult { f.native.retire(source, backup); return NativeResult(false, 1177) } }
            val coordinator = f.coordinator(fault); val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal())
            assertFailsWith<CompressException> { f.publish(coordinator, record, input.claim) }
            assertEquals(input.receipt.source.sha256, f.environment.evidence(record.backup!!).sha256)
            f.coordinator().recoverTransactions(); assertEquals(ItemState.SUCCEEDED, f.store.items("fixture").single().state)
        }
    }
    @Test fun ambiguousErrorAfterRealSuccessStillUsesPhysicalProof(): Unit = runBlocking {
        fixture().use { f ->
            val input = f.input()
            val fault = object : CommitFiles by f.native { override suspend fun replace(source: LocalPath, staged: LocalPath, backup: LocalPath): NativeResult { f.native.replace(source, staged, backup); return NativeResult(false, 32) } }
            val coordinator = f.coordinator(fault); val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal()); f.publish(coordinator, record, input.claim)
            assertEquals(CommitPhase.COMMITTED, f.store.commit(record.id).phase); assertEquals(32, f.store.commit(record.id).lastError)
        }
    }
    @Test fun stoppedValidatedEmptyEqualAndHardLinkedCandidatesAreBlocked(): Unit = runBlocking {
        for (size in listOf(0, 65536, 70000)) fixture().use { f ->
            val input = f.input(candidateBytes = size)
            assertFailsWith<Exception> { f.coordinator().prepare(input.claim, input.plan, input.receipt, StopSignal()) }
            assertEquals(input.receipt.source, f.environment.evidence(input.plan.source.path))
        }
        fixture().use { f ->
            val input = f.input(); val candidate = input.receipt.candidate.identity.path
            Files.delete(candidate.nio()); Files.createLink(candidate.nio(), input.plan.source.path.nio())
            val linked = input.receipt.copy(candidate = f.environment.evidence(candidate))
            assertFailsWith<Exception> { f.coordinator().prepare(input.claim, input.plan, linked, StopSignal()) }
        }
        fixture().use { f ->
            val input = f.input()
            assertFailsWith<Exception> { f.coordinator().prepare(input.claim, input.plan, input.receipt.copy(fullDecodePassed = false), StopSignal()) }
        }
        fixture().use { f ->
            val input = f.input(); val coordinator = f.coordinator(); val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal())
            f.store.requestPause("fixture", true, 10)
            assertFailsWith<CompressException> { coordinator.publish(record.id) }; coordinator.recoverTransactions()
            assertEquals(input.receipt.source, f.environment.evidence(input.plan.source.path))
        }
    }
    @Test fun realExclusiveLockAndReadonlyAttributePreserveOriginal(): Unit = runBlocking {
        for (kind in listOf("locked", "readonly")) fixture().use { f ->
            val input = f.input(); val coordinator = f.coordinator(); val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal())
            if (kind == "locked") {
                val kernel = com.sun.jna.platform.win32.Kernel32.INSTANCE
                val handle = kernel.CreateFile(input.plan.source.path.value, Int.MIN_VALUE, 0, null, 3, 0, null)
                require(handle != com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE)
                try { assertFailsWith<Exception> { coordinator.publish(record.id) } } finally { kernel.CloseHandle(handle) }
            } else {
                Files.setAttribute(input.plan.source.path.nio(), "dos:readonly", true)
                try { assertFailsWith<Exception> { coordinator.publish(record.id) } } finally { Files.setAttribute(input.plan.source.path.nio(), "dos:readonly", false) }
            }
            assertEquals(input.receipt.source.sha256, f.environment.evidence(input.plan.source.path).sha256)
            assertFalse(Files.exists(record.backup!!.nio()))
        }
    }
    @Test fun crossVolumeRequiresRegisteredWorkspaceAndStagesBeforeReplacing(): Unit = runBlocking {
        fixture().use { f ->
            val workspaceRoot = Path.of(System.getProperty("ergou.app.home"), "test-output", "s4-cross-volume", java.util.UUID.randomUUID().toString())
            Files.createDirectories(workspaceRoot)
            Assume.assumeTrue(f.native.volume(workspaceRoot.local()).id != f.native.volume(f.data.local()).id)
            val input = f.input(sourceDirectory = workspaceRoot.resolve("inputs")); val coordinator = f.coordinator()
            assertFailsWith<CompressException> { coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal()) }
            val registered = Files.createDirectory(workspaceRoot.resolve("managed")); f.registry.register(registered.local())
            val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal())
            assertTrue(record.staged.identity.path.nio().startsWith(registered))
            f.publish(coordinator, record, input.claim)
            assertEquals(input.receipt.source.sha256, f.environment.evidence(record.backup!!).sha256)
        }
    }
    @Test fun databaseOrMirrorWriteFailurePreventsNativeMutation(): Unit = runBlocking {
        fixture().use { f ->
            val input = f.input(); val coordinator = f.coordinator(); val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal())
            DriverManager.getConnection("jdbc:sqlite:${f.data.resolve("catalog.sqlite")}").use { it.createStatement().use { s -> s.execute("CREATE TRIGGER deny_update BEFORE UPDATE ON commit_log BEGIN SELECT RAISE(ABORT,'test write failure'); END") } }
            assertFailsWith<Exception> { coordinator.publish(record.id) }; assertEquals(input.receipt.source, f.environment.evidence(input.plan.source.path)); assertFalse(Files.exists(record.backup!!.nio()))
        }
        fixture().use { f ->
            val input = f.input(); Files.writeString(f.data.resolve("commits"), "not a directory")
            assertFailsWith<Exception> { f.coordinator().prepare(input.claim, input.plan, input.receipt, StopSignal()) }
            assertEquals(input.receipt.source, f.environment.evidence(input.plan.source.path))
        }
    }
    @Test fun existingBackupTargetAndModifiedRestoreTargetAreNeverOverwritten(): Unit = runBlocking {
        fixture().use { f ->
            val input = f.input(); val coordinator = f.coordinator(); val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal())
            Files.writeString(record.backup!!.nio(), "external backup")
            assertFailsWith<Exception> { coordinator.publish(record.id) }; assertEquals("external backup", Files.readString(record.backup!!.nio()))
        }
        fixture().use { f ->
            val input = f.input(OutputMode.COPY); Files.writeString(input.plan.destination.nio(), "existing target")
            assertFailsWith<Exception> { f.coordinator().prepare(input.claim, input.plan, input.receipt, StopSignal()) }
        }
        fixture().use { f ->
            val input = f.input(); val coordinator = f.coordinator(); val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal()); f.publish(coordinator, record, input.claim)
            Files.writeString(input.plan.destination.nio(), "external new content")
            assertFailsWith<IllegalArgumentException> { coordinator.restore(record.id) }; assertEquals("external new content", Files.readString(input.plan.destination.nio()))
        }
    }
    @Test fun longMediaPathUsesShortCatalogAndRealWindowsApis(): Unit = runBlocking {
        fixture().use { f ->
            var sourceDir = f.root.resolve("inputs")
            while (sourceDir.toString().length < 400) sourceDir = sourceDir.resolve("中文长路径_abcdefghijklmnop")
            val input = f.input(sourceDirectory = sourceDir); val coordinator = f.coordinator(); val record = coordinator.prepare(input.claim, input.plan, input.receipt, StopSignal())
            f.publish(coordinator, record, input.claim); assertEquals(CommitPhase.COMMITTED, f.store.commit(record.id).phase)
        }
    }
}
