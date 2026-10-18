package org.ergoutree.compress.bootstrap

import com.sun.jna.platform.win32.Kernel32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.ergoutree.compress.application.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.infrastructure.ffmpeg.*
import org.ergoutree.compress.infrastructure.files.*
import org.ergoutree.compress.infrastructure.process.*
import org.ergoutree.compress.infrastructure.storage.SqlDelightJobStore
import org.ergoutree.compress.platform.windows.WindowsProcessLauncher
import org.ergoutree.compress.platform.windows.WindowsFileIdentity
import org.ergoutree.compress.platform.windows.WindowsCommitFiles
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import org.ergoutree.compress.ports.*
import java.nio.file.*
import java.util.UUID

class AppWiring(private val session: DesktopSession) : DesktopWorkflow {
    override val dataRoot = session.location.root.toString()
    override val startupNotice = session.location.notice
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val fileKey: (Path, java.nio.file.attribute.BasicFileAttributes) -> String = { path, attr ->
        if (windows) WindowsFileIdentity.key(path, attr) else attr.fileKey()?.toString() ?: ""
    }
    private val files = NioEnvironment(fileKey)
    private val runner = JvmProcessRunner(if (windows) WindowsProcessLauncher() else PortableProcessLauncher())
    private val probe = FfprobeAdapter(runner)
    private val scanner = NioScanner(fileKey) { path ->
        if (windows) {
            val attributes = Kernel32.INSTANCE.GetFileAttributes(WindowsFileIdentity.extendedPath(path))
            if (attributes == -1) throw java.io.IOException("无法读取 Windows 文件属性：${Kernel32.INSTANCE.GetLastError()}")
            attributes and 0x400 != 0
        } else Files.isSymbolicLink(path)
    }
    private val scans = ScanInputs(scanner, probe, files)
    private val freezer = FreezeJob(files)
    private val artifacts = NioCopyArtifacts(session.location.root.local(), files)
    private val copies = RunCopy(files, FfmpegEncoder(runner), FullMediaValidator(runner, probe, files), artifacts)
    private val catalog = session.own(SqlDelightJobStore(session.location.root.resolve("catalog/catalog.sqlite")))
    private val commitFiles = if (windows) WindowsCommitFiles(session.location.root.local(), files) else null
    val workspaceRegistry = commitFiles?.let { RegisteredWorkspaces(session.location.root.local(), catalog, it) }
    val commitTransactions = commitFiles?.let { CommitCoordinator(catalog, catalog, it, files, workspaceRegistry!!,
        Clock { System.currentTimeMillis() }, IdGenerator { UUID.randomUUID().toString() }) }
    val queue = QueueCoordinator(catalog, ItemExecutor { plan, stop, lifecycle, emit ->
        val publisher = commitTransactions?.let { JournaledArtifacts(artifacts, it, requireNotNull(lifecycle.claim)) } ?: artifacts
        RunCopy(files, FfmpegEncoder(runner), FullMediaValidator(runner, probe, files), publisher).execute(plan, stop, lifecycle, emit)
    }, files, Clock { System.currentTimeMillis() }, IdGenerator { UUID.randomUUID().toString() }, commitTransactions ?: TransactionRecovery { })
    suspend fun commitHistory() = catalog.commits()
    suspend fun prepareRequest(request: ProcessingRequest): FrozenJob {
        val tools = validateTools(request.ffmpeg.ifBlank { savedTools.ffmpeg }, request.ffprobe.ifBlank { savedTools.ffprobe }, StopSignal())
        val found = mutableListOf<ScannedMedia>(); var complete = false
        scan(request.inputs, request.maxDepth, request.minimumBytes, if (request.mode == OutputMode.COPY) request.outputDirectory else "", tools, StopSignal()).collect {
            if (it is ScanUpdate.Entry) found += it.item
            if (it is ScanUpdate.Summary) complete = it.complete
        }
        require(complete) { "扫描不完整，不能创建任务" }
        val selections = found.filter { it.eligible }.map { PlanningSelection(it.file, requireNotNull(it.media)) }
        val budgetDirectory = if (request.mode == OutputMode.COPY) request.outputDirectory else session.location.root.toString()
        val job = freezer.execute(UUID.randomUUID().toString(), selections, tools, request.settings, Path.of(budgetDirectory).local(), request.mode, request.onlyIfSmaller)
        for (plan in job.plans) if (plan.outputMode != OutputMode.COPY) {
            require(commitFiles != null && workspaceRegistry != null) { "当前平台尚无原地提交适配器" }
            workspaceRegistry.forVolume(commitFiles.volume(plan.source.path))
        }
        withContext(Dispatchers.IO) {
            val folder = Files.createDirectories(session.location.root.resolve("jobs/frozen"))
            Files.writeString(folder.resolve("${job.id}.json"), SnapshotJson.encodeToString(job), StandardOpenOption.CREATE_NEW)
            Files.writeString(folder.resolve("${job.id}-scan.json"), SnapshotJson.encodeToString(found.map { mapOf("path" to it.file.path.value, "selected" to it.eligible.toString(), "reason" to it.reason.orEmpty()) }))
        }
        return job
    }
    private val config = session.location.root.resolve("config/tools.json")
    override val savedTools: SavedToolPaths = if (Files.exists(config)) {
        // Bad config is visible at startup, not silently replaced with an empty history.
        val saved = SnapshotJson.decodeFromString<Map<String, String>>(Files.readString(config))
        SavedToolPaths(saved["ffmpeg"].orEmpty(), saved["ffprobe"].orEmpty())
    } else SavedToolPaths()

    override suspend fun validateTools(ffmpeg: String, ffprobe: String, stop: StopSignal): Toolchain {
        val ff = Path.of(ffmpeg).toAbsolutePath().normalize()
        val fp = if (ffprobe.isBlank()) ff.resolveSibling(if (windows) "ffprobe.exe" else "ffprobe") else Path.of(ffprobe).toAbsolutePath().normalize()
        val tools = ValidateToolchain(runner, files).execute(ff.local(), fp.local(), session.location.root.resolve("toolchains").local(), stop)
        withContext(Dispatchers.IO) {
            val tmp = config.resolveSibling("tools-${UUID.randomUUID()}.tmp")
            Files.writeString(tmp, SnapshotJson.encodeToString(mapOf("ffmpeg" to tools.ffmpeg.path.value, "ffprobe" to tools.ffprobe.path.value)))
            java.nio.channels.FileChannel.open(tmp, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(tmp, config, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        return tools
    }

    override fun scan(roots: List<String>, depth: Int?, minimumBytes: Long, output: String, tools: Toolchain, stop: StopSignal): Flow<ScanUpdate> {
        val exclusions = listOf(session.location.root.local()) + if (output.isNotBlank()) listOf(Path.of(output).toAbsolutePath().normalize().local()) else emptyList()
        return flow {
            val managed = catalog.workspaces().map { it.root }
            emitAll(scans.execute(ScanRequest(roots.map { Path.of(it).toAbsolutePath().normalize().local() }, depth, exclusions + managed, minimumBytes), tools,
                session.location.root.resolve("tmp").local(), stop))
        }
    }
    override suspend fun freeze(selection: List<PlanningSelection>, tools: Toolchain, settings: EncodeSettings, output: String): FrozenJob {
        val job = freezer.execute(UUID.randomUUID().toString(), selection, tools, settings, Path.of(output).local())
        withContext(Dispatchers.IO) {
            val snapshots = Files.createDirectories(session.location.root.resolve("jobs/frozen"))
            val snapshot = snapshots.resolve("${job.id}.json")
            Files.writeString(snapshot, SnapshotJson.encodeToString(job), StandardOpenOption.CREATE_NEW)
            java.nio.channels.FileChannel.open(snapshot, StandardOpenOption.WRITE).use { it.force(true) }
        }
        return job
    }
    override suspend fun run(plan: ResolvedPlan, stop: StopSignal, emit: (AttemptEvent) -> Unit): AttemptResult = copies.execute(plan, stop, emit = emit)
}
