package org.ergoutree.compress.infrastructure

import kotlinx.coroutines.*
import org.ergoutree.compress.application.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.infrastructure.storage.SqlDelightJobStore
import org.ergoutree.compress.ports.*
import org.ergoutree.compress.testing.QueueFixture
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class QueueCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun queue(f: QueueFixture, store: JobStore, executor: ItemExecutor) = QueueCoordinator(store, executor, f.files, Clock { 100 }, IdGenerator { UUID.randomUUID().toString() })
    @Test fun recoveryIsRequiredAndDuplicateResumeNeverRunsTwoExecutors() = runBlocking {
        val f = QueueFixture(temporary.newFolder().toPath()); val job = f.job()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
        SqlDelightJobStore(f.root.resolve("db")).use { store ->
            val queue = queue(f, store, ItemExecutor { p, stop, life, _ -> calls++; started.complete(Unit); release.await(); f.complete(p, stop, life, UUID.randomUUID().toString()) })
            queue.submit(job); assertFailsWith<IllegalStateException> { queue.run(job.id) }; queue.recover()
            val running = async { queue.run(job.id) }; started.await()
            assertTrue(queue.run(job.id).isEmpty()); release.complete(Unit); running.await()
            assertEquals(2, calls); assertEquals(JobState.COMPLETED, store.job(job.id).state)
        }
    }
    @Test fun finishCurrentPausePreservesRemainingItemsAndSnapshotOnResume() = runBlocking {
        val f = QueueFixture(temporary.newFolder().toPath()); val job = f.job(); var calls = 0
        lateinit var queue: QueueCoordinator
        SqlDelightJobStore(f.root.resolve("db")).use { store ->
            queue = queue(f, store, ItemExecutor { p, stop, life, _ ->
                calls++; if (calls == 1) queue.pause(job.id)
                assertEquals(23, p.settings.quality); f.complete(p, stop, life, UUID.randomUUID().toString())
            })
            queue.submit(job); queue.recover(); queue.run(job.id)
            assertEquals(JobState.PAUSED, store.job(job.id).state)
            assertEquals(listOf(ItemState.SUCCEEDED, ItemState.READY), queue.items(job.id).map { it.state })
            queue.run(job.id); assertEquals(2, calls); assertEquals(JobState.COMPLETED, store.job(job.id).state)
        }
    }
    @Test fun immediateStopIsDurableBeforeExecutorSeesSignal() = runBlocking {
        val f = QueueFixture(temporary.newFolder().toPath()); val job = f.job(); lateinit var queue: QueueCoordinator
        SqlDelightJobStore(f.root.resolve("db")).use { store ->
            queue = queue(f, store, ItemExecutor { _, stop, _, _ ->
                queue.pause(job.id, true)
                assertTrue(store.attempts("${job.id}:0").last().stopRequested); assertTrue(stop.requested)
                AttemptResult(AttemptStage.INTERRUPTED, message = "stopped")
            })
            queue.submit(job); queue.recover(); queue.run(job.id)
            assertEquals(JobState.PAUSED, store.job(job.id).state)
            assertEquals(ItemState.INTERRUPTED, queue.items(job.id).first().state)
            assertEquals(ItemState.READY, queue.items(job.id).last().state)
        }
    }
    @Test fun restartRequeuesOnlyUnfinishedAndRejectsChangedCompletedOutput() = runBlocking {
        val f = QueueFixture(temporary.newFolder().toPath()); val job = f.job(); val path = f.root.resolve("db")
        SqlDelightJobStore(path).use { store ->
            store.enqueue(job, 1); store.resume(job.id, 2)
            val claim = store.claim(job.id, "first", "one", 3)!!
            val queue = queue(f, store, ItemExecutor { _, _, _, _ -> error("not run") })
            val (work, receipt) = f.workspace(claim.item.plan, "one")
            store.workspace(claim, WorkspaceRecord(work.id, work.root, work.candidate, receipt.source, receipt.planHash), 4)
            store.validated(claim, receipt, 5); store.publishing(claim, 6)
            Files.copy(work.candidate.nio(), claim.item.plan.destination.nio()) // Crash before Published DB write.
        }
        SqlDelightJobStore(path).use { store ->
            var executions = 0
            val queue = queue(f, store, ItemExecutor { p, stop, life, _ -> executions++; f.complete(p, stop, life, "two") })
            queue.recover(); assertEquals(ItemState.SUCCEEDED, queue.items(job.id).first().state)
            queue.run(job.id); assertEquals(1, executions)
            queue.hide(job.id); assertTrue(queue.history().isEmpty()); assertTrue(Files.exists(job.plans.first().destination.nio()))
            Files.writeString(job.plans.first().destination.nio(), "external edit")
            queue.recover(); assertEquals(ItemState.NEEDS_ATTENTION, queue.items(job.id).first().state)
        }
    }
    @Test fun failedItemRequiresExplicitRetryAndDoesNotBlockIndependentItems() = runBlocking {
        val f = QueueFixture(temporary.newFolder().toPath()); val job = f.job(); var fail = true
        SqlDelightJobStore(f.root.resolve("db")).use { store ->
            val queue = queue(f, store, ItemExecutor { p, stop, life, _ ->
                if (fail && p == job.plans.first()) AttemptResult(AttemptStage.FAILED, error = ErrorCode.ENCODE_FAILED, message = "injected")
                else f.complete(p, stop, life, UUID.randomUUID().toString())
            })
            queue.submit(job); queue.recover(); queue.run(job.id)
            assertEquals(JobState.COMPLETED_WITH_ERRORS, store.job(job.id).state)
            fail = false; queue.retry("${job.id}:0"); queue.run(job.id)
            assertEquals(JobState.COMPLETED, store.job(job.id).state)
            assertEquals(2, queue.attempts("${job.id}:0").size)
        }
    }
    @Test fun cancellationLeavesRecoverableAttemptAndDoesNotDispatchNextItem() = runBlocking {
        val f = QueueFixture(temporary.newFolder().toPath()); val job = f.job(); val started = CompletableDeferred<Unit>()
        SqlDelightJobStore(f.root.resolve("db")).use { store ->
            val queue = queue(f, store, ItemExecutor { _, _, _, _ -> started.complete(Unit); awaitCancellation() })
            queue.submit(job); queue.recover(); val task = launch { queue.run(job.id) }; started.await(); task.cancelAndJoin()
            assertTrue(store.attempts("${job.id}:0").last().stopRequested)
            queue.recover(); assertEquals(ItemState.INTERRUPTED, queue.items(job.id).first().state)
            assertEquals(ItemState.READY, queue.items(job.id).last().state)
        }
    }
}
