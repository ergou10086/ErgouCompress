package org.ergoutree.compress.workspace

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Win11 按钮样式：标准、强调、透明与破坏性填充。 */
enum class FButtonStyle { Standard, Accent, Subtle, Critical }

/**
 * 基础按钮，高度与内边距遵循 Win11 命令栏控件；
 * 焦点环和悬停/按下反馈不依赖 Material 组件。
 */
@Composable
fun FButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: FButtonStyle = FButtonStyle.Standard,
    glyph: FluentGlyph? = null,
    contentDescription: String? = null,
    tooltip: String? = null,
) {
    val c = LocalFluentColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FluentTokens.controlRadius)
    val foreground = when {
        !enabled -> c.textDisabled
        style == FButtonStyle.Accent -> c.onAccent
        style == FButtonStyle.Critical -> if (c.critical.luminance() > .5f) Color(0xFF1A1A1A) else Color.White
        else -> c.textPrimary
    }
    val baseBackground = when {
        !enabled && (style == FButtonStyle.Accent || style == FButtonStyle.Critical) -> c.baseSecondary
        style == FButtonStyle.Accent -> c.accent
        style == FButtonStyle.Critical -> c.critical
        style == FButtonStyle.Subtle -> Color.Transparent
        else -> if (enabled) c.control else c.baseSecondary
    }
    val overlay = when {
        !enabled -> Color.Transparent
        pressed -> c.pressed
        hovered -> c.hover
        else -> Color.Transparent
    }
    // 强调按钮上使用黑色低透明覆盖，保证深浅主题都有按下层次。
    val accentOverlay = when {
        !enabled || style != FButtonStyle.Accent -> Color.Transparent
        pressed -> Color(0x24000000)
        hovered -> Color(0x14000000)
        else -> Color.Transparent
    }
    val borderColor = when {
        focused -> c.accent
        style == FButtonStyle.Standard && enabled -> c.stroke
        style == FButtonStyle.Critical -> c.critical.copy(alpha = .45f)
        else -> Color.Transparent
    }
    val semantic = if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription }
    val button = @Composable {
        Row(
            modifier
                .defaultMinSize(minHeight = FluentTokens.controlHeight)
                .clip(shape)
                .background(baseBackground)
                .background(if (style == FButtonStyle.Accent) accentOverlay else overlay)
                .border(if (focused) 2.dp else if (style == FButtonStyle.Standard || style == FButtonStyle.Critical) 1.dp else 0.dp, borderColor, shape)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .onFocusChanged { focused = it.isFocused }
                .then(semantic)
                .padding(horizontal = if (glyph != null) 10.dp else 14.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (glyph != null) FIcon(glyph, 16.dp, foreground)
            FText(label, type = FType.Body, color = foreground, maxLines = 1)
        }
    }
    if (tooltip.isNullOrBlank()) button() else FToolTip(tooltip, anchorHeight = 34.dp) { button() }
}

/** 方形图标按钮；命令栏和列表行内操作使用。 */
@Composable
fun FIconButton(
    glyph: FluentGlyph,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    size: Dp = 32.dp,
    tint: Color = LocalFluentColors.current.textPrimary,
    tooltip: String? = null,
) {
    val c = LocalFluentColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(FluentTokens.controlRadius)
    val button = @Composable {
        Box(
            modifier.size(size).clip(shape)
                .background(if (selected) c.selected else if (!enabled) Color.Transparent else if (pressed) c.pressed else if (hovered) c.hover else Color.Transparent)
                .border(if (focused) 2.dp else 0.dp, if (focused) c.accent else Color.Transparent, shape)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .onFocusChanged { focused = it.isFocused }
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            FIcon(glyph, 16.dp, if (!enabled) c.textDisabled else if (selected) c.accentText else tint)
        }
    }
    if (tooltip.isNullOrBlank()) button() else FToolTip(tooltip, anchorHeight = size + 2.dp) { button() }
}

/** 标准复选框；`checked == null` 表示部分选择。 */
@Composable
fun FTriCheck(
    label: String,
    checked: Boolean?,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onChange: (Boolean) -> Unit,
) {
    val c = LocalFluentColors.current
    var focused by remember { mutableStateOf(false) }
    val fill by animateFloatAsState(if (checked == true) 1f else 0f, tween(120), label = "check")
    val semantic = if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription }
    Row(
        modifier
            .onFocusChanged { focused = it.isFocused }
            .toggleable(value = checked == true, enabled = enabled, role = Role.Checkbox) { onChange(!(checked == true)) }
            .then(semantic)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(3.dp))
                .background(if (checked == true) c.accent.copy(alpha = fill) else if (checked == null) c.accent else Color.Transparent)
                .border(if (focused) 2.dp else 1.dp, if (checked != null || focused) c.accent else c.textTertiary, RoundedCornerShape(3.dp))
                .semantics {
                    stateDescription = when (checked) { true -> "已选"; false -> "未选"; null -> "部分选择" }
                },
            contentAlignment = Alignment.Center,
        ) {
            when (checked) {
                true -> FIcon(FluentGlyph.CheckMark, 12.dp, c.onAccent)
                null -> Box(Modifier.width(10.dp).height(2.dp).background(c.onAccent))
                false -> Unit
            }
        }
        if (label.isNotBlank()) {
            FText(label, color = if (enabled) c.textPrimary else c.textDisabled, maxLines = 1)
        }
    }
}

@Composable
fun FCheck(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onChange: (Boolean) -> Unit,
) = FTriCheck(label, checked, enabled, modifier, onChange = onChange)

/** Win11 开关；轨道 40×20，滑块位置与颜色平滑过渡。 */
@Composable
fun FSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String? = null,
) {
    val c = LocalFluentColors.current
    val thumbOffset by animateDpAsState(if (checked) 21.dp else 3.dp, tween(160), label = "switch-offset")
    val thumbSize by animateDpAsState(if (checked) 14.dp else 12.dp, tween(160), label = "switch-thumb")
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (label != null) FText(label, color = if (enabled) c.textPrimary else c.textDisabled)
        Box(
            Modifier.size(40.dp, 20.dp).clip(CircleShape)
                .background(if (checked) c.accent else Color.Transparent)
                .border(if (focused) 2.dp else 1.dp, if (focused) c.accent else if (checked) c.accent else if (enabled) c.textTertiary else c.textDisabled, CircleShape)
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .onFocusChanged { focused = it.isFocused }
                .semantics { stateDescription = if (checked) "开" else "关" },
        ) {
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = thumbOffset).size(thumbSize).clip(CircleShape)
                    .background(if (checked) c.onAccent else if (enabled) c.textSecondary else c.textDisabled),
            )
        }
    }
}

/** 单选卡片：标题和说明清晰可见，适合编码器、处理方式等关键决策。 */
data class FChoice<T>(val value: T, val title: String, val description: String? = null, val trailing: String? = null)

@Composable
private fun <T> FChoiceCard(
    option: FChoice<T>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean,
    modifier: Modifier,
) {
    val c = LocalFluentColors.current
    val chosen = option.value == selected
    val shape = RoundedCornerShape(FluentTokens.controlRadius)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.clip(shape)
            .background(if (chosen) c.accentSubtle else c.control)
            .background(if (hovered && !chosen && enabled) c.hover else Color.Transparent)
            .border(if (focused) 2.dp else 1.dp, if (focused || chosen) c.accent else c.stroke, shape)
            .hoverable(interaction)
            .selectable(selected = chosen, enabled = enabled, role = Role.RadioButton) { onSelect(option.value) }
            .onFocusChanged { focused = it.isFocused }
            .semantics { this.selected = chosen }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(18.dp).clip(CircleShape)
                .border(1.dp, if (chosen) c.accent else c.textTertiary, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (chosen) Box(Modifier.size(10.dp).clip(CircleShape).background(c.accent))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            FText(option.title, type = FType.BodyStrong, color = if (enabled) c.textPrimary else c.textDisabled)
            if (!option.description.isNullOrBlank()) FText(option.description, type = FType.Caption, color = c.textSecondary, maxLines = 2)
        }
        if (!option.trailing.isNullOrBlank()) FBadge(option.trailing, if (chosen) FluentTone.Accent else FluentTone.Neutral)
    }
}

@Composable
fun <T> FChoiceCards(
    options: List<FChoice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    horizontal: Boolean = false,
) {
    if (horizontal) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option -> FChoiceCard(option, selected, onSelect, enabled, Modifier.weight(1f)) }
        }
    } else {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option -> FChoiceCard(option, selected, onSelect, enabled, Modifier.fillMaxWidth()) }
        }
    }
}

/** 顶部筛选条/模式切换，使用 Win11 选中片段而不是圆点字符。 */
@Composable
fun <T> FSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalFluentColors.current
    Row(
        modifier.clip(RoundedCornerShape(FluentTokens.controlRadius)).background(c.baseSecondary).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val interaction = remember(value) { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            var focused by remember(value) { mutableStateOf(false) }
            Box(
                Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (active) c.card else if (hovered) c.hover else Color.Transparent)
                    .border(if (focused) 2.dp else if (active) 1.dp else 0.dp,
                        if (focused) c.accent else if (active) c.stroke else Color.Transparent, RoundedCornerShape(3.dp))
                    .selectable(selected = active, enabled = enabled, role = Role.RadioButton) { onSelect(value) }
                    .hoverable(interaction)
                    .onFocusChanged { focused = it.isFocused }
                    .semantics { this.selected = active }
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                FText(label, type = if (active) FType.BodyStrong else FType.Body,
                    color = if (!enabled) c.textDisabled else if (active) c.textPrimary else c.textSecondary, maxLines = 1)
            }
        }
    }
}

/** 带标签、说明和焦点轮廓的文本框。 */
@Composable
fun FTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String = "",
    description: String? = null,
    placeholder: String = "",
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minHeight: Dp = FluentTokens.controlHeight,
    leading: FluentGlyph? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalFluentColors.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label.isNotBlank()) FText(label, type = FType.CaptionStrong, color = if (enabled) c.textPrimary else c.textDisabled)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = TextStyle(
                color = if (enabled) c.textPrimary else c.textDisabled,
                fontFamily = LocalFluentFont.current,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = if (singleLine) minHeight else maxOf(minHeight, 76.dp))
                .onFocusChanged { focused = it.isFocused }
                .clip(RoundedCornerShape(FluentTokens.controlRadius))
                .background(if (enabled) c.control else c.baseSecondary)
                .border(if (focused) 2.dp else 1.dp, if (focused) c.accent else c.stroke, RoundedCornerShape(FluentTokens.controlRadius))
                .semantics { contentDescription = "${label.ifBlank { placeholder }.ifBlank { "文本" }}输入框" },
            decorationBox = { inner ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = if (singleLine) 6.dp else 8.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (leading != null) FIcon(leading, 14.dp, c.textTertiary)
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder.isNotBlank()) {
                            FText(placeholder, color = c.textTertiary, maxLines = if (singleLine) 1 else 2)
                        }
                        inner()
                    }
                    trailing?.invoke()
                }
            },
        )
        if (!description.isNullOrBlank()) FText(description, type = FType.Caption, color = c.textSecondary, maxLines = 2)
    }
}

/** 搜索框：图标、清除按钮和紧凑高度，用于列表过滤。 */
@Composable
fun FSearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "搜索",
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalFluentColors.current
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = TextStyle(color = c.textPrimary, fontFamily = LocalFluentFont.current, fontSize = 14.sp, lineHeight = 20.sp),
        cursorBrush = SolidColor(c.accent),
        modifier = modifier.height(32.dp).onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(FluentTokens.controlRadius)).background(c.control)
            .border(if (focused) 2.dp else 1.dp, if (focused) c.accent else c.stroke, RoundedCornerShape(FluentTokens.controlRadius))
            .semantics { contentDescription = "搜索输入框" },
        decorationBox = { inner ->
            Row(
                Modifier.fillMaxSize().padding(horizontal = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                FIcon(FluentGlyph.Search, 14.dp, c.textTertiary)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) FText(placeholder, color = c.textTertiary, maxLines = 1)
                    inner()
                }
                if (value.isNotEmpty()) {
                    Box(
                        Modifier.size(20.dp).clip(RoundedCornerShape(3.dp)).clickable(role = Role.Button) { onValueChange("") }
                            .semantics { contentDescription = "清除搜索"; onClick { onValueChange(""); true } },
                        contentAlignment = Alignment.Center,
                    ) { FIcon(FluentGlyph.Cancel, 12.dp, c.textTertiary) }
                }
            }
        },
    )
}

/** Win11 下拉选择器；菜单与锚点同宽，选项显示选中勾选。 */
@Composable
fun <T> FComboBox(
    value: T,
    options: List<Pair<T, String>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    menuWidth: Dp = 200.dp,
    contentDescription: String = "选择",
) {
    val c = LocalFluentColors.current
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val density = LocalDensity.current
    val selectedLabel = options.firstOrNull { it.first == value }?.second ?: "—"
    Box(modifier) {
        Row(
            Modifier.width(menuWidth).height(FluentTokens.controlHeight)
                .clip(RoundedCornerShape(FluentTokens.controlRadius))
                .background(if (enabled) c.control else c.baseSecondary)
                .background(if (open || hovered) c.hover else Color.Transparent)
                .border(1.dp, if (open) c.accent else c.stroke, RoundedCornerShape(FluentTokens.controlRadius))
                .hoverable(interaction)
                .clickable(enabled = enabled, role = Role.Button) { open = !open }
                .semantics { this.contentDescription = contentDescription; stateDescription = selectedLabel }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FText(selectedLabel, modifier = Modifier.weight(1f), maxLines = 1, color = if (enabled) c.textPrimary else c.textDisabled)
            FIcon(FluentGlyph.ChevronDown, 12.dp, c.textSecondary)
        }
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { (FluentTokens.controlHeight + 4.dp).roundToPx() }),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
            ) {
                Column(
                    Modifier.width(menuWidth).shadow(12.dp, RoundedCornerShape(8.dp), ambientColor = c.shadow, spotColor = c.shadow)
                        .clip(RoundedCornerShape(8.dp)).background(c.card).border(1.dp, c.stroke, RoundedCornerShape(8.dp)).padding(4.dp),
                ) {
                    options.forEach { (option, label) ->
                        val active = option == value
                        val itemInteraction = remember(option) { MutableInteractionSource() }
                        val itemHovered by itemInteraction.collectIsHoveredAsState()
                        Row(
                            Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (itemHovered) c.hover else if (active) c.selected else Color.Transparent)
                                .hoverable(itemInteraction)
                                .clickable(role = Role.RadioButton) { onSelect(option); open = false }
                                .semantics { this.selected = active }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(14.dp), contentAlignment = Alignment.Center) {
                                if (active) FIcon(FluentGlyph.CheckMark, 12.dp, c.accent)
                            }
                            FText(label, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** 自定义滑块，保留真实数值语义和键盘方向键操作。 */
@Composable
fun FSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    steps: Int = 0,
    contentDescription: String = "数值",
) {
    val c = LocalFluentColors.current
    val reduced = LocalReducedMotion.current
    var focused by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var width by remember { mutableIntStateOf(0) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fraction = if (valueRange.endInclusive > valueRange.start)
        ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f) else 0f
    val thumbScale by animateFloatAsState(if (dragging) 1.08f else 1f, tween(100), label = "thumb")
    fun quantize(raw: Float): Float {
        val clamped = raw.coerceIn(valueRange.start, valueRange.endInclusive)
        if (steps <= 0) return clamped
        val step = (valueRange.endInclusive - valueRange.start) / (steps + 1)
        val index = ((clamped - valueRange.start) / step).roundToInt()
        return (valueRange.start + index * step).coerceIn(valueRange.start, valueRange.endInclusive)
    }
    fun update(x: Float, trackWidth: Float, paddingPx: Float) {
        val usable = (trackWidth - paddingPx * 2).coerceAtLeast(1f)
        onValueChange(quantize(valueRange.start + ((x - paddingPx) / usable).coerceIn(0f, 1f) * (valueRange.endInclusive - valueRange.start)))
    }
    Canvas(
        modifier.fillMaxWidth().height(32.dp).onSizeChanged { width = it.width }
            .hoverable(interaction).focusable(enabled = enabled)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = if (steps > 0) (valueRange.endInclusive - valueRange.start) / (steps + 1) else (valueRange.endInclusive - valueRange.start) / 100f
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionDown -> { onValueChange(quantize(value - step)); true }
                    Key.DirectionRight, Key.DirectionUp -> { onValueChange(quantize(value + step)); true }
                    Key.MoveHome -> { onValueChange(valueRange.start); true }
                    Key.MoveEnd -> { onValueChange(valueRange.endInclusive); true }
                    else -> false
                }
            }
            .pointerInput(enabled, width, valueRange, steps) {
                if (!enabled) return@pointerInput
                val padding = 10.dp.toPx()
                detectTapGestures { offset -> update(offset.x, size.width.toFloat(), padding) }
            }
            .pointerInput(enabled, width, valueRange, steps) {
                if (!enabled) return@pointerInput
                val padding = 10.dp.toPx()
                detectDragGestures(
                    onDragStart = { dragging = true; update(it.x, size.width.toFloat(), padding) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ -> change.consume(); update(change.position.x, size.width.toFloat(), padding) }
            }
            .semantics {
                this.contentDescription = contentDescription
                progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange)
                setProgress { target -> onValueChange(quantize(target)); true }
            },
    ) {
        val padding = 10.dp.toPx()
        val centerY = size.height / 2f
        val trackStart = padding
        val trackEnd = size.width - padding
        val usable = (trackEnd - trackStart).coerceAtLeast(1f)
        val position = trackStart + usable * fraction
        val radius = CornerRadius(2.dp.toPx())
        drawRoundRect(if (enabled) c.stroke else c.divider, topLeft = Offset(trackStart, centerY - 2.dp.toPx()),
            size = Size(usable, 4.dp.toPx()), cornerRadius = radius)
        if (enabled && position > trackStart) drawRoundRect(c.accent, topLeft = Offset(trackStart, centerY - 2.dp.toPx()),
            size = Size(position - trackStart, 4.dp.toPx()), cornerRadius = radius)
        if (focused) drawCircle(c.accent.copy(alpha = .22f), radius = 12.dp.toPx() * thumbScale, center = Offset(position, centerY))
        drawCircle(if (enabled) c.card else c.baseSecondary, radius = 9.dp.toPx() * thumbScale, center = Offset(position, centerY))
        drawCircle(if (enabled) (if (hovered || dragging) c.accentHover else c.accent) else c.textDisabled,
            radius = 9.dp.toPx() * thumbScale, center = Offset(position, centerY), style = Stroke(2.dp.toPx()))
        drawCircle(if (enabled) c.accent else c.textDisabled, radius = 3.5.dp.toPx() * thumbScale, center = Offset(position, centerY))
    }
}

/** 信息条：错误、警告和首次使用引导，附可选操作与关闭。 */
@Composable
fun FInfoBar(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    tone: FluentTone = FluentTone.Accent,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
) {
    val c = LocalFluentColors.current
    val (background, border, foreground, glyph) = when (tone) {
        FluentTone.Success -> Quad(c.successBackground, c.success.copy(alpha = .32f), c.success, FluentGlyph.Completed)
        FluentTone.Caution -> Quad(c.cautionBackground, c.caution.copy(alpha = .38f), c.caution, FluentGlyph.Warning)
        FluentTone.Critical -> Quad(c.criticalBackground, c.critical.copy(alpha = .35f), c.critical, FluentGlyph.Error)
        FluentTone.Neutral -> Quad(c.cardSubtle, c.stroke, c.textSecondary, FluentGlyph.Info)
        FluentTone.Accent -> Quad(c.accentSubtle, c.accent.copy(alpha = .28f), c.accentText, FluentGlyph.Info)
    }
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(FluentTokens.controlRadius)).background(background)
            .border(1.dp, border, RoundedCornerShape(FluentTokens.controlRadius)).padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FIcon(glyph, 16.dp, foreground)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (!title.isNullOrBlank()) FText(title, type = FType.BodyStrong, color = c.textPrimary)
            FText(message, type = if (title.isNullOrBlank()) FType.Body else FType.Caption, color = if (title.isNullOrBlank()) c.textPrimary else c.textSecondary, maxLines = 3)
        }
        if (actionLabel != null && onAction != null) FButton(actionLabel, onAction, style = FButtonStyle.Standard)
        if (onClose != null) FIconButton(FluentGlyph.Cancel, "关闭提示", onClose, size = 28.dp, tint = c.textSecondary)
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

/** 悬停提示；不与输入焦点竞争。 */
@Composable
fun FToolTip(text: String, anchorHeight: Dp = 28.dp, content: @Composable () -> Unit) {
    val c = LocalFluentColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var ready by remember { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(hovered) {
        if (hovered) { if (reduced) ready = true else { delay(450); ready = true } } else ready = false
    }
    val density = LocalDensity.current
    Box(Modifier.hoverable(interaction)) {
        content()
        if (ready) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { (anchorHeight + 4.dp).roundToPx() }),
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
            ) {
                Box(
                    Modifier.widthIn(max = 300.dp).shadow(10.dp, RoundedCornerShape(4.dp), ambientColor = c.shadow, spotColor = c.shadow)
                        .clip(RoundedCornerShape(4.dp)).background(c.card).border(1.dp, c.stroke, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) { FText(text, type = FType.Caption, maxLines = 6) }
            }
        }
    }
}

/** 参数说明入口，保留“${label}帮助”的稳定可访问名称。 */
@Composable
fun FHelpButton(label: String, message: String) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var pinned by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    val reduced = LocalReducedMotion.current
    LaunchedEffect(hovered) {
        if (hovered) { if (reduced) ready = true else { delay(450); ready = true } } else ready = false
    }
    val c = LocalFluentColors.current
    val density = LocalDensity.current
    Box(Modifier.hoverable(interaction)) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).border(1.dp, if (pinned) c.accent else c.strokeStrong, CircleShape)
                .clickable(role = Role.Button) { pinned = !pinned }
                .semantics {
                    contentDescription = "${label}帮助"
                    stateDescription = if (ready || pinned) "已展开" else "已收起"
                },
            contentAlignment = Alignment.Center,
        ) { FIcon(FluentGlyph.Info, 11.dp, if (pinned) c.accent else c.textTertiary) }
        if (ready || pinned) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { 24.dp.roundToPx() }),
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
            ) {
                Box(
                    Modifier.widthIn(max = 300.dp).shadow(10.dp, RoundedCornerShape(4.dp), ambientColor = c.shadow, spotColor = c.shadow)
                        .clip(RoundedCornerShape(4.dp)).background(c.card).border(1.dp, c.stroke, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                ) { FText(message, type = FType.Caption, maxLines = 8) }
            }
        }
    }
}

/** 可展开分组；减少动画时直接切换内容。 */
@Composable
fun FExpander(
    title: String,
    description: String? = null,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalFluentColors.current
    val rotation by animateFloatAsState(if (expanded) 0f else -90f, tween(160), label = "expander")
    Column(modifier.fillMaxWidth().animateContentSize(tween(180))) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(FluentTokens.controlRadius))
                .clickable(enabled = enabled, role = Role.Button) { onExpandedChange(!expanded) }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FIcon(FluentGlyph.ChevronDown, 14.dp, c.textSecondary, modifier = Modifier.rotate(rotation))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                FText(title, type = FType.BodyStrong)
                if (!description.isNullOrBlank()) FText(description, type = FType.Caption, color = c.textSecondary, maxLines = 2)
            }
        }
        AnimatedVisibility(visible = expanded, enter = fadeIn(tween(160)), exit = fadeOut(tween(100))) {
            Column(Modifier.fillMaxWidth().padding(start = 36.dp, end = 4.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

/** 窗口内内容对话框；使用遮罩和焦点捕获保持模态语义。 */
@Composable
fun FContentDialog(
    title: String,
    message: String,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val c = LocalFluentColors.current
    val focusRequester = remember { FocusRequester() }
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true; focusRequester.requestFocus() }
    val reduced = LocalReducedMotion.current
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (reduced) 0 else 140), label = "dialog-alpha")
    val scale by animateFloatAsState(if (visible) 1f else .96f, tween(if (reduced) 0 else 160), label = "dialog-scale")
    val dismiss = onSecondary ?: onPrimary
    Box(
        Modifier.fillMaxSize().background(c.scrim.copy(alpha = c.scrim.alpha * alpha))
            .focusRequester(focusRequester).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) { dismiss?.invoke(); true } else false
            }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button) {
                dismiss?.invoke()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 460.dp).graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
            }.shadow(32.dp, RoundedCornerShape(FluentTokens.dialogRadius), ambientColor = c.shadow, spotColor = c.shadow)
                .clip(RoundedCornerShape(FluentTokens.dialogRadius)).background(c.card)
                .border(1.dp, c.stroke, RoundedCornerShape(FluentTokens.dialogRadius))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
                .padding(24.dp)
                .semantics { contentDescription = title },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FText(title, type = FType.Subtitle)
            FText(message, color = c.textSecondary)
            content()
            if (primaryLabel != null || secondaryLabel != null) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (secondaryLabel != null) FButton(secondaryLabel, onClick = { dismiss?.invoke() })
                    if (primaryLabel != null) FButton(primaryLabel, onClick = { onPrimary?.invoke() },
                        style = if (destructive) FButtonStyle.Critical else FButtonStyle.Accent)
                }
            }
        }
    }
}

/** Win11 Settings 风格左侧导航项。 */
@Composable
fun FNavigationItem(
    glyph: FluentGlyph,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    enabled: Boolean = true,
) {
    val c = LocalFluentColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(FluentTokens.controlRadius))
            .background(if (selected) c.selected else if (hovered) c.hover else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) c.accent else Color.Transparent, RoundedCornerShape(FluentTokens.controlRadius))
            .hoverable(interaction)
            .clickable(enabled = enabled, role = Role.Button) { onClick() }
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(if (selected) c.accent else Color.Transparent))
        FIcon(glyph, 16.dp, if (!enabled) c.textDisabled else if (selected) c.accentText else c.textSecondary)
        FText(label, modifier = Modifier.weight(1f), color = if (!enabled) c.textDisabled else if (selected) c.textPrimary else c.textSecondary, maxLines = 1)
        if (badge != null) FBadge(badge, if (selected) FluentTone.Accent else FluentTone.Neutral)
    }
}

/** 行尾更多菜单。 */
data class FMenuItem(
    val label: String,
    val onClick: () -> Unit,
    val glyph: FluentGlyph? = null,
    val enabled: Boolean = true,
    val destructive: Boolean = false,
)

@Composable
fun FMoreMenu(
    items: List<FMenuItem>,
    contentDescription: String = "更多操作",
    enabled: Boolean = true,
) {
    val c = LocalFluentColors.current
    var open by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    Box {
        FIconButton(FluentGlyph.More, contentDescription, { open = !open }, enabled = enabled, size = 28.dp)
        if (open) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, with(density) { 32.dp.roundToPx() }),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true, dismissOnClickOutside = true),
            ) {
                Column(
                    Modifier.width(196.dp).shadow(12.dp, RoundedCornerShape(8.dp), ambientColor = c.shadow, spotColor = c.shadow)
                        .clip(RoundedCornerShape(8.dp)).background(c.card).border(1.dp, c.stroke, RoundedCornerShape(8.dp)).padding(4.dp),
                ) {
                    items.forEach { item ->
                        val interaction = remember(item) { MutableInteractionSource() }
                        val hovered by interaction.collectIsHoveredAsState()
                        Row(
                            Modifier.fillMaxWidth().height(32.dp).clip(RoundedCornerShape(4.dp))
                                .background(if (hovered) c.hover else Color.Transparent)
                                .hoverable(interaction)
                                .clickable(enabled = item.enabled, role = Role.Button) { open = false; item.onClick() }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (item.glyph != null) FIcon(item.glyph, 14.dp, if (item.destructive) c.critical else c.textSecondary)
                            FText(item.label, modifier = Modifier.weight(1f),
                                color = if (!item.enabled) c.textDisabled else if (item.destructive) c.critical else c.textPrimary, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** 带图标的命令栏按钮，顶部设置/历史/关于入口。 */
@Composable
fun FCommandButton(
    glyph: FluentGlyph,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    badge: String? = null,
) {
    val c = LocalFluentColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(FluentTokens.controlRadius))
            .background(if (hovered) c.hover else Color.Transparent)
            .border(if (focused) 2.dp else 0.dp, if (focused) c.accent else Color.Transparent, RoundedCornerShape(FluentTokens.controlRadius))
            .hoverable(interaction)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        FIcon(glyph, 15.dp, if (enabled) c.textSecondary else c.textDisabled)
        FText(label, type = FType.Body, color = if (enabled) c.textPrimary else c.textDisabled, maxLines = 1)
        if (badge != null) FBadge(badge, FluentTone.Accent)
    }
}

/** 状态胶囊，用于顶部“工具已就绪”。 */
@Composable
fun FStatusChip(label: String, tone: FluentTone = FluentTone.Neutral, pulsing: Boolean = false) {
    val c = LocalFluentColors.current
    val background = when (tone) {
        FluentTone.Accent -> c.accentSubtle
        FluentTone.Success -> c.successBackground
        FluentTone.Caution -> c.cautionBackground
        FluentTone.Critical -> c.criticalBackground
        FluentTone.Neutral -> c.baseSecondary
    }
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(background).padding(horizontal = 9.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FStatusDot(tone, 6.dp, pulsing)
        FText(label, type = FType.Caption, color = when (tone) {
            FluentTone.Accent -> c.accentText
            FluentTone.Success -> c.success
            FluentTone.Caution -> c.caution
            FluentTone.Critical -> c.critical
            FluentTone.Neutral -> c.textSecondary
        }, maxLines = 1)
    }
}

/** 小标题 + 右侧操作，用于卡片头部。 */
@Composable
fun FCardHeader(title: String, description: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FText(title, type = FType.BodyStrong)
            if (!description.isNullOrBlank()) FText(description, type = FType.Caption, color = LocalFluentColors.current.textSecondary, maxLines = 2)
        }
        actions()
    }
}

/** 列表空状态中的轻量操作行。 */
@Composable
fun FActionRow(content: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** 数值 + 单位的紧凑展示，例如“已选 12 项 · 2.4 GB”。 */
@Composable
fun FSummaryText(text: String, modifier: Modifier = Modifier) {
    FText(text, modifier = modifier, type = FType.Caption, color = LocalFluentColors.current.textSecondary, maxLines = 1)
}

/** 在页面右侧提供固定宽度操作列。 */
@Composable
fun RowScope.FSpacer() = Spacer(Modifier.weight(1f))
