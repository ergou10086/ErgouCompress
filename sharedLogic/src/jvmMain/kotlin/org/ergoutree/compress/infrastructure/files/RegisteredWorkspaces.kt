package org.ergoutree.compress.infrastructure.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.SnapshotJson
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.ports.*
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.UUID

class RegisteredWorkspaces(private val root: LocalPath, private val store: CommitStore, private val files: CommitFiles) : WorkspaceRegistry {
    override suspend fun register(root: LocalPath): Workspace = withContext(Dispatchers.IO) {
        require(Files.isDirectory(root.nio())) { "请先创建并明确选择工作区目录" }
        val volume = files.volume(root)
        require(volume.local && volume.fileSystem.equals("NTFS", true))
        val marker = root.nio().resolve("workspace.json")
        val workspace = if (Files.exists(marker)) SnapshotJson.decodeFromString<Workspace>(Files.readString(marker)) else {
            Workspace(UUID.randomUUID().toString(), root.nio().toRealPath().local(), volume.id).also {
                Files.writeString(marker, SnapshotJson.encodeToString(it), StandardOpenOption.CREATE_NEW)
            }
        }
        require(workspace.volumeId == volume.id && workspace.root == root.nio().toRealPath().local()) { "工作区标记与实际位置/卷不符" }
        store.registerWorkspace(workspace); workspace
    }
    override suspend fun forVolume(volume: VolumeInfo): Workspace {
        val own = files.volume(root)
        var registered = store.workspaces()
        if (registered.none { it.volumeId == own.id }) { register(root); registered = store.workspaces() }
        return registered.firstOrNull { it.volumeId == volume.id } ?: throw CompressException(ErrorCode.COMMIT_BLOCKED, "该卷未登记工作区，请明确配置后继续")
    }
}
