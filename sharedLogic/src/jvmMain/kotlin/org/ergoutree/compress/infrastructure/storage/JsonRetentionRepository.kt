package org.ergoutree.compress.infrastructure.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.domain.RetentionPolicy
import org.ergoutree.compress.ports.RetentionRepository
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID

/** 原子保存保留策略，损坏配置明确报错而不静默重置备份规则。 */
class JsonRetentionRepository(private val path: Path) : RetentionRepository {
    override suspend fun load(): RetentionPolicy = withContext(Dispatchers.IO) { if (Files.exists(path)) SnapshotJson.decodeFromString(Files.readString(path)) else RetentionPolicy() }
    override suspend fun save(policy: RetentionPolicy): Unit = withContext(Dispatchers.IO) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("retention-${UUID.randomUUID()}.tmp")
        Files.writeString(temporary, SnapshotJson.encodeToString(policy), StandardOpenOption.CREATE_NEW)
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
