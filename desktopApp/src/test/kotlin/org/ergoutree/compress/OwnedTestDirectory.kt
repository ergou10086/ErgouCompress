package org.ergoutree.compress

import com.sun.jna.platform.win32.Kernel32
import org.ergoutree.compress.platform.windows.WindowsFileIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** 为跨卷测试提供独占目录，退出时校验边界并清理，不跟随重解析点。 */
class OwnedTestDirectory(prefix: String) : AutoCloseable {
    private val parent = Path.of(System.getProperty("ergou.app.home"), "test-output").toAbsolutePath().normalize()
    val path: Path = parent.resolve("$prefix-${UUID.randomUUID()}")
    init {
        require(prefix.matches(Regex("[a-z0-9-]+")) && path.startsWith(parent) && path != parent)
        Files.createDirectories(parent)
        Files.createDirectory(path)
    }
    override fun close() {
        if (Files.notExists(path)) return
        // 先核验整个列表，再删除；发现重解析点时保留现场，禁止越界清理。
        val entries = Files.walk(path).use { it.toList() }
        entries.forEach {
            require(it.toAbsolutePath().normalize().startsWith(path) && path.startsWith(parent))
            require(Kernel32.INSTANCE.GetFileAttributes(WindowsFileIdentity.extendedPath(it)) and 0x400 == 0)
        }
        entries.sortedDescending().forEach { Files.delete(it) }
    }
}
