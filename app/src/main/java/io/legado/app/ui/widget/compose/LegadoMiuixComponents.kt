package io.legado.app.ui.widget.compose

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material3.Text
import io.legado.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.SliderDefaults as MiuixSliderDefaults
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.SwitchDefaults as MiuixSwitchDefaults

@Immutable
data class LegadoMiuixPalette(
    val accent: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val danger: Color,
    val onAccent: Color = Color.White
)

fun AppDialogStyle.toMiuixPalette(): LegadoMiuixPalette {
    return LegadoMiuixPalette(
        accent = accent,
        surface = surface,
        surfaceVariant = fieldSurface,
        primaryText = primaryText,
        secondaryText = secondaryText,
        danger = danger
    )
}

private fun canUseRealMiuix(): Boolean {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
}

@Composable
fun LegadoMiuixCard(
    modifier: Modifier = Modifier,
    color: Color,
    contentColor: Color,
    cornerRadius: Dp,
    insidePadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    if (canUseRealMiuix()) {
        MiuixCard(
            modifier = modifier,
            cornerRadius = cornerRadius,
            insideMargin = insidePadding,
            colors = MiuixCardDefaults.defaultColors(
                color = color,
                contentColor = contentColor
            ),
            content = content
        )
        return
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
        color = color,
        contentColor = contentColor,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(insidePadding), content = content)
    }
}

@Composable
fun LegadoMiuixActionButton(
    text: String,
    palette: LegadoMiuixPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    cornerRadius: Dp = 16.dp,
    minWidth: Dp = 76.dp,
    minHeight: Dp = 40.dp,
    insidePadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
) {
    val background = when {
        primary -> palette.accent
        danger -> palette.danger.copy(alpha = 0.13f)
        else -> palette.surfaceVariant
    }
    val content = when {
        primary -> palette.onAccent
        danger -> palette.danger
        else -> palette.primaryText
    }
    if (canUseRealMiuix()) {
        MiuixButton(
            onClick = onClick,
            modifier = modifier,
            cornerRadius = cornerRadius,
            minWidth = minWidth,
            minHeight = minHeight,
            insideMargin = insidePadding,
            colors = MiuixButtonDefaults.buttonColors(
                color = background,
                disabledColor = background.copy(alpha = 0.46f),
                contentColor = content,
                disabledContentColor = content.copy(alpha = 0.38f)
            )
        ) {
            Text(
                text = text,
                color = content,
                fontSize = 14.sp,
                fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        return
    }
    Surface(
        modifier = modifier
            .defaultMinSize(minWidth = minWidth, minHeight = minHeight)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(cornerRadius),
        color = background,
        contentColor = content,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(insidePadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                color = content,
                fontSize = 14.sp,
                fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun LegadoMiuixSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    palette: LegadoMiuixPalette,
    modifier: Modifier = Modifier
) {
    if (canUseRealMiuix()) {
        MiuixSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            colors = MiuixSwitchDefaults.switchColors(
                checkedThumbColor = palette.onAccent,
                uncheckedThumbColor = palette.secondaryText.copy(alpha = 0.72f),
                disabledCheckedThumbColor = palette.onAccent.copy(alpha = 0.42f),
                disabledUncheckedThumbColor = palette.secondaryText.copy(alpha = 0.32f),
                checkedTrackColor = palette.accent,
                uncheckedTrackColor = palette.surfaceVariant,
                disabledCheckedTrackColor = palette.accent.copy(alpha = 0.24f),
                disabledUncheckedTrackColor = palette.surfaceVariant.copy(alpha = 0.46f)
            )
        )
        return
    }
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = palette.onAccent,
            uncheckedThumbColor = palette.secondaryText.copy(alpha = 0.72f),
            checkedTrackColor = palette.accent,
            uncheckedTrackColor = palette.surfaceVariant
        )
    )
}

@Composable
fun LegadoMiuixSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    palette: LegadoMiuixPalette,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0
) {
    if (canUseRealMiuix()) {
        MiuixSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            valueRange = valueRange,
            steps = steps,
            colors = MiuixSliderDefaults.sliderColors(
                foregroundColor = palette.accent,
                disabledForegroundColor = palette.accent.copy(alpha = 0.28f),
                backgroundColor = palette.surfaceVariant,
                disabledBackgroundColor = palette.surfaceVariant.copy(alpha = 0.44f),
                thumbColor = palette.onAccent,
                disabledThumbColor = palette.onAccent.copy(alpha = 0.42f),
                keyPointColor = palette.secondaryText.copy(alpha = 0.32f),
                keyPointForegroundColor = palette.onAccent.copy(alpha = 0.68f)
            )
        )
        return
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        valueRange = valueRange,
        steps = steps,
        colors = SliderDefaults.colors(
            activeTrackColor = palette.accent,
            inactiveTrackColor = palette.surfaceVariant,
            thumbColor = palette.onAccent,
            activeTickColor = palette.onAccent.copy(alpha = 0.72f),
            inactiveTickColor = palette.secondaryText.copy(alpha = 0.32f)
        )
    )
}

@Composable
fun LegadoMiuixFloatingPanel(
    visible: Boolean,
    palette: LegadoMiuixPalette,
    modifier: Modifier = Modifier,
    width: Dp = 304.dp,
    cornerRadius: Dp = 22.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "legadoMiuixFloatingPanel"
    )
    Surface(
        modifier = modifier
            .width(width)
            .graphicsLayer {
                alpha = progress
                scaleX = 0.96f + 0.04f * progress
                scaleY = 0.96f + 0.04f * progress
                translationY = (1f - progress) * 12f
            },
        shape = RoundedCornerShape(cornerRadius),
        color = palette.surface,
        contentColor = palette.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun LegadoMiuixChoiceRow(
    text: String,
    selected: Boolean,
    palette: LegadoMiuixPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    minHeight: Dp = 40.dp,
    compact: Boolean = false,
    showSelectedMark: Boolean = true
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = minHeight)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(if (compact) 13.dp else 16.dp),
        color = if (selected) palette.accent.copy(alpha = 0.14f) else palette.surfaceVariant,
        contentColor = if (selected) palette.accent else palette.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = if (compact) 11.dp else 13.dp,
                vertical = if (compact) 7.dp else 9.dp
            ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text,
                    color = if (selected) palette.accent else palette.primaryText,
                    fontSize = if (compact) 13.sp else 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                description?.takeIf { it.isNotBlank() }?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = it,
                        color = palette.secondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (showSelectedMark) {
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier.width(18.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(palette.accent)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun <T> LegadoMiuixSelectField(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    optionDescription: ((T) -> String)? = null,
    onSelected: (T) -> Unit,
    palette: LegadoMiuixPalette,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    compact: Boolean = false,
    showSelectedMark: Boolean = true,
    popupTitle: String? = label,
    popupWidth: Dp = 304.dp
) {
    var expanded by remember { mutableStateOf(false) }
    var panelVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun dismissOptions() {
        panelVisible = false
        scope.launch {
            delay(120)
            expanded = false
        }
    }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "miuixSelectArrow"
    )
    val labelSpacing = if (compact) 5.dp else 7.dp
    val fieldHorizontalPadding = if (compact) 12.dp else 13.dp
    val fieldVerticalPadding = if (compact) 9.dp else 11.dp
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(labelSpacing)
    ) {
        Text(
            text = label,
            color = palette.secondaryText,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (expanded) {
                        dismissOptions()
                    } else {
                        expanded = true
                    }
                },
            color = palette.surface,
            contentColor = palette.primaryText,
            cornerRadius = cornerRadius,
            insidePadding = PaddingValues(
                horizontal = fieldHorizontalPadding,
                vertical = fieldVerticalPadding
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = optionLabel(selected),
                        color = palette.primaryText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    optionDescription?.invoke(selected)?.takeIf { it.isNotBlank() }?.let {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = it,
                            color = palette.secondaryText,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Box(
                    modifier = Modifier.width(30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_expand_more),
                        contentDescription = null,
                        tint = palette.secondaryText,
                        modifier = Modifier
                            .size(20.dp)
                            .graphicsLayer { rotationZ = arrowRotation }
                    )
                }
            }
        }
    }
    if (expanded) {
        Popup(
            alignment = Alignment.Center,
            onDismissRequest = { dismissOptions() },
            properties = PopupProperties(focusable = true)
        ) {
            LaunchedEffect(Unit) {
                panelVisible = true
            }
            LegadoMiuixFloatingPanel(
                visible = panelVisible,
                palette = palette,
                width = popupWidth,
                cornerRadius = 22.dp,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                popupTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        color = palette.primaryText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    options.forEach { option ->
                        val isSelected = option == selected
                        LegadoMiuixChoiceRow(
                            text = optionLabel(option),
                            selected = isSelected,
                            palette = palette,
                            onClick = {
                                onSelected(option)
                                dismissOptions()
                            },
                            description = optionDescription?.invoke(option),
                            minHeight = if (compact) 38.dp else 42.dp,
                            compact = compact,
                            showSelectedMark = showSelectedMark
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LegadoMiuixSection(
    title: String,
    palette: LegadoMiuixPalette,
    cornerRadius: Dp,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    LegadoMiuixCard(
        modifier = modifier.fillMaxWidth(),
        color = palette.surfaceVariant,
        contentColor = palette.primaryText,
        cornerRadius = cornerRadius,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            color = palette.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        content()
    }
}

@Composable
fun LegadoMiuixActionRow(
    text: String,
    palette: LegadoMiuixPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    cornerRadius: Dp = 16.dp
) {
    LegadoMiuixCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (danger) palette.danger.copy(alpha = 0.12f) else palette.surfaceVariant,
        contentColor = if (danger) palette.danger else palette.primaryText,
        cornerRadius = cornerRadius,
        insidePadding = PaddingValues(horizontal = 16.dp, vertical = 13.dp)
    ) {
        Text(
            text = text,
            color = if (danger) palette.danger else palette.primaryText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
