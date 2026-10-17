package org.ergoutree.compress

import org.ergoutree.compress.bootstrap.DesktopBootstrap
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.test.*

class BootstrapTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun customRootProbesSqliteAndPreventsSecondWriter() {
        val root = temporary.newFolder("数据 根").toPath()
        DesktopBootstrap.open(root.toString()).use { first ->
            assertEquals(root.toRealPath(), first.location.root)
            assertTrue(Files.exists(root.resolve("config/root-id")))
            assertFailsWith<Exception> { DesktopBootstrap.open(root.toString()) }
        }
        DesktopBootstrap.open(root.toString()).use { assertEquals(root.toRealPath(), it.location.root) }
    }
    @Test fun invalidCustomRootDoesNotSilentlyFallback() {
        val file = temporary.newFile("not-a-directory").toPath()
        assertFailsWith<IllegalStateException> { DesktopBootstrap.open(file.toString()) }
    }
}
