package org.ergoutree.compress.infrastructure.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.PlanEnvironment
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

fun LocalPath.nio(): Path = Path.of(value)
fun Path.local(): LocalPath = LocalPath(toAbsolutePath().normalize().toString())
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

class NioEnvironment(private val fileKey: (Path, BasicFileAttributes) -> String = { _, a -> a.fileKey()?.toString() ?: "" }) : PlanEnvironment {
    override suspend fun identity(path: LocalPath): FileIdentity = withContext(Dispatchers.IO) {
        val file = path.nio().toRealPath(LinkOption.NOFOLLOW_LINKS)
        val attr = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attr.isRegularFile || attr.isSymbolicLink || attr.isOther) throw CompressException(ErrorCode.INPUT_MISSING, "不是可处理的普通文件：$file")
        FileIdentity(file.local(), attr.size(), attr.lastModifiedTime().toMillis(), fileKey(file, attr))
    }

    override suspend fun evidence(path: LocalPath): ContentEvidence = withContext(Dispatchers.IO) {
        val before = identity(path)
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path.nio(), LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        if (identity(path) != before) throw CompressException(ErrorCode.INPUT_CHANGED, "哈希计算期间文件发生变化：${path.value}")
        ContentEvidence(before, digest.digest().hex())
    }

    override suspend fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()
    override suspend fun exists(path: LocalPath): Boolean = withContext(Dispatchers.IO) { Files.exists(path.nio(), LinkOption.NOFOLLOW_LINKS) }
    override suspend fun availableBytes(path: LocalPath): Long = withContext(Dispatchers.IO) { Files.getFileStore(path.nio()).usableSpace }
    override suspend fun target(directory: LocalPath, source: LocalPath, container: Container): LocalPath = withContext(Dispatchers.IO) {
        val root = directory.nio().toRealPath()
        require(Files.isDirectory(root)) { "请选择已存在的输出目录" }
        val name = source.nio().fileName.toString().substringBeforeLast('.') + "." + container.extension
        val target = root.resolve(name)
        if (target == source.nio().toRealPath() || (Files.exists(target) && Files.isSameFile(target, source.nio())))
            throw CompressException(ErrorCode.DESTINATION_CONFLICT, "副本目标不能是源文件，请选择其他目录")
        // Conservative collision key on Windows: avoid per-directory case-sensitivity surprises.
        target.local()
    }
}
