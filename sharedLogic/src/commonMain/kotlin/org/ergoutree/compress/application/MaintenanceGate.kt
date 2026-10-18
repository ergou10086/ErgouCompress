package org.ergoutree.compress.application

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 允许多个普通操作共存，但维护操作必须独占；迁移成功后封存旧会话。 */
class MaintenanceGate {
    private val mutex = Mutex()
    private var workers = 0
    private var maintenance = false
    private var sealed = false
    suspend fun <T> work(block: suspend () -> T): T {
        mutex.withLock { check(!maintenance && !sealed) { "存储维护中或数据根已迁移，请稍后或重启" }; workers++ }
        try { return block() } finally { withContext(NonCancellable) { mutex.withLock { workers-- } } }
    }
    suspend fun <T> exclusive(block: suspend () -> T): T {
        mutex.withLock { check(workers == 0 && !maintenance && !sealed) { "任务或分析尚未结束，禁止维护" }; maintenance = true }
        try { return block() } finally { withContext(NonCancellable) { mutex.withLock { maintenance = false } } }
    }
    suspend fun seal() = mutex.withLock { sealed = true }
}
