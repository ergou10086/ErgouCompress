package org.ergoutree.compress.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.presentation.*

@Composable
fun HistoryPage(controller: WorkspaceController, openLogs: (String) -> Unit, reveal: (String) -> Unit,
    formatTime: (Long) -> String = { it.toString() }, close: () -> Unit) {
    val s by controller.state.collectAsState()
    val c = LocalFluentColors.current
    var filter by remember { mutableStateOf("") }
    var restoreConfirmation by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { controller.refresh() }
    Column(Modifier.fillMaxSize().background(c.background)) {
        PageHeading("任务历史", "每次处理保留参数快照、执行状态、目标与日志", close)
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FField("搜索文件名或任务编号", filter, modifier = Modifier.weight(1f)) { filter = it }
            FButton("刷新") { controller.refresh() }
        }
        val list = s.jobs.filter { job -> filter.isBlank() || job.id.contains(filter, true) || job.snapshot.plans.any { it.source.path.value.contains(filter, true) } }
        LazyColumn(Modifier.weight(1f).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (list.isEmpty()) item { FCard { FText("还没有匹配的任务", 20); FText("开始处理后，任务会完整记录在这里。", 12, c.secondary) } }
            items(list, key = { it.id }) { job ->
                val selected = s.selectedJobId == job.id
                FCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            FText("${job.id.take(8)}  ·  ${jobLabel(job.state)}", 18, strong = true)
                            FText("${formatTime(job.createdAt)} · ${job.snapshot.plans.size} 个文件 · ${backendLabel(job.snapshot.plans.first().settings.backend)}", 12, c.secondary)
                        }
                        FButton(if (selected) "重新载入明细" else "展开明细") { controller.selectJob(job.id) }
                        FButton("日志", quiet = true) { openLogs(job.id) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (job.state in listOf(JobState.PAUSED, JobState.READY)) FButton("继续处理", !s.closing && !s.restartRequired) { controller.resume(job.id) }
                        if (job.state in listOf(JobState.READY, JobState.RUNNING, JobState.PAUSED, JobState.PAUSE_REQUESTED)) FButton("取消剩余项") { controller.cancel(job.id) }
                        if (job.state in listOf(JobState.COMPLETED, JobState.COMPLETED_WITH_ERRORS, JobState.CANCELLED)) FButton("隐藏记录", quiet = true) { controller.hide(job.id) }
                    }
                    if (selected) {
                        FDivider()
                        if (s.detailsLoading) FText("正在载入…", 12, c.secondary)
                        s.detailItems.forEach { item ->
                            FText("${fileName(item.plan.source.path.value)} · ${itemLabel(item.state)}", strong = true)
                            FText("源文件：${item.plan.source.path.value}", 12, c.secondary)
                            FText("输出：${item.plan.destination.value}", 12, c.secondary)
                            val settings = item.plan.settings
                            val rate = if (settings.rateControl == RateControl.BITRATE) "视频 ${settings.videoBitrateKbps} kbps" else "${if (settings.backend == Backend.X264) "CRF" else "CQ"} ${settings.quality}"
                            FText("${modeLabel(item.plan.outputMode)} · ${item.plan.container} · ${if (settings.operation == MediaOperation.REMUX) "仅转封装" else backendLabel(settings.backend) + " · " + rate} · 尝试 ${item.attemptSequence} 次", 12, c.secondary)
                            FText("${settings.streams.joinToString { "轨 ${it.sourceIndex}: ${when (it.action) { StreamAction.COPY -> "复制"; StreamAction.ENCODE -> "编码"; StreamAction.DROP -> "移除" }}" }.ifBlank { "主视频按任务处理，其余轨道复制" }} · ${if (item.plan.onlyIfSmaller) "结果变大时跳过" else "允许输出更大"}", 12, c.secondary)
                            s.detailAttempts[item.id].orEmpty().forEach { attempt ->
                                FText("第 ${attempt.sequence} 次：${executionLabel(attempt.state)} · ${attempt.diagnostic.orEmpty()}", 12, if (attempt.state == ExecutionState.FAILED) c.danger else c.secondary)
                                attempt.output?.let { FText("实际输出 ${bytes(it.identity.sizeBytes)} · 完整解码校验${if (attempt.validation?.fullDecodePassed == true) "通过" else "待核对"}", 12, c.secondary) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FButton("打开目标位置", item.state == ItemState.SUCCEEDED) { reveal(item.plan.destination.value) }
                                if (item.state in listOf(ItemState.FAILED, ItemState.INTERRUPTED, ItemState.CANCELLED)) FButton("重试此项", !s.closing && !s.restartRequired) { controller.retry(item.id, job.id) }
                            }
                            FDivider()
                        }
                        s.commits.filter { it.restoreOf == null }.forEach { commit ->
                            if (commit.backup != null && !commit.backupReleased && commit.phase == CommitPhase.COMMITTED) {
                                FButton("从备份恢复 ${fileName(commit.source.identity.path.value)}", !s.busy) { restoreConfirmation = commit.id }
                            }
                        }
                        if (restoreConfirmation != null) {
                            FText("恢复会核验备份与当前目标，并把当前结果保留为恢复事务的一部分。确认恢复所选文件？", 13, c.danger)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FButton("确认恢复", !s.busy) { controller.restore(restoreConfirmation!!); restoreConfirmation = null }
                                FButton("返回") { restoreConfirmation = null }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        s.error?.let { FText(it, 13, c.danger, modifier = Modifier.padding(20.dp)) }
    }
}

@Composable
fun TaskLogPageView(controller: WorkspaceController, jobId: String, close: () -> Unit) {
    val s by controller.state.collectAsState()
    val c = LocalFluentColors.current
    var level by remember(jobId) { mutableStateOf(s.preferences.logLevel) }
    var source by remember(jobId) { mutableStateOf("全部") }
    var search by remember(jobId) { mutableStateOf("") }
    var follow by remember(jobId) { mutableStateOf(true) }
    val scroll = rememberLazyListState()
    LaunchedEffect(jobId) { while (true) { controller.readLogs(jobId); delay(1000) } }
    val lines = if (s.logJobId == jobId) s.logLines.filter { it.level.ordinal >= level.ordinal && (source == "全部" || if (source == "程序") it.source == "程序" else it.source != "程序") && it.text.contains(search, true) } else emptyList()
    LaunchedEffect(lines.lastOrNull()?.id, follow) { if (follow && lines.isNotEmpty()) scroll.scrollToItem(lines.lastIndex) }
    Column(Modifier.fillMaxSize().background(c.background)) {
        PageHeading("任务日志", "任务 ${jobId.take(8)} · 实时读取程序事件与 FFmpeg 输出", close)
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FChoice("显示级别", level, listOf(LogLevel.DEBUG to "调试及以上", LogLevel.INFO to "信息及以上", LogLevel.WARNING to "警告及以上", LogLevel.ERROR to "仅错误")) { level = it }
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                FChoice("来源", source, listOf("全部" to "全部", "程序" to "程序", "FFmpeg" to "FFmpeg")) { source = it }
                FCheck("自动跟随", follow) { follow = it }
            }
            FField("查找日志", search) { search = it }
        }
        FDivider()
        Box(Modifier.weight(1f).background(c.surface)) {
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().padding(16.dp), state = scroll, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (lines.isEmpty()) item { FText("当前筛选下暂无日志；完整日志仍保存在任务档案中。", 13, c.secondary) }
                    items(lines, key = { it.id }) { line ->
                        FText("[${line.level}] [${line.source}] ${line.text}", 12, if (line.level == LogLevel.ERROR) c.danger else if (line.level == LogLevel.WARNING) c.accent else c.text)
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
        FText("显示 ${lines.size} 行 · 窗口最多保留 ${s.preferences.logBufferLines} 行 · 可选择并复制文本", 12, c.secondary, modifier = Modifier.padding(16.dp))
    }
}

@Composable
fun AboutPage(close: () -> Unit) {
    val c = LocalFluentColors.current
    Column(Modifier.fillMaxSize().background(c.background)) {
        PageHeading("关于", "ErgouCompress · 0.1.0", close)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            FCard { FolderSymbol(); SectionTitle("为珍藏留出更多空间")
                FText("基于 FFmpeg 的本地批量媒体压缩工具。把扫描、逐轨审核、编码、完整校验与安全发布组织为可追溯的任务。")
                FDivider()
                FText("默认复制音轨，保持分辨率与帧率。支持 CPU H.264、NVIDIA HEVC、AAC 和 MP4 / MKV。硬件可用性以本机自检为准。", 13, c.secondary)
                FText("当前验证路径针对 8-bit 4:2:0 SDR 视频；HDR、复杂侧数据和其他组合需要审核。固定质量输出可能更大；采样体积不是保证。", 13, c.secondary)
                FText("桌面界面使用 Kotlin / Compose，按 Windows 11 Fluent 规范设计。FFmpeg 由用户自行提供。", 13, c.secondary)
                FText("ErgouCompress 采用 GPL-3.0，详见项目 LICENSE。", 12, c.secondary)
            }
        }
    }
}

private fun itemLabel(value: ItemState) = when (value) { ItemState.READY -> "等待处理"; ItemState.RUNNING -> "正在处理"; ItemState.INTERRUPTED -> "已中断"; ItemState.SUCCEEDED -> "已完成"; ItemState.FAILED -> "失败"; ItemState.NEEDS_ATTENTION -> "需要检查"; ItemState.CANCELLED -> "已取消"; ItemState.SKIPPED -> "已跳过" }
