package org.ergoutree.compress.infrastructure.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.nio
import org.ergoutree.compress.ports.JobStore
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*

/** 从权威执行目录增量读取日志，拒绝链接路径，每次读取限制为 128 KiB。 */
class TaskLogReader(private val jobs: JobStore, private val paths: ManagedPaths) {
    suspend fun read(jobId: String, previous: TaskLogCursor?): TaskLogPage = withContext(Dispatchers.IO) {
        jobs.job(jobId)
        require(previous == null || previous.jobId == jobId) { "日志游标不属于当前任务" }
        val cursor = previous ?: TaskLogCursor(jobId)
        val events = jobs.events(jobId).filter { it.sequence > cursor.eventSequence }
        val lines = events.take(150).map { event ->
            TaskLogLine("event:${event.sequence}", "程序", if (event.type.contains("Fail", true) || event.type.contains("Attention", true)) LogLevel.ERROR else LogLevel.INFO,
                "${event.type}  ${event.payload}", event.createdAt)
        }.toMutableList()
        val offsets = cursor.offsets.toMutableMap()
        var remaining = 128 * 1024
        for (item in jobs.items(jobId)) for (attempt in jobs.attempts(item.id)) {
            val root = attempt.workspace?.root?.nio() ?: continue
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue
            paths.checkAncestors(root)
            val logFiles = Files.list(root).use { stream -> stream.filter { it.fileName.toString().endsWith(".log") && Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.sorted().toList() }
            for (file in logFiles) {
                if (remaining <= 0) break
                paths.checkAncestors(file)
                val key = "${attempt.id}/${file.fileName}"
                val size = Files.size(file)
                val offset = (offsets[key] ?: 0L).takeIf { it <= size } ?: 0L
                val count = minOf(size - offset, remaining.toLong()).toInt()
                if (count <= 0) continue
                val buffer = ByteBuffer.allocate(count)
                FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel -> channel.position(offset); while (buffer.hasRemaining() && channel.read(buffer) > 0) {} }
                val bytes = buffer.array().copyOf(buffer.position())
                // 活动文件只消费完整行，避免跨轮询截断 UTF-8 字符；终结执行可以消费最后一行。
                val active = attempt.state in setOf(ExecutionState.RUNNING, ExecutionState.VALIDATED, ExecutionState.PUBLISHING)
                val newline = bytes.indexOfLast { it == 10.toByte() }
                val consumed = if (!active && offset + bytes.size >= size) bytes.size else if (newline >= 0) newline + 1 else if (bytes.size == remaining) {
                    // 超长单行分块推进，UTF-8 解码器只消费完整字符，避免永远卡在同一偏移。
                    val source = ByteBuffer.wrap(bytes)
                    Charsets.UTF_8.newDecoder().decode(source, java.nio.CharBuffer.allocate(bytes.size), false)
                    source.position()
                } else 0
                if (consumed <= 0) continue
                val text = bytes.copyOf(consumed).toString(Charsets.UTF_8)
                text.lineSequence().filter { it.isNotBlank() }.forEachIndexed { index, line ->
                    val level = when {
                        Regex("(?i)(error|failed|invalid)").containsMatchIn(line) -> LogLevel.ERROR
                        Regex("(?i)warn").containsMatchIn(line) -> LogLevel.WARNING
                        file.fileName.toString().contains("stdout") -> LogLevel.DEBUG
                        else -> LogLevel.INFO
                    }
                    lines += TaskLogLine("$key:$offset:$index", "FFmpeg · ${file.fileName}", level, line.take(16_384))
                }
                offsets[key] = offset + consumed
                remaining -= consumed
            }
        }
        TaskLogPage(TaskLogCursor(jobId, events.take(150).lastOrNull()?.sequence ?: cursor.eventSequence, offsets), lines)
    }
}
