package org.ergoutree.compress

import org.ergoutree.compress.platform.windows.WindowsFileIdentity
import org.junit.Rule
import org.junit.Assume
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.*

class WindowsFileIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun hardLinksShareFileIdAndNewFilesDoNot() {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val root = temporary.newFolder().toPath()
        val a = Files.writeString(root.resolve("a"), "same")
        val alias = Files.createLink(root.resolve("alias"), a)
        val b = Files.writeString(root.resolve("b"), "same")
        fun key(path: java.nio.file.Path) = WindowsFileIdentity.key(path, Files.readAttributes(path, BasicFileAttributes::class.java))
        assertEquals(key(a), key(alias)); assertNotEquals(key(a), key(b))
    }
    @Test fun unicodePathBeyondMaxPathHasAStableIdentity() {
        Assume.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        var directory = temporary.newFolder().toPath()
        while (directory.toString().length < 400) directory = Files.createDirectory(directory.resolve("中文长路径_abcdefghij"))
        val file = Files.writeString(directory.resolve("视频.mp4"), "fixture")
        val attr = Files.readAttributes(file, BasicFileAttributes::class.java)
        assertTrue(WindowsFileIdentity.key(file, attr).isNotBlank())
    }
}
