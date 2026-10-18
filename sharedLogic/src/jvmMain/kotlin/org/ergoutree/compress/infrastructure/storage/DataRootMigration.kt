package org.ergoutree.compress.infrastructure.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.ergoutree.compress.application.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.ports.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.sql.DriverManager
import java.util.UUID

/** 迁移检查点用于契约测试，不作为生产命令的故障开关。 */
fun interface MigrationCheckpoint { fun reached(phase: String) }
/** 在不改动源档案的前提下复制、校验并重定位操作引用，最后原子切换启动指针。 */
class DataRootMigration(private val source: LocalPath, private val rootId: String, private val store: MaintenanceStore,
    private val commitStore: CommitStore, private val files: PlanEnvironment, private val native: CommitFiles,
    private val paths: ManagedPaths, private val gate: MaintenanceGate, private val checkpoint: MigrationCheckpoint = MigrationCheckpoint { }) {
    suspend fun migrate(destination: LocalPath, stop: StopSignal = StopSignal()): MigrationResult = gate.exclusive { withContext(Dispatchers.IO) {
        val oldRoot = source.nio().toRealPath()
        val target = destination.nio().toAbsolutePath().normalize()
        paths.checkAncestors(target)
        require(!target.startsWith(oldRoot) && !oldRoot.startsWith(target)) { "新旧数据根不能相同或相互包含" }
        require(Files.isDirectory(target) && Files.list(target).use { !it.findAny().isPresent }) { "迁移目标必须是已存在的空目录" }
        val view = store.maintenanceView()
        require(view.jobs.all { it.state in setOf(JobState.COMPLETED, JobState.COMPLETED_WITH_ERRORS, JobState.CANCELLED) }) { "未终结任务禁止迁移数据根" }
        require(view.commits.all { it.phase in setOf(CommitPhase.COMMITTED, CommitPhase.CANCELLED) }) { "未决提交禁止迁移" }
        require(view.artifacts.none { it.state == ArtifactState.MARKED }) { "请先完成清理对账" }
        fun internal(path: LocalPath) = path.nio().toAbsolutePath().normalize().startsWith(oldRoot)
        require(view.items.none { item -> listOf(item.plan.source.path, item.plan.destination, item.plan.tools.ffmpeg.path, item.plan.tools.ffprobe.path).any(::internal) }) { "素材、最终输出或工具位于数据根内，不能把用户文件当作内部路径迁移" }
        view.artifacts.filter { it.state == ArtifactState.LIVE && internal(it.evidence.identity.path) }.forEach { require(files.evidence(it.evidence.identity.path) == it.evidence) { "登记产物内容变化，迁移已阻止" } }
        view.commits.filter { it.phase == CommitPhase.COMMITTED }.forEach {
            require(CommitLineage.current(it, view.commits).let { current -> native.evidence(current.plan.destination) == current.output }) { "已完成输出变化，不能迁移" }
            if (!it.backupReleased && it.backup != null) {
                val backup = requireNotNull(native.evidence(it.backup)); val expected = it.backupEvidence ?: it.source
                require(backup.sha256 == expected.sha256 && backup.identity.fileKey == expected.identity.fileKey) { "原始备份证据变化" }
            }
        }
        val id = UUID.randomUUID().toString()
        val pending = target.resolve("migration-pending.json")
        atomicJson(pending, buildJsonObject { put("id", id); put("rootId", rootId); put("source", source.value) }.toString())
        val catalog = Files.createDirectory(target.resolve("catalog"))
        FileChannel.open(catalog.resolve("app.lock"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { lockChannel ->
            lockChannel.lock().use {
                // Path 的 Windows 相等性处理大小写和分隔符，不能用历史 LocalPath 原始字符串查表。
                val copied = linkedMapOf<Path, Pair<ContentEvidence, ContentEvidence>>()
                val existingWorkspaces = commitStore.workspaces()
                try {
                    val sourceFiles = Files.walk(oldRoot).use { it.toList().filter { entry ->
                        paths.checkAncestors(entry)
                        val relative = oldRoot.relativize(entry).toString().replace('\\', '/')
                        Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS) && relative.substringBefore('/') != "native" &&
                            relative !in setOf("catalog/app.lock", "catalog/catalog.sqlite", "catalog/catalog.sqlite-journal", "config/current-root.json", "migration-ready.json", "migration-pending.json")
                    } }
                    for (entry in sourceFiles) {
                        stop.checkRunning()
                        val out = target.resolve(oldRoot.relativize(entry)); Files.createDirectories(out.parent)
                        val before = files.evidence(entry.local())
                        val metadata = native.metadata(entry.local())
                        native.copy(entry.local(), out.local(), stop)
                        native.prepareMetadata(entry.local(), out.local(), metadata); native.applyMetadata(out.local(), metadata, true)
                        val after = files.evidence(out.local())
                        require(before.sha256 == after.sha256 && files.evidence(entry.local()) == before) { "迁移复制验证失败" }
                        copied[entry] = before to after
                    }
                    checkpoint.reached("AfterCopy"); stop.checkRunning()
                    val db = catalog.resolve("catalog.sqlite")
                    store.backupDatabase(db.local())
                    val backups = Files.createDirectories(catalog.resolve("catalog-backups"))
                    // 这个数据库副本保留旧绝对引用，作为迁移前证据而非可直接启用的新档案。
                    Files.copy(db, backups.resolve("migration-$id.sqlite"))
                    fun mapped(path: LocalPath): LocalPath = if (internal(path)) target.resolve(oldRoot.relativize(path.nio().toAbsolutePath().normalize())).local() else path
                    fun evidence(value: ContentEvidence): ContentEvidence {
                        val copy = copied[value.identity.path.nio().toAbsolutePath().normalize()]?.second
                        if (copy != null) { require(copy.sha256 == value.sha256) { "迁移不能修改历史媒体内容证据" }; return copy }
                        return value.copy(identity = value.identity.copy(path = mapped(value.identity.path)))
                    }
                    fun receipt(value: ValidationReceipt?) = value?.copy(candidate = evidence(value.candidate))
                    val volume = native.volume(target.local())
                    val movedWorkspaces = existingWorkspaces.filter { internal(it.root) }.map {
                        // 跨卷后旧根仍作为原输出卷的已登记工作区；新根获得独立工作区身份。
                        it.copy(id = if (it.volumeId == volume.id) it.id else UUID.randomUUID().toString(), root = mapped(it.root), volumeId = volume.id)
                    }
                    val workspaceMap = existingWorkspaces.filter { internal(it.root) }.zip(movedWorkspaces).associate { it.first.id to it.second }
                    val migratedCommits = view.commits.map { record ->
                        record.copy(candidate = evidence(record.candidate), staged = evidence(record.staged), backup = record.backup?.let(::mapped), receipt = receipt(record.receipt),
                            workspace = if (internal(record.workspace.root)) workspaceMap[record.workspace.id] ?: record.workspace.copy(root = mapped(record.workspace.root), volumeId = volume.id) else record.workspace,
                            backupEvidence = record.backup?.takeIf { internal(it) && !record.backupReleased }?.let { copied[it.nio().toAbsolutePath().normalize()]?.second } ?: record.backupEvidence,
                            revision = record.revision + 1)
                    }
                    val changedMetadata = mutableMapOf<LocalPath, ContentEvidence>()
                    for (record in migratedCommits) {
                        val mirror = target.resolve("commits/${record.id}.json")
                        atomicJson(mirror, SnapshotJson.encodeToString(record))
                        changedMetadata[source.nio().resolve("commits/${record.id}.json").local()] = files.evidence(mirror.local())
                    }
                    for (workspace in movedWorkspaces) atomicJson(workspace.root.nio().resolve("workspace.json"), SnapshotJson.encodeToString(workspace))
                    DriverManager.getConnection("jdbc:sqlite:$db").use { connection ->
                        connection.createStatement().use { it.execute("PRAGMA foreign_keys=ON"); it.execute("PRAGMA journal_mode=DELETE"); it.execute("PRAGMA synchronous=FULL") }
                        connection.autoCommit = false
                        fun update(sql: String, vararg values: Any?) = connection.prepareStatement(sql).use { statement -> values.forEachIndexed { index, value -> statement.setObject(index + 1, value) }; statement.executeUpdate() }
                        for (attempt in view.attempts) {
                            val work = attempt.workspace?.let { it.copy(root = mapped(it.root), candidate = mapped(it.candidate)) }
                            update("UPDATE attempt SET workspace=?, validation=? WHERE id=?", work?.let { SnapshotJson.encodeToString(it) }, receipt(attempt.validation)?.let { SnapshotJson.encodeToString(it) }, attempt.id)
                        }
                        for (record in migratedCommits) update("UPDATE commit_log SET payload=?,revision=? WHERE id=?", SnapshotJson.encodeToString(record), record.revision, record.id)
                        for (artifact in view.artifacts) {
                            val currentEvidence = changedMetadata[artifact.evidence.identity.path] ?: evidence(artifact.evidence)
                            // 根内的迁移副本统一归新主根管理，避免跨卷工作区身份变化导致产物失联。
                            val moved = internal(artifact.evidence.identity.path)
                            val current = artifact.copy(root = if (moved) target.local() else artifact.root,
                                rootId = if (moved) rootId else artifact.rootId,
                                relativePath = if (moved) target.relativize(currentEvidence.identity.path.nio()).toString() else artifact.relativePath,
                                evidence = currentEvidence, revision = artifact.revision + 1)
                            update("UPDATE managed_artifact SET path=?,payload=?,revision=? WHERE id=?", currentEvidence.identity.path.value, SnapshotJson.encodeToString(current), current.revision, current.id)
                        }
                        // 旧预览包含旧路径和旧文件身份，迁移后必须重新预览。
                        update("UPDATE cleanup_preview SET consumed=1")
                        existingWorkspaces.filter { internal(it.root) && it.volumeId == volume.id }.forEach { update("DELETE FROM workspace WHERE id=?", it.id) }
                        val occupied = existingWorkspaces.filterNot { internal(it.root) && it.volumeId == volume.id }.map { it.volumeId }.toMutableSet()
                        movedWorkspaces.forEach { workspace -> if (occupied.add(workspace.volumeId)) update("INSERT INTO workspace(id,volume_id,root) VALUES(?,?,?)", workspace.id, workspace.volumeId, workspace.root.value) }
                        connection.commit()
                        connection.createStatement().use { statement -> statement.executeQuery("PRAGMA integrity_check").use { require(it.next() && it.getString(1) == "ok") } }
                    }
                    FileChannel.open(db, StandardOpenOption.WRITE).use { it.force(true) }
                    checkpoint.reached("AfterRebase"); stop.checkRunning()
                    copied.forEach { (path, pair) -> require(files.evidence(path.local()) == pair.first) { "迁移结束前源档案发生变化" } }
                    val finalHashes = copied.mapValues { (_, pair) -> files.evidence(pair.second.identity.path).sha256 }
                    val manifest = buildJsonObject {
                        put("id", id); put("rootId", rootId); put("source", source.value); put("destination", target.toString())
                        put("databaseSha256", files.evidence(db.local()).sha256)
                        put("files", buildJsonArray { copied.forEach { (path, pair) -> add(buildJsonObject { put("source", path.toString()); put("destination", pair.second.identity.path.value); put("copiedContentSha256", pair.first.sha256); put("finalContentSha256", finalHashes.getValue(path)); put("oldFileId", pair.first.identity.fileKey); put("newFileId", pair.second.identity.fileKey) }) } })
                    }
                    atomicJson(target.resolve("migration-ready.json"), manifest.toString())
                    checkpoint.reached("BeforeSwitch"); stop.checkRunning()
                    // 唯一激活点：目标已经完整验证，源连接和所有原始文件仍保持可回退。
                    withContext(NonCancellable) {
                        Files.delete(pending)
                        try {
                            checkpoint.reached("BeforePointer")
                            atomicJson(oldRoot.resolve("config/current-root.json"), buildJsonObject { put("schemaVersion", 1); put("rootId", rootId); put("migrationId", id); put("destination", target.toString()) }.toString())
                        } catch (failure: Exception) {
                            // 激活失败仍封锁目标；就绪副本不能被误当作已切换的主档案。
                            atomicJson(pending, buildJsonObject { put("id", id); put("rootId", rootId); put("source", source.value) }.toString())
                            throw failure
                        }
                        gate.seal()
                    }
                    MigrationResult(id, source, target.local(), rootId, copied.size)
                } catch (failure: Exception) {
                    // 未切换时保留 pending 目标供定位，不启用不完整档案，也不删除源数据。
                    throw failure
                }
            }
        }
    } }
    private fun atomicJson(path: Path, json: String) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling("${path.fileName}-${UUID.randomUUID()}.tmp")
        Files.writeString(temporary, json, StandardOpenOption.CREATE_NEW)
        FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
