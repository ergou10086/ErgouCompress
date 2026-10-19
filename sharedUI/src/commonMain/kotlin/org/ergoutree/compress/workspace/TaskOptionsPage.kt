package org.ergoutree.compress.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.presentation.*

@Composable
fun TaskOptionsPage(controller: WorkspaceController, pickFolder: ((String) -> Unit) -> Unit, close: () -> Unit) {
    val s by controller.state.collectAsState()
    val d = s.draft
    val c = LocalFluentColors.current
    var advanced by remember { mutableStateOf(false) }
    val enabled = !s.intakeBusy && !s.closing
    val conflict = s.selected.firstNotNullOfOrNull { it.rejection(d) }
    Column(Modifier.fillMaxSize().background(c.background)) {
        PageHeading("处理选项", "本次任务 · ${s.selected.size} 个文件 · ${bytes(s.selected.sumOf { it.media.file.sizeBytes })}", close)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            FCard {
                SectionTitle("视频与容器", "音轨默认复制；保持原分辨率和帧率。参数只作用于这次任务。")
                EncodingEditor(d, enabled, s.tools) { next -> controller.editDraft { next } }
            }
            FCard {
                SectionTitle("输出与文件保护")
                FChoice("处理方式", d.mode, listOf(OutputMode.COPY to "保存副本", OutputMode.REPLACE to "原地替换", OutputMode.CHANGE_EXTENSION to "原地改扩展名"), enabled) { value -> controller.editDraft { it.copy(mode = value, acknowledgeInPlace = false) } }
                if (d.mode == OutputMode.COPY) {
                    FField("副本输出目录", d.output, "选择已存在的目录；同名目标会报告冲突。", enabled) { value -> controller.editDraft { it.copy(output = value) } }
                    FButton("浏览输出目录", enabled) { pickFolder { path -> controller.editDraft { it.copy(output = path) } } }
                } else {
                    FText("原地处理会建立原始备份，完整校验后再提交。请先在设置中登记源文件所在卷的独立工作区。", 13, c.secondary)
                    FCheck("我确认使用备份与安全替换流程", d.acknowledgeInPlace, enabled) { value -> controller.editDraft { it.copy(acknowledgeInPlace = value) } }
                }
                FCheck("仅在输出更小时发布", d.onlyIfSmaller, enabled) { value -> controller.editDraft { it.copy(onlyIfSmaller = value) } }
            }
            FCard {
                SectionTitle("体积预估", "固定质量的输出大小取决于内容。采样结果是估算范围，不能保证最终体积。")
                FButton(if (s.estimating) "正在采样…" else "采样估算所选文件", enabled && s.tools != null && s.selected.isNotEmpty()) { controller.estimateSelected() }
                if (s.estimating) { FProgress(null, "正在采样估算"); FButton("停止估算") { controller.stopScan() } }
                s.selected.take(8).forEach { row -> val estimate = row.estimate(d)
                    FText("${fileName(row.media.file.path.value)}  ·  ${estimate.expectedBytes?.let { "约 ${bytes(it)}" } ?: "待采样"}", 13)
                    if (estimate.basis != EstimateBasis.UNKNOWN) FText(estimate.explanation, 12, c.secondary)
                }
                if (s.selected.size > 8) FText("其余结果请在主页列表查看。", 12, c.secondary)
            }
            FButton(if (advanced) "⌄  收起高级选项与逐轨审核" else "›  高级选项与逐轨审核", quiet = true) { advanced = !advanced }
            if (advanced) {
                FCard {
                    SectionTitle("画面与元数据", "尺寸为偶数上限，不放大；留空表示保留。转封装不接受画面变换。")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FField("最大宽度", d.width, enabled = enabled && d.operation == MediaOperation.ENCODE, modifier = Modifier.weight(1f)) { value -> controller.editDraft { it.copy(width = value) } }
                        FField("最大高度", d.height, enabled = enabled && d.operation == MediaOperation.ENCODE, modifier = Modifier.weight(1f)) { value -> controller.editDraft { it.copy(height = value) } }
                        FField("输出帧率", d.fps, "如 30 或 30000/1001", enabled && d.operation == MediaOperation.ENCODE, Modifier.weight(1f)) { value -> controller.editDraft { it.copy(fps = value) } }
                    }
                    FCheck("保留元数据", d.preserveMetadata, enabled) { value -> controller.editDraft { it.copy(preserveMetadata = value) } }
                    FCheck("保留章节", d.preserveChapters, enabled) { value -> controller.editDraft { it.copy(preserveChapters = value) } }
                }
                s.selected.forEach { row -> key(row.media.file.path.value) { StreamEditor(controller, row, enabled) } }
            }
            if (conflict != null) FText(conflict, 13, c.danger)
            s.error?.let { FText(it, 13, c.danger) }
        }
        FDivider()
        Row(Modifier.fillMaxWidth().background(c.surface).padding(20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FButton("存为默认参数", enabled) { controller.saveTaskAsDefaults() }
            Spacer(Modifier.weight(1f))
            FButton("返回", onClick = close)
            FButton(if (s.preparing) "正在创建…" else if (s.active != null) "加入处理队列" else "开始处理", s.canStart && conflict == null && (d.mode != OutputMode.COPY || d.output.isNotBlank()) && (d.mode == OutputMode.COPY || d.acknowledgeInPlace), primary = true) { controller.startTask() }
        }
    }
}

@Composable
fun EncodingEditor(d: TaskDraft, enabled: Boolean, tools: Toolchain? = null, onChange: (TaskDraft) -> Unit) {
    FChoice("处理类型", d.operation, listOf(MediaOperation.ENCODE to "压缩视频", MediaOperation.REMUX to "仅转封装"), enabled) { value ->
        // 转封装切换清除不适用的画面参数，避免隐藏的旧值让计划无法创建。
        onChange(if (value == MediaOperation.REMUX) d.copy(operation = value, width = "", height = "", fps = "", rateControl = RateControl.QUALITY, quality = if (d.backend == Backend.X264) "23" else "28") else d.copy(operation = value))
    }
    if (d.operation == MediaOperation.ENCODE) {
        FChoice("编码器", d.backend, listOf(Backend.X264 to "CPU · H.264", Backend.NVENC to "NVIDIA · HEVC"), enabled) { value -> onChange(d.copy(backend = value, quality = if (value == Backend.X264) "23" else "28")) }
        if (d.backend == Backend.NVENC && tools?.supports(Backend.NVENC) != true) FText("NVIDIA 需要通过本机实际编码自检，开始时会校验可用性。", 12, LocalFluentColors.current.secondary)
        FChoice("体积控制", d.rateControl, listOf(RateControl.QUALITY to "固定质量", RateControl.BITRATE to "目标码率"), enabled) { onChange(d.copy(rateControl = it, quality = d.quality.toIntOrNull()?.takeIf { q -> q in 0..51 }?.toString() ?: if (d.backend == Backend.X264) "23" else "28")) }
        if (d.rateControl == RateControl.QUALITY) FField(if (d.backend == Backend.X264) "质量 CRF" else "质量 CQ", d.quality, "0–51，越低质量越高；CRF 与 CQ 不等价。", enabled) { onChange(d.copy(quality = it)) }
        else FField("视频码率（kbps）", d.bitrate, "音频和容器开销另计；16–200000 kbps。", enabled) { onChange(d.copy(bitrate = it)) }
        FChoice("编码速度", d.speed, listOf(Speed.BALANCED to "均衡", Speed.FAST to "快速"), enabled) { onChange(d.copy(speed = it)) }
    }
    FChoice("输出容器", d.container, listOf(null to "跟随输入", Container.MP4 to "MP4", Container.MKV to "MKV"), enabled) { onChange(d.copy(container = it)) }
}

@Composable
private fun StreamEditor(controller: WorkspaceController, row: WorkspaceRow, enabled: Boolean) {
    FCard {
        SectionTitle(fileName(row.media.file.path.value), "逐轨决定会保存在本次任务快照中。")
        row.media.media?.streams?.forEach { stream ->
            val existing = row.streams.firstOrNull { it.sourceIndex == stream.index }
            val automatic = if (stream.kind == StreamKind.VIDEO && !stream.attachedPicture && controller.state.value.draft.operation == MediaOperation.ENCODE) StreamAction.ENCODE else StreamAction.COPY
            val selected = existing?.action ?: automatic
            val options = listOf(StreamAction.COPY to "复制", StreamAction.DROP to "移除") + if (stream.kind in listOf(StreamKind.VIDEO, StreamKind.AUDIO)) listOf(StreamAction.ENCODE to if (stream.kind == StreamKind.AUDIO) "AAC 编码" else "编码") else emptyList()
            FChoice("#${stream.index} · ${streamLabel(stream.kind)} · ${stream.codec} · ${stream.tags["language"].orEmpty()}", selected, options, enabled) { action ->
                controller.editStream(row.media.file.path, StreamPolicy(stream.index, action, if (action == StreamAction.ENCODE && stream.kind == StreamKind.AUDIO) AacSettings() else null, existing?.defaultDisposition))
            }
            if (stream.kind in listOf(StreamKind.VIDEO, StreamKind.AUDIO) && selected != StreamAction.DROP) FCheck("设为默认轨 #${stream.index}", existing?.defaultDisposition ?: (stream.dispositions["default"] == 1), enabled) { value ->
                controller.editStream(row.media.file.path, StreamPolicy(stream.index, selected, existing?.aac, value))
            }
            if (stream.kind == StreamKind.AUDIO && selected == StreamAction.ENCODE) {
                val aac = existing?.aac ?: AacSettings()
                FChoice("AAC 码率 #${stream.index}", aac.bitrateKbps, listOf(128 to "128 kbps", 192 to "192 kbps", 256 to "256 kbps", 320 to "320 kbps"), enabled) { value ->
                    controller.editStream(row.media.file.path, StreamPolicy(stream.index, selected, aac.copy(bitrateKbps = value), existing?.defaultDisposition))
                }
                FText("保留源采样率和声道；不隐式降混。", 12, LocalFluentColors.current.secondary)
            }
            FDivider()
        }
        row.rejection(controller.state.value.draft)?.let { FText(it, 12, LocalFluentColors.current.danger) }
    }
}

@Composable
fun PageHeading(title: String, subtitle: String, close: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FButton("‹  返回", quiet = true, onClick = close)
        Column { FText(title, 28, strong = true); FText(subtitle, 12, LocalFluentColors.current.secondary) }
    }
    FDivider()
}
