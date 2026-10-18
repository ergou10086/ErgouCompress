package org.ergoutree.compress.bootstrap

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.domain.*
import java.nio.file.Files
import java.nio.file.Path

/** 无界面后端入口；危险清理必须引用已保存的预览并显式确认。 */
object BackendCommandLine {
    fun accepts(args: Array<String>) = args.any { it == "--history" || it == "--recover" || it == "--commits" || it.startsWith("--enqueue=") || it.startsWith("--run-job=") || it.startsWith("--retry-item=") || it.startsWith("--register-workspace=") || it.startsWith("--restore=") || it.startsWith("--request=") || it.startsWith("--analyze-job=") || it == "--storage" || it == "--cleanup-preview" || it == "--retention" || it.startsWith("--cleanup=") || it.startsWith("--export-support=") || it.startsWith("--migrate-data-root=") || it.startsWith("--retention-settings=") }
    fun execute(args: Array<String>, app: AppWiring) = runBlocking {
        args.firstOrNull { it.startsWith("--register-workspace=") }?.substringAfter('=')?.let { app.registerWorkspace(org.ergoutree.compress.domain.LocalPath(it)) }
        app.queue.recover()
        args.firstOrNull { it.startsWith("--request=") }?.substringAfter('=')?.let {
            val request = SnapshotJson.decodeFromString<org.ergoutree.compress.domain.ProcessingRequest>(Files.readString(Path.of(it)))
            require(request.mode == org.ergoutree.compress.domain.OutputMode.COPY || "--allow-in-place" in args) { "开发原地任务需要明确 --allow-in-place；所有验证和备份门仍然执行" }
            val job = app.prepareRequest(request); app.queue.submit(job)
            if ("--execute" in args) app.queue.run(job.id) { event -> System.err.println("${event.stage}: ${event.message}") }
        }
        args.firstOrNull { it.startsWith("--restore=") }?.substringAfter('=')?.let { require("--allow-in-place" in args); app.restoreCommit(it) }
        args.firstOrNull { it.startsWith("--enqueue=") }?.substringAfter('=')?.let {
            app.queue.submit(SnapshotJson.decodeFromString<FrozenJob>(Files.readString(Path.of(it))))
        }
        args.firstOrNull { it.startsWith("--retry-item=") }?.substringAfter('=')?.let { app.queue.retry(it) }
        args.firstOrNull { it.startsWith("--run-job=") }?.substringAfter('=')?.let {
            require(app.queue.items(it).all { item -> item.plan.outputMode == org.ergoutree.compress.domain.OutputMode.COPY } || "--allow-in-place" in args) { "开发原地执行需要明确 --allow-in-place" }
            app.queue.run(it) { event -> System.err.println("${event.stage}: ${event.message}") }
        }
        val analysisReports = args.firstOrNull { it.startsWith("--analyze-job=") }?.substringAfter('=')?.let { jobId ->
            val items = app.queue.items(jobId)
            require(items.isNotEmpty()) { "任务不存在或为空" }
            items.map { app.analyze(it.plan, org.ergoutree.compress.domain.AnalysisOptions(sample = "--sample" in args, staticHint = "--static" in args)) }
        }
        fun option(prefix: String) = args.firstOrNull { it.startsWith(prefix) }?.substringAfter('=')
        val maintenanceActions = listOf("--storage", "--cleanup-preview", "--cleanup=", "--export-support=", "--migrate-data-root=", "--retention", "--retention-settings=")
        require(maintenanceActions.count { key -> args.any { if (key.endsWith('=')) it.startsWith(key) else it == key } } <= 1) { "每次只执行一个存储维护操作" }
        var maintenanceReport: String? = null
        var reportRoot = app.dataRoot
        when {
            "--storage" in args -> maintenanceReport = SnapshotJson.encodeToString(app.storageReport())
            "--cleanup-preview" in args -> maintenanceReport = SnapshotJson.encodeToString(app.cleanupPreview(CleanupOptions(candidates = "--include-candidates" in args, originalBackups = "--include-backups" in args, clearCacheNow = "--clear-cache" in args)))
            option("--cleanup=") != null -> maintenanceReport = SnapshotJson.encodeToString(app.cleanupExecute(option("--cleanup=")!!, requireNotNull(option("--confirm=")) { "请提供预览确认令牌" }, "--ack-backup-loss" in args))
            option("--export-support=") != null -> maintenanceReport = SnapshotJson.encodeToString(app.exportSupport(LocalPath(option("--export-support=")!!)))
            option("--migrate-data-root=") != null -> {
                val migrated = app.migrateDataRoot(LocalPath(option("--migrate-data-root=")!!))
                reportRoot = migrated.destination.value; maintenanceReport = SnapshotJson.encodeToString(migrated)
            }
            option("--retention-settings=") != null -> {
                val policy = SnapshotJson.decodeFromString<RetentionPolicy>(Files.readString(Path.of(option("--retention-settings=")!!)))
                app.saveRetention(policy); maintenanceReport = SnapshotJson.encodeToString(policy)
            }
            "--retention" in args -> maintenanceReport = SnapshotJson.encodeToString(app.loadRetention())
        }
        val report = maintenanceReport ?: if (analysisReports != null) SnapshotJson.encodeToString(analysisReports) else if ("--commits" in args) SnapshotJson.encodeToString(app.commitHistory()) else SnapshotJson.encodeToString(app.queue.history())
        val folder = Files.createDirectories(Path.of(reportRoot, "diagnostics"))
        Files.writeString(folder.resolve("backend-result.json"), report)
        println(report)
    }
}
