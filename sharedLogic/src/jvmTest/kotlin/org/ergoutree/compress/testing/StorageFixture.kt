package org.ergoutree.compress.testing

import org.ergoutree.compress.application.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.infrastructure.storage.*
import org.ergoutree.compress.ports.*
import java.nio.file.*

/** 每个场景拥有独立数据根，可重开真实 SQLite，并由测试框架统一清理文件。 */
class StorageFixture(val root: Path) : AutoCloseable {
    val environment = NioEnvironment()
    var store = SqlDelightJobStore(root.resolve("catalog/catalog.sqlite")); private set
    val paths = ManagedPaths()
    val gate = MaintenanceGate()
    val retention = JsonRetentionRepository(root.resolve("config/retention.json"))
    var now = System.currentTimeMillis() + 100L * 86_400_000
    private var sequence = 0
    init { Files.createDirectories(root.resolve("config")); Files.writeString(root.resolve("config/root-id"), "storage-test") }
    // JVM 契约夹具显式使用普通删除；生产 Windows 组合根必须注入句柄删除实现。
    val files: NioManagedStorage get() = NioManagedStorage(root.local(), "storage-test", store, environment, paths,
        { VolumeInfo("test-volume", root.local(), "test", true) }, { path, _ -> Files.delete(path) })
    val index get() = ManagedArtifactIndex(root.local(), "storage-test", store, store, environment, paths)
    fun cleanup(repository: MaintenanceStore = store, storage: ManagedStorageFiles = files) = ArtifactCleanup(repository, storage, retention, gate, Clock { now }, IdGenerator { "id-${++sequence}" })
    suspend fun artifact(name: String, kind: ArtifactKind = ArtifactKind.CACHE): ManagedArtifact {
        val path = root.resolve(name); Files.createDirectories(path.parent); Files.writeString(path, "fixture:$name")
        index.file(path.local(), kind)
        return store.maintenanceView().artifacts.single { it.evidence.identity.path == path.local() }
    }
    fun reopen() { store.close(); store = SqlDelightJobStore(root.resolve("catalog/catalog.sqlite")) }
    override fun close() = store.close()
}
