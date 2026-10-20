package org.ergoutree.compress.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.presentation.*

private enum class HistoryFilter(val label: String) {
    ALL("全部"),
    ACTIVE("进行中"),
    DONE("已完成"),
    ATTENTION("需检查"),
}

/**
 * 任务历史采用列表—详情布局：左侧稳定定位任务，右侧展示逐项结果、尝试和备份恢复。
 * 窗口打开期间静默轮询，进行中的任务与详情无需手动刷新。
 */
@Composable
fun HistoryPage(
    controller: WorkspaceController,
    openLogs: (String) -> Unit,
    reveal: (String) -> Unit,
    formatTime: (Long) -> String = { it.toString() },
    liveRefresh: Boolean = true,
    close: () -> Unit,
) {
    val s by controller.state.collectAsState()
    val c = LocalFluentColors.current
    var filter by remember { mutableStateOf(HistoryFilter.ALL) }
    var search by remember { mutableStateOf("") }
    var restoreTarget by remember { mutableStateOf<CommitRecord?>(null) }
    var hideTarget by remember { mutableStateOf<JobRecord?>(null) }
    var cancelTarget by remember { mutableStateOf<JobRecord?>(null) }
    LaunchedEffect(Unit) { controller.refresh() }
    LaunchedEffect(liveRefresh) {
        if (liveRefresh) while (true) { delay(1500); controller.refresh() }
    }
    LaunchedEffect(s.jobs) {
        if (s.jobs.isNotEmpty() && s.jobs.none { it.id == s.selectedJobId }) controller.selectJob(s.jobs.first().id)
    }
    val filtered = s.jobs.filter { job ->
        val matches = search.isBlank() || job.id.contains(search, true) ||
            job.snapshot.plans.any { it.source.path.value.contains(search, true) || fileName(it.source.path.value).contains(search, true) }
        matches && when (filter) {
            HistoryFilter.ALL -> true
            HistoryFilter.ACTIVE -> job.state in setOf(JobState.READY, JobState.RUNNING, JobState.PAUSE_REQUESTED, JobState.PAUSED)
            HistoryFilter.DONE -> job.state in setOf(JobState.COMPLETED, JobState.COMPLETED_WITH_ERRORS, JobState.CANCELLED)
            HistoryFilter.ATTENTION -> job.state in setOf(JobState.NEEDS_ATTENTION, JobState.COMPLETED_WITH_ERRORS)
        }
    }
    val selectedJob = s.jobs.firstOrNull { it.id == s.selectedJobId }

    Box(Modifier.fillMaxSize().background(c.base)) {
        Column(Modifier.fillMaxSize()) {
            FPageHeader("任务历史", "参数快照、执行状态、目标位置与日志都保存在本地档案中", onBack = close) {
                FButton("刷新", controller::refresh, style = FButtonStyle.Subtle, glyph = FluentGlyph.Refresh)
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.width(330.dp).fillMaxHeight().background(c.cardSubtle)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FSearchBox(search, { search = it }, "搜索文件名或任务编号", Modifier.fillMaxWidth())
                        FSegmented(
                            HistoryFilter.entries.map { it to it.label },
                            filter,
                            { filter = it },
                            Modifier.fillMaxWidth(),
                        )
                        FText("${filtered.size} 个任务", type = FType.Caption, color = c.textTertiary)
                    }
                    FDivider()
                    if (filtered.isEmpty()) {
                        FEmptyState(FluentGlyph.History, "没有匹配的任务", "开始处理后会在这里保留完整记录。", Modifier.fillMaxWidth().padding(top = 48.dp))
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(filtered, key = { it.id }) { job ->
                                HistoryJobItem(job, selected = job.id == s.selectedJobId, formatTime = formatTime) {
                                    controller.selectJob(job.id)
                                }
                                FDivider()
                            }
                        }
                    }
                }
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val job = selectedJob
                    if (job == null) {
                        FEmptyState(FluentGlyph.TaskView, "选择一个任务查看详情",
                            "左侧列表会随任务进度实时更新；任务可以继续、取消或在历史中隐藏。",
                            Modifier.align(Alignment.Center))
                    } else {
                        HistoryDetail(
                            controller = controller,
                            s = s,
                            job = job,
                            formatTime = formatTime,
                            openLogs = { openLogs(job.id) },
                            reveal = reveal,
                            onRestore = { restoreTarget = it },
                            onHide = { hideTarget = job },
                            onCancel = { cancelTarget = job },
                        )
                    }
                }
            }
        }
        restoreTarget?.let { commit ->
            FContentDialog(
                title = "从备份恢复？",
                message = "恢复会核验备份与当前目标，并把当前结果保留为恢复事务的一部分。确认恢复 ${fileName(commit.source.identity.path.value)}？",
                primaryLabel = "确认恢复",
                onPrimary = { restoreTarget = null; controller.restore(commit.id) },
                secondaryLabel = "取消",
                onSecondary = { restoreTarget = null },
                destructive = true,
            )
        }
        hideTarget?.let { job ->
            FContentDialog(
                title = "隐藏这条历史记录？",
                message = "隐藏只影响历史列表，不会删除媒体、日志或备份，也不会解除文件保护。",
                primaryLabel = "隐藏记录",
                onPrimary = { hideTarget = null; controller.hide(job.id) },
                secondaryLabel = "取消",
                onSecondary = { hideTarget = null },
            )
        }
        cancelTarget?.let { job ->
            FContentDialog(
                title = "取消剩余项？",
                message = "当前文件会按队列的立即停止规则处理；已完成结果不会回滚，剩余项标记为取消。",
                primaryLabel = "取消剩余项",
                onPrimary = { cancelTarget = null; controller.cancel(job.id) },
                secondaryLabel = "继续处理",
                onSecondary = { cancelTarget = null },
                destructive = true,
            )
        }
    }
}

@Composable
private fun HistoryJobItem(job: JobRecord, selected: Boolean, formatTime: (Long) -> String, onClick: () -> Unit) {
    val c = LocalFluentColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 66.dp)
            .background(if (selected) c.selected else if (hovered) c.hover else androidx.compose.ui.graphics.Color.Transparent)
            .drawBehind {
                if (selected) {
                    val height = 28.dp.toPx()
                    drawRect(c.accent, topLeft = Offset(0f, (size.height - height) / 2f), size = Size(3.dp.toPx(), height))
                }
            }
            .hoverable(interaction)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FStatusDot(jobTone(job.state), 7.dp, pulsing = job.state == JobState.RUNNING)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            FText(fileName(job.snapshot.plans.first().source.path.value), type = if (selected) FType.BodyStrong else FType.Body, maxLines = 1)
            FText("${job.id.take(8)} · ${job.snapshot.plans.size} 个文件 · ${formatTime(job.createdAt)}",
                type = FType.Caption, color = c.textSecondary, maxLines = 1)
        }
        FBadge(jobLabel(job.state), jobTone(job.state))
    }
}

@Composable
private fun HistoryDetail(
    controller: WorkspaceController,
    s: WorkspaceState,
    job: JobRecord,
    formatTime: (Long) -> String,
    openLogs: () -> Unit,
    reveal: (String) -> Unit,
    onRestore: (CommitRecord) -> Unit,
    onHide: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = LocalFluentColors.current
    val completed = s.detailItems.count { it.state == ItemState.SUCCEEDED }
    val failed = s.detailItems.count { it.state in setOf(ItemState.FAILED, ItemState.INTERRUPTED, ItemState.NEEDS_ATTENTION) }
    val total = s.detailItems.size.takeIf { it > 0 } ?: job.snapshot.plans.size
    val isActive = s.active?.id == job.id
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FText("任务 ${job.id.take(8)}", type = FType.BodyLarge)
                    FBadge(jobLabel(job.state), jobTone(job.state))
                }
                FText("${formatTime(job.createdAt)} · ${job.snapshot.plans.size} 个文件 · ${backendLabel(job.snapshot.plans.first().settings.backend)}",
                    type = FType.Caption, color = c.textSecondary, maxLines = 1)
            }
            if (isActive) {
                FButton("完成本项后暂停", { controller.pause(job.id, false) }, style = FButtonStyle.Subtle)
                FButton("立即暂停", { controller.pause(job.id, true) }, style = FButtonStyle.Subtle)
            } else if (job.state == JobState.PAUSED || job.state == JobState.READY) {
                FButton("继续处理", { controller.resume(job.id) }, style = FButtonStyle.Accent, glyph = FluentGlyph.Play)
            }
            FButton("日志", openLogs, glyph = FluentGlyph.List)
            if (job.state in setOf(JobState.READY, JobState.RUNNING, JobState.PAUSED, JobState.PAUSE_REQUESTED)) {
                FButton("取消剩余项", onCancel, style = FButtonStyle.Subtle)
            }
            if (job.state in setOf(JobState.COMPLETED, JobState.COMPLETED_WITH_ERRORS, JobState.CANCELLED)) {
                FButton("隐藏记录", onHide, style = FButtonStyle.Subtle, glyph = FluentGlyph.Delete)
            }
        }
        FCard(padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FProgressBar(if (total > 0) completed.toFloat() / total else null, Modifier.weight(1f), label = "任务完成进度")
                FText("$completed / $total", type = FType.BodyStrong, maxLines = 1)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SummaryPill("成功", completed, FluentTone.Success)
                SummaryPill("失败或中断", failed, if (failed > 0) FluentTone.Critical else FluentTone.Neutral)
                SummaryPill("剩余", (total - completed - failed).coerceAtLeast(0), FluentTone.Neutral)
                Spacer(Modifier.weight(1f))
                FText("计划冻结于创建时，后续设置变化不会修改此任务。", type = FType.Caption, color = c.textTertiary, maxLines = 1)
            }
        }
        if (s.detailsLoading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FSpinner(14.dp); FText("正在载入任务明细…", type = FType.Caption, color = c.textSecondary)
            }
        }
        FCard(padding = 0.dp, spacing = 0.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                FText("文件与执行结果", type = FType.BodyStrong, modifier = Modifier.weight(1f))
                FText("${s.detailItems.size} 项", type = FType.Caption, color = c.textTertiary)
            }
            FDivider()
            if (s.detailItems.isEmpty() && !s.detailsLoading) {
                FEmptyState(FluentGlyph.File, "没有明细记录", "任务可能尚未创建项目，或相关记录已不可用。", Modifier.fillMaxWidth())
            }
            s.detailItems.forEachIndexed { index, item ->
                HistoryItemRow(
                    item = item,
                    attempts = s.detailAttempts[item.id].orEmpty(),
                    reveal = reveal,
                    openLogs = openLogs,
                    onRetry = { controller.retry(item.id, job.id) },
                    retryEnabled = !s.busy,
                )
                if (index < s.detailItems.lastIndex) FDivider()
            }
        }
        val recoverable = s.commits.filter { it.restoreOf == null && it.backup != null && !it.backupReleased && it.phase == CommitPhase.COMMITTED }
        if (recoverable.isNotEmpty()) {
            FCard {
                FSectionTitle("备份恢复", "恢复会创建新的恢复事务；已释放备份不能恢复。")
                recoverable.forEach { commit ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FIcon(FluentGlyph.Completed, 15.dp, c.success)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            FText(fileName(commit.source.identity.path.value), type = FType.Caption, maxLines = 1)
                            FText("备份 ${commit.backup?.value.orEmpty()}", type = FType.Caption, color = c.textTertiary, maxLines = 1)
                        }
                        FButton("从备份恢复", { onRestore(commit) }, enabled = !s.busy, glyph = FluentGlyph.Refresh)
                    }
                }
            }
        }
        s.error?.let { FInfoBar(it, title = "操作未完成", tone = FluentTone.Critical, onClose = controller::dismissError) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HistoryItemRow(
    item: ItemRecord,
    attempts: List<AttemptRecord>,
    reveal: (String) -> Unit,
    openLogs: () -> Unit,
    onRetry: () -> Unit,
    retryEnabled: Boolean,
) {
    val c = LocalFluentColors.current
    val settings = item.plan.settings
    val streams = settings.streams.joinToString(" · ") { "轨 ${it.sourceIndex}: ${streamActionLabel(it.action)}" }
        .ifBlank { "主视频按任务处理，其余轨道复制" }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FStatusDot(itemTone(item.state), 7.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                FText(fileName(item.plan.source.path.value), type = FType.BodyStrong, maxLines = 1)
                FText(item.plan.source.path.value, type = FType.Caption, color = c.textTertiary, maxLines = 1)
            }
            FBadge(itemLabel(item.state), itemTone(item.state))
            if (item.state == ItemState.SUCCEEDED) FButton("打开目标位置", { reveal(item.plan.destination.value) }, style = FButtonStyle.Subtle, glyph = FluentGlyph.OpenFolder)
            if (item.state in setOf(ItemState.FAILED, ItemState.INTERRUPTED, ItemState.CANCELLED)) {
                FButton("重试此项", onRetry, style = FButtonStyle.Subtle, glyph = FluentGlyph.Refresh, enabled = retryEnabled)
            }
        }
        FText("输出：${item.plan.destination.value}", type = FType.Caption, color = c.textSecondary, maxLines = 1)
        FText("${modeLabel(item.plan.outputMode)} · ${item.plan.container} · ${operationLabel(settings.operation)} · ${rateLabel(settings)} · 尝试 ${item.attemptSequence} 次",
            type = FType.Caption, color = c.textSecondary, maxLines = 1)
        FText("$streams · ${if (item.plan.onlyIfSmaller) "结果变大时跳过" else "允许输出更大"}", type = FType.Caption, color = c.textTertiary, maxLines = 2)
        attempts.forEach { attempt ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FText("第 ${attempt.sequence} 次 · ${executionLabel(attempt.state)}", type = FType.Caption,
                    color = if (attempt.state == ExecutionState.FAILED) c.critical else c.textSecondary, maxLines = 1)
                if (attempt.state == ExecutionState.SUCCEEDED) FBadge("校验通过", FluentTone.Success)
                attempt.output?.let { FText("输出 ${bytes(it.identity.sizeBytes)}", type = FType.Caption, color = c.textTertiary, maxLines = 1) }
            }
            attempt.diagnostic?.takeIf { it.isNotBlank() }?.let {
                FText(it, type = FType.Caption, color = if (attempt.state == ExecutionState.FAILED) c.critical else c.textTertiary, maxLines = 2)
            }
            if (attempt.state == ExecutionState.FAILED) FButton("查看日志", openLogs, style = FButtonStyle.Subtle, glyph = FluentGlyph.List)
        }
    }
}

@Composable
private fun SummaryPill(label: String, value: Int, tone: FluentTone) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FStatusDot(tone, 6.dp)
        FText("$label $value", type = FType.Caption, color = LocalFluentColors.current.textSecondary, maxLines = 1)
    }
}

/** 日志窗口：筛选、搜索、自动跟随与可复制文本，继续使用增量读取。 */
@Suppress("DEPRECATION")
@Composable
fun TaskLogPageView(
    controller: WorkspaceController,
    jobId: String,
    close: () -> Unit,
    formatClock: (Long) -> String = { it.toString() },
    live: Boolean = true,
) {
    val s by controller.state.collectAsState()
    val c = LocalFluentColors.current
    var level by remember(jobId) { mutableStateOf(s.preferences.logLevel) }
    var source by remember(jobId) { mutableStateOf("全部") }
    var search by remember(jobId) { mutableStateOf("") }
    var follow by remember(jobId) { mutableStateOf(true) }
    val scroll = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(jobId, live) {
        if (!live) { controller.readLogs(jobId); return@LaunchedEffect }
        while (true) { controller.readLogs(jobId); delay(1000) }
    }
    val lines = if (s.logJobId == jobId) s.logLines.filter { line ->
        line.level.ordinal >= level.ordinal &&
            (source == "全部" || if (source == "程序") line.source == "程序" else line.source != "程序") &&
            (search.isBlank() || line.text.contains(search, true))
    } else emptyList()
    LaunchedEffect(lines.lastOrNull()?.id, follow) {
        if (follow && lines.isNotEmpty()) scroll.scrollToItem(lines.lastIndex)
    }
    LaunchedEffect(scroll.lastScrolledBackward) { if (scroll.lastScrolledBackward) follow = false }
    Column(Modifier.fillMaxSize().background(c.base)) {
        FPageHeader("任务日志", "任务 ${jobId.take(8)} · 实时读取程序事件与 FFmpeg 输出", onBack = close) {
            FStatusChip(if (s.logJobId == jobId) "实时读取" else "连接中", FluentTone.Success, pulsing = true)
            FButton("复制全部", {
                clipboard.setText(AnnotatedString(lines.joinToString("\n") { line ->
                    "[${levelShort(line.level)}] [${line.source}] ${line.timeMillis?.let(formatClock)?.plus(" ") ?: ""}${line.text}"
                }))
            }, style = FButtonStyle.Subtle, glyph = FluentGlyph.Copy, enabled = lines.isNotEmpty())
        }
        Column(Modifier.fillMaxWidth().background(c.card).padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FComboBox(level,
                    listOf(LogLevel.DEBUG to "调试及以上", LogLevel.INFO to "信息及以上", LogLevel.WARNING to "警告及以上", LogLevel.ERROR to "仅错误"),
                    { level = it }, menuWidth = 160.dp, contentDescription = "日志级别")
                FSegmented(listOf("全部" to "全部", "程序" to "程序", "FFmpeg" to "FFmpeg"), source, { source = it }, Modifier.width(210.dp))
                FSearchBox(search, { search = it }, "查找日志", Modifier.weight(1f))
                FSwitch(follow, { follow = it }, label = "自动跟随")
                FButton("跳到最新", { follow = true },
                    style = FButtonStyle.Subtle, glyph = FluentGlyph.ChevronDown, enabled = lines.isNotEmpty())
            }
            FText("每 1 秒增量读取；滚动到较早位置会自动暂停跟随。", type = FType.Caption, color = c.textTertiary)
        }
        FDivider()
        Box(Modifier.weight(1f).fillMaxWidth().background(c.cardSubtle)) {
            if (lines.isEmpty()) {
                FEmptyState(FluentGlyph.List, "当前筛选下暂无日志", "完整日志仍保存在任务档案中；调整级别、来源或搜索条件。", Modifier.align(Alignment.Center))
            } else {
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxSize().padding(vertical = 6.dp), state = scroll) {
                        items(lines, key = { it.id }) { line ->
                            val color = when (line.level) {
                                LogLevel.ERROR -> c.critical
                                LogLevel.WARNING -> c.caution
                                LogLevel.DEBUG -> c.textTertiary
                                LogLevel.INFO -> c.textPrimary
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                                FText(levelShort(line.level), type = FType.Caption, color = color, modifier = Modifier.width(52.dp), maxLines = 1)
                                FText(line.source, type = FType.Caption, color = c.textTertiary, modifier = Modifier.width(56.dp), maxLines = 1)
                                line.timeMillis?.let { FText(formatClock(it), type = FType.Caption, color = c.textTertiary, modifier = Modifier.width(64.dp), maxLines = 1) }
                                FMonoText(line.text, Modifier.weight(1f), 12, color)
                            }
                        }
                    }
                }
                VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
            }
        }
        FDivider()
        Row(Modifier.fillMaxWidth().background(c.card).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FText("显示 ${lines.size} 行 · 窗口最多保留 ${s.preferences.logBufferLines} 行 · 可选择并复制文本",
                type = FType.Caption, color = c.textSecondary, modifier = Modifier.weight(1f), maxLines = 1)
            if (search.isNotBlank()) FBadge("已筛选", FluentTone.Accent)
            if (!follow) FBadge("跟随已暂停", FluentTone.Caution)
        }
    }
}

private fun levelShort(level: LogLevel) = when (level) {
    LogLevel.DEBUG -> "调试"
    LogLevel.INFO -> "信息"
    LogLevel.WARNING -> "警告"
    LogLevel.ERROR -> "错误"
}

/** 关于窗口：能力、边界、隐私与版本，不使用超大字号的营销式标题。 */
@Composable
fun AboutPage(close: () -> Unit, dataRoot: String = "", openDataRoot: () -> Unit = {}) {
    val c = LocalFluentColors.current
    Column(Modifier.fillMaxSize().background(c.base)) {
        FPageHeader("关于", "ErgouCompress 0.1.0", onBack = close)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                FAppMark(48.dp)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    FText("ErgouCompress", type = FType.Subtitle)
                    FText("基于 FFmpeg 的本地批量媒体压缩工具 · 0.1.0", type = FType.Caption, color = c.textSecondary)
                    FText("扫描 · 审核 · 编码 · 完整校验 · 安全发布", type = FType.Caption, color = c.textTertiary)
                }
            }
            FCard {
                FSectionTitle("主要能力")
                CapabilityRow(FluentGlyph.Video, "CPU H.264 与 NVIDIA HEVC", "硬件可用性以本机实际编码自检为准，不会静默切换后端。")
                FDivider()
                CapabilityRow(FluentGlyph.Audio, "音轨策略与容器", "支持 AAC 编码、逐轨 Copy / Encode / Drop，以及 MP4 / MKV。")
                FDivider()
                CapabilityRow(FluentGlyph.Completed, "完整解码校验与安全发布", "编码结束后仍会校验输出；只有校验通过才发布，原件可通过备份恢复。")
                FDivider()
                CapabilityRow(FluentGlyph.TaskView, "持久队列与可追溯历史", "参数快照、执行尝试、日志与目标位置保存在本地档案中。")
            }
            FCard {
                FSectionTitle("验证边界")
                FText("当前自动处理路径针对已验证的 8-bit 4:2:0 SDR 视频。HDR、复杂侧数据、交错视频与未验证的流组合会要求人工审核。",
                    color = c.textSecondary)
                FText("固定质量模式的输出大小取决于内容，采样估算给出范围而不是保证。仅在结果更小时发布的默认策略可避免无意增大文件。",
                    color = c.textSecondary, modifier = Modifier.padding(top = 4.dp))
            }
            FCard {
                FSectionTitle("本地与隐私")
                FText("文件始终留在本机。桌面界面使用 Kotlin / Compose 按 Windows 11 Fluent 规范设计，FFmpeg 由用户自行提供。",
                    color = c.textSecondary)
                if (dataRoot.isNotBlank()) {
                    FTextField(value = dataRoot, onValueChange = {}, label = "数据根", enabled = false, modifier = Modifier.fillMaxWidth())
                    FButton("打开数据根", openDataRoot, glyph = FluentGlyph.OpenFolder, enabled = dataRoot.isNotBlank())
                }
                FText("ErgouCompress 采用 GPL-3.0，详见项目 LICENSE。", type = FType.Caption, color = c.textTertiary)
            }
            FText("在任务运行或迁移数据目录时关闭窗口，应用会先安全停止正在进行的写入。", type = FType.Caption, color = c.textTertiary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CapabilityRow(glyph: FluentGlyph, title: String, description: String) {
    val c = LocalFluentColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(c.accentSubtle), contentAlignment = Alignment.Center) {
            FIcon(glyph, 16.dp, c.accent)
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            FText(title, type = FType.BodyStrong)
            FText(description, type = FType.Caption, color = c.textSecondary)
        }
    }
}
