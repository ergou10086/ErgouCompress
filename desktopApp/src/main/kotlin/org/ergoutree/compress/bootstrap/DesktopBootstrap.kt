package org.ergoutree.compress.bootstrap

import org.ergoutree.compress.domain.LocalPath
import org.ergoutree.compress.infrastructure.files.local
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.*
import java.sql.DriverManager
import java.util.UUID

data class DataLocation(val root: Path, val notice: String?)

class DesktopSession(val location: DataLocation, private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() { lock.release(); channel.close() }
}

object DesktopBootstrap {
    fun open(customRoot: String? = null, strictPortable: Boolean = false): DesktopSession {
        val command = ProcessHandle.current().info().command().orElse("")
        val packaged = command.isNotBlank() && !Path.of(command).fileName.toString().lowercase().let { it == "java.exe" || it == "java" || it == "javaw.exe" }
        val home = if (packaged) Path.of(command).parent else System.getProperty("ergou.app.home")?.let(Path::of)
            ?: throw IllegalStateException("开发启动需要明确设置 -Dergou.app.home，拒绝向 JDK 或当前工作目录写档案")
        val preferred = customRoot?.let(Path::of) ?: home.resolve("ErgouCompressData")
        val existing = Files.exists(preferred.resolve("config/root-id")) || Files.exists(preferred.resolve("catalog/catalog.sqlite"))
        val location = try {
            probe(preferred)
            DataLocation(preferred.toRealPath(), if (customRoot != null) "自定义数据根：$preferred" else null)
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
