package org.ergoutree.compress.infrastructure.analysis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.domain.AnalysisReport
import org.ergoutree.compress.ports.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.local
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID

/** 原子保存可再生分析缓存，与权威任务和提交日志分开管理。 */
class JsonAnalysisCache(private val root: Path, private val recorder: ArtifactRecorder = NoopArtifactRecorder) : AnalysisCache {
    private fun path(key: String): Path {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return root.resolve("$key.json")
    }
    override suspend fun get(key: String): AnalysisReport? = withContext(Dispatchers.IO) {
        val file = path(key)
        if (!Files.exists(file) || Files.size(file) > 2_000_000) return@withContext null
        try { SnapshotJson.decodeFromString<AnalysisReport>(Files.readString(file)) }
        catch (_: SerializationException) { null }
        catch (_: IllegalArgumentException) { null }
    }
    override suspend fun put(report: AnalysisReport): Unit = withContext(Dispatchers.IO) {
        Files.createDirectories(root)
        val temporary = root.resolve("${UUID.randomUUID()}.tmp")
        Files.writeString(temporary, SnapshotJson.encodeToString(report), StandardOpenOption.CREATE_NEW)
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(temporary, path(report.cacheKey), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        recorder.file(path(report.cacheKey).local(), ArtifactKind.CACHE)
    }
}
