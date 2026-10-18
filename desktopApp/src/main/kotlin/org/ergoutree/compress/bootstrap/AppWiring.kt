package org.ergoutree.compress.bootstrap

import com.sun.jna.platform.win32.Kernel32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.ffmpeg.*
import org.ergoutree.compress.infrastructure.analysis.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.infrastructure.process.*
import org.ergoutree.compress.infrastructure.storage.*
import org.ergoutree.compress.platform.windows.WindowsManagedDeletion
import org.ergoutree.compress.platform.windows.WindowsProcessLauncher
import org.ergoutree.compress.platform.windows.WindowsFileIdentity
import org.ergoutree.compress.platform.windows.WindowsCommitFiles
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import org.ergoutree.compress.ports.*
import java.nio.file.*
import java.util.UUID

/** 桌面组合根只装配端口与平台实现，业务决策保持在共享用例中。 */
class AppWiring(private val session: DesktopSession) : DesktopWorkflow {
    override val dataRoot = session.location.root.toString()
    override val startupNotice = session.location.notice
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val fileKey: (Path, java.nio.file.attribute.BasicFileAttributes) -> String = { path, attr ->
        if (windows) WindowsFileIdentity.key(path, attr) else attr.fileKey()?.toString() ?: ""
    }
    private val files = NioEnvironment(fileKey)
    private val activity = MaintenanceGate()
    private val catalog = session.own(SqlDelightJobStore(session.location.root.resolve("catalog/catalog.sqlite")))
    private val rootId = Files.readString(session.location.root.resolve("config/root-id")).trim()
    private val paths = ManagedPaths { path ->
        if (windows) Kernel32.INSTANCE.GetFileAttributes(WindowsFileIdentity.extendedPath(path)).let { it != -1 && it and 0x400 != 0 } else Files.isSymbolicLink(path)
    }
    private val artifactIndex = ManagedArtifactIndex(session.location.root.local(), rootId, catalog, catalog, files, paths)
    private val runner = JvmProcessRunner(if (windows) WindowsProcessLauncher() else PortableProcessLauncher())
    private val probe = FfprobeAdapter(runner, artifactIndex)
    private val scanner = NioScanner(fileKey) { path ->
        if (windows) {
            val attributes = Kernel32.INSTANCE.GetFileAttributes(WindowsFileIdentity.extendedPath(path))
            if (attributes == -1) throw java.io.IOException("无法读取 Windows 文件属性：${Kernel32.INSTANCE.GetLastError()}")
            attributes and 0x400 != 0
        } else Files.isSymbolicLink(path)
    }
    private val scans = ScanInputs(scanner, probe, files)
    private val freezer = FreezeJob(files)
    private val artifacts = NioCopyArtifacts(session.location.root.local(), files, artifactIndex)
    private val copies = RunCopy(files, FfmpegEncoder(runner), FullMediaValidator(runner, probe, files), artifacts)
    private val commitFiles: CommitFiles? = if (windows) IndexedCommitFiles(WindowsCommitFiles(session.location.root.local(), files), artifactIndex, session.location.root.local()) else null
    internal val workspaceRegistry = commitFiles?.let { RegisteredWorkspaces(session.location.root.local(), catalog, it) }
    internal val commitTransactions = commitFiles?.let { CommitCoordinator(catalog, catalog, it, files, workspaceRegistry!!,
        Clock { System.currentTimeMillis() }, IdGenerator { UUID.randomUUID().toString() }) }
    private val retention: RetentionRepository = JsonRetentionRepository(session.location.root.resolve("config/retention.json"))
    private val managedFiles = NioManagedStorage(session.location.root.local(), rootId, catalog, files, paths,
        { path -> commitFiles?.volume(path) ?: Files.getFileStore(path.nio()).let { VolumeInfo(it.name() + ":" + it.type(), path.nio().root.local(), it.type(), true) } },
        { path, artifact -> check(windows) { "当前平台尚未实现安全清理适配器" }; WindowsManagedDeletion.delete(path, artifact) })
    private val cleanup = ArtifactCleanup(catalog, managedFiles, retention, activity, Clock { System.currentTimeMillis() }, IdGenerator { UUID.randomUUID().toString() })
    private val storage = StorageOverview(catalog, managedFiles, session.location.root.local())
    private val support = SupportExporter(catalog, managedFiles, activity)
    private val admission = VolumeSpaceAdmission(managedFiles, session.location.root.local())
    val queue = QueueCoordinator(catalog, SpaceAwareExecutor(ItemExecutor { plan, stop, lifecycle, emit ->
        val publisher = commitTransactions?.let { JournaledArtifacts(artifacts, it, requireNotNull(lifecycle.claim)) } ?: artifacts
        RunCopy(files, FfmpegEncoder(runner), FullMediaValidator(runner, probe, files), publisher).execute(plan, stop, lifecycle, emit)
    }, admission), files, Clock { System.currentTimeMillis() }, IdGenerator { UUID.randomUUID().toString() }, commitTransactions ?: TransactionRecovery { }, activity, beforeRecovery = { cleanup.reconcile() })
    private val analysis = AnalyzeMedia(files, FfmpegMediaSampler(runner, probe, session.location.root.resolve("analysis/artifacts").local(), artifactIndex), JsonAnalysisCache(session.location.root.resolve("analysis/cache"), artifactIndex))
    suspend fun analyze(plan: ResolvedPlan, options: AnalysisOptions = AnalysisOptions(), stop: StopSignal = StopSignal()) = activity.work { analysis.execute(plan, options, stop) }
    suspend fun commitHistory() = catalog.commits()
    suspend fun prepareRequest(request: ProcessingRequest): FrozenJob = activity.work {
        val tools = validateTools(request.ffmpeg.ifBlank { savedTools.ffmpeg }, request.ffprobe.ifBlank { savedTools.ffprobe }, StopSignal())
        val found = mutableListOf<ScannedMedia>(); var complete = false
        scan(request.inputs, request.maxDepth, request.minimumBytes, if (request.mode == OutputMode.COPY) request.outputDirectory else "", tools, StopSignal()).collect {
            if (it is ScanUpdate.Entry) found += it.item
            if (it is ScanUpdate.Summary) complete = it.complete
        }
        require(complete) { "扫描不完整，不能创建任务" }
        val reviewed = found.map { entry ->
            val media = entry.media ?: return@map entry
            val container = request.settings.container ?: if (media.format.split(',').contains("matroska")) Container.MKV else if (media.format.split(',').contains("mp4")) Container.MP4 else null
            val reason = if (container == null) "请明确选择 MP4 或 MKV" else MediaStrategy.rejection(media, request.settings, container)
            entry.copy(eligible = reason == null, reason = reason)
        }
        val selections = reviewed.filter { it.eligible }.map { PlanningSelection(it.file, requireNotNull(it.media)) }
        require(selections.isNotEmpty()) { "没有合格输入：" + reviewed.joinToString { "${it.file.path.value}: ${it.reason}" } }
        val budgetDirectory = if (request.mode == OutputMode.COPY) request.outputDirectory else session.location.root.toString()
        val job = freezer.execute(UUID.randomUUID().toString(), selections, tools, request.settings, Path.of(budgetDirectory).local(), request.mode, request.onlyIfSmaller)
        for (plan in job.plans) if (plan.outputMode != OutputMode.COPY) {
            require(commitFiles != null && workspaceRegistry != null) { "当前平台尚无原地提交适配器" }
            workspaceRegistry.forVolume(commitFiles.volume(plan.source.path))
        }
        withContext(Dispatchers.IO) {
            val folder = Files.createDirectories(session.location.root.resolve("jobs/frozen"))
            Files.writeString(folder.resolve("${job.id}.json"), SnapshotJson.encodeToString(job), StandardOpenOption.CREATE_NEW)
            Files.writeString(folder.resolve("${job.id}-scan.json"), SnapshotJson.encodeToString(reviewed.map { mapOf("path" to it.file.path.value, "selected" to it.eligible.toString(), "reason" to it.reason.orEmpty()) }))
        }
        job
    }
    private val config = session.location.root.resolve("config/tools.json")
    override val savedTools: SavedToolPaths = if (Files.exists(config)) {
        // 配置损坏在启动时明确报错，不能静默重置。
        val saved = SnapshotJson.decodeFromString<Map<String, String>>(Files.readString(config))
        SavedToolPaths(saved["ffmpeg"].orEmpty(), saved["ffprobe"].orEmpty())
    } else SavedToolPaths()

    override suspend fun validateTools(ffmpeg: String, ffprobe: String, stop: StopSignal): Toolchain = activity.work {
        val ff = Path.of(ffmpeg).toAbsolutePath().normalize()
        val fp = if (ffprobe.isBlank()) ff.resolveSibling(if (windows) "ffprobe.exe" else "ffprobe") else Path.of(ffprobe).toAbsolutePath().normalize()
        val tools = ValidateToolchain(runner, files, artifactIndex).execute(ff.local(), fp.local(), session.location.root.resolve("toolchains").local(), stop)
        withContext(Dispatchers.IO) {
            val tmp = config.resolveSibling("tools-${UUID.randomUUID()}.tmp")
            Files.writeString(tmp, SnapshotJson.encodeToString(mapOf("ffmpeg" to tools.ffmpeg.path.value, "ffprobe" to tools.ffprobe.path.value)))
            java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(tmp, config, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        tools
    }

    override fun scan(roots: List<String>, depth: Int?, minimumBytes: Long, output: String, tools: Toolchain, stop: StopSignal): Flow<ScanUpdate> {
        val exclusions = listOf(session.location.root.local()) + if (output.isNotBlank()) listOf(Path.of(output).toAbsolutePath().normalize().local()) else emptyList()
        return flow {
            val managed = catalog.workspaces().map { it.root }
            activity.work { emitAll(scans.execute(ScanRequest(roots.map { Path.of(it).toAbsolutePath().normalize().local() }, depth, exclusions + managed, minimumBytes), tools,
                session.location.root.resolve("tmp").local(), stop)) }
        }
    }
    override suspend fun freeze(selection: List<PlanningSelection>, tools: Toolchain, settings: EncodeSettings, output: String): FrozenJob = activity.work {
        val job = freezer.execute(UUID.randomUUID().toString(), selection, tools, settings, Path.of(output).local())
        withContext(Dispatchers.IO) {
            val snapshots = Files.createDirectories(session.location.root.resolve("jobs/frozen"))
            val snapshot = snapshots.resolve("${job.id}.json")
            Files.writeString(snapshot, SnapshotJson.encodeToString(job), StandardOpenOption.CREATE_NEW)
            java.nio.channels.FileChannel.open(snapshot, StandardOpenOption.WRITE).use { it.force(true) }
        }
        job
    }
    override suspend fun run(plan: ResolvedPlan, stop: StopSignal, emit: (AttemptEvent) -> Unit): AttemptResult = activity.work { copies.execute(plan, stop, emit = emit) }

    suspend fun loadRetention() = activity.work { retention.load() }
    suspend fun saveRetention(policy: RetentionPolicy) = activity.work { retention.save(policy) }
    suspend fun registerWorkspace(root: LocalPath) = activity.work { requireNotNull(workspaceRegistry).register(root) }
    suspend fun restoreCommit(id: String) = activity.work { requireNotNull(commitTransactions).restore(id) }
    suspend fun storageReport(): StorageReport = activity.work { indexLegacyArtifacts(); storage.report() }
    suspend fun cleanupPreview(options: CleanupOptions = CleanupOptions()): CleanupPreview { activity.work { indexLegacyArtifacts() }; return cleanup.preview(options) }
    suspend fun cleanupExecute(id: String, confirmation: String, acknowledgeBackupLoss: Boolean = false) = cleanup.execute(id, confirmation, acknowledgeBackupLoss)
    suspend fun exportSupport(destination: LocalPath) = support.export(destination)
    suspend fun migrateDataRoot(destination: LocalPath, stop: StopSignal = StopSignal()): MigrationResult {
        activity.work { indexLegacyArtifacts() }
        return DataRootMigration(session.location.root.local(), rootId, catalog, catalog, files, requireNotNull(commitFiles), paths, activity).migrate(destination, stop)
    }
    private suspend fun indexLegacyArtifacts() {
        val view = catalog.maintenanceView()
        val known = view.artifacts.map { it.evidence.identity.path }.toSet()
        val roots = listOf(session.location.root.local()) + catalog.workspaces().map { it.root }
        suspend fun capture(path: LocalPath, kind: ArtifactKind, commitId: String? = null) {
            if (path !in known && roots.any { path.nio().startsWith(it.nio()) }) artifactIndex.file(path, kind, commitId)
        }
        for (attempt in view.attempts) attempt.workspace?.let { work ->
            capture(work.candidate, ArtifactKind.CANDIDATE)
            if (roots.any { work.root.nio().startsWith(it.nio()) } && Files.isDirectory(work.root.nio())) {
                Files.list(work.root.nio()).use { entries ->
                    for (path in entries.toList()) {
                        val name = path.fileName.toString()
                        if (name in setOf("plan.json", "identity.json", "validation.json", "failure.json", "published.json", "publish-intent.json")) capture(path.local(), ArtifactKind.METADATA)
                        if (name.startsWith("encode.") || name.startsWith("decode.")) capture(path.local(), ArtifactKind.LOG)
                    }
                }
            }
        }
        for (record in view.commits) {
            if (record.restoreOf == null) capture(record.candidate.identity.path, ArtifactKind.CANDIDATE)
            capture(record.staged.identity.path, ArtifactKind.STAGING, record.id)
            if (!record.backupReleased) record.backup?.let { capture(it, ArtifactKind.ORIGINAL_BACKUP, record.id) }
        }
    }
}
