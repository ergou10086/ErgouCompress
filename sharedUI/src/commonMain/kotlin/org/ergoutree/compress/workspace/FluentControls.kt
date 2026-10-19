package org.ergoutree.compress.workspace

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import org.ergoutree.compress.domain.*
import kotlin.math.roundToInt

/** Fluent 语义色随主题切换，避免各窗口分别维护不一致的颜色。 */
data class FluentColors(val background: Color, val surface: Color, val raised: Color, val text: Color,
    val secondary: Color, val border: Color, val accent: Color, val selected: Color, val danger: Color)
val LocalFluentColors = staticCompositionLocalOf { lightColors() }
val LocalFluentFont = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }
val LocalReducedMotion = staticCompositionLocalOf { false }
private fun lightColors() = FluentColors(Color(0xfff3f3f3), Color(0xfffbfbfb), Color.White, Color(0xff1b1b1b),
    Color(0xff616161), Color(0xffdedede), Color(0xff0067c0), Color(0xffe8f2fc), Color(0xffb3261e))
private fun darkColors() = FluentColors(Color(0xff202020), Color(0xff292929), Color(0xff323232), Color(0xfff3f3f3),
    Color(0xffbdbdbd), Color(0xff454545), Color(0xff75baff), Color(0xff243e53), Color(0xffffb4ab))

@Composable
fun FluentTheme(preferences: DesktopPreferences, systemDark: Boolean = false, font: FontFamily = LocalFluentFont.current, content: @Composable () -> Unit) {
    val dark = preferences.theme == AppTheme.DARK || preferences.theme == AppTheme.SYSTEM && systemDark
    CompositionLocalProvider(LocalFluentColors provides if (dark) darkColors() else lightColors(),
        LocalReducedMotion provides preferences.reduceMotion, LocalFluentFont provides font, content = content)
}

@Composable
fun FText(text: String, size: Int = 14, color: Color = LocalFluentColors.current.text,
    strong: Boolean = false, modifier: Modifier = Modifier, lines: Int = Int.MAX_VALUE) {
    BasicText(text, modifier, style = TextStyle(color = color, fontSize = size.sp, lineHeight = (size + 6).sp,
        fontFamily = LocalFluentFont.current, fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal),
        maxLines = lines, overflow = TextOverflow.Ellipsis)
}

@Composable
fun FButton(label: String, enabled: Boolean = true, primary: Boolean = false, quiet: Boolean = false,
    modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = LocalFluentColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(4.dp)
    val background = when { !enabled -> c.border.copy(alpha = .4f); primary -> c.accent.copy(alpha = if (pressed) .8f else if (hovered) .9f else 1f)
        hovered || pressed -> c.border.copy(alpha = .5f); quiet -> Color.Transparent; else -> c.raised }
    Box(modifier.heightIn(min = 34.dp).clip(shape).background(background)
        .border(if (focused) 2.dp else 1.dp, if (focused) c.accent else if (quiet || primary) Color.Transparent else c.border, shape)
        .onFocusChanged { focused = it.isFocused }.hoverable(interaction)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
        FText(label, color = if (!enabled) c.secondary else if (primary) if (c.accent.red > .3f) Color(0xff102030) else Color.White else c.text)
    }
}

@Composable
fun FCheck(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val c = LocalFluentColors.current
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.onFocusChanged { focused = it.isFocused }.toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(20.dp).background(if (checked) c.accent else c.raised, RoundedCornerShape(4.dp))
            .border(if (focused) 2.dp else 1.dp, if (focused || checked) c.accent else c.secondary, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
            if (checked) FText("✓", 13, if (c.accent.red > .3f) Color.Black else Color.White, true)
        }
        FText(label, color = if (enabled) c.text else c.secondary)
    }
}

@Composable
fun FField(label: String, value: String, hint: String = "", enabled: Boolean = true, modifier: Modifier = Modifier,
    singleLine: Boolean = true, onValue: (String) -> Unit) {
    val c = LocalFluentColors.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        FText(label, strong = true)
        BasicTextField(value, onValue, enabled = enabled, singleLine = singleLine,
            textStyle = TextStyle(color = if (enabled) c.text else c.secondary, fontFamily = LocalFluentFont.current, fontSize = 14.sp),
            cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth().heightIn(min = 34.dp).onFocusChanged { focused = it.isFocused }
                .background(c.raised, RoundedCornerShape(4.dp)).border(if (focused) 2.dp else 1.dp, if (focused) c.accent else c.border, RoundedCornerShape(4.dp))
                .padding(9.dp).semantics { contentDescription = "${label}输入框" })
        if (hint.isNotBlank()) FText(hint, 12, c.secondary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> FChoice(label: String, selected: T, values: List<Pair<T, String>>, enabled: Boolean = true, onChange: (T) -> Unit) {
    val c = LocalFluentColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FText(label, strong = true)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            values.forEach { (value, title) -> FButton((if (selected == value) "●  " else "") + title, enabled, quiet = selected != value,
                modifier = Modifier.semantics { this.selected = selected == value }.border(1.dp, if (selected == value) c.accent else c.border, RoundedCornerShape(4.dp))) { onChange(value) } }
        }
    }
}

@Composable
fun FCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = LocalFluentColors.current
    Column(modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(8.dp)).border(1.dp, c.border, RoundedCornerShape(8.dp))
        .padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

@Composable
fun FDivider() = Box(Modifier.fillMaxWidth().height(1.dp).background(LocalFluentColors.current.border))

@Composable
fun SectionTitle(title: String, description: String = "") {
    FText(title, 20, strong = true)
    if (description.isNotBlank()) FText(description, 12, LocalFluentColors.current.secondary)
}

@Composable
fun FProgress(fraction: Float?, label: String, modifier: Modifier = Modifier) {
    val c = LocalFluentColors.current
    val reduced = LocalReducedMotion.current
    var phase by remember { mutableFloatStateOf(0f) }
    // 未知时长只呈现不定进度，不能把动画的相位当作任务百分比。
    LaunchedEffect(fraction == null, reduced) {
        if (fraction == null && !reduced) while (true) { withFrameMillis { phase = (it % 1600) / 1600f } }
    }
    Canvas(modifier.fillMaxWidth().height(4.dp).semantics {
        contentDescription = label
        progressBarRangeInfo = fraction?.let { ProgressBarRangeInfo(it.coerceIn(0f, 1f), 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
    }) {
        drawRoundRect(c.border, cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        val start = if (fraction == null) (size.width * phase * .7f) else 0f
        val width = if (fraction == null) size.width * .3f else size.width * fraction.coerceIn(0f, 1f)
        drawRoundRect(c.accent, topLeft = androidx.compose.ui.geometry.Offset(start, 0f), size = androidx.compose.ui.geometry.Size(width, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
    }
}

@Composable
fun FolderSymbol(scanning: Boolean = false) {
    val c = LocalFluentColors.current
    Canvas(Modifier.size(52.dp)) {
        val path = Path().apply { moveTo(size.width * .08f, size.height * .25f); lineTo(size.width * .4f, size.height * .25f)
            lineTo(size.width * .5f, size.height * .36f); lineTo(size.width * .92f, size.height * .36f)
            lineTo(size.width * .92f, size.height * .84f); lineTo(size.width * .08f, size.height * .84f); close() }
        drawPath(path, c.accent.copy(alpha = .12f)); drawPath(path, c.accent, style = androidx.compose.ui.graphics.drawscope.Stroke(1.7.dp.toPx()))
        if (!scanning) { drawLine(c.accent, androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .48f), androidx.compose.ui.geometry.Offset(size.width * .5f, size.height * .72f), 1.7.dp.toPx())
            drawLine(c.accent, androidx.compose.ui.geometry.Offset(size.width * .38f, size.height * .6f), androidx.compose.ui.geometry.Offset(size.width * .62f, size.height * .6f), 1.7.dp.toPx()) }
    }
}

fun fileName(path: String) = path.replace('\\', '/').substringAfterLast('/')
fun bytes(value: Long?): String { if (value == null) return "待估算"; val units = listOf("B", "KB", "MB", "GB", "TB"); var n = value.toDouble(); var i = 0
    while (n >= 1000 && i < units.lastIndex) { n /= 1000; i++ }; return "${(n * 10).roundToInt() / 10.0} ${units[i]}" }
fun duration(value: Long?): String { if (value == null) return "时长未知"; val seconds = value / 1_000_000; return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}" }
fun backendLabel(value: Backend) = if (value == Backend.X264) "CPU · H.264" else "NVIDIA · HEVC"
fun modeLabel(value: OutputMode) = when (value) { OutputMode.COPY -> "保存副本"; OutputMode.REPLACE -> "原地替换"; OutputMode.CHANGE_EXTENSION -> "原地改扩展名" }
fun streamLabel(value: StreamKind) = when (value) { StreamKind.VIDEO -> "视频"; StreamKind.AUDIO -> "音频"; StreamKind.SUBTITLE -> "字幕"; StreamKind.ATTACHMENT -> "附件"; StreamKind.DATA -> "数据"; StreamKind.UNKNOWN -> "未知" }
fun executionLabel(value: ExecutionState) = when (value) { ExecutionState.RUNNING -> "处理中"; ExecutionState.VALIDATED -> "已校验"; ExecutionState.PUBLISHING -> "发布中"; ExecutionState.PUBLISHED -> "已发布"; ExecutionState.SUCCEEDED -> "成功"; ExecutionState.FAILED -> "失败"; ExecutionState.INTERRUPTED -> "中断"; ExecutionState.NEEDS_ATTENTION -> "需要检查"; ExecutionState.CANCELLED -> "取消"; ExecutionState.SKIPPED -> "跳过" }
fun jobLabel(state: JobState) = when (state) { JobState.READY -> "等待处理"; JobState.RUNNING -> "正在处理"; JobState.PAUSE_REQUESTED -> "正在暂停"; JobState.PAUSED -> "已暂停"; JobState.COMPLETED -> "已完成"; JobState.COMPLETED_WITH_ERRORS -> "完成，部分失败"; JobState.CANCELLED -> "已取消"; JobState.NEEDS_ATTENTION -> "需要检查" }
fun stageLabel(stage: AttemptStage) = when (stage) { AttemptStage.PREPARING -> "准备文件"; AttemptStage.ENCODING -> "正在编码"; AttemptStage.VALIDATING -> "完整校验"; AttemptStage.PUBLISHING -> "安全发布"; AttemptStage.SUCCEEDED -> "已完成"; AttemptStage.INTERRUPTED -> "已中断"; AttemptStage.FAILED -> "处理失败"; AttemptStage.SKIPPED -> "已跳过" }
