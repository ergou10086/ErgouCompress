package org.ergoutree.compress.bootstrap

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.domain.FrozenJob
import java.nio.file.Files
import java.nio.file.Path

/** Headless backend entry. UI integration is intentionally deferred until backend MVP is ready. */
object BackendCommandLine {
    fun accepts(args: Array<String>) = args.any { it == "--history" || it == "--recover" || it == "--commits" || it.startsWith("--enqueue=") || it.startsWith("--run-job=") || it.startsWith("--retry-item=") || it.startsWith("--register-workspace=") || it.startsWith("--restore=") || it.startsWith("--request=") || it.startsWith("--analyze-job=") }
    fun execute(args: Array<String>, app: AppWiring) = runBlocking {
        args.firstOrNull { it.startsWith("--register-workspace=") }?.substringAfter('=')?.let { app.workspaceRegistry!!.register(org.ergoutree.compress.domain.LocalPath(it)) }
        app.queue.recover()
        args.firstOrNull { it.startsWith("--request=") }?.substringAfter('=')?.let {
            val request = SnapshotJson.decodeFromString<org.ergoutree.compress.domain.ProcessingRequest>(Files.readString(Path.of(it)))
            require(request.mode == org.ergoutree.compress.domain.OutputMode.COPY || "--allow-in-place" in args) { "开发原地任务需要明确 --allow-in-place；所有验证和备份门仍然执行" }
            val job = app.prepareRequest(request); app.queue.submit(job)
            if ("--execute" in args) app.queue.run(job.id) { event -> System.err.println("${event.stage}: ${event.message}") }
        }
        args.firstOrNull { it.startsWith("--restore=") }?.substringAfter('=')?.let { require("--allow-in-place" in args); app.commitTransactions!!.restore(it) }
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
        val report = if (analysisReports != null) SnapshotJson.encodeToString(analysisReports) else if ("--commits" in args) SnapshotJson.encodeToString(app.commitHistory()) else SnapshotJson.encodeToString(app.queue.history())
        val folder = Files.createDirectories(Path.of(app.dataRoot, "diagnostics"))
        Files.writeString(folder.resolve("backend-result.json"), report)
        println(report)
    }
}
