package org.ergoutree.compress.bootstrap

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.domain.FrozenJob
import java.nio.file.Files
import java.nio.file.Path

/** Headless backend entry. UI integration is intentionally deferred until backend MVP is ready. */
object BackendCommandLine {
    fun accepts(args: Array<String>) = args.any { it == "--history" || it == "--recover" || it.startsWith("--enqueue=") || it.startsWith("--run-job=") || it.startsWith("--retry-item=") }
    fun execute(args: Array<String>, app: AppWiring) = runBlocking {
        app.queue.recover()
        args.firstOrNull { it.startsWith("--enqueue=") }?.substringAfter('=')?.let {
            app.queue.submit(SnapshotJson.decodeFromString<FrozenJob>(Files.readString(Path.of(it))))
        }
        args.firstOrNull { it.startsWith("--retry-item=") }?.substringAfter('=')?.let { app.queue.retry(it) }
        args.firstOrNull { it.startsWith("--run-job=") }?.substringAfter('=')?.let {
            app.queue.run(it) { event -> System.err.println("${event.stage}: ${event.message}") }
        }
        println(SnapshotJson.encodeToString(app.queue.history()))
    }
}
