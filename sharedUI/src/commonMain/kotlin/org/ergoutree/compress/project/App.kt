package org.ergoutree.compress.project

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.*
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.presentation.*

private val Ink = Color(0xff202020)
private val Accent = Color(0xff0067c0)
private val Muted = Color(0xff656565)
private val Stroke = Color(0xffd5d5d5)

@Composable
fun App(controller: DesktopController, importModifier: Modifier = Modifier, pickFile: ((String) -> Unit) -> Unit = {}, pickFolder: ((String) -> Unit) -> Unit = {}) {
    val state by controller.state.collectAsState()
    val form = state.form
    Row(Modifier.fillMaxSize().background(Color(0xfff3f3f3)).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(Modifier.width(330.dp).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("ErgouCompress", 26)
            Text("S1 / S2 · 桌面副本工作流", 13, Muted)
            SettingField("FFmpeg", form.ffmpeg, "选择自己的 ffmpeg.exe。自检会实际生成短片，并分别验证 CPU 与 NVIDIA。", !state.busy) { controller.edit { f -> f.copy(ffmpeg = it) } }
            Action("选择 FFmpeg", !state.busy) { pickFile { path -> controller.edit { it.copy(ffmpeg = path) } } }
            SettingField("ffprobe", form.ffprobe, "留空时查找 FFmpeg 同目录的 ffprobe；两个工具都通过才能扫描和编码。", !state.busy) { controller.edit { f -> f.copy(ffprobe = it) } }
            Action("验证工具链", !state.busy) { controller.validateTools() }
            SettingField("输入路径（每行一项）", form.roots, "文件或目录均可；导入只引用原文件。允许混合和重叠根，不跟随链接或 junction。", !state.busy, false) { controller.edit { f -> f.copy(roots = it) } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("添加文件", !state.busy) { pickFile { path -> controller.edit { it.copy(roots = appendRoot(it.roots, path)) } } }
                Action("添加文件夹", !state.busy) { pickFolder { path -> controller.edit { it.copy(roots = appendRoot(it.roots, path)) } } }
            }
            SettingField("扫描深度", form.depth, "0 仅根目录文件，1 再包含一层子目录；默认 4。输入“无限”遍历所有层级。", !state.busy) { controller.edit { f -> f.copy(depth = it) } }
            SettingField("最小体积（MB）", form.minimumMb, "1 MB = 1,000,000 bytes。低于阈值的文件仍显示，默认不选。", !state.busy) { controller.edit { f -> f.copy(minimumMb = it) } }
            SettingField("副本输出目录", form.output, "选择已存在的独立输出目录，扫描排除此目录和数据根。同名目标一律拒绝覆盖。", !state.busy) { controller.edit { f -> f.copy(output = it) } }
            Action("选择输出目录", !state.busy) { pickFolder { path -> controller.edit { it.copy(output = path) } } }
            Action("扫描并探测", !state.busy && state.tools != null) { controller.scan() }
            Choice("编码后端", "GPU 必须通过真实自检，不会静默切换 CPU。默认保持分辨率、帧率和音轨。") {
                Backend.entries.forEach { b -> Action((if (form.backend == b) "● " else "○ ") + b.name, !state.busy && (b == Backend.X264 || state.tools?.supports(b) == true)) { controller.edit { it.copy(backend = b, quality = if (b == Backend.X264) "23" else "28") } } }
            }
            SettingField("质量 CRF / CQ", form.quality, "0–51；越低通常质量越高、体积越大。CPU CRF 和 GPU CQ 不等价，默认 23 / 28，不保证输出更小。", !state.busy) { controller.edit { f -> f.copy(quality = it) } }
            Choice("速度", "均衡：x264 medium / NVIDIA p5；快速：veryfast / p4。速度不保证输出大小。") {
                Speed.entries.forEach { s -> Action((if (form.speed == s) "● " else "○ ") + s.name, !state.busy) { controller.edit { it.copy(speed = s) } } }
            }
            Choice("输出容器", "默认保留 MP4/MKV；转换时扩展名变化，受支持音轨全部复制。复杂媒体暂需审核。") {
                listOf<Container?>(null, Container.MP4, Container.MKV).forEach { c -> Action((if (form.container == c) "● " else "○ ") + (c?.name ?: "保留"), !state.busy) { controller.edit { it.copy(container = c) } } }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(importModifier.fillMaxWidth().background(Color.White, RoundedCornerShape(8.dp)).border(1.dp, Stroke, RoundedCornerShape(8.dp)).padding(14.dp)) {
                Text("审核输入与计划", 20)
                Text("可拖入文件/文件夹。逐项处理副本；立即停止后重跑当前文件。", 13, Muted)
                Text("数据根：${controller.workflow.dataRoot}", 12, Muted)
                controller.workflow.startupNotice?.let { Text(it, 13, Color(0xff885000)) }
            }
            Text(state.status, 14)
            Text("扫描 ${state.fileCount} 个文件 · ${state.totalBytes / 1_000_000.0} MB · 选中 ${state.rows.count { it.selected }} 项${if (state.scanComplete) "" else " · 尚未完整扫描"}", 13, Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Action("全选合格项", !state.busy) { controller.selectAll(true) }
                Action("取消全选", !state.busy) { controller.selectAll(false) }
                Action("冻结并审核计划", !state.busy && state.scanComplete && state.rows.any { it.selected }) { controller.freeze() }
                Action("立即停止", state.busy) { controller.stop() }
            }
            state.attempt?.let { event ->
                Text("阶段：${event.stage}${event.progress?.let { " · ${it.outTimeUs?.div(1_000_000.0) ?: "?"} 秒 · ${it.speed ?: "?"}x" } ?: ""}", 14, Accent)
                Text("编码进度结束后仍需完整校验与发布。", 12, Muted)
                event.progress?.fraction(state.activeDurationUs)?.let { fraction ->
                    Box(Modifier.fillMaxWidth().height(6.dp).background(Stroke, RoundedCornerShape(3.dp)).semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction.toFloat(), 0f..1f) }) {
                        Box(Modifier.fillMaxWidth(fraction.toFloat()).fillMaxHeight().background(Accent, RoundedCornerShape(3.dp)))
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.frozen?.let { job -> items(job.plans, key = { "plan:${it.source.path.value}" }) { plan ->
                    Column(Modifier.fillMaxWidth().background(Color(0xffe8f2fb), RoundedCornerShape(6.dp)).padding(12.dp)) {
                        Text("目标：${plan.destination.value}", 13)
                        Text("${plan.settings.backend} · ${plan.container} · 音频复制 · 保持原分辨率与帧率", 12, Muted)
                        Action(if (plan.destination in state.completed) "已完成副本" else "处理此项", !state.busy && plan.destination !in state.completed) { controller.run(plan) }
                    }
                } }
                items(state.rows, key = { "source:${it.media.file.path.value}" }) { row ->
                    Column(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(6.dp)).padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(24.dp).border(1.dp, Stroke, RoundedCornerShape(4.dp)).semantics {
                                role = Role.Checkbox; stateDescription = if (row.selected) "已选" else "未选"; contentDescription = "选择 ${row.media.file.path.value}"
                            }.clickable(enabled = !state.busy && row.media.eligible, role = Role.Checkbox) { controller.toggle(row.media.file.path) }, contentAlignment = Alignment.Center) { Text(if (row.selected) "✓" else "", 16, Accent) }
                            Text(row.media.file.path.value, 13)
                        }
                        Text("${row.media.file.sizeBytes / 1_000_000.0} MB · ${row.media.reason ?: "合格 · 已保留全部受支持音轨"}", 12, Muted)
                        if (row.media.eligible) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Action("以当前参数覆盖此项", !state.busy) { controller.overrideFromCurrent(row.media.file.path) }
                            if (row.override != null) Action("恢复全局参数", !state.busy) { controller.overrideFromCurrent(row.media.file.path, true) }
                        }
                        row.override?.let { Text("单项覆盖：${it.backend} / ${it.quality} / ${it.speed} / ${it.container ?: "保留容器"}", 12, Accent) }
                    }
                }
            }
        }
    }
}

private fun appendRoot(existing: String, path: String) = (existing.lines().filter(String::isNotBlank) + path).distinct().joinToString("\n")

@Composable
private fun Choice(label: String, help: String, content: @Composable RowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text(label, 13); HelpTip(label, help) }
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), content = content)
    }
}

@Composable
fun SettingField(label: String, value: String, help: String, enabled: Boolean, singleLine: Boolean = true, onValue: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Text(label, 13); HelpTip(label, help) }
        BasicTextField(value, onValue, enabled = enabled, singleLine = singleLine, textStyle = TextStyle(color = Ink, fontSize = 13.sp), modifier = Modifier.fillMaxWidth()
            .background(Color.White, RoundedCornerShape(4.dp)).border(1.dp, Stroke, RoundedCornerShape(4.dp)).padding(9.dp).semantics { contentDescription = "${label}输入框" })
    }
}

@Composable
fun HelpTip(label: String, message: String) {
    val interactions = remember { MutableInteractionSource() }
    val hover by interactions.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) }
    var dismissed by remember { mutableStateOf(false) }
    val expanded = !dismissed && (hover || focused || pinned)
    Column {
        Box(Modifier.size(24.dp).border(1.dp, if (focused) Accent else Stroke, RoundedCornerShape(12.dp))
            .hoverable(interactions).onFocusChanged { if (focused != it.isFocused) dismissed = false; focused = it.isFocused }
            .onPreviewKeyEvent { if (it.key == Key.Escape && it.type == KeyEventType.KeyDown) { pinned = false; dismissed = true; true } else false }
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClickLabel = "显示或关闭${label}说明") { pinned = !pinned; dismissed = !pinned }
            .semantics { contentDescription = "${label}帮助"; stateDescription = if (expanded) "已展开" else "已收起" }, contentAlignment = Alignment.Center) { Text("i", 13, Accent) }
        if (expanded) Box(Modifier.widthIn(max = 260.dp).background(Color(0xfffff7df)).padding(8.dp)) { BasicText(message, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = TextStyle(color = Ink, fontSize = 12.sp)) }
    }
}

@Composable
fun Action(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.background(if (enabled) Color.White else Color(0xffe5e5e5), RoundedCornerShape(4.dp)).border(1.dp, Stroke, RoundedCornerShape(4.dp))
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)) { Text(label, 12, if (enabled) Ink else Muted) }
}

@Composable
private fun Text(value: String, size: Int = 14, color: Color = Ink) = BasicText(value, style = TextStyle(color = color, fontSize = size.sp))
