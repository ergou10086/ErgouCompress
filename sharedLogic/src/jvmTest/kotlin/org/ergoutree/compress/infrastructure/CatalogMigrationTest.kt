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

/** 验证所有旧数据库版本的备份升级和最低读取版本边界。 */
class CatalogMigrationTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun realHistoricalSchemasMigrateTransactionallyWithConsistentBackupAndDowngradeBoundary() = runBlocking {
        for (version in 1..3) {
        val root = temporary.newFolder().toPath(); val db = root.resolve("catalog.sqlite")
        val ddl = javaClass.getResourceAsStream("/sql/catalog-v1.sql")!!.bufferedReader().readText()
        DriverManager.getConnection("jdbc:sqlite:$db").use { c -> c.createStatement().use { s -> ddl.split(';').filter(String::isNotBlank).forEach { s.execute(it) }; if (version >= 2) javaClass.getResourceAsStream("/sql/catalog-v2-migration.sql")!!.bufferedReader().use { it.readText() }.split(';').filter(String::isNotBlank).forEach { s.execute(it) }; if (version == 3) s.execute("CREATE INDEX item_dispatch ON item(job_id,state,ordinal)"); s.execute("PRAGMA user_version=$version") } }
        val job = QueueFixture(root).job("legacy", 1)
        DriverManager.getConnection("jdbc:sqlite:$db").use { c ->
            c.prepareStatement("INSERT INTO job(id,snapshot,state,created_at) VALUES(?,?,'READY',1)").use { s -> s.setString(1, job.id); s.setString(2, SnapshotJson.encodeToString(job)); s.executeUpdate() }
        }
        SqlDelightJobStore(db).use { assertEquals(job, it.job("legacy").snapshot); assertTrue(it.commits().isEmpty()) }
        DriverManager.getConnection("jdbc:sqlite:$db").use { c -> c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { assertTrue(it.next()); assertEquals(4, it.getInt(1)); assertTrue(it.getInt(1) > 3, "旧 S5 reader 必须拒绝 S6 档案语义") } } }
        val backup = Files.list(root.resolve("catalog-backups")).use { it.toList().single() }
        DriverManager.getConnection("jdbc:sqlite:$backup").use { c -> c.createStatement().use { s -> s.executeQuery("PRAGMA user_version").use { assertTrue(it.next()); assertEquals(version, it.getInt(1)) } } }
        }
    }
}
