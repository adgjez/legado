package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.ColorUtils

abstract class ReaderBottomSheetComposeDialogFragment : ComposeDialogFragment() {

    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom
    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    protected open val maxSheetHeightFraction: Float = 0.72f

    private var bottomDialogRegistered = false

    override fun onStart() {
        super.onStart()
        registerBottomDialog()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0f
            attr.gravity = Gravity.BOTTOM
            attr.windowAnimations = if (AppConfig.isEInkMode) 0 else R.style.AnimDialogBottom
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        unregisterBottomDialog()
        super.onDismiss(dialog)
    }

    private fun registerBottomDialog() {
        if (bottomDialogRegistered) return
        (activity as? ReadBookActivity)?.let {
            it.bottomDialog++
            bottomDialogRegistered = true
        }
    }

    private fun unregisterBottomDialog() {
        if (!bottomDialogRegistered) return
        (activity as? ReadBookActivity)?.let {
            it.bottomDialog--
        }
        bottomDialogRegistered = false
    }
}

@Immutable
data class ReaderComposePalette(
    val surface: Color,
    val panel: Color,
    val panelStrong: Color,
    val stroke: Color,
    val text: Color,
    val secondaryText: Color,
    val accent: Color
)

@Composable
fun rememberReaderComposePalette(
    baseColor: Int = LocalContext.current.bottomBackground,
    alphaPercent: Int? = null
): ReaderComposePalette {
    val context = LocalContext.current
    val resolvedAlpha = alphaPercent?.coerceIn(0, 100)
    val palette = remember(context, baseColor, resolvedAlpha, AppConfig.isNightTheme) {
        val raw = ReaderSheetStyle.resolve(context, baseColor)
        val alpha = resolvedAlpha?.let { it / 100f } ?: 1f
        ReaderComposePalette(
            surface = Color(ColorUtils.withAlpha(raw.surface, alpha)),
            panel = Color(ColorUtils.withAlpha(raw.panel, alpha)),
            panelStrong = Color(ColorUtils.withAlpha(raw.panelStrong, alpha)),
            stroke = Color(raw.stroke),
            text = Color(raw.textColor),
            secondaryText = Color(raw.secondaryTextColor),
            accent = Color(raw.accentColor)
        )
    }
    return palette
}

@Composable
fun ReaderBottomSheetFrame(
    modifier: Modifier = Modifier,
    maxHeightFraction: Float = 0.72f,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    content: @Composable (AppDialogStyle, ReaderComposePalette) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = rememberReaderComposePalette()
    val maxHeight = (LocalConfiguration.current.screenHeightDp * maxHeightFraction)
        .toInt()
        .coerceAtLeast(280)
        .dp
    val shape = RoundedCornerShape(topStart = style.panelRadius, topEnd = style.panelRadius)
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .navigationBarsPadding()
                .border(1.dp, palette.stroke, shape),
            shape = shape,
            color = palette.surface,
            contentColor = palette.text,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(contentPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                content(style, palette)
            }
        }
    }
}

@Composable
fun ReaderSheetHeader(
    title: String,
    palette: ReaderComposePalette,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = palette.text,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = palette.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        trailing?.let {
            Spacer(modifier = Modifier.width(10.dp))
            it()
        }
    }
}

@Composable
fun ReaderSectionCard(
    palette: ReaderComposePalette,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    title: String? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 9.dp),
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.actionRadius),
        color = palette.panel,
        contentColor = palette.text,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = palette.accent,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            content()
        }
    }
}

@Composable
fun ReaderIconAction(
    iconRes: Int,
    contentDescription: String?,
    palette: ReaderComposePalette,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val bg = if (selected) palette.accent.copy(alpha = 0.18f) else palette.panelStrong
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(style.actionRadius))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = if (enabled) palette.text else palette.secondaryText.copy(alpha = 0.55f),
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
fun ReaderTextAction(
    text: String,
    palette: ReaderComposePalette,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(38.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(style.actionRadius),
        color = if (selected) palette.accent.copy(alpha = 0.18f) else palette.panelStrong,
        contentColor = if (enabled) palette.text else palette.secondaryText.copy(alpha = 0.55f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = if (enabled) palette.text else palette.secondaryText.copy(alpha = 0.55f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ReaderSliderRow(
    title: String,
    value: Int,
    range: IntRange,
    palette: ReaderComposePalette,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    valueText: String = value.toString(),
    enabled: Boolean = true,
    onValueChange: (Int) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                color = if (enabled) palette.text else palette.secondaryText.copy(alpha = 0.55f),
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = valueText,
                color = if (enabled) palette.accent else palette.secondaryText.copy(alpha = 0.55f),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
        AppThemedStepperSlider(
            value = value.coerceIn(range),
            range = range,
            onValueChange = { onValueChange(it.coerceIn(range)) },
            palette = style.toMiuixPalette(),
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished
        )
    }
}

@Composable
fun ReaderSwitchRow(
    title: String,
    checked: Boolean,
    palette: ReaderComposePalette,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.actionRadius))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (enabled) palette.text else palette.secondaryText.copy(alpha = 0.55f),
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            summary?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = palette.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        LegadoMiuixSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            palette = style.toMiuixPalette(),
            enabled = enabled
        )
    }
}

@Composable
fun ReaderSegmentedOptions(
    options: List<ReaderOption>,
    selectedValue: String,
    palette: ReaderComposePalette,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    scrollable: Boolean = false,
    onSelected: (String) -> Unit
) {
    if (scrollable) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { option ->
                val selected = option.value == selectedValue
                Surface(
                    modifier = Modifier
                        .heightIn(min = 34.dp)
                        .clickable { onSelected(option.value) },
                    shape = RoundedCornerShape(style.actionRadius),
                    color = if (selected) palette.accent.copy(alpha = 0.18f) else palette.panelStrong,
                    contentColor = if (selected) palette.accent else palette.text,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Text(
                        text = option.label,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        color = if (selected) palette.accent else palette.text,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { option ->
                val selected = option.value == selectedValue
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 34.dp)
                        .clickable { onSelected(option.value) },
                    shape = RoundedCornerShape(style.actionRadius),
                    color = if (selected) palette.accent.copy(alpha = 0.18f) else palette.panelStrong,
                    contentColor = if (selected) palette.accent else palette.text,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Text(
                        text = option.label,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        color = if (selected) palette.accent else palette.text,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

data class ReaderOption(
    val value: String,
    val label: String
)
