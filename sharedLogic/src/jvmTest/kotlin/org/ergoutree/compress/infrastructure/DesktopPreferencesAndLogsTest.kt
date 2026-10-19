package org.ergoutree.compress.infrastructure

import kotlinx.coroutines.runBlocking
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.infrastructure.storage.*
import org.ergoutree.compress.testing.QueueFixture
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.*
import kotlin.test.*

/** 使用真实 SQLite 和 UTF-8 文件验证偏好落盘及活动日志增量读取边界。 */
class DesktopPreferencesAndLogsTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun preferencesRoundTripAllDesktopSettingsAndRejectCorruption(): Unit = runBlocking {
        val path = temporary.newFolder().toPath().resolve("config/preferences.json")
        val value = DesktopPreferences(theme = AppTheme.DARK, reduceMotion = true, scanDepth = null, minimumBytes = 10_000_000,
            defaults = EncodeSettings(quality = 30, maxWidth = 1920), ffmpegVerbose = true, logLevel = LogLevel.WARNING, logBufferLines = 300)
        JsonDesktopPreferences(path).save(value)
        assertEquals(value, JsonDesktopPreferences(path).current())
        Files.list(path.parent).use { files -> assertEquals(listOf("preferences.json"), files.map { it.fileName.toString() }.toList()) }
        Files.writeString(path, "broken")
        assertFailsWith<Exception> { JsonDesktopPreferences(path) }
    }
    @Test fun activeLogsKeepIncompleteUtf8LinesAndDoNotRepeatConsumedLines(): Unit = runBlocking {
        withLogs { reader, file ->
            val firstBytes = "第一行\n第二行".toByteArray(Charsets.UTF_8)
            Files.write(file, firstBytes.copyOf(firstBytes.size - 1))
            val first = reader.read("job", null)
            assertEquals(listOf("第一行"), first.lines.filter { it.source != "程序" }.map { it.text })
            Files.write(file, firstBytes.takeLast(1).toByteArray() + "\n".toByteArray(), StandardOpenOption.APPEND)
            val second = reader.read("job", first.cursor)
            assertEquals(listOf("第二行"), second.lines.map { it.text })
            assertTrue(reader.read("job", second.cursor).lines.isEmpty())
            assertFailsWith<IllegalArgumentException> { reader.read("job", TaskLogCursor("another")) }
        }
    }
    @Test fun longLinesCannotPermanentlyBlockTheBoundedReader(): Unit = runBlocking {
        withLogs { reader, file ->
            Files.writeString(file, "中".repeat(100_000) + "\n后续日志\n")
            var page = reader.read("job", null)
            val collected = page.lines.toMutableList()
            repeat(4) { page = reader.read("job", page.cursor); collected += page.lines }
            assertTrue(collected.any { it.text == "后续日志" })
            assertFalse(collected.any { it.text.contains('\ufffd') })
            assertTrue(reader.read("job", page.cursor).lines.isEmpty())
        }
    }
    private suspend fun withLogs(block: suspend (TaskLogReader, Path) -> Unit) {
        val root = temporary.newFolder().toPath()
        SqlDelightJobStore(root.resolve("catalog.sqlite")).use { store ->
            val fixture = QueueFixture(root)
            val job = fixture.job("job", 1); store.enqueue(job, 1); store.resume(job.id, 2)
            val claim = requireNotNull(store.claim(job.id, "test", "attempt", 3))
            val (work, receipt) = fixture.workspace(job.plans.single(), "attempt")
            store.workspace(claim, WorkspaceRecord(work.id, work.root, work.candidate, receipt.source, receipt.planHash), 4)
            block(TaskLogReader(store, ManagedPaths()), work.root.nio().resolve("encode.stderr.log"))
        }
    }
}
