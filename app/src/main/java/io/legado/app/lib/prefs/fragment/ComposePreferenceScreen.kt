package io.legado.app.lib.prefs.fragment

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import io.legado.app.R
import io.legado.app.lib.prefs.ColorPreference
import io.legado.app.lib.prefs.IconListPreference
import io.legado.app.lib.prefs.SeekBarPreference
import io.legado.app.lib.prefs.Preference as LegadoPreference
import io.legado.app.lib.prefs.SwitchPreference as LegadoSwitchPreference
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import kotlin.math.roundToInt

private val PanelHorizontalPadding = 12.dp

private data class ComposePreferenceSection(
    val title: CharSequence?,
    val rows: List<Preference>
)

private fun ComposePreferenceSection.stableKey(index: Int): String {
    return "$index:${title?.toString().orEmpty()}:${rows.firstOrNull()?.key.orEmpty()}"
}

private data class ComposePreferenceColors(
    val page: Color,
    val row: Int,
    val rowPressed: Int,
    val divider: Color,
    val border: Int?,
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val disabledText: Color,
    val onAccent: Color
)

@Composable
internal fun ComposePreferenceScreen(
    root: PreferenceGroup?,
    refreshTick: Int,
    onPreferenceClick: (Preference) -> Unit,
    onSwitchChange: (SwitchPreferenceCompat, Boolean) -> Unit,
    onSeekChange: (SeekBarPreference, Int) -> Unit,
    scrollTargetKey: String?,
    onScrollTargetConsumed: () -> Unit
) {
    val context = LocalContext.current
    val style = rememberAppDialogStyle()
    val rowBaseColor = ContextCompat.getColor(context, R.color.background_card)
    val panelRadiusPx = UiCorner.panelRadius(context)
    val colors = ComposePreferenceColors(
        page = Color(context.backgroundColor),
        row = UiCorner.surfaceColor(rowBaseColor),
        rowPressed = UiCorner.surfaceColor(rowBaseColor, pressed = true),
        divider = Color(ContextCompat.getColor(context, R.color.bg_divider_line)),
        border = UiCorner.panelBorderColor(context),
        primaryText = Color(ContextCompat.getColor(context, R.color.primaryText)),
        secondaryText = Color(ContextCompat.getColor(context, R.color.tv_text_summary)),
        accent = style.accent,
        disabledText = Color(ContextCompat.getColor(context, R.color.tv_text_summary)).copy(alpha = 0.48f),
        onAccent = Color.White
    )
    val sections = remember(root, refreshTick) {
        root?.toComposeSections().orEmpty()
    }
    val listState = rememberLazyListState()
    LaunchedEffect(scrollTargetKey, sections) {
        val targetKey = scrollTargetKey?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val sectionIndex = sections.indexOfFirst { section ->
            section.rows.any { it.key == targetKey }
        }
        if (sectionIndex >= 0) {
            listState.animateScrollToItem(sectionIndex)
        } else {
            onScrollTargetConsumed()
        }
    }
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(colors.page)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(
                sections,
                key = { index, section -> section.stableKey(index) }
            ) { _, section ->
                PreferenceSectionPanel(
                    section = section,
                    colors = colors,
                    panelRadiusPx = panelRadiusPx,
                    titleFontFamily = style.titleFontFamily,
                    onPreferenceClick = onPreferenceClick,
                    onSwitchChange = onSwitchChange,
                    onSeekChange = onSeekChange,
                    scrollTargetKey = scrollTargetKey,
                    onScrollTargetConsumed = onScrollTargetConsumed
                )
            }
        }
    }
}

@Composable
private fun PreferenceSectionPanel(
    section: ComposePreferenceSection,
    colors: ComposePreferenceColors,
    panelRadiusPx: Float,
    titleFontFamily: FontFamily,
    onPreferenceClick: (Preference) -> Unit,
    onSwitchChange: (SwitchPreferenceCompat, Boolean) -> Unit,
    onSeekChange: (SeekBarPreference, Int) -> Unit,
    scrollTargetKey: String?,
    onScrollTargetConsumed: () -> Unit
) {
    val context = LocalContext.current
    val panelImage = UiCorner.panelImageDrawable(context, panelRadiusPx)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PanelHorizontalPadding)
            .preferencePanelBackground(
                normalColor = colors.row,
                panelImage = panelImage,
                borderColor = colors.border,
                radiusPx = panelRadiusPx
            )
    ) {
        if (!section.title.isNullOrBlank()) {
            Text(
                text = section.title.toString(),
                color = colors.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = titleFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
            )
        }
        section.rows.forEachIndexed { index, preference ->
            PreferenceRow(
                preference = preference,
                colors = colors,
                panelRadiusPx = panelRadiusPx,
                isLast = index == section.rows.lastIndex,
                showDivider = index != section.rows.lastIndex,
                onPreferenceClick = onPreferenceClick,
                onSwitchChange = onSwitchChange,
                onSeekChange = onSeekChange,
                scrollTargetKey = scrollTargetKey,
                onScrollTargetConsumed = onScrollTargetConsumed
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PreferenceRow(
    preference: Preference,
    colors: ComposePreferenceColors,
    panelRadiusPx: Float,
    isLast: Boolean,
    showDivider: Boolean,
    onPreferenceClick: (Preference) -> Unit,
    onSwitchChange: (SwitchPreferenceCompat, Boolean) -> Unit,
    onSeekChange: (SeekBarPreference, Int) -> Unit,
    scrollTargetKey: String?,
    onScrollTargetConsumed: () -> Unit
) {
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(scrollTargetKey, preference.key) {
        if (!scrollTargetKey.isNullOrBlank() && preference.key == scrollTargetKey) {
            bringIntoViewRequester.bringIntoView()
            onScrollTargetConsumed()
        }
    }
    if (preference is SeekBarPreference) {
        SeekBarPreferenceRow(
            preference = preference,
            colors = colors,
            panelRadiusPx = panelRadiusPx,
            isLast = isLast,
            showDivider = showDivider,
            onSeekChange = onSeekChange,
            modifier = Modifier.bringIntoViewRequester(bringIntoViewRequester)
        )
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val enabled = preference.isEnabled
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
            .defaultMinSize(minHeight = 60.dp)
            .preferenceRowDecoration(
                pressed = pressed,
                pressedColor = colors.rowPressed,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isLast = isLast
            )
            .combinedClickable(
                enabled = enabled && preference.isSelectable,
                interactionSource = interactionSource,
                indication = null,
                onClick = { onPreferenceClick(preference) },
                onLongClick = preference.composeLongClickHandler()
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        PreferenceText(
            preference = preference,
            colors = colors,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        PreferenceWidget(
            preference = preference,
            colors = colors,
            enabled = enabled,
            onSwitchChange = onSwitchChange
        )
    }
}

private fun Preference.composeLongClickHandler(): (() -> Unit)? {
    return when (this) {
        is LegadoPreference -> if (hasLongClickListener()) {
            { performLongClick() }
        } else {
            null
        }

        is LegadoSwitchPreference -> if (hasLongClickListener()) {
            { performLongClick() }
        } else {
            null
        }

        else -> null
    }
}

@Composable
private fun PreferenceText(
    preference: Preference,
    colors: ComposePreferenceColors,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val titleColor = if (enabled) colors.primaryText else colors.disabledText
    val summaryColor = if (enabled) colors.secondaryText else colors.disabledText
    Column(modifier = modifier) {
        preference.title?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it.toString(),
                color = titleColor,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        preference.summary?.takeIf { it.isNotBlank() }?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = it.toString(),
                color = summaryColor,
                fontSize = 14.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PreferenceWidget(
    preference: Preference,
    colors: ComposePreferenceColors,
    enabled: Boolean,
    onSwitchChange: (SwitchPreferenceCompat, Boolean) -> Unit
) {
    when (preference) {
        is SwitchPreferenceCompat -> {
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = preference.isChecked,
                enabled = enabled,
                onCheckedChange = { onSwitchChange(preference, it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                    uncheckedThumbColor = colors.secondaryText.copy(alpha = 0.72f),
                    uncheckedTrackColor = Color(colors.row),
                    uncheckedBorderColor = colors.secondaryText.copy(alpha = 0.28f)
                )
            )
        }

        is IconListPreference -> {
            preference.selectedIconDrawable()?.let { drawable ->
                Spacer(modifier = Modifier.width(12.dp))
                DrawablePreview(
                    drawable = drawable,
                    enabled = enabled,
                    modifier = Modifier.size(42.dp)
                )
            }
        }

        is ListPreference -> {
            val entry = preference.entry?.toString().orEmpty()
            if (entry.isNotBlank()) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = entry,
                    color = if (enabled) colors.accent else colors.disabledText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.accent.copy(alpha = if (enabled) 0.10f else 0.05f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }

        is ColorPreference -> {
            Spacer(modifier = Modifier.width(12.dp))
            val color = preference.sharedPreferences
                ?.getInt(preference.key, colors.accent.toArgb())
                ?: colors.accent.toArgb()
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color(color))
            )
        }
    }
}

@Composable
private fun DrawablePreview(
    drawable: Drawable,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val alpha = if (enabled) 1f else 0.42f
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .drawWithCache {
                onDrawBehind {
                    drawIntoCanvas { canvas ->
                        drawable.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                        drawable.bounds = Rect(0, 0, size.width.toInt(), size.height.toInt())
                        drawable.draw(canvas.nativeCanvas)
                    }
                }
            }
    )
}

@Composable
private fun SeekBarPreferenceRow(
    preference: SeekBarPreference,
    colors: ComposePreferenceColors,
    panelRadiusPx: Float,
    isLast: Boolean,
    showDivider: Boolean,
    onSeekChange: (SeekBarPreference, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val enabled = preference.isEnabled
    var sliderValue by remember(preference.key, preference.value) {
        mutableFloatStateOf(preference.value.toFloat())
    }
    fun commitValue(value: Int) {
        val nextValue = value.coerceIn(preference.minValue, preference.maxValue)
        sliderValue = nextValue.toFloat()
        onSeekChange(preference, nextValue)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .preferenceRowDecoration(
                pressed = false,
                pressedColor = colors.rowPressed,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isLast = isLast
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PreferenceText(
                preference = preference,
                colors = colors,
                enabled = enabled,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = sliderValue.toInt().toString(),
                color = if (enabled) colors.accent else colors.disabledText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = sliderValue,
            enabled = enabled,
            valueRange = preference.minValue.toFloat()..preference.maxValue.toFloat(),
            onValueChange = {
                val nextValue = it.roundToInt().coerceIn(preference.minValue, preference.maxValue)
                if (sliderValue.toInt() != nextValue) {
                    commitValue(nextValue)
                } else {
                    sliderValue = it
                }
            },
            onValueChangeFinished = {
                commitValue(sliderValue.toInt())
            },
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.secondaryText.copy(alpha = 0.22f),
                disabledThumbColor = colors.disabledText,
                disabledActiveTrackColor = colors.disabledText.copy(alpha = 0.32f),
                disabledInactiveTrackColor = colors.disabledText.copy(alpha = 0.18f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            modifier = Modifier.fillMaxWidth()
        ) {
            StepControlButton(
                text = "-",
                enabled = enabled && sliderValue.toInt() > preference.minValue,
                colors = colors
            ) {
                commitValue(sliderValue.toInt() - 1)
            }
            StepControlButton(
                text = "+",
                enabled = enabled && sliderValue.toInt() < preference.maxValue,
                colors = colors
            ) {
                commitValue(sliderValue.toInt() + 1)
            }
        }
    }
}

@Composable
private fun StepControlButton(
    text: String,
    enabled: Boolean,
    colors: ComposePreferenceColors,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = if (enabled) colors.accent else colors.disabledText,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.accent.copy(alpha = if (enabled) 0.10f else 0.04f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 4.dp)
    )
}

private fun PreferenceGroup.toComposeSections(): List<ComposePreferenceSection> {
    val sections = mutableListOf<ComposePreferenceSection>()
    val pendingRows = mutableListOf<Preference>()
    for (index in 0 until preferenceCount) {
        val preference = getPreference(index)
        if (!preference.isVisible) continue
        if (preference is PreferenceCategory) {
            if (pendingRows.isNotEmpty()) {
                sections += ComposePreferenceSection(null, pendingRows.toList())
                pendingRows.clear()
            }
            val rows = preference.visibleRows()
            if (rows.isNotEmpty()) {
                sections += ComposePreferenceSection(preference.title, rows)
            }
        } else if (preference is PreferenceGroup) {
            val rows = preference.visibleRows()
            if (rows.isNotEmpty()) {
                sections += ComposePreferenceSection(preference.title, rows)
            }
        } else {
            pendingRows += preference
        }
    }
    if (pendingRows.isNotEmpty()) {
        sections += ComposePreferenceSection(null, pendingRows.toList())
    }
    return sections
}

private fun PreferenceGroup.visibleRows(): List<Preference> {
    val rows = mutableListOf<Preference>()
    for (index in 0 until preferenceCount) {
        val preference = getPreference(index)
        if (!preference.isVisible) continue
        if (preference is PreferenceCategory || preference is PreferenceGroup) {
            rows += preference.visibleRows()
        } else {
            rows += preference
        }
    }
    return rows
}

private fun CharSequence.isNotBlank(): Boolean = toString().isNotBlank()

private fun Modifier.preferencePanelBackground(
    normalColor: Int,
    panelImage: Drawable?,
    borderColor: Int?,
    radiusPx: Float
): Modifier {
    return drawWithCache {
        val path = Path()
        val rect = RectF(0f, 0f, size.width, size.height)
        path.addRoundRect(rect, radiusPx, radiusPx, Path.Direction.CW)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = normalColor
        }
        val strokePaint = borderColor?.let { color ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1f
                this.color = color
            }
        }
        onDrawBehind {
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                nativeCanvas.drawPath(path, fillPaint)
                panelImage?.let { drawable ->
                    drawable.bounds = Rect(0, 0, size.width.toInt(), size.height.toInt())
                    drawable.draw(nativeCanvas)
                }
                strokePaint?.let { nativeCanvas.drawPath(path, it) }
            }
        }
    }
}

private fun Modifier.preferenceRowDecoration(
    pressed: Boolean,
    pressedColor: Int,
    dividerColor: Color,
    showDivider: Boolean,
    radiusPx: Float,
    isLast: Boolean
): Modifier {
    return drawWithCache {
        val path = Path()
        val rect = RectF(0f, 0f, size.width, size.height)
        val bottom = if (isLast) radiusPx else 0f
        path.addRoundRect(
            rect,
            floatArrayOf(0f, 0f, 0f, 0f, bottom, bottom, bottom, bottom),
            Path.Direction.CW
        )
        val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = pressedColor
        }
        val dividerInset = 16.dp.toPx()
        onDrawBehind {
            if (pressed) {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawPath(path, pressedPaint)
                }
            }
            if (showDivider) {
                val y = size.height - 1f
                drawLine(
                    color = dividerColor,
                    start = Offset(dividerInset, y),
                    end = Offset(size.width - dividerInset, y),
                    strokeWidth = 1f
                )
            }
        }
    }
}
