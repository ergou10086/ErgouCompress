package org.ergoutree.compress.infrastructure

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.infrastructure.storage.SqlDelightJobStore
import org.ergoutree.compress.testing.QueueFixture
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.*

class SqlDelightJobStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun frozenSnapshotRoundTripsAcrossReopenWithMonotonicEvents() = runBlocking {
        val f = QueueFixture(temporary.newFolder().toPath()); val job = f.job()
        val path = f.root.resolve("catalog.sqlite")
        SqlDelightJobStore(path).use { store ->
            store.enqueue(job, 10); store.enqueue(job, 11)
            assertEquals(1, store.history().size); assertEquals(1, store.events(job.id).size)
            store.resume(job.id, 12); store.requestPause(job.id, false, 13)
        }
        SqlDelightJobStore(path).use { store ->
            assertEquals(job, store.job(job.id).snapshot)
            assertEquals(JobState.PAUSED, store.job(job.id).state)
            val events = store.events(job.id)
            assertEquals((1L..events.size.toLong()).toList(), events.map { it.sequence })
            assertTrue(store.job(job.id).revision > 0)
        }
    }
    @Test fun conflictingReservationRollsBackWholeJob() = runBlocking {
        val f = QueueFixture(temporary.newFolder().toPath()); val job = f.job()
        SqlDelightJobStore(f.root.resolve("db")).use { store ->
            store.enqueue(job, 1)
            assertFailsWith<CompressException> { store.enqueue(job.copy(id = "conflict"), 2) }
            assertEquals(listOf(job.id), store.history().map { it.id })
        }
    }
    @Test fun concurrentClaimsHaveExactlyOneWinnerAndStaleTokenCannotWrite() = runBlocking {
        val f = QueueFixture(temporary.newFolder().toPath()); val job = f.job()
        SqlDelightJobStore(f.root.resolve("db")).use { store ->
            store.enqueue(job, 1); store.resume(job.id, 2)
            val claims = (0..15).map { i -> async(Dispatchers.Default) { store.claim(job.id, "worker-$i", "attempt-$i", 3) } }.awaitAll().filterNotNull()
            val claim = claims.single()
            assertFailsWith<IllegalStateException> { store.finish(claim.copy(epoch = claim.epoch + 1), AttemptResult(AttemptStage.FAILED, message = "stale"), 4) }
            assertEquals(ItemState.RUNNING, store.items(job.id).first().state)
        }
    }
    @Test fun successWithoutValidationAndOutputEvidenceIsRejected() = runBlocking {
        val f = QueueFixture(temporary.newFolder().toPath()); val job = f.job()
        SqlDelightJobStore(f.root.resolve("db")).use { store ->
            store.enqueue(job, 1); store.resume(job.id, 2)
            val claim = store.claim(job.id, "worker", "attempt", 3)!!
            assertFailsWith<IllegalArgumentException> { store.finish(claim, AttemptResult(AttemptStage.SUCCEEDED, message = "fake success"), 4) }
            assertEquals(ItemState.RUNNING, store.items(job.id).first().state)
        }
    }
    @Test fun persistedStopRejectsPublicationAndRetryCreatesANewAttempt() = runBlocking {
        val f = QueueFixture(temporary.newFolder().toPath()); val job = f.job(count = 1)
        SqlDelightJobStore(f.root.resolve("db")).use { store ->
            store.enqueue(job, 1); store.resume(job.id, 2)
            val claim = store.claim(job.id, "worker", "first", 3)!!
            val (work, receipt) = f.workspace(claim.item.plan, "first")
            store.workspace(claim, WorkspaceRecord(work.id, work.root, work.candidate, receipt.source, receipt.planHash), 4)
            store.validated(claim, receipt, 5); store.requestPause(job.id, true, 6)
            assertFailsWith<CompressException> { store.publishing(claim, 7) }
            store.finish(claim, AttemptResult(AttemptStage.INTERRUPTED, message = "stopped"), 8)
            store.resume(job.id, 9)
            val next = store.claim(job.id, "worker", "second", 10)!!
            assertEquals(2, next.item.attemptSequence); assertTrue(store.attempts(claim.item.id).first().stopRequested)
            assertFalse(store.attempts(claim.item.id).last().stopRequested)
        }
    }
    @Test fun receiptCannotBeReusedFromAnotherSourceOrPlan(): Unit = runBlocking {
        val f = QueueFixture(temporary.newFolder().toPath()); val job = f.job()
        SqlDelightJobStore(f.root.resolve("db")).use { store ->
            store.enqueue(job, 1); store.resume(job.id, 2)
            val claim = store.claim(job.id, "worker", "one", 3)!!
            val (work, receipt) = f.workspace(claim.item.plan, "one")
            store.workspace(claim, WorkspaceRecord(work.id, work.root, work.candidate, receipt.source, receipt.planHash), 4)
            assertFailsWith<IllegalArgumentException> { store.validated(claim, receipt.copy(planHash = "different"), 5) }
        }
    }
    @Test fun corruptOrNewerDatabaseIsNeverDeletedAndRecreated() {
        val root = temporary.newFolder().toPath()
        val bad = Files.write(root.resolve("bad"), byteArrayOf(5, 6, 7, 8))
        assertFailsWith<Exception> { SqlDelightJobStore(bad) }; assertContentEquals(byteArrayOf(5, 6, 7, 8), Files.readAllBytes(bad))
        val future = root.resolve("future")
        DriverManager.getConnection("jdbc:sqlite:$future").use { it.createStatement().use { s -> s.execute("PRAGMA user_version=999") } }
        assertFailsWith<IllegalArgumentException> { SqlDelightJobStore(future) }
    }
    @Test fun nativeKernelAndSafetyPragmasAreReal() = runBlocking {
        val path = temporary.newFolder().toPath().resolve("db")
        SqlDelightJobStore(path).use { }
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.createStatement().use { s ->
                s.executeQuery("SELECT sqlite_version()").use { assertTrue(it.next()); assertEquals("3.53.4", it.getString(1)) }
                s.executeQuery("PRAGMA journal_mode").use { assertTrue(it.next()); assertEquals("delete", it.getString(1)) }
            }
        }
    }
}
