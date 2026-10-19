package org.ergoutree.compress.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.delay
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.presentation.*

/** 窗口路由只表达页面意图，桌面宿主负责独立窗口的生命周期。 */
enum class WorkspacePage { OPTIONS, SETTINGS, HISTORY, ABOUT }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkspaceApp(controller: WorkspaceController, importModifier: Modifier = Modifier, dragging: Boolean = false,
    openPage: (WorkspacePage) -> Unit = {}, openLogs: (String) -> Unit = {},
    pickFiles: () -> Unit = {}, pickFolder: () -> Unit = {}) {
    val s by controller.state.collectAsState()
    val c = LocalFluentColors.current
    LaunchedEffect(Unit) { controller.initialize() }
    LaunchedEffect(s.active?.id) { while (s.active != null) { controller.refresh(); delay(1000) } }
    Column(Modifier.fillMaxSize().background(c.background).onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) when (event.key) {
            Key.O -> { pickFiles(); true }; Key.Comma -> { openPage(WorkspacePage.SETTINGS); true }; else -> false
        } else false
    }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FText("ErgouCompress", 14, strong = true)
            FText("  /  媒体压缩", 12, c.secondary)
            Spacer(Modifier.weight(1f))
            FButton("设置", quiet = true) { openPage(WorkspacePage.SETTINGS) }
            FButton("历史", quiet = true) { controller.refresh(); openPage(WorkspacePage.HISTORY) }
            FButton("关于", quiet = true) { openPage(WorkspacePage.ABOUT) }
        }
        FDivider()
        Column(Modifier.weight(1f).padding(horizontal = 32.dp).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FText("压缩", 28, strong = true)
                    FText("为收藏腾出空间，保留值得聆听和观看的细节。", 14, c.secondary)
                }
                FText(if (s.tools != null) "●  工具已就绪" else "○  待验证工具", 12, if (s.tools != null) c.accent else c.secondary)
            }
            Column(importModifier.fillMaxWidth().background(if (dragging) c.selected else c.surface, RoundedCornerShape(8.dp))
                .border(if (dragging) 2.dp else 1.dp, if (dragging) c.accent else c.border, RoundedCornerShape(8.dp))
                .padding(horizontal = 24.dp, vertical = if (s.rows.isEmpty()) 24.dp else 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (s.rows.isEmpty()) FolderSymbol(s.scanning)
                FText(if (dragging) "松开以添加到本次任务" else if (s.scanning) "正在扫描文件夹" else "将文件夹或视频拖到这里", 20, strong = true)
                FText(if (s.scanning) "已发现 ${s.scannedFiles} 个文件 · ${bytes(s.scannedBytes)}" else "支持多个文件与文件夹，拖入后自动扫描和探测媒体", 12, c.secondary)
                if (s.scanning || s.toolBusy || s.startupBusy) {
                    FProgress(null, "正在扫描或验证工具", Modifier.widthIn(max = 420.dp))
                    FText(s.scanningPath.ifBlank { s.message }, 12, c.secondary, lines = 1)
                    if (s.scanning) FButton("停止扫描", quiet = true) { controller.stopScan() }
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FButton("添加文件", enabled = !s.closing && !s.restartRequired, onClick = pickFiles)
                    FButton("添加文件夹", enabled = !s.closing && !s.restartRequired, onClick = pickFolder)
                }
            }
            if (s.ffmpeg.isBlank() && !s.startupBusy) FCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { FText("先连接你的 FFmpeg", strong = true); FText("选择本机工具并完成实际编码自检，之后拖入即可开始。", 12, c.secondary) }
                    FButton("打开设置", primary = true) { openPage(WorkspacePage.SETTINGS) }
                }
            }
            if (s.jobs.isNotEmpty()) TaskStrip(controller, s, openLogs) { openPage(WorkspacePage.HISTORY) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FText("本次任务", 20, strong = true)
                FText("${s.rows.size} 个文件 · 已选 ${s.selected.size} 个", 12, c.secondary, modifier = Modifier.weight(1f))
                FButton("全选可用项", !s.intakeBusy, quiet = true) { controller.selectAll(true) }
                FButton("取消选择", !s.intakeBusy, quiet = true) { controller.selectAll(false) }
                FButton("清空", !s.intakeBusy && s.rows.isNotEmpty(), quiet = true) { controller.clearDraft() }
            }
            FileReviewList(controller, s, Modifier.weight(1f))
        }
        if (s.error != null) Row(Modifier.fillMaxWidth().background(c.danger.copy(alpha = .09f)).padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FText(s.error!!, 13, c.danger, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            FButton("关闭提示", quiet = true) { controller.dismissError() }
        }
        Row(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 32.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                FText(s.message, 12, c.secondary, lines = 1)
                if (s.rows.isNotEmpty()) FText("已选 ${bytes(s.selected.sumOf { it.media.file.sizeBytes })} · 体积以最终校验后的结果为准", 12, c.secondary)
            }
            if (s.rows.isNotEmpty() && !s.scanComplete) FButton("重新扫描", !s.intakeBusy) { controller.rescan() }
            FButton("处理选项  ›", s.selected.isNotEmpty() && !s.intakeBusy && !s.closing, primary = true) { openPage(WorkspacePage.OPTIONS) }
        }
    }
}

@Composable
private fun FileReviewList(controller: WorkspaceController, s: WorkspaceState, modifier: Modifier) {
    val c = LocalFluentColors.current
    val scroll = rememberLazyListState()
    Column(modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(8.dp)).border(1.dp, c.border, RoundedCornerShape(8.dp))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FText("文件名 / 媒体信息", 12, c.secondary, modifier = Modifier.weight(1f))
            FText("原始大小", 12, c.secondary, modifier = Modifier.width(90.dp))
            FText("预计处理大小", 12, c.secondary, modifier = Modifier.width(135.dp))
            FText("审核", 12, c.secondary, modifier = Modifier.width(100.dp))
        }
        FDivider()
        Box(Modifier.weight(1f)) {
            if (s.rows.isEmpty()) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FText("文件会显示在这里", 18, c.secondary)
                FText("先添加素材，再审核参数。运行任务时也能继续导入。", 12, c.secondary)
            }
            LazyColumn(Modifier.fillMaxSize().padding(end = 10.dp), state = scroll) {
                items(s.rows, key = { it.media.file.path.value }) { row ->
                    val reason = row.rejection(s.draft)
                    val estimate = row.estimate(s.draft)
                    Row(Modifier.fillMaxWidth().background(if (row.selected) c.selected.copy(alpha = .65f) else c.surface)
                        .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            FCheck(fileName(row.media.file.path.value), row.selected, !s.intakeBusy && row.media.eligible) { controller.toggle(row.media.file.path) }
                            val video = row.media.media?.streams?.firstOrNull { it.kind == StreamKind.VIDEO && !it.attachedPicture }
                            FText(listOfNotNull(video?.codec?.uppercase(), video?.width?.let { "$it × ${video.height}" }, row.media.media?.durationUs?.let(::duration)).joinToString(" · "), 12, c.secondary)
                            FText(row.media.file.path.value, 12, c.secondary, lines = 1)
                        }
                        FText(bytes(row.media.file.sizeBytes), 13, modifier = Modifier.width(90.dp))
                        Column(Modifier.width(135.dp)) {
                            FText(if (estimate.expectedBytes == null) "待采样估算" else "约 ${bytes(estimate.expectedBytes)}", 13)
                            FText(when (estimate.basis) { EstimateBasis.VIDEO_SAMPLES -> "采样推算"; EstimateBasis.BITRATE_BUDGET -> "码率预算"; else -> "可在处理选项中估算" }, 12, c.secondary)
                            if (estimate.lowerBytes != null && estimate.upperBytes != null) FText("${bytes(estimate.lowerBytes)}–${bytes(estimate.upperBytes)}", 12, c.secondary)
                        }
                        FText(reason ?: "可处理", 12, if (reason == null) c.accent else c.secondary, modifier = Modifier.width(100.dp), lines = 3)
                    }
                    FDivider()
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskStrip(controller: WorkspaceController, s: WorkspaceState, openLogs: (String) -> Unit, history: () -> Unit) {
    val c = LocalFluentColors.current
    val job = s.jobs.firstOrNull { it.id == s.active?.id } ?: s.jobs.firstOrNull { it.state in listOf(JobState.READY, JobState.PAUSED, JobState.PAUSE_REQUESTED) } ?: s.jobs.first()
    val event = s.active?.takeIf { it.id == job.id }?.event
    val plan = job.snapshot.plans.firstOrNull { it.source.path == event?.source }
    FCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                FText("任务 ${job.id.take(8)}  ·  ${event?.stage?.let(::stageLabel) ?: jobLabel(job.state)}", strong = true)
                FText(event?.source?.value?.let(::fileName) ?: "${job.snapshot.plans.size} 个文件 · ${backendLabel(job.snapshot.plans.first().settings.backend)}", 12, c.secondary, lines = 1)
            }
            if (job.id == s.active?.id) {
                FButton("完成本项后暂停") { controller.pause(job.id, false) }
                FButton("立即暂停") { controller.pause(job.id, true) }
            } else if (job.state in listOf(JobState.PAUSED, JobState.READY)) FButton("继续", !s.closing && !s.restartRequired) { controller.resume(job.id) }
            FButton("日志") { openLogs(job.id) }
            FButton("查看任务", quiet = true, onClick = history)
        }
        if (job.id == s.active?.id) {
            val fraction = if (event?.stage == AttemptStage.ENCODING) event.progress?.fraction(plan?.media?.durationUs)?.toFloat() else null
            FProgress(fraction, "当前文件${event?.stage?.let(::stageLabel).orEmpty()}")
            FText(when (event?.stage) { AttemptStage.ENCODING -> "当前文件 ${fraction?.let { "${(it * 100).toInt()}%" } ?: "处理中"} · ${event.progress?.speed?.let { "$it ×" } ?: "计算速度中"}"
                AttemptStage.VALIDATING -> "编码完成，正在完整解码校验"; AttemptStage.PUBLISHING -> "校验通过，正在安全发布"; else -> event?.message ?: "正在领取任务" }, 12, c.secondary)
        }
    }
}
