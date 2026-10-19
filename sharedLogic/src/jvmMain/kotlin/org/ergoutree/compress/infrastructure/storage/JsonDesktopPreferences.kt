package org.ergoutree.compress.infrastructure.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.domain.DesktopPreferences
import java.nio.channels.FileChannel
import java.nio.file.*
import java.util.UUID

/** 偏好原子落盘；损坏配置明确报错，不能静默覆盖用户默认参数。 */
class JsonDesktopPreferences(private val path: Path) {
    @Volatile private var current = if (Files.exists(path)) SnapshotJson.decodeFromString<DesktopPreferences>(Files.readString(path)) else DesktopPreferences()
    fun current(): DesktopPreferences = current
    suspend fun save(value: DesktopPreferences) = withContext(Dispatchers.IO) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("preferences-${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temporary, SnapshotJson.encodeToString(value), StandardOpenOption.CREATE_NEW)
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            current = value
        } finally { Files.deleteIfExists(temporary) }
    }
}
