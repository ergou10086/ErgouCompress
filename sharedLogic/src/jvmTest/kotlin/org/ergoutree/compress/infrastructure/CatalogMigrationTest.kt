package org.ergoutree.compress.infrastructure

import kotlinx.coroutines.runBlocking
import org.ergoutree.compress.infrastructure.storage.SqlDelightJobStore
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.sql.DriverManager
import org.ergoutree.compress.testing.QueueFixture
import org.ergoutree.compress.application.SnapshotJson
import kotlinx.serialization.encodeToString
import kotlin.test.*

class CatalogMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun realVersionOneSchemaMigratesTransactionallyWithConsistentBackup() = runBlocking {
        val root = temporary.newFolder().toPath(); val db = root.resolve("catalog.sqlite")
        val ddl = javaClass.getResourceAsStream("/sql/catalog-v1.sql")!!.bufferedReader().readText()
        DriverManager.getConnection("jdbc:sqlite:$db").use { c -> c.createStatement().use { s -> ddl.split(';').filter(String::isNotBlank).forEach { s.execute(it) }; s.execute("PRAGMA user_version=1") } }
        val job = QueueFixture(root).job("legacy", 1)
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.prepareStatement("INSERT INTO job(id,snapshot,state,created_at) VALUES(?,?,'READY',1)").use { s -> s.setString(1, job.id); s.setString(2, SnapshotJson.encodeToString(job)); s.executeUpdate() }
        }
        SqlDelightJobStore(db).use { assertEquals(job, it.job("legacy").snapshot); assertTrue(it.commits().isEmpty()) }
        DriverManager.getConnection("jdbc:sqlite:$db").use { c -> c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { assertTrue(it.next()); assertEquals(2, it.getInt(1)) } } }
        val backup = Files.list(root.resolve("catalog-backups")).use { it.toList().single() }
        DriverManager.getConnection("jdbc:sqlite:$backup").use { c -> c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { assertTrue(it.next()); assertEquals(1, it.getInt(1)) } } }
    }
}
