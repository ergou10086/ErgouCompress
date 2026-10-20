package org.ergoutree.compress.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.presentation.*
import kotlin.math.roundToInt

/** 窗口路由只表达页面意图，桌面宿主负责独立窗口的生命周期。 */
enum class WorkspacePage { OPTIONS, SETTINGS, HISTORY, ABOUT }

private enum class ReviewFilter(val label: String) {
    ALL("全部"),
    READY("可处理"),
    REVIEW("需审核"),
    UNSELECTED("未选"),
}

private val SizeColumn = 92.dp
private val EstimateColumn = 138.dp
private val StatusColumn = 164.dp
private val RowActionColumn = 32.dp

/**
 * 无侧栏主工作台：顶部命令栏、拖入自动扫描、实时任务卡、审核列表与固定底部操作区。
 * 主界面直接使用当前参数开始任务，处理选项窗口只承担完整审核与逐轨调整。
 */
@Composable
fun WorkspaceApp(
    controller: WorkspaceController,
    importModifier: Modifier = Modifier,
    dragging: Boolean = false,
    openPage: (WorkspacePage) -> Unit = {},
    openLogs: (String) -> Unit = {},
    pickFiles: () -> Unit = {},
    pickFolder: () -> Unit = {},
    reveal: (String) -> Unit = {},
    formatTime: (Long) -> String = { it.toString() },
) {
    val s by controller.state.collectAsState()
    val c = LocalFluentColors.current
    var search by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(ReviewFilter.ALL) }
    var detailPath by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        controller.initialize()
        delay(60)
        runCatching { rootFocus.requestFocus() }
    }
    // 运行中的任务每秒刷新队列与详情；只在存在活动任务时轮询。
    LaunchedEffect(s.active?.id) {
        while (controller.state.value.active != null) { controller.refresh(); delay(1000) }
    }

    val conflict = s.selected.firstNotNullOfOrNull { it.rejection(s.draft) }
    val outputMissing = s.draft.mode == OutputMode.COPY && s.draft.output.isBlank()
    val canStart = s.canStart && conflict == null && !outputMissing &&
        (s.draft.mode == OutputMode.COPY || s.draft.acknowledgeInPlace)
    val filteredRows = s.rows.filter { row ->
        val matches = search.isBlank() || row.media.file.path.value.contains(search, true) || fileName(row.media.file.path.value).contains(search, true)
        val rejection = row.rejection(s.draft)
        matches && when (filter) {
            ReviewFilter.ALL -> true
            ReviewFilter.READY -> rejection == null
            ReviewFilter.REVIEW -> rejection != null
            ReviewFilter.UNSELECTED -> !row.selected
        }
    }
    val selectedOriginal = s.selected.sumOf { it.media.file.sizeBytes }
    val selectedEstimates = s.selected.map { it.estimate(s.draft).expectedBytes }
    val estimatedCount = selectedEstimates.count { it != null }
    val selectedEstimate = selectedEstimates.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
    val selectedEligible = s.rows.count { it.media.eligible }
    val triState: Boolean? = when {
        s.selected.isEmpty() -> false
        selectedEligible > 0 && s.selected.size >= selectedEligible -> true
        else -> null
    }

    Box(Modifier.fillMaxSize().background(c.base)) {
        Column(
            Modifier.fillMaxSize().focusRequester(rootFocus).focusable()
                .onKeyEvent { event -> handleShortcut(event, s, controller, pickFiles, pickFolder, openPage) },
        ) {
            CommandBar(s, openPage)
            FDivider()
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp).padding(top = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                s.error?.let { error ->
                    FInfoBar(error, title = "操作未完成", tone = FluentTone.Critical, onClose = controller::dismissError)
                }
                if (s.restartRequired) {
                    FInfoBar("数据根已迁移，请关闭并重新启动工具；当前会话不会继续写入旧目录。", tone = FluentTone.Caution)
                }
                if (s.tools == null && !s.startupBusy) {
                    FInfoBar(
                        "选择本机 ffmpeg.exe 并完成实际编码自检后，即可导入素材。",
                        title = "先连接 FFmpeg",
                        tone = FluentTone.Accent,
                        actionLabel = "打开设置",
                        onAction = { openPage(WorkspacePage.SETTINGS) },
                    )
                }
                if (!s.scanComplete && s.rows.isNotEmpty() && !s.scanning) {
                    FInfoBar(
                        "扫描没有完整结束，部分目录可能不可读或已停止。重新扫描后再创建任务。",
                        title = "扫描未完成",
                        tone = FluentTone.Caution,
                        actionLabel = "重新扫描",
                        onAction = { controller.rescan() },
                    )
                }
                s.currentJob?.let { TaskStrip(controller, s, openLogs, history = { openPage(WorkspacePage.HISTORY) }) }

                if (s.rows.isEmpty() && s.jobs.isEmpty()) {
                    EmptyHome(s, importing = dragging, pickFiles = pickFiles, pickFolder = pickFolder,
                        stopScan = controller::stopScan, modifier = Modifier.weight(1f))
                } else {
                    if (s.rows.isNotEmpty()) {
                        ReviewHeader(
                            s = s,
                            summary = buildString {
                                append("${s.rows.size} 个文件")
                                append(" · 已选 ${s.selected.size} 个")
                                append(" · 原始 ${bytes(selectedOriginal)}")
                                if (selectedEstimate != null) {
                                    append(if (estimatedCount == s.selected.size) " · 预计 ${bytes(selectedEstimate)}"
                                    else " · 已估算 ${bytes(selectedEstimate)}（$estimatedCount/${s.selected.size}）")
                                }
                            },
                            onEstimate = controller::estimateSelected,
                            onStopEstimate = controller::stopScan,
                            pickFiles = pickFiles,
                            pickFolder = pickFolder,
                        )
                        if (s.estimating) EstimateProgress(s, controller::stopScan)
                        if (outputMissing) {
                            FInfoBar("副本输出目录尚未设置，开始处理前需要选择一个已存在的目录。", tone = FluentTone.Caution,
                                actionLabel = "设置输出位置", onAction = { openPage(WorkspacePage.OPTIONS) })
                        }
                        if (conflict != null) {
                            val count = s.selected.count { it.rejection(s.draft) != null }
                            FInfoBar("$count 个选中文件需要审核：$conflict", title = "参数与媒体冲突", tone = FluentTone.Caution,
                                actionLabel = "处理选项", onAction = { openPage(WorkspacePage.OPTIONS) })
                        }
                        ReviewCard(
                            s = s,
                            rows = filteredRows,
                            search = search,
                            onSearch = { search = it },
                            filter = filter,
                            onFilter = { filter = it },
                            triState = triState,
                            onSelectAll = controller::selectAll,
                            onToggle = controller::toggle,
                            onDetail = { detailPath = it },
                            onReveal = reveal,
                            onRescan = controller::rescan,
                            onClear = { confirmClear = true },
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        CompactImport(s, pickFiles, pickFolder, Modifier.fillMaxWidth())
                        RecentJobs(s, openLogs = openLogs, history = { openPage(WorkspacePage.HISTORY) },
                            formatTime = formatTime, modifier = Modifier.weight(1f))
                    }
                }
            }
            StatusBar(
                s = s,
                conflict = conflict,
                outputMissing = outputMissing,
                canStart = canStart,
                onOptions = { openPage(WorkspacePage.OPTIONS) },
                onStart = controller::startTask,
            )
        }

        detailPath?.let { path ->
            s.rows.firstOrNull { it.media.file.path.value == path }?.let { row ->
                FileDetailDialog(
                    row = row,
                    draft = s.draft,
                    onClose = { detailPath = null },
                    onReveal = { reveal(path); detailPath = null },
                )
            }
        }
        if (confirmClear) {
            FContentDialog(
                title = "清空本次任务？",
                message = "已扫描的 ${s.rows.size} 个文件会从列表中移除，原始文件不会被修改。",
                primaryLabel = "清空列表",
                onPrimary = { confirmClear = false; controller.clearDraft() },
                secondaryLabel = "取消",
                onSecondary = { confirmClear = false },
                destructive = true,
            )
        }
    }
}

private fun handleShortcut(
    event: KeyEvent,
    s: WorkspaceState,
    controller: WorkspaceController,
    pickFiles: () -> Unit,
    pickFolder: () -> Unit,
    openPage: (WorkspacePage) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return false
    return when (event.key) {
        Key.O -> { if (event.isShiftPressed) pickFolder() else pickFiles(); true }
        Key.Comma -> { openPage(WorkspacePage.SETTINGS); true }
        Key.H -> { openPage(WorkspacePage.HISTORY); true }
        Key.A -> { if (!s.intakeBusy) controller.selectAll(true); true }
        else -> false
    }
}

@Composable
private fun CommandBar(s: WorkspaceState, openPage: (WorkspacePage) -> Unit) {
    val c = LocalFluentColors.current
    val (toolLabel, toolTone) = when {
        s.startupBusy || s.toolBusy -> "正在验证工具" to FluentTone.Accent
        s.tools != null -> "工具已就绪" to FluentTone.Success
        s.ffmpeg.isBlank() -> "未配置 FFmpeg" to FluentTone.Caution
        else -> "待验证工具" to FluentTone.Caution
    }
    val pending = s.jobs.count { it.state in setOf(JobState.READY, JobState.RUNNING, JobState.PAUSED, JobState.PAUSE_REQUESTED) }
    Row(
        Modifier.fillMaxWidth().height(52.dp).background(c.base).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FAppMark(22.dp)
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            FText("ErgouCompress", type = FType.BodyStrong, maxLines = 1)
            FText("本地批量媒体压缩", type = FType.Caption, color = c.textTertiary, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        FStatusChip(toolLabel, toolTone, pulsing = s.toolBusy || s.startupBusy)
        FVerticalDivider(height = 20.dp)
        FCommandButton(FluentGlyph.Settings, "设置", onClick = { openPage(WorkspacePage.SETTINGS) })
        FCommandButton(FluentGlyph.History, "历史", onClick = { openPage(WorkspacePage.HISTORY) }, badge = pending.takeIf { it > 0 }?.toString())
        FCommandButton(FluentGlyph.Info, "关于", onClick = { openPage(WorkspacePage.ABOUT) })
    }
}

@Composable
private fun EmptyHome(
    s: WorkspaceState,
    importing: Boolean,
    pickFiles: () -> Unit,
    pickFolder: () -> Unit,
    stopScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalFluentColors.current
    val busy = s.scanning || s.toolBusy || s.startupBusy
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(FluentTokens.cardRadius)).background(c.card)
            .border(if (importing) 2.dp else 1.dp, if (importing) c.accent else c.stroke, RoundedCornerShape(FluentTokens.cardRadius)),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.widthIn(max = 460.dp)) {
                FSpinner(24.dp)
                FText(if (s.startupBusy) "正在恢复任务档案…" else if (s.toolBusy) "正在实际编码自检…" else "正在扫描与探测媒体…",
                    type = FType.BodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (s.scanning) {
                    FText("已发现 ${s.scannedFiles} 个文件 · ${bytes(s.scannedBytes)}", type = FType.Caption,
                        color = c.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    FProgressBar(null, Modifier.fillMaxWidth(), label = "扫描进度")
                    FText(s.scanningPath.ifBlank { s.message }, type = FType.Caption, color = c.textTertiary,
                        maxLines = 1, modifier = Modifier.fillMaxWidth())
                    FButton("停止扫描", stopScan, style = FButtonStyle.Subtle)
                } else {
                    FText(s.message, type = FType.Caption, color = c.textSecondary, maxLines = 2,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FEmptyState(
                    FluentGlyph.Folder,
                    if (importing) "松开以添加到本次任务" else "将文件或文件夹拖到此窗口",
                    "导入后自动扫描和探测媒体。默认保存副本，原文件不会被修改。",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FButton("添加文件", pickFiles, style = FButtonStyle.Accent, glyph = FluentGlyph.Add)
                        FButton("添加文件夹", pickFolder, glyph = FluentGlyph.OpenFolder)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FKeyHint("Ctrl")
                    FText("+", type = FType.Caption, color = c.textTertiary)
                    FKeyHint("O")
                    FText("添加文件", type = FType.Caption, color = c.textTertiary)
                    Spacer(Modifier.width(10.dp))
                    FBadge("副本优先", FluentTone.Neutral)
                    FBadge("完整校验", FluentTone.Neutral)
                    FBadge("本地处理", FluentTone.Neutral)
                }
            }
        }
    }
}

@Composable
private fun ReviewHeader(
    s: WorkspaceState,
    summary: String,
    onEstimate: () -> Unit,
    onStopEstimate: () -> Unit,
    pickFiles: () -> Unit,
    pickFolder: () -> Unit,
) {
    val c = LocalFluentColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FText("本次任务", type = FType.Subtitle)
                if (s.selected.isNotEmpty()) FBadge("已选 ${s.selected.size}", FluentTone.Accent)
            }
            FText(summary, type = FType.Caption, color = c.textSecondary, maxLines = 1)
        }
        if (s.estimating) {
            FSpinner(14.dp)
            FText("估算 ${s.estimateDone}/${s.estimateTotal}", type = FType.Caption, color = c.textSecondary)
            FButton("停止估算", onStopEstimate, style = FButtonStyle.Subtle)
        } else {
            FButton("估算体积", onEstimate, style = FButtonStyle.Subtle, glyph = FluentGlyph.Lightning,
                enabled = !s.intakeBusy && s.tools != null && s.selected.isNotEmpty(),
                tooltip = "采样推算输出体积；结果不是保证")
        }
        FButton("添加文件", pickFiles, glyph = FluentGlyph.Add, enabled = !s.closing && !s.restartRequired)
        FButton("添加文件夹", pickFolder, glyph = FluentGlyph.OpenFolder, enabled = !s.closing && !s.restartRequired)
    }
}

@Composable
private fun EstimateProgress(s: WorkspaceState, onStop: () -> Unit) {
    val fraction = if (s.estimateTotal > 0) s.estimateDone.toFloat() / s.estimateTotal else null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FProgressBar(fraction, Modifier.weight(1f), label = "体积估算进度")
        FText("${s.estimateDone} / ${s.estimateTotal}", type = FType.Caption, color = LocalFluentColors.current.textSecondary)
        FButton("取消", onStop, style = FButtonStyle.Subtle)
    }
}

@Composable
private fun ReviewCard(
    s: WorkspaceState,
    rows: List<WorkspaceRow>,
    search: String,
    onSearch: (String) -> Unit,
    filter: ReviewFilter,
    onFilter: (ReviewFilter) -> Unit,
    triState: Boolean?,
    onSelectAll: (Boolean) -> Unit,
    onToggle: (org.ergoutree.compress.domain.LocalPath) -> Unit,
    onDetail: (String) -> Unit,
    onReveal: (String) -> Unit,
    onRescan: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalFluentColors.current
    val listState = rememberLazyListState()
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(FluentTokens.cardRadius)).background(c.card)
        .border(1.dp, c.stroke, RoundedCornerShape(FluentTokens.cardRadius))) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FSearchBox(search, onSearch, "搜索文件名或路径", Modifier.width(230.dp), enabled = !s.intakeBusy)
            FSegmented(
                listOf(ReviewFilter.ALL to "全部", ReviewFilter.READY to "可处理", ReviewFilter.REVIEW to "需审核", ReviewFilter.UNSELECTED to "未选"),
                filter, onFilter, Modifier.width(400.dp),
            )
            Spacer(Modifier.weight(1f))
            FText("${rows.size} / ${s.rows.size} 项", type = FType.Caption, color = c.textSecondary)
            FMoreMenu(
                items = listOf(
                    FMenuItem("全选可用项", { onSelectAll(true) }, FluentGlyph.CheckMark, enabled = !s.intakeBusy),
                    FMenuItem("取消选择", { onSelectAll(false) }, FluentGlyph.Cancel, enabled = !s.intakeBusy),
                    FMenuItem("重新扫描", onRescan, FluentGlyph.Refresh, enabled = !s.intakeBusy),
                    FMenuItem("清空列表", onClear, FluentGlyph.Delete, enabled = !s.intakeBusy, destructive = true),
                ),
                contentDescription = "列表操作",
                enabled = !s.intakeBusy,
            )
        }
        FDivider()
        Row(
            Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FTriCheck("全选", triState, enabled = !s.intakeBusy, contentDescription = "全选可处理项", onChange = { onSelectAll(it) })
                FText("名称", type = FType.CaptionStrong, color = c.textSecondary)
            }
            FText("原始大小", type = FType.CaptionStrong, color = c.textSecondary, modifier = Modifier.width(SizeColumn))
            FText("预计输出", type = FType.CaptionStrong, color = c.textSecondary, modifier = Modifier.width(EstimateColumn))
            FText("状态", type = FType.CaptionStrong, color = c.textSecondary, modifier = Modifier.width(StatusColumn))
            Spacer(Modifier.width(RowActionColumn))
        }
        FDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (rows.isEmpty()) {
                FEmptyState(
                    FluentGlyph.Filter,
                    "没有匹配的文件",
                    if (s.rows.isEmpty()) "先添加素材，再审核参数。运行任务时也能继续导入。"
                    else "调整搜索或筛选条件，或清空筛选查看全部文件。",
                    Modifier.align(Alignment.Center),
                ) {
                    if (search.isNotBlank() || filter != ReviewFilter.ALL) {
                        FButton("清除筛选", { onSearch(""); onFilter(ReviewFilter.ALL) }, style = FButtonStyle.Subtle)
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize().padding(end = 12.dp), state = listState) {
                    items(rows, key = { it.media.file.path.value }) { row ->
                        ReviewRow(
                            s = s,
                            row = row,
                            onToggle = { onToggle(row.media.file.path) },
                            onDetail = { onDetail(row.media.file.path.value) },
                            onReveal = { onReveal(row.media.file.path.value) },
                        )
                        FDivider(Modifier.padding(start = 12.dp))
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun ReviewRow(
    s: WorkspaceState,
    row: WorkspaceRow,
    onToggle: () -> Unit,
    onDetail: () -> Unit,
    onReveal: () -> Unit,
) {
    val c = LocalFluentColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val reason = row.rejection(s.draft)
    val estimate = row.estimate(s.draft)
    val selectable = !s.intakeBusy && row.media.eligible
    Row(
        Modifier.fillMaxWidth().heightIn(min = 62.dp)
            .background(if (row.selected) c.selected else if (hovered) c.hover else androidx.compose.ui.graphics.Color.Transparent)
            .drawBehind {
                // Win11 列表的选中指示条：左侧 3px，垂直居中，不随行高拉伸。
                if (row.selected) {
                    val height = 28.dp.toPx()
                    drawRect(c.accent, topLeft = Offset(0f, (size.height - height) / 2f), size = Size(3.dp.toPx(), height))
                }
            }
            .hoverable(interaction)
            .clickable(enabled = selectable, role = Role.Checkbox) { onToggle() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FTriCheck("", row.selected, enabled = selectable,
                contentDescription = "选择 ${fileName(row.media.file.path.value)}", onChange = { onToggle() })
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (row.media.eligible) c.accentSubtle else c.baseSecondary),
                contentAlignment = Alignment.Center,
            ) {
                FIcon(FluentGlyph.Video, 16.dp, if (row.media.eligible) c.accent else c.textTertiary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                FText(fileName(row.media.file.path.value), type = FType.Body, maxLines = 1)
                FText(mediaSummary(row), type = FType.Caption, color = c.textSecondary, maxLines = 1)
                FText(parentPath(row.media.file.path.value), type = FType.Caption, color = c.textTertiary, maxLines = 1)
            }
        }
        FText(bytes(row.media.file.sizeBytes), type = FType.Caption, color = c.textSecondary, modifier = Modifier.width(SizeColumn), maxLines = 1)
        Column(Modifier.width(EstimateColumn), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            FText(if (estimate.expectedBytes == null) "待估算" else "约 ${bytes(estimate.expectedBytes)}", type = FType.Caption,
                color = if (estimate.expectedBytes == null) c.textTertiary else c.textPrimary, maxLines = 1)
            if (estimate.basis != EstimateBasis.UNKNOWN) FText(estimateBasisLabel(estimate.basis), type = FType.Caption, color = c.textTertiary, maxLines = 1)
            estimateRange(estimate)?.let { FText(it, type = FType.Caption, color = c.textTertiary, maxLines = 1) }
        }
        Column(Modifier.width(StatusColumn), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (reason == null) {
                FBadge(if (row.selected) "已选" else "可处理", if (row.selected) FluentTone.Accent else FluentTone.Success)
                FText(if (row.selected) "已加入本次任务" else "未选择", type = FType.Caption, color = c.textTertiary, maxLines = 1)
            } else {
                FBadge("需审核", FluentTone.Caution)
                FText(reason, type = FType.Caption, color = c.textSecondary, maxLines = 2)
            }
        }
        FMoreMenu(
            items = listOf(
                FMenuItem("查看详情", onDetail, FluentGlyph.Info),
                FMenuItem("在资源管理器中显示", onReveal, FluentGlyph.OpenFolder),
                FMenuItem(if (row.selected) "取消选择" else "选择此项", onToggle, FluentGlyph.CheckMark, enabled = selectable),
            ),
            contentDescription = "文件操作",
            enabled = !s.closing,
        )
    }
}

@Composable
private fun CompactImport(
    s: WorkspaceState,
    pickFiles: () -> Unit,
    pickFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalFluentColors.current
    FCard(modifier, padding = 16.dp, subtle = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(c.accentSubtle), contentAlignment = Alignment.Center) {
                FIcon(FluentGlyph.Folder, 18.dp, c.accent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                FText("继续添加文件", type = FType.BodyStrong)
                FText("运行任务时也可以导入下一批；当前任务的参数快照不会改变。", type = FType.Caption, color = c.textSecondary, maxLines = 1)
            }
            FButton("添加文件", pickFiles, style = FButtonStyle.Accent, glyph = FluentGlyph.Add, enabled = !s.closing)
            FButton("添加文件夹", pickFolder, glyph = FluentGlyph.OpenFolder, enabled = !s.closing)
        }
    }
}

@Composable
private fun RecentJobs(
    s: WorkspaceState,
    openLogs: (String) -> Unit,
    history: () -> Unit,
    formatTime: (Long) -> String,
    modifier: Modifier = Modifier,
) {
    val c = LocalFluentColors.current
    FCard(modifier, padding = 16.dp) {
        FCardHeader("最近任务", "完成后仍可在历史中查看日志、目标位置与备份恢复。") {
            FButton("查看全部", history, style = FButtonStyle.Subtle, glyph = FluentGlyph.History)
        }
        FDivider()
        s.jobs.take(4).forEach { job ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FStatusDot(jobTone(job.state), 7.dp, pulsing = job.state == JobState.RUNNING)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    FText("${job.snapshot.plans.size} 个文件 · ${jobLabel(job.state)}", type = FType.Body, maxLines = 1)
                    FText("${job.id.take(8)} · ${formatTime(job.createdAt)} · ${backendLabel(job.snapshot.plans.first().settings.backend)}",
                        type = FType.Caption, color = c.textSecondary, maxLines = 1)
                }
                FButton("日志", { openLogs(job.id) }, style = FButtonStyle.Subtle, glyph = FluentGlyph.List)
            }
            FDivider()
        }
    }
}

@Composable
private fun StatusBar(
    s: WorkspaceState,
    conflict: String?,
    outputMissing: Boolean,
    canStart: Boolean,
    onOptions: () -> Unit,
    onStart: () -> Unit,
) {
    val c = LocalFluentColors.current
    val selectedBytes = s.selected.sumOf { it.media.file.sizeBytes }
    Column(Modifier.fillMaxWidth().background(c.card)) {
        FDivider()
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s.scanning || s.estimating || s.toolBusy) FSpinner(14.dp)
            else FStatusDot(
                when {
                    s.error != null -> FluentTone.Critical
                    conflict != null || outputMissing -> FluentTone.Caution
                    s.tools != null -> FluentTone.Success
                    else -> FluentTone.Neutral
                },
                7.dp, pulsing = s.busy,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                FText(s.message, type = FType.Caption, color = c.textSecondary, maxLines = 1,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                FText(
                    buildList {
                        add("已选 ${s.selected.size} 个 · 原始 ${bytes(selectedBytes)}")
                        if (s.draft.mode == OutputMode.COPY) add(if (s.draft.output.isBlank()) "输出位置未设置" else "输出 ${s.draft.output}")
                        else add(modeLabel(s.draft.mode))
                    }.joinToString(" · "),
                    type = FType.Caption, color = if (outputMissing) c.caution else c.textTertiary, maxLines = 1,
                )
            }
            FButton("处理选项", onOptions, glyph = FluentGlyph.Settings,
                enabled = s.selected.isNotEmpty() && !s.intakeBusy && !s.closing, tooltip = "审核参数、输出位置与逐轨策略")
            FButton(
                if (s.active != null) "加入处理队列" else "开始处理",
                onStart,
                style = FButtonStyle.Accent,
                glyph = FluentGlyph.Play,
                enabled = canStart && !s.closing,
                tooltip = when {
                    s.selected.isEmpty() -> "先选择要处理的文件"
                    outputMissing -> "请先设置副本输出目录"
                    conflict != null -> "所选文件存在需要审核的参数冲突"
                    else -> null
                },
            )
        }
    }
}

@Composable
private fun FileDetailDialog(
    row: WorkspaceRow,
    draft: TaskDraft,
    onClose: () -> Unit,
    onReveal: () -> Unit,
) {
    val c = LocalFluentColors.current
    val media = row.media.media
    val estimate = row.estimate(draft)
    val reason = row.rejection(draft)
    FContentDialog(
        title = fileName(row.media.file.path.value),
        message = row.media.file.path.value,
        primaryLabel = "在资源管理器中显示",
        onPrimary = onReveal,
        secondaryLabel = "关闭",
        onSecondary = onClose,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            DetailStat("原始大小", bytes(row.media.file.sizeBytes))
            DetailStat("时长", duration(media?.durationUs))
            DetailStat("预计输出", bytesOrPending(estimate.expectedBytes))
        }
        FDivider()
        if (media == null) {
            FText("无法读取媒体信息。${row.media.reason.orEmpty()}", color = c.textSecondary)
        } else {
            val video = media.streams.firstOrNull { it.kind == StreamKind.VIDEO && !it.attachedPicture }
            if (video != null) {
                DetailStat("主视频", listOfNotNull(video.codec.uppercase(),
                    video.width?.let { w -> video.height?.let { h -> "$w × $h" } }).joinToString(" · "))
            }
            media.streams.take(8).forEach { stream ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FIcon(
                        when (stream.kind) {
                            StreamKind.VIDEO -> FluentGlyph.Video
                            StreamKind.AUDIO -> FluentGlyph.Audio
                            else -> FluentGlyph.File
                        },
                        14.dp, c.textTertiary,
                    )
                    FText("#${stream.index} ${streamLabel(stream.kind)} · ${stream.codec}",
                        type = FType.Caption, modifier = Modifier.weight(1f), maxLines = 1)
                    FText(
                        listOfNotNull(
                            stream.width?.let { w -> stream.height?.let { h -> "$w×$h" } },
                            stream.channels?.let { "$it 声道" },
                            stream.bitrateBps?.let { "${bytes(it / 8)}/s" },
                        ).joinToString(" · "),
                        type = FType.Caption, color = c.textTertiary, maxLines = 1,
                    )
                }
            }
            if (media.streams.size > 8) FText("另有 ${media.streams.size - 8} 条流", type = FType.Caption, color = c.textTertiary)
        }
        if (reason != null) FInfoBar(reason, title = "需要审核", tone = FluentTone.Caution)
        FInlineNote("${estimateBasisLabel(estimate.basis)} · ${estimate.explanation}", FluentTone.Neutral)
    }
}

@Composable
private fun DetailStat(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        FText(label, type = FType.Caption, color = LocalFluentColors.current.textTertiary, maxLines = 1)
        FText(value, type = FType.BodyStrong, maxLines = 1)
    }
}
