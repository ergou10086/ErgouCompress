package org.ergoutree.compress.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.presentation.*

private enum class SettingsTab(val label: String, val glyph: FluentGlyph) {
    GENERAL("常规", FluentGlyph.Settings),
    DEFAULTS("默认任务", FluentGlyph.Lightning),
    STORAGE("存储与清理", FluentGlyph.OpenFolder),
    LOGS("日志与快捷键", FluentGlyph.List),
}

/**
 * 设置窗口采用 Win11 Settings 的“左分类、右卡片”结构。
 * 外观即时生效；其余偏好通过底部保存按钮一次写入，已有任务快照不受影响。
 */
@Composable
fun SettingsPage(
    controller: WorkspaceController,
    pickFile: ((String) -> Unit) -> Unit,
    pickFolder: ((String) -> Unit) -> Unit,
    saveSupport: ((String) -> Unit) -> Unit,
    close: () -> Unit,
) {
    val s by controller.state.collectAsState()
    val c = LocalFluentColors.current
    var tab by remember { mutableStateOf(SettingsTab.GENERAL) }
    var preferences by remember(s.preferences) { mutableStateOf(s.preferences) }
    var defaults by remember(s.preferences) { mutableStateOf(TaskDraft.from(s.preferences)) }
    var depth by remember(s.preferences) { mutableStateOf(s.preferences.scanDepth?.toString() ?: "无限") }
    var minimum by remember(s.preferences) { mutableStateOf((s.preferences.minimumBytes / 1_000_000.0).toString()) }
    var buffer by remember(s.preferences) { mutableStateOf(s.preferences.logBufferLines.toString()) }
    var localError by remember { mutableStateOf<String?>(null) }
    var cleanupConfirmation by remember { mutableStateOf(false) }
    var migrationTarget by remember { mutableStateOf<String?>(null) }
    val enabled = !s.closing && !s.restartRequired && !s.maintenanceBusy
    val draftSettings = runCatching { defaults.settings() }.getOrNull()
    val computed = if (draftSettings != null) {
        runCatching {
            val scanDepth = if (depth.trim() == "无限") null else depth.toIntOrNull() ?: error("扫描深度应为 0–100 或“无限”")
            val minimumMb = minimum.toDoubleOrNull() ?: error("最小大小需要数字")
            require(minimumMb.isFinite() && minimumMb >= 0 && minimumMb <= Long.MAX_VALUE / 1_000_000.0) { "最小大小超出范围" }
            preferences.copy(
                scanDepth = scanDepth,
                minimumBytes = (minimumMb * 1_000_000).toLong(),
                defaults = draftSettings,
                outputDirectory = defaults.output,
                outputMode = defaults.mode,
                onlyIfSmaller = defaults.onlyIfSmaller,
                logBufferLines = buffer.toIntOrNull() ?: error("日志行数需要整数"),
            )
        }.getOrNull()
    } else null
    val dirty = computed != null && computed != s.preferences

    Box(Modifier.fillMaxSize().background(c.base)) {
        Column(Modifier.fillMaxSize()) {
            FPageHeader("设置", "偏好只影响新任务；已冻结任务保留创建时的参数快照", onBack = close)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(210.dp).fillMaxHeight().padding(horizontal = 12.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SettingsTab.entries.forEach { item ->
                        FNavigationItem(item.glyph, item.label, item == tab, { tab = item })
                    }
                    Spacer(Modifier.height(12.dp))
                    FDivider()
                    Spacer(Modifier.height(12.dp))
                    FInlineNote("媒体引擎与路径写入本机数据目录，不上传任何文件或路径。", FluentTone.Neutral)
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider))
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (tab) {
                        SettingsTab.GENERAL -> GeneralSettings(controller, s, preferences, enabled,
                            onChange = { preferences = it },
                            onAppearance = { theme, reduce ->
                                val next = preferences.copy(theme = theme, reduceMotion = reduce)
                                preferences = next
                                controller.savePreferences(next)
                            },
                            depth = depth, onDepth = { depth = it },
                            minimum = minimum, onMinimum = { minimum = it },
                            pickFile = pickFile,
                        )
                        SettingsTab.DEFAULTS -> DefaultSettings(controller, s, defaults, enabled, pickFolder) { defaults = it }
                        SettingsTab.STORAGE -> StorageSettings(controller, s, enabled, pickFolder, saveSupport,
                            onCleanup = { cleanupConfirmation = true }, onMigrate = { path -> migrationTarget = path })
                        SettingsTab.LOGS -> LogSettings(preferences, buffer, enabled, onChange = { preferences = it }, onBuffer = { buffer = it })
                    }
                    localError?.let { FInfoBar(it, title = "设置无法保存", tone = FluentTone.Critical) }
                    s.error?.let { FInfoBar(it, title = "操作未完成", tone = FluentTone.Critical, onClose = controller::dismissError) }
                    Spacer(Modifier.height(8.dp))
                }
            }
            FDivider()
            Row(
                Modifier.fillMaxWidth().background(c.card).padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    FText(if (s.restartRequired) "数据根已迁移，请重新启动工具" else if (dirty) "有未保存的更改" else s.message,
                        type = FType.Caption, color = if (dirty) c.caution else c.textSecondary, maxLines = 1)
                    FText("外观设置即时生效；其他设置保存后用于下一次新建草稿。", type = FType.Caption, color = c.textTertiary, maxLines = 1)
                }
                FButton("还原更改", {
                    preferences = s.preferences
                    defaults = TaskDraft.from(s.preferences)
                    depth = s.preferences.scanDepth?.toString() ?: "无限"
                    minimum = (s.preferences.minimumBytes / 1_000_000.0).toString()
                    buffer = s.preferences.logBufferLines.toString()
                    localError = null
                }, enabled = enabled && dirty, style = FButtonStyle.Subtle)
                FButton("关闭", close)
                FButton("保存设置", {
                    try {
                        controller.savePreferences(requireNotNull(computed) { "请先修正无效的数值" })
                        localError = null
                    } catch (e: Exception) {
                        localError = e.message ?: "设置无效"
                    }
                }, enabled = enabled && dirty && computed != null, style = FButtonStyle.Accent, glyph = FluentGlyph.Save)
            }
        }
        if (cleanupConfirmation) {
            s.cleanup?.let { preview ->
                FContentDialog(
                    title = if (preview.options.originalBackups) "清理原始备份？" else "执行清理？",
                    message = if (preview.options.originalBackups)
                        "本次预览包含原始备份。删除后对应文件将失去恢复能力，且会重新核对文件证据。"
                    else "将删除预览中标记为可清理的 ${preview.decisions.count { it.eligible }} 项；执行时会重新检查状态与引用。",
                    primaryLabel = "执行清理",
                    onPrimary = { cleanupConfirmation = false; controller.executeCleanup(true) },
                    secondaryLabel = "取消",
                    onSecondary = { cleanupConfirmation = false },
                    destructive = preview.options.originalBackups,
                )
            }
        }
        migrationTarget?.let { target ->
            FContentDialog(
                title = "迁移数据目录？",
                message = "将把本地档案迁移到：\n$target\n\n迁移会核验文件并停止后续写入。完成后需要关闭并重新启动工具。处理任务期间不可迁移。",
                primaryLabel = "开始迁移",
                onPrimary = { migrationTarget = null; controller.migrate(target) },
                secondaryLabel = "取消",
                onSecondary = { migrationTarget = null },
                destructive = true,
            )
        }
    }
}

@Composable
private fun GeneralSettings(
    controller: WorkspaceController,
    s: WorkspaceState,
    preferences: DesktopPreferences,
    enabled: Boolean,
    onChange: (DesktopPreferences) -> Unit,
    onAppearance: (AppTheme, Boolean) -> Unit,
    depth: String,
    onDepth: (String) -> Unit,
    minimum: String,
    onMinimum: (String) -> Unit,
    pickFile: ((String) -> Unit) -> Unit,
) {
    val c = LocalFluentColors.current
    val toolsEnabled = enabled && !s.intakeBusy && s.active == null
    FCard {
        FSectionTitle("外观", "主题更改会立即应用到所有窗口并保存。")
        FChoiceCards(
            listOf(
                FChoice(AppTheme.SYSTEM, "跟随系统", trailing = null),
                FChoice(AppTheme.LIGHT, "浅色", trailing = null),
                FChoice(AppTheme.DARK, "深色", trailing = null),
            ),
            preferences.theme,
            { onAppearance(it, preferences.reduceMotion) },
            enabled = enabled, horizontal = true,
        )
        FSettingRow("减少动画", "关闭切换、进度与提示的过渡动画，保留必要的状态反馈。") {
            FSwitch(preferences.reduceMotion, { onAppearance(preferences.theme, it) }, enabled = enabled)
        }
    }
    FCard {
        FSectionTitle("媒体引擎", "首次使用必须选择你自己的 FFmpeg；自检会实际生成短片并验证 CPU / NVIDIA / AAC。")
        FTextField(
            value = s.ffmpeg,
            onValueChange = { controller.editTools(it, s.ffprobe) },
            label = "FFmpeg 路径",
            placeholder = "例如 D:\\tools\\ffmpeg\\bin\\ffmpeg.exe",
            leading = FluentGlyph.File,
            enabled = toolsEnabled,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FButton("选择 ffmpeg.exe", { pickFile { controller.editTools(it, s.ffprobe) } },
                glyph = FluentGlyph.OpenFile, enabled = toolsEnabled)
            FButton("选择 ffprobe.exe", { pickFile { controller.editTools(s.ffmpeg, it) } },
                glyph = FluentGlyph.OpenFile, enabled = toolsEnabled)
        }
        FTextField(
            value = s.ffprobe,
            onValueChange = { controller.editTools(s.ffmpeg, it) },
            label = "ffprobe 路径",
            description = "留空时使用 FFmpeg 同目录的 ffprobe。",
            placeholder = "留空自动查找",
            leading = FluentGlyph.File,
            enabled = toolsEnabled,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FButton(if (s.toolBusy) "正在实际编码自检…" else "验证并保存工具", controller::validateTools,
                style = FButtonStyle.Accent, enabled = toolsEnabled && s.ffmpeg.isNotBlank())
            if (s.toolBusy) { FSpinner(14.dp); FProgressBar(null, Modifier.width(180.dp), label = "工具自检进度") }
            if (s.tools != null) FBadge("工具链已验证", FluentTone.Success)
        }
        s.tools?.let { tools ->
            FDivider()
            FInlineNote("FFmpeg ${tools.ffmpeg.version} · ${tools.buildConfiguration}", FluentTone.Success)
            tools.checks.forEach { check ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FStatusDot(if (check.available) FluentTone.Success else FluentTone.Critical, 7.dp)
                    FText("${backendLabel(check.backend)} · ${if (check.available) "可用" else "不可用"}",
                        type = FType.Caption, modifier = Modifier.width(150.dp), maxLines = 1)
                    FText(check.diagnostic, type = FType.Caption, color = c.textSecondary, maxLines = 2)
                }
            }
            FInlineNote(if (tools.aacAvailable) "AAC 编码可用。" else "AAC 编码不可用；需要 AAC 的政策会失败。",
                if (tools.aacAvailable) FluentTone.Neutral else FluentTone.Caution)
        }
    }
    FCard {
        FSectionTitle("导入与扫描", "扫描范围与阈值只影响导入列表；低于阈值的文件仍显示但默认不选。")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FTextField(
                value = depth,
                onValueChange = onDepth,
                label = "扫描深度",
                description = "0 仅根目录；输入“无限”遍历所有层级。",
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
            FTextField(
                value = minimum,
                onValueChange = onMinimum,
                label = "最小文件大小（MB）",
                description = "1 MB = 1,000,000 字节。",
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
        }
    }
    FCard(subtle = true) {
        FInlineNote("工具路径与验证结果保存在本机数据目录；扫描不会读取媒体正文，只探测元数据。", FluentTone.Neutral)
    }
}

@Composable
private fun DefaultSettings(
    controller: WorkspaceController,
    s: WorkspaceState,
    defaults: TaskDraft,
    enabled: Boolean,
    pickFolder: ((String) -> Unit) -> Unit,
    onChange: (TaskDraft) -> Unit,
) {
    val c = LocalFluentColors.current
    FCard {
        FSectionTitle("新任务的起点", "保存后用于下一次新建草稿；不会修改正在运行或已冻结的任务。")
        EncodingEditor(defaults, enabled, s.tools, onChange)
    }
    FCard {
        FSectionTitle("默认输出与保护")
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FTextField(
                value = defaults.output,
                onValueChange = { value -> onChange(defaults.copy(output = value)) },
                label = "默认副本目录",
                placeholder = "选择已存在的文件夹",
                modifier = Modifier.weight(1f),
                leading = FluentGlyph.Folder,
                enabled = enabled,
            )
            FButton("浏览…", { pickFolder { path -> onChange(defaults.copy(output = path)) } },
                glyph = FluentGlyph.OpenFolder, enabled = enabled)
        }
        FChoiceCards(
            listOf(
                FChoice(OutputMode.COPY, "保存副本", "默认推荐；原文件保持不动。"),
                FChoice(OutputMode.REPLACE, "原地替换", "需要登记工作区并确认备份流程。"),
                FChoice(OutputMode.CHANGE_EXTENSION, "原地改扩展名", "需要登记工作区。"),
            ),
            defaults.mode,
            { value -> onChange(defaults.copy(mode = value, acknowledgeInPlace = false)) },
            enabled = enabled,
        )
        FSettingRow("仅在输出更小时发布", "结果更大时跳过，避免无意增加体积。") {
            FSwitch(defaults.onlyIfSmaller, { onChange(defaults.copy(onlyIfSmaller = it)) }, enabled = enabled)
        }
        FSettingDivider()
        FSettingRow("保留元数据", "文件与流标签默认保留。") {
            FSwitch(defaults.preserveMetadata, { onChange(defaults.copy(preserveMetadata = it)) }, enabled = enabled)
        }
        FSettingDivider()
        FSettingRow("保留章节", "章节默认保留。") {
            FSwitch(defaults.preserveChapters, { onChange(defaults.copy(preserveChapters = it)) }, enabled = enabled)
        }
    }
    if (defaults.mode != OutputMode.COPY) {
        FInfoBar("默认使用原位处理时，每个源卷都需要在“存储与清理”中登记独立工作区。", tone = FluentTone.Caution)
    }
    FCard(subtle = true) {
        FInlineNote("默认参数只用于新草稿；主窗口的“处理选项”可以按本次任务覆盖。", FluentTone.Neutral)
    }
}

@Composable
private fun StorageSettings(
    controller: WorkspaceController,
    s: WorkspaceState,
    enabled: Boolean,
    pickFolder: ((String) -> Unit) -> Unit,
    saveSupport: ((String) -> Unit) -> Unit,
    onCleanup: () -> Unit,
    onMigrate: (String) -> Unit,
) {
    val c = LocalFluentColors.current
    var cacheDays by remember(s.retention) { mutableStateOf(s.retention.cacheDays.toString()) }
    var logDays by remember(s.retention) { mutableStateOf(s.retention.logDays.toString()) }
    var backupDays by remember(s.retention) { mutableStateOf(s.retention.minimumBackupDays.toString()) }
    var options by remember { mutableStateOf(CleanupOptions()) }
    var acknowledge by remember(s.cleanup?.id) { mutableStateOf(false) }
    var workspace by remember { mutableStateOf("") }
    var migration by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { controller.refreshStorage() }
    val busy = s.maintenanceBusy || s.busy

    FCard {
        FCardHeader("本地档案与空间", "逻辑占用与实际磁盘回收分别统计；删除备份会失去对应恢复能力。") {
            FButton("刷新统计", controller::refreshStorage, style = FButtonStyle.Subtle, glyph = FluentGlyph.Refresh, enabled = !busy)
            FButton("导出诊断包…", { saveSupport { controller.exportSupport(it) } },
                style = FButtonStyle.Subtle, glyph = FluentGlyph.Save, enabled = !busy)
        }
        FTextField(
            value = controller.workflow.dataRoot,
            onValueChange = {},
            label = "数据根",
            modifier = Modifier.fillMaxWidth(),
            enabled = false,
        )
        if (s.maintenanceBusy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FSpinner(14.dp); FText("正在读取或更新存储状态…", type = FType.Caption, color = c.textSecondary)
        }
        s.storage?.let { report ->
            report.volumes.forEach { volume ->
                FDivider()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FIcon(FluentGlyph.OpenFolder, 16.dp, c.textSecondary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        FText(volume.volume.root.value, type = FType.BodyStrong, maxLines = 1)
                        FText("${volume.volume.fileSystem} · ${if (volume.volume.local) "本地卷" else "网络或可移动卷"}",
                            type = FType.Caption, color = c.textTertiary)
                    }
                    StorageStat("可用", bytes(volume.usableBytes))
                    StorageStat("受管占用", bytes(volume.managedLogicalBytes))
                    StorageStat("备份", bytes(volume.backupBytesRetained))
                    StorageStat("临时", bytes(volume.temporaryBytes))
                }
            }
            FInlineNote(report.explanation, FluentTone.Neutral)
        } ?: FInlineNote("打开此页后会自动读取空间统计。", FluentTone.Neutral)
    }

    FCard {
        FSectionTitle("保留策略", "自动保留只覆盖可再生的缓存与日志；原始备份没有自动删除开关。")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            FTextField(cacheDays, { cacheDays = it.filter(Char::isDigit).take(4) }, label = "缓存保留天数", modifier = Modifier.weight(1f), enabled = enabled)
            FTextField(logDays, { logDays = it.filter(Char::isDigit).take(4) }, label = "日志保留天数", modifier = Modifier.weight(1f), enabled = enabled)
            FTextField(backupDays, { backupDays = it.filter(Char::isDigit).take(4) }, label = "备份最少保留天数", modifier = Modifier.weight(1f), enabled = enabled)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FButton("保存保留策略", {
                try {
                    controller.saveRetention(s.retention.copy(cacheDays = cacheDays.toInt(), logDays = logDays.toInt(),
                        minimumBackupDays = backupDays.toInt()))
                    error = null
                } catch (e: Exception) { error = "保留天数需要 0–3650 的整数" }
            }, enabled = !busy, style = FButtonStyle.Accent, glyph = FluentGlyph.Save)
            FText("缓存 ${s.retention.cacheBudgetBytes / 1_048_576} MiB 预算", type = FType.Caption, color = c.textSecondary)
        }
        error?.let { FInlineNote(it, FluentTone.Critical) }
    }

    FCard {
        FSectionTitle("清理预览", "先预览再确认；执行时会重新核对所有文件证据与引用。")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FCheck("过期缓存", options.caches, enabled = !busy) { options = options.copy(caches = it) }
            FCheck("过期日志", options.logs, enabled = !busy) { options = options.copy(logs = it) }
            FCheck("失去引用的候选文件", options.candidates, enabled = !busy) { options = options.copy(candidates = it) }
            FCheck("清空可再生缓存（忽略年龄）", options.clearCacheNow, enabled = !busy) { options = options.copy(clearCacheNow = it) }
            FCheck("包含满足保留条件的原始备份", options.originalBackups, enabled = !busy) { options = options.copy(originalBackups = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FButton("预览清理", { controller.previewCleanup(options) }, enabled = !busy, glyph = FluentGlyph.Search)
            if (s.cleanup != null) FButton("重新预览", { controller.previewCleanup(options) }, enabled = !busy, style = FButtonStyle.Subtle)
        }
        s.cleanup?.let { preview ->
            FDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FBadge("可清理 ${preview.decisions.count { it.eligible }} 项", FluentTone.Success)
                FBadge("受保护 ${preview.decisions.count { !it.eligible }} 项", FluentTone.Neutral)
                FText("预览 ${preview.id.take(8)}", type = FType.Caption, color = c.textTertiary)
            }
            preview.decisions.take(30).forEach { decision ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FBadge(if (decision.eligible) "可清理" else "受保护",
                        if (decision.eligible) FluentTone.Success else FluentTone.Neutral)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        FText(decision.artifact.relativePath, type = FType.Caption, maxLines = 1)
                        FText(decision.reason, type = FType.Caption, color = c.textTertiary, maxLines = 2)
                    }
                }
            }
            if (preview.decisions.size > 30) FText("共 ${preview.decisions.size} 项；执行时会重新检查全部文件。",
                type = FType.Caption, color = c.textSecondary)
            if (preview.options.originalBackups) {
                FInfoBar("此次预览包含原始备份。删除后无法从备份恢复对应文件。", tone = FluentTone.Caution)
            }
            FSwitch(acknowledge, { acknowledge = it }, enabled = !busy,
                label = if (preview.options.originalBackups) "确认清理并接受失去备份恢复能力" else "确认执行本次预览中的清理")
            FButton("执行清理", onCleanup, enabled = !busy && acknowledge && preview.decisions.any { it.eligible },
                style = FButtonStyle.Critical, glyph = FluentGlyph.Delete)
        }
    }

    FCard {
        FSectionTitle("原地处理工作区", "每个源文件卷登记独立目录，用于提交日志、暂存与原始备份。")
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FTextField(workspace, { workspace = it }, label = "工作区目录", placeholder = "选择该卷下的独立目录",
                modifier = Modifier.weight(1f), enabled = !busy, leading = FluentGlyph.Folder)
            FButton("选择…", { pickFolder { workspace = it } }, glyph = FluentGlyph.OpenFolder, enabled = !busy)
            FButton("登记工作区", { controller.registerWorkspace(workspace) }, enabled = !busy && workspace.isNotBlank(),
                style = FButtonStyle.Accent)
        }
    }

    FCard {
        FSectionTitle("迁移数据目录", "选择空目录。迁移会核验文件并停止后续写入，完成后需要重启。")
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FTextField(migration, { migration = it }, label = "新的数据目录", placeholder = "选择一个空目录",
                modifier = Modifier.weight(1f), enabled = !busy, leading = FluentGlyph.Folder)
            FButton("选择…", { pickFolder { migration = it } }, glyph = FluentGlyph.OpenFolder, enabled = !busy)
            FButton("开始迁移", { onMigrate(migration) }, enabled = !busy && migration.isNotBlank(), style = FButtonStyle.Critical)
        }
        FInlineNote("迁移成功后当前会话停止写入，必须重启工具。", FluentTone.Caution)
    }
}

@Composable
private fun StorageStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        FText(label, type = FType.Caption, color = LocalFluentColors.current.textTertiary, maxLines = 1)
        FText(value, type = FType.Caption, maxLines = 1)
    }
}

@Composable
private fun LogSettings(
    preferences: DesktopPreferences,
    buffer: String,
    enabled: Boolean,
    onChange: (DesktopPreferences) -> Unit,
    onBuffer: (String) -> Unit,
) {
    val c = LocalFluentColors.current
    FCard {
        FSectionTitle("日志显示与采集", "显示级别只筛选窗口内容；任务状态事件仍会持久记录。")
        FSettingRow("默认显示级别", "打开任务日志时使用的初始筛选。") {
            FComboBox(preferences.logLevel,
                listOf(LogLevel.DEBUG to "调试及以上", LogLevel.INFO to "信息及以上", LogLevel.WARNING to "警告及以上", LogLevel.ERROR to "仅错误"),
                { onChange(preferences.copy(logLevel = it)) }, enabled = enabled, menuWidth = 160.dp, contentDescription = "默认日志级别")
        }
        FSettingDivider()
        FSettingRow("采集 FFmpeg 详细日志", "会增加日志体积；完整日志仍受保留策略约束。") {
            FSwitch(preferences.ffmpegVerbose, { onChange(preferences.copy(ffmpegVerbose = it)) }, enabled = enabled)
        }
        FSettingDivider()
        FSettingRow("窗口最多保留行数", "200–5000 行；完整日志保存在任务档案中。") {
            FTextField(buffer, { value -> onBuffer(value.filter(Char::isDigit).take(4)) },
                modifier = Modifier.width(110.dp), enabled = enabled)
        }
    }
    FCard {
        FSectionTitle("快捷键")
        ShortcutRow("添加文件", "Ctrl", "O")
        ShortcutRow("添加文件夹", "Ctrl", "Shift", "O")
        ShortcutRow("打开设置", "Ctrl", ",")
        ShortcutRow("打开历史", "Ctrl", "H")
        ShortcutRow("全选可处理项", "Ctrl", "A")
        ShortcutRow("关闭提示或对话框", "Esc")
    }
    FCard(subtle = true) {
        FInlineNote("日志保留天数与清理预览在“存储与清理”中设置。诊断包会对路径等信息脱敏。", FluentTone.Neutral)
    }
}

@Composable
private fun ShortcutRow(label: String, vararg keys: String) {
    val c = LocalFluentColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        FText(label, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            keys.forEachIndexed { index, key ->
                if (index > 0) FText("+", type = FType.Caption, color = c.textTertiary)
                FKeyHint(key)
            }
        }
    }
}
