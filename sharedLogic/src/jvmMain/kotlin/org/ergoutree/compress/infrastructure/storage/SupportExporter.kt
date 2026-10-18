package org.ergoutree.compress.infrastructure.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.ergoutree.compress.application.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*
import org.ergoutree.compress.infrastructure.files.*
import java.nio.file.*
import java.nio.channels.FileChannel
import java.util.zip.*

/** 导出有界、默认脱敏的诊断 ZIP，不包含数据库、素材、备份或原始文件名。 */
class SupportExporter(private val store: MaintenanceStore, private val files: NioManagedStorage, private val gate: MaintenanceGate) {
    suspend fun export(destination: LocalPath): LocalPath = gate.exclusive { withContext(Dispatchers.IO) {
        val target = destination.nio().toAbsolutePath().normalize()
        files.requireExternalDestination(target)
        require(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) { "导出目标已存在" }
        val view = store.maintenanceView()
        val privateValues = view.items.flatMap { item ->
            val plan = item.plan
            listOf(plan.source.path.value.substringAfterLast('\\').substringAfterLast('/'), plan.destination.value.substringAfterLast('\\').substringAfterLast('/')) +
                plan.media.tags.values + plan.media.streams.flatMap { it.tags.values } + plan.media.chapters.flatMap { it.tags.values }
        }
        val redactor = DiagnosticRedactor(privateValues)
        fun sanitizedJson(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> JsonObject(element.mapValues { (key, value) -> if (Regex("(?i)(token|secret|password|cookie|authorization|key)").containsMatchIn(key)) JsonPrimitive("<PRIVATE>") else sanitizedJson(value) })
            is JsonArray -> JsonArray(element.map(::sanitizedJson))
            is JsonPrimitive -> if (element.isString) JsonPrimitive(redactor.redact(element.content)) else element
        }
        var remaining = 4 * 1024 * 1024
        ZipOutputStream(Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)).use { zip ->
            fun entry(name: String, content: String) { zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            val summary = buildJsonObject {
                put("schemaVersion", 1); put("generatedAt", System.currentTimeMillis()); put("jobs", view.jobs.size); put("commits", view.commits.size)
                put("dataRoot", "<DATA_ROOT>"); put("containsMedia", false); put("containsDatabase", false)
                put("jobStates", buildJsonObject { view.jobs.groupingBy { it.state.name }.eachCount().forEach { (key, value) -> put(key, value) } })
            }
            entry("summary.json", summary.toString())
            var index = 0
            for (artifact in view.artifacts.filter { it.kind == ArtifactKind.LOG && it.state == ArtifactState.LIVE }) {
                if (remaining <= 0) break
                if (files.evidence(artifact) != artifact.evidence) continue
                val source = files.resolve(artifact)
                val limit = minOf(256 * 1024, remaining)
                val size = Files.size(source)
                if (source.toString().endsWith(".json") && size > limit) continue
                val offset = (size - limit).coerceAtLeast(0)
                val buffer = java.nio.ByteBuffer.allocate((size - offset).toInt())
                FileChannel.open(source, StandardOpenOption.READ).use { channel -> channel.position(offset); while (buffer.hasRemaining() && channel.read(buffer) >= 0) {} }
                var raw = buffer.array().toString(Charsets.UTF_8)
                if (offset > 0) raw = raw.substringAfter('\n', "") // 丢弃截断的首行，避免残留半段私有路径。
                val safe = if (source.toString().endsWith(".json")) try { sanitizedJson(Json.parseToJsonElement(raw)).toString() } catch (_: Exception) { "<无法安全解析的诊断 JSON 已略过>" }
                    else raw.lineSequence().joinToString("\n", transform = redactor::redact)
                entry("logs/${++index}.txt", safe)
                remaining -= buffer.capacity()
            }
        }
        FileChannel.open(target, StandardOpenOption.WRITE).use { it.force(true) }
        target.local()
    } }
}
