package org.ergoutree.compress.infrastructure.storage

import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import java.nio.file.*

/** 验证相对路径和每级目录，不允许符号链接或 Windows 重解析点越过登记根。 */
class ManagedPaths(private val reparse: (Path) -> Boolean = Files::isSymbolicLink) {
    fun resolve(root: LocalPath, relative: String): Path {
        val base = root.nio().toAbsolutePath().normalize()
        val rel = Path.of(relative)
        require(!rel.isAbsolute && rel.nameCount > 0 && rel.none { it.toString() == ".." } && ':' !in relative) { "产物必须使用安全相对路径" }
        val path = base.resolve(rel).normalize()
        require(path != base && path.startsWith(base)) { "产物路径越界" }
        checkAncestors(path)
        return path
    }
    fun checkAncestors(path: Path) {
        generateSequence(path.toAbsolutePath().normalize()) { it.parent }.forEach {
            require(!reparse(it) && !Files.isSymbolicLink(it)) { "路径包含重解析点或符号链接：$it" }
        }
    }
}
