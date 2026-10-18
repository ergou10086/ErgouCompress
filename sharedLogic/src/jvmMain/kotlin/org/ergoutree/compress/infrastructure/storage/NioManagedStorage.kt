package org.ergoutree.compress.infrastructure.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.ports.*
import java.nio.file.*
import java.util.UUID

/** 将登记根校验、强证据与平台删除能力组合，禁止删除任意传入的绝对路径。 */
class NioManagedStorage(private val root: LocalPath, private val rootId: String, private val commits: CommitStore,
    private val environment: PlanEnvironment, val paths: ManagedPaths,
    private val volumeReader: suspend (LocalPath) -> VolumeInfo,
    private val deleter: (Path, ManagedArtifact) -> Unit) : ManagedStorageFiles {
    suspend fun resolve(artifact: ManagedArtifact): Path {
        val allowed = artifact.root == root && artifact.rootId == rootId || commits.workspaces().any { it.root == artifact.root && it.id == artifact.rootId }
        require(allowed) { "产物根未登记或根身份改变" }
        val marker = if (artifact.root == root) artifact.root.nio().resolve("config/root-id") else artifact.root.nio().resolve("workspace.json")
        paths.checkAncestors(marker)
        val markerId = withContext(Dispatchers.IO) { if (artifact.root == root) Files.readString(marker).trim() else SnapshotJson.decodeFromString<Workspace>(Files.readString(marker)).id }
        require(markerId == artifact.rootId) { "工作根标记发生变化" }
        val path = paths.resolve(artifact.root, artifact.relativePath)
        require(path.local() == artifact.evidence.identity.path) { "相对路径与强证据不一致" }
        return path
    }
    suspend fun requireExternalDestination(target: Path) {
        paths.checkAncestors(target)
        require(!target.startsWith(root.nio()) && commits.workspaces().none { target.startsWith(it.root.nio()) }) { "导出应位于应用工作根之外" }
        require(Files.isDirectory(target.parent)) { "导出父目录不存在" }
    }
    override suspend fun evidence(artifact: ManagedArtifact): ContentEvidence? = try { evidence(resolve(artifact).local()) } catch (_: IllegalArgumentException) { null }
    override suspend fun missing(artifact: ManagedArtifact): Boolean = try {
        val path = resolve(artifact)
        withContext(Dispatchers.IO) { Files.notExists(path, LinkOption.NOFOLLOW_LINKS) }
    } catch (_: IllegalArgumentException) { false } catch (_: java.io.IOException) { false }
    override suspend fun evidence(path: LocalPath): ContentEvidence? = withContext(Dispatchers.IO) { if (Files.notExists(path.nio(), LinkOption.NOFOLLOW_LINKS)) null else environment.evidence(path) }
    override suspend fun delete(artifact: ManagedArtifact): Unit = withContext(Dispatchers.IO) {
        val path = resolve(artifact)
        check(environment.evidence(path.local()) == artifact.evidence) { "删除前强证据不符" }
        // Windows 正式组合根注入按句柄删除；目录不执行递归删除。
        deleter(path, artifact)
    }
    override suspend fun volume(path: LocalPath) = volumeReader(path)
    override suspend fun usableBytes(path: LocalPath) = environment.availableBytes(path)
}

/** 生产者显式登记新建文件，目录采集仅接受其独占的应用工作目录。 */
class ManagedArtifactIndex(private val root: LocalPath, private val rootId: String, private val store: MaintenanceStore,
    private val commits: CommitStore, private val environment: PlanEnvironment, private val paths: ManagedPaths) : ArtifactRecorder {
    override suspend fun file(path: LocalPath, kind: ArtifactKind, commitId: String?) = withContext(Dispatchers.IO) {
        if (!Files.isRegularFile(path.nio(), LinkOption.NOFOLLOW_LINKS)) return@withContext
        val roots = listOf(rootId to root) + commits.workspaces().map { it.id to it.root }
        val parent = roots.filter { path.nio().toAbsolutePath().normalize().startsWith(it.second.nio()) }.maxByOrNull { it.second.value.length } ?: error("不能登记工作根外的文件")
        val relative = parent.second.nio().relativize(path.nio().toAbsolutePath().normalize()).toString()
        paths.resolve(parent.second, relative)
        require(relative.substringBefore('\\').substringBefore('/') !in setOf("catalog", "config", "native")) { "运行配置与数据库不是可清理产物" }
        val evidence = environment.evidence(path)
        // 备份继承源修改时间，保留期必须从登记时刻起算，不能把旧素材误判为过期备份。
        store.registerArtifact(ManagedArtifact(UUID.randomUUID().toString(), parent.first, parent.second, relative, kind, evidence, System.currentTimeMillis(), commitId = commitId))
        Unit
    }
    override suspend fun consumedStaging(commitId: String) = store.consumePublishedStaging(commitId)
    override suspend fun directory(path: LocalPath): Unit = withContext(Dispatchers.IO) {
        paths.checkAncestors(path.nio())
        // 这里只接收生产者刚创建的目录，绝不扫描素材目录按扩展名认领文件。
        Files.walk(path.nio()).use { stream ->
            for (entry in stream.toList().filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }) {
                val name = entry.fileName.toString()
                val kind = when {
                    name.endsWith(".log") -> ArtifactKind.LOG
                    name in setOf("plan.json", "identity.json", "validation.json", "failure.json", "published.json", "publish-intent.json", "capabilities.json") -> ArtifactKind.METADATA
                    name.startsWith("probe-") -> ArtifactKind.CACHE
                    name.endsWith(".json") -> ArtifactKind.LOG
                    name.startsWith("encoded.partial.") -> ArtifactKind.CANDIDATE
                    else -> ArtifactKind.CACHE
                }
                file(entry.local(), kind)
            }
        }
    }
}
