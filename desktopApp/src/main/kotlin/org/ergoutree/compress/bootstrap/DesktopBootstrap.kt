package org.ergoutree.compress.bootstrap

import org.ergoutree.compress.domain.LocalPath
import org.ergoutree.compress.application.SnapshotJson
import kotlinx.serialization.json.*
import org.ergoutree.compress.infrastructure.files.local
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.sql.DriverManager
import java.util.UUID

/** 记录最终数据位置及需要向用户说明的兼容或迁移提示。 */
data class DataLocation(val root: Path, val notice: String?)

/** 持有数据根独占锁，并按逆序关闭该会话拥有的资源。 */
class DesktopSession(val location: DataLocation, private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    private val resources = mutableListOf<AutoCloseable>()
    fun <T : AutoCloseable> own(resource: T): T = resource.also { resources += it }
    override fun close() {
        try { resources.asReversed().forEach { it.close() } }
        finally { try { lock.release() } finally { channel.close() } }
    }
}

/** 验证数据根和迁移激活指针，拒绝打开未完成的迁移目标。 */
object DesktopBootstrap {
    fun open(customRoot: String? = null, strictPortable: Boolean = false): DesktopSession {
        val command = ProcessHandle.current().info().command().orElse("")
        val packaged = command.isNotBlank() && !Path.of(command).fileName.toString().lowercase().let { it == "java.exe" || it == "java" || it == "javaw.exe" }
        val home = if (packaged) Path.of(command).parent else System.getProperty("ergou.app.home")?.let(Path::of)
            ?: throw IllegalStateException("开发启动需要明确设置 -Dergou.app.home，拒绝向 JDK 或当前工作目录写档案")
        val initial = customRoot?.let(Path::of) ?: home.resolve("ErgouCompressData")
        val preferred = resolveRoot(initial)
        val existing = Files.exists(preferred.resolve("config/root-id")) || Files.exists(preferred.resolve("catalog/catalog.sqlite"))
        val location = try {
            probe(preferred)
            DataLocation(preferred.toRealPath(), if (preferred.toAbsolutePath().normalize() != initial.toAbsolutePath().normalize()) "数据根已迁移：$preferred" else if (customRoot != null) "自定义数据根：$preferred" else null)
        } catch (failure: Exception) {
            if (customRoot != null || strictPortable || existing) throw IllegalStateException("数据根无法使用：$preferred。已有档案时禁止静默切换空目录：${failure.message}", failure)
            val fallback = System.getenv("LOCALAPPDATA")?.let(Path::of)?.resolve("ErgouCompress") ?: throw failure
            probe(fallback)
            DataLocation(fallback.toRealPath(), "便携目录不可用：${failure.message}；已使用兼容后备：$fallback")
        }
        val channel = FileChannel.open(location.root.resolve("catalog/app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock = try { channel.tryLock() ?: error("该数据根已有实例运行") } catch (e: Exception) { channel.close(); throw e }
        return DesktopSession(location, channel, lock)
    }

    private fun resolveRoot(initial: Path): Path {
        var current = initial.toAbsolutePath().normalize()
        val visited = mutableSetOf<Path>()
        repeat(16) {
            require(visited.add(current)) { "数据根迁移指针形成循环" }
            require(!Files.exists(current.resolve("migration-pending.json"))) { "迁移目标尚未完成，禁止启用" }
            val pointer = current.resolve("config/current-root.json")
            if (!Files.exists(pointer)) return current
            val value = SnapshotJson.parseToJsonElement(Files.readString(pointer)).jsonObject
            require(value.getValue("schemaVersion").jsonPrimitive.int == 1)
            val next = Path.of(value.getValue("destination").jsonPrimitive.content).toAbsolutePath().normalize()
            val rootId = value.getValue("rootId").jsonPrimitive.content
            require(Files.readString(current.resolve("config/root-id")).trim() == rootId && Files.readString(next.resolve("config/root-id")).trim() == rootId) { "迁移根身份不符" }
            val ready = SnapshotJson.parseToJsonElement(Files.readString(next.resolve("migration-ready.json"))).jsonObject
            require(ready.getValue("id") == value.getValue("migrationId") && ready.getValue("rootId").jsonPrimitive.content == rootId) { "迁移目标缺少对应的就绪凭据" }
            current = next
        }
        error("迁移指针链过长，请人工定位最终数据根")
    }

    private fun probe(root: Path) {
        listOf("config", "catalog", "jobs", "tmp", "native", "toolchains").forEach { Files.createDirectories(root.resolve(it)) }
        System.setProperty("jna.tmpdir", root.resolve("native").toAbsolutePath().toString())
        System.setProperty("org.sqlite.tmpdir", root.resolve("native").toAbsolutePath().toString())
        val id = UUID.randomUUID().toString()
        val write = root.resolve("tmp/$id.write")
        val renamed = root.resolve("tmp/$id.renamed")
        val db = root.resolve("catalog/startup-$id.sqlite")
        try {
            FileChannel.open(write, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
                it.write(java.nio.ByteBuffer.wrap(id.toByteArray())); it.force(true)
            }
            Files.move(write, renamed, StandardCopyOption.ATOMIC_MOVE)
            check(Files.readString(renamed) == id)
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { it.execute("PRAGMA journal_mode=DELETE"); it.execute("PRAGMA synchronous=FULL"); it.execute("CREATE TABLE startup(value TEXT NOT NULL)") }
                connection.autoCommit = false
                connection.prepareStatement("INSERT INTO startup VALUES(?)").use { it.setString(1, id); it.executeUpdate() }
                connection.commit()
            }
            DriverManager.getConnection("jdbc:sqlite:${db.toAbsolutePath()}").use { connection ->
                connection.createStatement().use { statement -> statement.executeQuery("SELECT value FROM startup").use { check(it.next() && it.getString(1) == id) } }
            }
            if (System.getProperty("os.name").startsWith("Windows")) com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcessId()
            val marker = root.resolve("config/root-id")
            if (!Files.exists(marker)) Files.writeString(marker, UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW)
        } finally {
            Files.deleteIfExists(write); Files.deleteIfExists(renamed); Files.deleteIfExists(db)
        }
    }
}
