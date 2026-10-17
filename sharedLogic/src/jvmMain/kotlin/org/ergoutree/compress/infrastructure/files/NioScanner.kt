package org.ergoutree.compress.infrastructure.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes

/** Inject the platform reparse detector; Files.isSymbolicLink alone misses Windows junctions. */
class NioScanner(
    private val fileKey: (Path, BasicFileAttributes) -> String = { _, a -> a.fileKey()?.toString() ?: "" },
    private val isReparse: (Path) -> Boolean = { Files.isSymbolicLink(it) },
) : InputScanner {
    override fun scan(request: ScanRequest, stop: StopSignal): Flow<ScanEvent> = flow {
        val excluded = request.excludedRoots.map { it.nio().toAbsolutePath().normalize() }
        val seenFiles = HashSet<String>()
        val keylessFiles = HashMap<Pair<Long, Long>, MutableList<Path>>()
        val seenDirectories = HashSet<String>()
        var count = 0L
        var bytes = 0L
        var complete = true
        fun excluded(path: Path) = excluded.any { path.startsWith(it) }
        suspend fun issue(path: Path, reason: String) { complete = false; emit(ScanEvent.Issue(path.local(), reason)) }
        suspend fun file(path: Path, attr: BasicFileAttributes) {
            if (stop.requested || excluded(path)) return
            if (isReparse(path) || attr.isSymbolicLink || attr.isOther || !attr.isRegularFile) return
            val key = fileKey(path, attr)
            if (key.isNotEmpty()) { if (!seenFiles.add(key)) return } else {
                val group = keylessFiles.getOrPut(attr.size() to attr.lastModifiedTime().toMillis()) { mutableListOf() }
                if (group.any { Files.isSameFile(it, path) }) return
                group.add(path)
            }
            count++
            bytes = Math.addExact(bytes, attr.size())
            emit(ScanEvent.Found(FileIdentity(path.local(), attr.size(), attr.lastModifiedTime().toMillis(), key),
                path.fileName.toString().substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS, attr.size() >= request.minimumBytes))
        }
        // Depth is measured independently for each selected root. This preserves a deeper explicit
        // root even when an overlapping parent was visited with a finite depth.
        for (input in request.roots) {
            if (stop.requested) break
            val root = input.nio().toAbsolutePath().normalize()
            try {
                if (excluded(root) || generateSequence(root) { it.parent }.any(isReparse)) continue
                val attr = Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                if (attr.isRegularFile) { file(root, attr); continue }
                if (!attr.isDirectory) continue
                val queue = ArrayDeque<Pair<Path, Int>>()
                queue.add(root to 0)
                while (queue.isNotEmpty() && !stop.requested) {
                    val (directory, depth) = queue.removeFirst()
                    if (isReparse(directory)) { issue(directory, "目录在扫描期间变为链接/重解析点"); continue }
                    // Global directory visit key includes remaining depth to avoid losing explicit roots.
                    val visitKey = "${directory.toRealPath()}|${request.maxDirectoryDepth?.minus(depth)}"
                    if (!seenDirectories.add(visitKey)) continue
                    try {
                        Files.newDirectoryStream(directory).use { entries ->
                            for (entry in entries) {
                                if (stop.requested) break
                                val path = entry.toAbsolutePath().normalize()
                                if (excluded(path) || isReparse(path)) continue
                                try {
                                    val a = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                                    when {
                                        a.isSymbolicLink || a.isOther -> Unit
                                        a.isDirectory && (request.maxDirectoryDepth == null || depth < request.maxDirectoryDepth) -> queue.add(path to depth + 1)
                                        a.isRegularFile -> file(path, a)
                                    }
                                } catch (e: java.io.IOException) { issue(path, e.message ?: "无法读取") }
                            }
                        }
                    } catch (e: java.io.IOException) { issue(directory, e.message ?: "无法枚举") }
                }
            } catch (e: java.io.IOException) { issue(root, e.message ?: "输入不可读") }
        }
        emit(ScanEvent.Finished(count, bytes, complete && !stop.requested))
    }.flowOn(Dispatchers.IO)

    companion object { val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "avi", "webm", "ts", "m2ts", "m4v", "wmv", "flv") }
}
