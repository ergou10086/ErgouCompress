package org.ergoutree.compress.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.presentation.*

@Composable
fun SettingsPage(controller: WorkspaceController, pickFile: ((String) -> Unit) -> Unit,
    pickFolder: ((String) -> Unit) -> Unit, saveSupport: ((String) -> Unit) -> Unit, close: () -> Unit) {
    val s by controller.state.collectAsState()
    val c = LocalFluentColors.current
    var tab by remember { mutableStateOf("常规") }
    var preferences by remember(s.preferences) { mutableStateOf(s.preferences) }
    var defaults by remember(s.preferences) { mutableStateOf(TaskDraft.from(s.preferences)) }
    var depth by remember(s.preferences) { mutableStateOf(s.preferences.scanDepth?.toString() ?: "无限") }
    var minimum by remember(s.preferences) { mutableStateOf((s.preferences.minimumBytes / 1_000_000.0).toString()) }
    var buffer by remember(s.preferences) { mutableStateOf(s.preferences.logBufferLines.toString()) }
    var localError by remember { mutableStateOf<String?>(null) }
    val enabled = !s.closing && !s.restartRequired && !s.maintenanceBusy
    Column(Modifier.fillMaxSize().background(c.background)) {
        PageHeading("设置", "工具偏好、默认任务参数与本地档案", close)
        Row(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("常规", "默认任务", "存储管理", "日志管理").forEach { title -> FButton(title, primary = tab == title, quiet = tab != title) { tab = title } }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            when (tab) {
                "常规" -> {
                    FCard {
                        SectionTitle("外观与交互")
                        FChoice("应用主题", preferences.theme, listOf(AppTheme.SYSTEM to "跟随系统", AppTheme.LIGHT to "浅色", AppTheme.DARK to "深色"), enabled) { preferences = preferences.copy(theme = it) }
                        FCheck("减少动画", preferences.reduceMotion, enabled) { preferences = preferences.copy(reduceMotion = it) }
                        FText("Ctrl+O 添加文件 · Ctrl+, 打开设置 · Tab 在控件间移动", 12, c.secondary)
                    }
                    FCard {
                        SectionTitle("媒体引擎", "使用你自己的 FFmpeg；首次导入会自动验证已保存的路径。")
                        FField("FFmpeg 路径", s.ffmpeg, enabled = enabled && !s.intakeBusy && s.active == null) { controller.editTools(it, s.ffprobe) }
                        FButton("选择 ffmpeg.exe", enabled && !s.intakeBusy && s.active == null) { pickFile { controller.editTools(it, s.ffprobe) } }
                        FField("ffprobe 路径", s.ffprobe, "留空时使用 FFmpeg 同目录的 ffprobe。", enabled && !s.intakeBusy && s.active == null) { controller.editTools(s.ffmpeg, it) }
                        FButton("选择 ffprobe.exe", enabled && !s.intakeBusy && s.active == null) { pickFile { controller.editTools(s.ffmpeg, it) } }
                        FButton(if (s.toolBusy) "正在实际编码自检…" else "验证并保存工具", enabled && !s.intakeBusy && s.active == null && s.ffmpeg.isNotBlank()) { controller.validateTools() }
                        if (s.toolBusy) FProgress(null, "正在验证工具链")
                        s.tools?.checks?.forEach { FText("${it.backend} · ${if (it.available) "可用" else "不可用"} · ${it.diagnostic}", 12, c.secondary) }
                    }
                    FCard {
                        SectionTitle("导入与扫描")
                        FField("扫描深度", depth, "0 仅根目录，4 再向下四层；输入“无限”遍历全部层级。", enabled) { depth = it }
                        FField("最小文件大小（MB）", minimum, "1 MB = 1,000,000 字节；低于阈值仍显示，默认不选。", enabled) { minimum = it }
                    }
                }
                "默认任务" -> FCard {
                    SectionTitle("新任务的起点", "保存后用于新草稿；正在处理的任务保留创建时的快照。")
                    EncodingEditor(defaults, enabled, s.tools) { defaults = it }
                    FField("默认副本目录", defaults.output, enabled = enabled) { defaults = defaults.copy(output = it) }
                    FButton("浏览默认输出目录", enabled) { pickFolder { defaults = defaults.copy(output = it) } }
                    FChoice("默认处理方式", defaults.mode, listOf(OutputMode.COPY to "保存副本", OutputMode.REPLACE to "原地替换", OutputMode.CHANGE_EXTENSION to "原地改扩展名"), enabled) { defaults = defaults.copy(mode = it) }
                    FCheck("仅在输出更小时发布", defaults.onlyIfSmaller, enabled) { defaults = defaults.copy(onlyIfSmaller = it) }
                    FCheck("保留元数据", defaults.preserveMetadata, enabled) { defaults = defaults.copy(preserveMetadata = it) }
                    FCheck("保留章节", defaults.preserveChapters, enabled) { defaults = defaults.copy(preserveChapters = it) }
                }
                "存储管理" -> StorageSettings(controller, pickFolder, saveSupport)
                "日志管理" -> FCard {
                    SectionTitle("日志显示与采集", "显示级别只筛选窗口内容；任务状态事件仍会持久记录。")
                    FChoice("默认显示级别", preferences.logLevel, listOf(LogLevel.DEBUG to "调试", LogLevel.INFO to "信息", LogLevel.WARNING to "警告", LogLevel.ERROR to "错误"), enabled) { preferences = preferences.copy(logLevel = it) }
                    FCheck("采集 FFmpeg 详细日志", preferences.ffmpegVerbose, enabled) { preferences = preferences.copy(ffmpegVerbose = it) }
                    FField("窗口最多保留行数", buffer, "200–5000 行；完整日志保存在任务档案中。", enabled) { buffer = it }
                    FText("日志保留天数与清理预览在“存储管理”中设置。诊断包会对路径等信息进行脱敏。", 12, c.secondary)
                }
            }
            (localError ?: s.error)?.let { FText(it, 13, c.danger) }
            FText(s.message, 12, c.secondary)
        }
        FDivider()
        Row(Modifier.fillMaxWidth().background(c.surface).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FText(if (s.restartRequired) "数据根已迁移，请重新启动" else "设置保存在本机数据目录", 12, c.secondary, modifier = Modifier.weight(1f))
            FButton("关闭", onClick = close)
            FButton("保存设置", enabled, primary = true) {
                try {
                    val scanDepth = if (depth.trim() == "无限") null else depth.toIntOrNull() ?: error("扫描深度应为 0–100 或“无限”")
                    val minimumMb = minimum.toDoubleOrNull() ?: error("最小大小需要数字")
                    require(minimumMb.isFinite() && minimumMb >= 0 && minimumMb <= Long.MAX_VALUE / 1_000_000.0) { "最小大小超出范围" }
                    val value = preferences.copy(scanDepth = scanDepth, minimumBytes = (minimumMb * 1_000_000).toLong(), defaults = defaults.settings(),
                        outputDirectory = defaults.output, outputMode = defaults.mode, onlyIfSmaller = defaults.onlyIfSmaller,
                        logBufferLines = buffer.toIntOrNull() ?: error("日志行数需要整数"))
                    localError = null; controller.savePreferences(value)
                } catch (e: Exception) { localError = e.message ?: "设置无效" }
            }
        }
    }
}

@Composable
private fun StorageSettings(controller: WorkspaceController, pickFolder: ((String) -> Unit) -> Unit, saveSupport: ((String) -> Unit) -> Unit) {
    val s by controller.state.collectAsState()
    val c = LocalFluentColors.current
    var cacheDays by remember(s.retention) { mutableStateOf(s.retention.cacheDays.toString()) }
    var logDays by remember(s.retention) { mutableStateOf(s.retention.logDays.toString()) }
    var backupDays by remember(s.retention) { mutableStateOf(s.retention.minimumBackupDays.toString()) }
    var options by remember { mutableStateOf(CleanupOptions()) }
    var acknowledge by remember(s.cleanup?.id) { mutableStateOf(false) }
    var workspace by remember { mutableStateOf("") }
    var migration by remember { mutableStateOf("") }
    var migrateConfirm by remember(migration) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val enabled = !s.busy && !s.closing && !s.restartRequired
    LaunchedEffect(Unit) { controller.refreshStorage() }
    FCard {
        SectionTitle("本地档案与空间")
        FText(controller.workflow.dataRoot, 13)
        s.storage?.let { report ->
            report.volumes.forEach { volume -> FText("${volume.volume.root.value} · 可用 ${bytes(volume.usableBytes)} · 受管逻辑占用 ${bytes(volume.managedLogicalBytes)} · 备份 ${bytes(volume.backupBytesRetained)}", 13) }
            FText(report.explanation, 12, c.secondary)
        }
        FButton("刷新空间统计", enabled) { controller.refreshStorage() }
        FButton("导出脱敏诊断包…", enabled) { saveSupport { controller.exportSupport(it) } }
    }
    FCard {
        SectionTitle("保留策略", "保存策略后，通过下方预览核对可清理内容。原始备份需要单独选择和确认。")
        FField("缓存保留天数", cacheDays, enabled = enabled) { cacheDays = it }
        FField("日志保留天数", logDays, enabled = enabled) { logDays = it }
        FField("备份最少保留天数", backupDays, enabled = enabled) { backupDays = it }
        FButton("保存保留策略", enabled) { try {
            controller.saveRetention(s.retention.copy(cacheDays = cacheDays.toInt(), logDays = logDays.toInt(), minimumBackupDays = backupDays.toInt())); error = null
        } catch (e: Exception) { error = "保留天数需要 0–3650 的整数" } }
        FDivider()
        FCheck("过期缓存", options.caches, enabled) { options = options.copy(caches = it) }
        FCheck("过期日志", options.logs, enabled) { options = options.copy(logs = it) }
        FCheck("失去引用的候选文件", options.candidates, enabled) { options = options.copy(candidates = it) }
        FCheck("清空可再生缓存（忽略年龄）", options.clearCacheNow, enabled) { options = options.copy(clearCacheNow = it) }
        FCheck("包含满足保留条件的原始备份", options.originalBackups, enabled) { options = options.copy(originalBackups = it) }
        FButton("预览清理", enabled) { controller.previewCleanup(options) }
        s.cleanup?.let { preview ->
            FText("本次预览：可清理 ${preview.decisions.count { it.eligible }} 项，保护 ${preview.decisions.count { !it.eligible }} 项", strong = true)
            preview.decisions.take(30).forEach { FText("${if (it.eligible) "可清理" else "受保护"} · ${it.artifact.relativePath} · ${it.reason}", 12, c.secondary) }
            if (preview.decisions.size > 30) FText("共 ${preview.decisions.size} 项；执行时会重新检查所有文件证据。", 12, c.secondary)
            FCheck(if (preview.options.originalBackups) "确认清理；删除备份后将失去对应恢复能力" else "确认删除本次预览中可清理的文件", acknowledge, enabled) { acknowledge = it }
            FButton("执行本次清理", enabled && acknowledge && preview.decisions.any { it.eligible }) { controller.executeCleanup(preview.options.originalBackups && acknowledge) }
        }
    }
    FCard {
        SectionTitle("原地处理工作区", "每个源文件卷登记独立目录，用于提交日志、暂存与原始备份。")
        FField("工作区目录", workspace, enabled = enabled) { workspace = it }
        FButton("选择工作区", enabled) { pickFolder { workspace = it } }
        FButton("登记工作区", enabled && workspace.isNotBlank()) { controller.registerWorkspace(workspace) }
    }
    FCard {
        SectionTitle("迁移数据目录", "选择空目录。迁移会核验文件并要求重启；处理任务期间不可迁移。")
        FField("新的数据目录", migration, enabled = enabled) { migration = it }
        FButton("选择新目录", enabled) { pickFolder { migration = it } }
        FCheck("迁移完成后关闭并重新启动工具", migrateConfirm, enabled) { migrateConfirm = it }
        FButton("开始迁移", enabled && migration.isNotBlank() && migrateConfirm) { controller.migrate(migration) }
    }
    error?.let { FText(it, 12, c.danger) }
}
