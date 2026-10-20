package org.ergoutree.compress.workspace

import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.ergoutree.compress.domain.*
import org.ergoutree.compress.presentation.*
import kotlin.math.roundToInt

private enum class OptionTab(val label: String, val glyph: FluentGlyph) {
    COMPRESSION("压缩参数", FluentGlyph.Lightning),
    OUTPUT("输出与保护", FluentGlyph.OpenFolder),
    ESTIMATE("体积估算", FluentGlyph.Info),
    STREAMS("逐轨审核", FluentGlyph.List),
    ADVANCED("高级选项", FluentGlyph.Settings),
}

/**
 * 处理选项窗口：左侧分类导航 + 右侧滚动设置，底部固定开始操作。
 * 参数只作用于本次草稿；开始任务后冻结快照，不读取后续设置变化。
 */
@Composable
fun TaskOptionsPage(
    controller: WorkspaceController,
    pickFolder: ((String) -> Unit) -> Unit,
    close: () -> Unit,
    openSettings: () -> Unit = {},
) {
    val s by controller.state.collectAsState()
    val d = s.draft
    val c = LocalFluentColors.current
    var tab by remember { mutableStateOf(OptionTab.COMPRESSION) }
    val enabled = !s.intakeBusy && !s.closing && !s.restartRequired
    val conflict = s.selected.firstNotNullOfOrNull { it.rejection(d) }
    val conflictCount = s.selected.count { it.rejection(d) != null }
    val outputMissing = d.mode == OutputMode.COPY && d.output.isBlank()
    val canStart = s.canStart && conflict == null && !outputMissing && (d.mode == OutputMode.COPY || d.acknowledgeInPlace)
    val selectedBytes = s.selected.sumOf { it.media.file.sizeBytes }
    val estimatedBytes = s.selected.mapNotNull { it.estimate(d).expectedBytes }.takeIf { it.isNotEmpty() }?.sum()

    Column(Modifier.fillMaxSize().background(c.base)) {
        FPageHeader(
            title = "处理选项",
            subtitle = "本次任务 · ${s.selected.size} 个文件 · 原始 ${bytes(selectedBytes)}",
            onBack = close,
        ) {
            if (conflictCount > 0) FBadge("$conflictCount 项需审核", FluentTone.Caution)
            FButton("存为默认参数", controller::saveTaskAsDefaults, style = FButtonStyle.Subtle, glyph = FluentGlyph.Save,
                enabled = enabled && s.selected.isNotEmpty())
        }
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier.width(190.dp).fillMaxHeight().padding(horizontal = 12.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                OptionTab.entries.forEach { item ->
                    FNavigationItem(
                        glyph = item.glyph,
                        label = item.label,
                        selected = item == tab,
                        onClick = { tab = item },
                        badge = when (item) {
                            OptionTab.STREAMS -> conflictCount.takeIf { it > 0 }?.toString()
                            OptionTab.OUTPUT -> if (outputMissing) "!" else null
                            else -> null
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                FDivider()
                Spacer(Modifier.height(12.dp))
                FInlineNote("处理参数会冻结到任务快照；之后修改设置不会影响已创建的任务。", FluentTone.Neutral)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (s.selected.isEmpty()) {
                    FEmptyState(
                        FluentGlyph.Filter,
                        "没有选中的文件",
                        "返回主窗口，从列表中选择要处理的文件后再打开处理选项。",
                        Modifier.align(Alignment.Center),
                    ) { FButton("返回主窗口", close) }
                } else if (tab == OptionTab.STREAMS) {
                    StreamsTab(controller, s, enabled)
                } else {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        if (conflict != null) {
                            FInfoBar("$conflictCount 个选中文件存在参数冲突：$conflict", title = "开始前需要处理",
                                tone = FluentTone.Caution, actionLabel = "逐轨审核", onAction = { tab = OptionTab.STREAMS })
                        }
                        when (tab) {
                            OptionTab.COMPRESSION -> CompressionTab(d, enabled, s.tools, controller)
                            OptionTab.OUTPUT -> OutputTab(controller, d, enabled, outputMissing, openSettings, pickFolder)
                            OptionTab.ESTIMATE -> EstimateTab(controller, s, d, enabled)
                            OptionTab.ADVANCED -> AdvancedTab(controller, d, enabled)
                            OptionTab.STREAMS -> Unit
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        FDivider()
        Row(
            Modifier.fillMaxWidth().background(c.card).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                FText(
                    buildList {
                        add("已选 ${s.selected.size} 个 · 原始 ${bytes(selectedBytes)}")
                        if (estimatedBytes != null) add("预计 ${bytes(estimatedBytes)}")
                        add(operationLabel(d.operation))
                    }.joinToString(" · "),
                    type = FType.Caption, maxLines = 1,
                )
                FText(
                    when {
                        outputMissing -> "尚未设置副本输出目录"
                        conflict != null -> conflict
                        d.mode != OutputMode.COPY && !d.acknowledgeInPlace -> "需要确认原位处理与备份流程"
                        else -> "开始后任务参数将冻结，可在历史与日志中追踪"
                    },
                    type = FType.Caption, color = if (outputMissing || conflict != null) c.caution else c.textSecondary, maxLines = 1,
                )
            }
            FButton("取消", close)
            FButton(
                if (s.preparing) "正在创建…" else if (s.active != null) "加入处理队列" else "开始处理",
                controller::startTask,
                style = FButtonStyle.Accent,
                glyph = FluentGlyph.Play,
                enabled = canStart && !s.preparing && !s.closing,
            )
        }
    }
}

@Composable
private fun CompressionTab(d: TaskDraft, enabled: Boolean, tools: Toolchain?, controller: WorkspaceController) {
    val c = LocalFluentColors.current
    FCard {
        FSectionTitle("处理方式", "压缩视频会重新编码主视频；仅转封装只更换容器或调整轨道。")
        FChoiceCards(
            listOf(
                FChoice(MediaOperation.ENCODE, "压缩视频", "重新编码主视频，可调整质量、速度与尺寸。"),
                FChoice(MediaOperation.REMUX, "仅转封装", "不重新编码画面，只调整容器与逐轨策略。"),
            ),
            d.operation,
            { value ->
                controller.editDraft {
                    if (value == MediaOperation.REMUX) it.copy(operation = value, width = "", height = "", fps = "",
                        rateControl = RateControl.QUALITY, quality = if (it.backend == Backend.X264) "23" else "28")
                    else it.copy(operation = value)
                }
            },
            enabled = enabled,
            horizontal = true,
        )
    }
    if (d.operation == MediaOperation.ENCODE) {
        FCard {
            FSectionTitle("编码器", "不会在硬件不可用时静默切换到 CPU。")
            FChoiceCards(
                listOf(
                    FChoice(Backend.X264, "CPU · H.264", "兼容性最好；默认 CRF 23。", trailing = "内置"),
                    FChoice(Backend.NVENC, "NVIDIA · HEVC", "使用本机 NVENC；需要实际编码自检。",
                        trailing = when {
                            tools == null -> "待自检"
                            tools.supports(Backend.NVENC) -> "已通过"
                            else -> "不可用"
                        }),
                ),
                d.backend,
                { value -> controller.editDraft { it.copy(backend = value, quality = if (value == Backend.X264) "23" else "28") } },
                enabled = enabled,
                horizontal = true,
            )
            if (d.backend == Backend.NVENC && tools?.supports(Backend.NVENC) != true) {
                FInlineNote("NVIDIA HEVC 尚未通过本机自检；开始任务前会再次校验。", FluentTone.Caution)
            }
        }
        FCard {
            FSectionTitle("质量与速度", "固定质量不保证文件大小；CRF 与 CQ 不是等价刻度。")
            FSegmented(
                listOf(RateControl.QUALITY to "固定质量", RateControl.BITRATE to "目标码率"),
                d.rateControl,
                { value -> controller.editDraft { it.copy(rateControl = value, quality = it.quality.toIntOrNull()?.takeIf { q -> q in 0..51 }?.toString() ?: if (it.backend == Backend.X264) "23" else "28") } },
                enabled = enabled,
            )
            if (d.rateControl == RateControl.QUALITY) {
                QualityControl(d, enabled) { quality -> controller.editDraft { it.copy(quality = quality) } }
            } else {
                FTextField(
                    value = d.bitrate,
                    onValueChange = { value -> if (value.isEmpty() || value.all(Char::isDigit)) controller.editDraft { it.copy(bitrate = value) } },
                    label = "视频码率（kbps）",
                    description = "16–200000；音频与容器开销另计。",
                    placeholder = "2000",
                    modifier = Modifier.width(220.dp),
                    enabled = enabled,
                )
            }
            FText("编码速度", type = FType.CaptionStrong, modifier = Modifier.padding(top = 4.dp))
            FSegmented(listOf(Speed.BALANCED to "均衡", Speed.FAST to "快速"), d.speed,
                { value -> controller.editDraft { it.copy(speed = value) } }, enabled = enabled)
            FInlineNote("均衡：x264 medium / NVENC p5；快速：veryfast / p4。速度不保证输出更小。", FluentTone.Neutral)
        }
    }
    FCard {
        FSectionTitle("输出容器", "更换容器不等于重新编码；音频与字幕组合不兼容时会提示审核。")
        FSegmented(
            listOf<Pair<Container?, String>>(null to "跟随输入", Container.MP4 to "MP4", Container.MKV to "MKV"),
            d.container,
            { value -> controller.editDraft { it.copy(container = value) } },
            enabled = enabled,
        )
        if (d.container != null) FInlineNote("文件主名保持不变，扩展名随真实容器变化，不会用 .mkv 名称伪装 MP4。", FluentTone.Neutral)
    }
    FCard(subtle = true) {
        FInlineNote(when {
            d.operation == MediaOperation.REMUX -> "转封装不会改变画质，也不会自动缩小体积；轨道 Copy/Drop 在“逐轨审核”中设置。"
            d.backend == Backend.X264 -> "当前使用 CPU H.264。保持原分辨率与帧率，除非在高级选项中明确修改。"
            else -> "当前使用 NVIDIA HEVC。自检只证明编码器可运行，不保证画质或体积。"
        }, FluentTone.Neutral)
    }
}

@Composable
private fun QualityControl(d: TaskDraft, enabled: Boolean, onChange: (String) -> Unit) {
    val c = LocalFluentColors.current
    val quality = d.quality.toIntOrNull()?.coerceIn(0, 51) ?: if (d.backend == Backend.X264) 23 else 28
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FText(if (d.backend == Backend.X264) "质量 CRF" else "质量 CQ", type = FType.CaptionStrong)
                FHelpButton(if (d.backend == Backend.X264) "质量 CRF" else "质量 CQ", "0–51，越低通常越清晰且体积越大。CRF 与 CQ 使用不同编码器刻度，不能直接比较。")
            }
            FSlider(quality.toFloat(), 0f..51f, { onChange(it.roundToInt().toString()) }, Modifier.fillMaxWidth(),
                enabled = enabled, steps = 50, contentDescription = "质量值")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FText("更小", type = FType.Caption, color = c.textTertiary)
                FText("更清晰", type = FType.Caption, color = c.textTertiary)
            }
        }
        FTextField(d.quality, { value ->
            if (value.isEmpty() || value.all(Char::isDigit)) onChange(value.take(2))
        }, label = "质量值", modifier = Modifier.width(78.dp), enabled = enabled)
        if (d.quality.toIntOrNull() !in 0..51) FBadge("0–51", FluentTone.Critical)
    }
}

@Composable
private fun OutputTab(
    controller: WorkspaceController,
    d: TaskDraft,
    enabled: Boolean,
    outputMissing: Boolean,
    openSettings: () -> Unit,
    pickFolder: ((String) -> Unit) -> Unit,
) {
    val c = LocalFluentColors.current
    FCard {
        FSectionTitle("处理方式", "默认保存副本；原地模式需要原始备份与安全提交工作区。")
        FChoiceCards(
            listOf(
                FChoice(OutputMode.COPY, "保存副本", "在输出目录生成新文件，原文件保持不动。"),
                FChoice(OutputMode.REPLACE, "原地替换", "校验通过后以同名替换原文件，并保留原始备份。"),
                FChoice(OutputMode.CHANGE_EXTENSION, "原地改扩展名", "结果按真实容器扩展名留在原目录。"),
            ),
            d.mode,
            { value -> controller.editDraft { it.copy(mode = value, acknowledgeInPlace = false) } },
            enabled = enabled,
        )
    }
    if (d.mode == OutputMode.COPY) {
        FCard {
            FSectionTitle("副本输出目录", "必须选择已存在的目录；同名目标会报告冲突，不会覆盖。")
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FTextField(d.output, { value -> controller.editDraft { it.copy(output = value) } },
                    label = "输出目录", placeholder = "选择一个已存在的文件夹", modifier = Modifier.weight(1f), enabled = enabled,
                    leading = FluentGlyph.Folder)
                FButton("浏览…", { pickFolder { path -> controller.editDraft { it.copy(output = path) } } },
                    glyph = FluentGlyph.OpenFolder, enabled = enabled)
            }
            if (outputMissing) FInlineNote("尚未设置输出目录，无法开始副本任务。", FluentTone.Caution)
            else FInlineNote("输出：${d.output}", FluentTone.Neutral)
        }
    } else {
        FCard {
            FSectionTitle("原位处理保护", "源文件所在卷必须先在设置中登记独立工作区。")
            FInfoBar(
                "原作会先建立经过证据核对的原始备份，完整解码校验通过后才替换；失败不会删除源文件。",
                title = "这是一个需要明确确认的操作",
                tone = FluentTone.Caution,
                actionLabel = "打开设置",
                onAction = openSettings,
            )
            FSwitch(d.acknowledgeInPlace, { value -> controller.editDraft { it.copy(acknowledgeInPlace = value) } },
                enabled = enabled, label = "我确认使用备份与安全替换流程")
        }
    }
    FCard {
        FSectionTitle("文件保护")
        FSettingRow("仅在输出更小时发布", "结果变大时跳过发布，保留原文件与候选记录。") {
            FSwitch(d.onlyIfSmaller, { value -> controller.editDraft { it.copy(onlyIfSmaller = value) } }, enabled = enabled)
        }
        FSettingDivider()
        FSettingRow("保留元数据", "关闭会丢失标题等标签；轨道 disposition 仍按计划保留。") {
            FSwitch(d.preserveMetadata, { value -> controller.editDraft { it.copy(preserveMetadata = value) } }, enabled = enabled)
        }
        FSettingDivider()
        FSettingRow("保留章节", "关闭是明确的内容删除决定。") {
            FSwitch(d.preserveChapters, { value -> controller.editDraft { it.copy(preserveChapters = value) } }, enabled = enabled)
        }
    }
    FCard(subtle = true) {
        FInlineNote("当前处理方式：${modeLabel(d.mode)}。输出路径与同名冲突会在冻结任务时统一核对。", FluentTone.Neutral)
    }
}

@Composable
private fun EstimateTab(controller: WorkspaceController, s: WorkspaceState, d: TaskDraft, enabled: Boolean) {
    val c = LocalFluentColors.current
    val estimates = s.selected.map { row -> row to row.estimate(d) }
    val original = s.selected.sumOf { it.media.file.sizeBytes }
    val expected = estimates.mapNotNull { it.second.expectedBytes }.takeIf { it.isNotEmpty() }?.sum()
    FCard {
        FSectionTitle("采样估算", "固定质量取决于画面内容；采样结果给出范围与依据，不保证最终体积。")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.estimating) {
                FSpinner(14.dp)
                FText("正在估算 ${s.estimateDone}/${s.estimateTotal}", type = FType.Caption)
                FProgressBar(if (s.estimateTotal > 0) s.estimateDone.toFloat() / s.estimateTotal else null,
                    Modifier.weight(1f), label = "估算进度")
                FButton("停止", controller::stopScan, style = FButtonStyle.Subtle)
            } else {
                FButton("估算所选文件", controller::estimateSelected, style = FButtonStyle.Accent,
                    glyph = FluentGlyph.Lightning, enabled = enabled && s.tools != null && s.selected.isNotEmpty())
                FText("按当前参数采样所选文件", type = FType.Caption, color = c.textSecondary)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            DetailValue("原始大小", bytes(original))
            DetailValue("预计输出", expected?.let(::bytes) ?: "待估算")
            if (expected != null && original > 0) {
                val change = (original - expected).toDouble() / original * 100
                val rounded = (kotlin.math.abs(change) * 10).roundToInt() / 10.0
                DetailValue("预计变化", "${if (change >= 0) "−" else "+"}$rounded%")
            }
        }
    }
    FCard {
        FSectionTitle("逐项结果", "估算范围仅用于决策；实际大小以完整校验后的结果为准。")
        estimates.take(40).forEach { (row, estimate) ->
            val rejection = row.rejection(d)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    FText(fileName(row.media.file.path.value), maxLines = 1)
                    FText(listOfNotNull(rejection, estimate.explanation.takeIf { it.isNotBlank() }).joinToString(" · "),
                        type = FType.Caption, color = if (rejection != null) c.caution else c.textSecondary, maxLines = 2)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    FText(bytes(row.media.file.sizeBytes), type = FType.Caption, color = c.textTertiary)
                    FText(estimate.expectedBytes?.let { "约 ${bytes(it)}" } ?: "待估算", type = FType.Caption,
                        color = if (estimate.expectedBytes == null) c.textTertiary else c.textPrimary)
                    estimateRange(estimate)?.let { FText(it, type = FType.Caption, color = c.textTertiary) }
                }
            }
            FDivider()
        }
        if (s.selected.size > 40) FText("其余 ${s.selected.size - 40} 项可在主窗口列表中查看。", type = FType.Caption, color = c.textSecondary)
    }
}

@Composable
private fun DetailValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        FText(label, type = FType.Caption, color = LocalFluentColors.current.textTertiary)
        FText(value, type = FType.BodyLarge, maxLines = 1)
    }
}

@Composable
private fun AdvancedTab(controller: WorkspaceController, d: TaskDraft, enabled: Boolean) {
    val c = LocalFluentColors.current
    val canResize = d.operation == MediaOperation.ENCODE
    FCard {
        FSectionTitle("画面尺寸与帧率", "尺寸是偶数上限，不放大；留空保持原值。转封装不接受画面变换。")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            FTextField(
                value = d.width,
                onValueChange = { value -> if (value.isEmpty() || value.all(Char::isDigit)) controller.editDraft { it.copy(width = value) } },
                label = "最大宽度",
                placeholder = "保持",
                modifier = Modifier.weight(1f),
                enabled = enabled && canResize,
            )
            FTextField(
                value = d.height,
                onValueChange = { value -> if (value.isEmpty() || value.all(Char::isDigit)) controller.editDraft { it.copy(height = value) } },
                label = "最大高度",
                placeholder = "保持",
                modifier = Modifier.weight(1f),
                enabled = enabled && canResize,
            )
            FTextField(
                value = d.fps,
                onValueChange = { value -> controller.editDraft { it.copy(fps = value) } },
                label = "输出帧率",
                placeholder = "如 30 或 30000/1001",
                modifier = Modifier.weight(1f),
                enabled = enabled && canResize,
            )
        }
        if (!canResize) FInlineNote("仅转封装模式不启用尺寸与帧率变换。", FluentTone.Neutral)
    }
    FCard {
        FSectionTitle("轨道与元数据")
        FSettingRow("保留元数据", "文件与流标签默认保留。") {
            FSwitch(d.preserveMetadata, { value -> controller.editDraft { it.copy(preserveMetadata = value) } }, enabled = enabled)
        }
        FSettingDivider()
        FSettingRow("保留章节", "章节默认保留；关闭会在计划中明确记录。") {
            FSwitch(d.preserveChapters, { value -> controller.editDraft { it.copy(preserveChapters = value) } }, enabled = enabled)
        }
    }
    FCard(subtle = true) {
        FInlineNote("逐轨 Copy / Encode / Drop 与默认音轨在“逐轨审核”中设置；未指定的非视频轨默认复制。", FluentTone.Neutral)
    }
}

@Composable
private fun StreamsTab(controller: WorkspaceController, s: WorkspaceState, enabled: Boolean) {
    val c = LocalFluentColors.current
    var selectedPath by remember(s.selected.map { it.media.file.path.value }.joinToString("|")) {
        mutableStateOf(s.selected.firstOrNull()?.media?.file?.path?.value)
    }
    val row = s.selected.firstOrNull { it.media.file.path.value == selectedPath } ?: s.selected.firstOrNull()
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(220.dp).fillMaxHeight().background(c.cardSubtle)) {
            FText("文件", type = FType.CaptionStrong, color = c.textSecondary, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
            FDivider()
            LazyColumn(Modifier.fillMaxSize()) {
                items(s.selected, key = { it.media.file.path.value }) { item ->
                    val selected = item.media.file.path.value == selectedPath
                    val conflict = item.rejection(s.draft)
                    val interaction = remember { MutableInteractionSource() }
                    val hovered by interaction.collectIsHoveredAsState()
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .background(if (selected) c.selected else if (hovered) c.hover else androidx.compose.ui.graphics.Color.Transparent)
                            .hoverable(interaction)
                            .clickable(role = Role.Button) { selectedPath = item.media.file.path.value }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) c.accent else androidx.compose.ui.graphics.Color.Transparent))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            FText(fileName(item.media.file.path.value), type = if (selected) FType.BodyStrong else FType.Body, maxLines = 1)
                            FText(mediaSummary(item), type = FType.Caption, color = c.textTertiary, maxLines = 1)
                        }
                        if (conflict != null) FBadge("!", FluentTone.Caution)
                    }
                    FDivider()
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.divider))
        if (row == null) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                FEmptyState(FluentGlyph.List, "没有可审核的轨道", "选择文件后会在这里显示逐轨策略。")
            }
        } else {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                FCard {
                    FSectionTitle(fileName(row.media.file.path.value), row.media.file.path.value)
                    row.rejection(s.draft)?.let { FInfoBar(it, title = "当前参数冲突", tone = FluentTone.Caution) }
                    FInlineNote("未指定策略时：主视频按任务处理，其余轨道复制。丢弃默认轨后，MP4 需要为保留轨明确选择默认。", FluentTone.Neutral)
                }
                row.media.media?.streams?.forEach { stream ->
                    val existing = row.streams.firstOrNull { it.sourceIndex == stream.index }
                    val automatic = if (stream.kind == StreamKind.VIDEO && !stream.attachedPicture && s.draft.operation == MediaOperation.ENCODE)
                        StreamAction.ENCODE else StreamAction.COPY
                    val selectedAction = existing?.action ?: automatic
                    val actions = buildList {
                        add(StreamAction.COPY to "复制")
                        if (stream.kind == StreamKind.VIDEO || stream.kind == StreamKind.AUDIO) {
                            add(StreamAction.ENCODE to if (stream.kind == StreamKind.AUDIO) "AAC 编码" else "编码")
                        }
                        add(StreamAction.DROP to "移除")
                    }
                    FCard(padding = 14.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FIcon(
                                when (stream.kind) {
                                    StreamKind.VIDEO -> FluentGlyph.Video
                                    StreamKind.AUDIO -> FluentGlyph.Audio
                                    else -> FluentGlyph.File
                                }, 16.dp, c.textSecondary,
                            )
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                FText("#${stream.index} · ${streamLabel(stream.kind)} · ${stream.codec}", type = FType.BodyStrong, maxLines = 1)
                                FText(
                                    listOfNotNull(
                                        stream.tags["language"]?.takeIf { it.isNotBlank() }?.let { "语言 $it" },
                                        stream.width?.let { w -> stream.height?.let { h -> "$w × $h" } },
                                        stream.channels?.let { "$it 声道" },
                                        stream.sampleRate?.let { "${it} Hz" },
                                    ).joinToString(" · ").ifBlank { "无附加信息" },
                                    type = FType.Caption, color = c.textTertiary, maxLines = 1,
                                )
                            }
                            FSegmented(actions, selectedAction, { action ->
                                controller.editStream(row.media.file.path,
                                    StreamPolicy(stream.index, action, if (action == StreamAction.ENCODE && stream.kind == StreamKind.AUDIO) AacSettings() else null,
                                        existing?.defaultDisposition))
                            }, Modifier.width(220.dp), enabled = enabled)
                        }
                        if (stream.kind == StreamKind.VIDEO || stream.kind == StreamKind.AUDIO) {
                            FSettingRow("设为默认轨", "只影响容器 disposition，不改变媒体的实际轨道内容。") {
                                FSwitch(existing?.defaultDisposition ?: (stream.dispositions["default"] == 1), { value ->
                                    controller.editStream(row.media.file.path,
                                        StreamPolicy(stream.index, selectedAction, existing?.aac, value))
                                }, enabled = enabled)
                            }
                        }
                        if (stream.kind == StreamKind.AUDIO && selectedAction == StreamAction.ENCODE) {
                            val aac = existing?.aac ?: AacSettings()
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                FText("AAC 码率", type = FType.CaptionStrong)
                                FComboBox(aac.bitrateKbps,
                                    listOf(128 to "128 kbps", 192 to "192 kbps", 256 to "256 kbps", 320 to "320 kbps"),
                                    { value -> controller.editStream(row.media.file.path,
                                        StreamPolicy(stream.index, selectedAction, aac.copy(bitrateKbps = value), existing?.defaultDisposition)) },
                                    enabled = enabled, menuWidth = 150.dp, contentDescription = "AAC 码率")
                                FText("保留源采样率与声道，不隐式降混", type = FType.Caption, color = c.textTertiary)
                            }
                        }
                    }
                }
                if (row.media.media == null) FCard { FText("无法读取轨道信息：${row.media.reason.orEmpty()}", color = c.textSecondary) }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** 默认任务设置复用同一套编码控件，避免设置页与处理选项产生两套语义。 */
@Composable
fun EncodingEditor(d: TaskDraft, enabled: Boolean, tools: Toolchain? = null, onChange: (TaskDraft) -> Unit) {
    val c = LocalFluentColors.current
    FChoiceCards(
        listOf(
            FChoice(MediaOperation.ENCODE, "压缩视频", "重新编码主视频。"),
            FChoice(MediaOperation.REMUX, "仅转封装", "只更换容器或调整轨道。"),
        ),
        d.operation,
        { value ->
            onChange(if (value == MediaOperation.REMUX) d.copy(operation = value, width = "", height = "", fps = "",
                rateControl = RateControl.QUALITY, quality = if (d.backend == Backend.X264) "23" else "28") else d.copy(operation = value))
        },
        enabled = enabled, horizontal = true,
    )
    if (d.operation == MediaOperation.ENCODE) {
        FChoiceCards(
            listOf(
                FChoice(Backend.X264, "CPU · H.264", trailing = "内置"),
                FChoice(Backend.NVENC, "NVIDIA · HEVC",
                    trailing = if (tools?.supports(Backend.NVENC) == true) "已通过" else "待自检"),
            ),
            d.backend,
            { value -> onChange(d.copy(backend = value, quality = if (value == Backend.X264) "23" else "28")) },
            enabled = enabled, horizontal = true,
        )
        FSegmented(listOf(RateControl.QUALITY to "固定质量", RateControl.BITRATE to "目标码率"), d.rateControl,
            { onChange(d.copy(rateControl = it)) }, enabled = enabled)
        if (d.rateControl == RateControl.QUALITY) {
            val quality = d.quality.toIntOrNull()?.coerceIn(0, 51) ?: 23
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FSlider(quality.toFloat(), 0f..51f, { onChange(d.copy(quality = it.roundToInt().toString())) },
                    Modifier.weight(1f), enabled = enabled, steps = 50, contentDescription = "默认质量值")
                FTextField(d.quality, { value -> if (value.isEmpty() || value.all(Char::isDigit)) onChange(d.copy(quality = value.take(2))) },
                    label = "质量值", enabled = enabled, modifier = Modifier.width(86.dp))
            }
        } else {
            FTextField(
                value = d.bitrate,
                onValueChange = { value -> if (value.isEmpty() || value.all(Char::isDigit)) onChange(d.copy(bitrate = value)) },
                label = "视频码率（kbps）",
                placeholder = "2000",
                modifier = Modifier.width(220.dp),
                enabled = enabled,
            )
        }
        FSegmented(listOf(Speed.BALANCED to "均衡", Speed.FAST to "快速"), d.speed, { onChange(d.copy(speed = it)) }, enabled = enabled)
    }
    FText("输出容器", type = FType.CaptionStrong)
    FSegmented(
        listOf<Pair<Container?, String>>(null to "跟随输入", Container.MP4 to "MP4", Container.MKV to "MKV"),
        d.container, { onChange(d.copy(container = it)) }, enabled = enabled,
    )
    if (d.backend == Backend.NVENC && tools?.supports(Backend.NVENC) != true) {
        FInlineNote("NVIDIA 编码器需要先通过本机实际编码自检。", FluentTone.Caution)
    }
    if (d.operation == MediaOperation.REMUX && d.rateControl == RateControl.BITRATE) {
        FInlineNote("转封装模式不会应用码率设置。", FluentTone.Neutral)
    }
    if (enabled && d.quality.toIntOrNull() !in 0..51) FInlineNote("质量需要 0–51 的整数。", FluentTone.Critical)
}
