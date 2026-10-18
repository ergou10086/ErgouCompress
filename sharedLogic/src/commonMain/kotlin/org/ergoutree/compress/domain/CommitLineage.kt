package org.ergoutree.compress.domain

/** 沿明确的恢复关系寻找当前版本，避免将主动恢复误判为输出损坏。 */
object CommitLineage {
    fun current(record: CommitRecord, records: List<CommitRecord>): CommitRecord {
        var current = record
        val visited = mutableSetOf<String>()
        while (true) {
            check(visited.add(current.id)) { "版本恢复关系形成循环" }
            val next = records.filter { it.restoreOf == current.id && it.phase == CommitPhase.COMMITTED }
            check(next.size <= 1) { "版本恢复关系存在分叉" }
            current = next.singleOrNull() ?: return current
        }
    }
}
