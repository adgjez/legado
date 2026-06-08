package io.legado.app.ui.widget.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

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

@Composable
fun LegadoMiuixCard(
    modifier: Modifier = Modifier,
    color: Color,
    contentColor: Color,
    cornerRadius: Dp,
    insidePadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit
) {
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
fun <T> LegadoMiuixSelectField(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
    palette: LegadoMiuixPalette,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(7.dp)
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
                .clickable { expanded = !expanded },
            color = palette.surface,
            contentColor = palette.primaryText,
            cornerRadius = cornerRadius,
            insidePadding = PaddingValues(horizontal = 13.dp, vertical = 11.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = optionLabel(selected),
                    modifier = Modifier.weight(1f),
                    color = palette.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (expanded) "^" else "v",
                    color = palette.secondaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = shrinkVertically() + fadeOut()
        ) {
            LegadoMiuixCard(
                modifier = Modifier.fillMaxWidth(),
                color = palette.surface,
                contentColor = palette.primaryText,
                cornerRadius = cornerRadius,
                insidePadding = PaddingValues(vertical = 5.dp)
            ) {
                options.forEach { option ->
                    val isSelected = option == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 42.dp)
                            .clickable {
                                expanded = false
                                onSelected(option)
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = optionLabel(option),
                            modifier = Modifier.weight(1f),
                            color = if (isSelected) palette.accent else palette.primaryText,
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isSelected) {
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "OK",
                                color = palette.accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
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
