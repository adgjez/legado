package io.legado.app.ui.config.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.LegadoMiuixSlider
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.appSettingRowDecoration
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.compose.toMiuixPalette
import kotlin.math.roundToInt

private val PanelHorizontalPadding = 12.dp

@Composable
fun SettingSpecScreen(
    page: SettingPageSpec,
    scrollTargetKey: String?,
    onTargetReady: (String) -> Unit,
    onTargetMissing: () -> Unit,
    onItemClick: (SettingItemSpec) -> Unit
) {
    val colors = rememberAppSettingPalette()
    val panelRadiusPx = colors.panelRadiusPx
    val sections = remember(page) {
        page.sections.mapNotNull { section ->
            val rows = section.items.filter { it.visible }
            rows.takeIf { it.isNotEmpty() }?.let {
                SettingSectionSpec(title = section.title, items = it)
            }
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(scrollTargetKey, sections) {
        val targetKey = scrollTargetKey?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val sectionIndex = sections.indexOfFirst { section ->
            section.items.any { it.key == targetKey || targetKey in it.searchKeys }
        }
        if (sectionIndex >= 0) {
            listState.animateScrollToItem(sectionIndex)
            onTargetReady(targetKey)
        } else {
            onTargetMissing()
        }
    }

    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = colors.bodyFontFamily)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(colors.page)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(
                sections,
                key = { index, section -> "${index}:${section.title}:${section.items.firstOrNull()?.key}" }
            ) { _, section ->
                SettingSectionPanel(
                    section = section,
                    colors = colors,
                    panelRadiusPx = panelRadiusPx,
                    onItemClick = onItemClick
                )
            }
        }
    }
}

@Composable
private fun SettingSectionPanel(
    section: SettingSectionSpec,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    onItemClick: (SettingItemSpec) -> Unit
) {
    val context = LocalContext.current
    val panelImage = UiCorner.panelImageDrawable(context, panelRadiusPx)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PanelHorizontalPadding)
            .appSettingPanelBackground(
                normalColor = colors.row,
                panelImage = panelImage,
                borderColor = colors.border,
                radiusPx = panelRadiusPx
            )
    ) {
        AppSettingSectionTitle(title = section.title, palette = colors)
        section.items.forEachIndexed { index, item ->
            SettingRow(
                item = item,
                colors = colors,
                panelRadiusPx = panelRadiusPx,
                showDivider = index != section.items.lastIndex,
                isLast = index == section.items.lastIndex,
                onItemClick = onItemClick
            )
        }
    }
}

@Composable
private fun SettingRow(
    item: SettingItemSpec,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    showDivider: Boolean,
    isLast: Boolean,
    onItemClick: (SettingItemSpec) -> Unit
) {
    if (item is SettingSliderSpec) {
        SettingSliderRow(
            item = item,
            colors = colors,
            panelRadiusPx = panelRadiusPx,
            showDivider = showDivider,
            isLast = isLast
        )
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .appSettingRowDecoration(
                pressed = pressed,
                pressedColor = colors.rowPressed,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isLast = isLast
            )
            .clickable(
                enabled = item.enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = { onItemClick(item) }
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        SettingText(
            item = item,
            colors = colors,
            modifier = Modifier.weight(1f)
        )
        when (item) {
            is SettingSwitchSpec -> {
                Spacer(modifier = Modifier.width(12.dp))
                val palette = rememberAppDialogStyle().toMiuixPalette()
                LegadoMiuixSwitch(
                    checked = item.checked,
                    onCheckedChange = item.onCheckedChange,
                    palette = palette,
                    enabled = item.enabled
                )
            }

            is SettingChoiceSpec -> {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = item.selectedLabel.toString(),
                    color = if (item.enabled) colors.accent else colors.disabledText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(colors.accent.copy(alpha = if (item.enabled) 0.10f else 0.05f))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }

            is SettingSliderSpec -> Unit
            is SettingActionSpec -> Unit
        }
    }
}

@Composable
private fun SettingSliderRow(
    item: SettingSliderSpec,
    colors: AppSettingPalette,
    panelRadiusPx: Float,
    showDivider: Boolean,
    isLast: Boolean
) {
    var sliderValue by remember(item.key, item.value) {
        mutableFloatStateOf(item.value.toFloat())
    }
    val palette = rememberAppDialogStyle().toMiuixPalette()
    fun commit(value: Int) {
        val nextValue = value.coerceIn(item.valueRange.first, item.valueRange.last)
        sliderValue = nextValue.toFloat()
        item.onValueChange(nextValue)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .appSettingRowDecoration(
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
            SettingText(
                item = item,
                colors = colors,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = sliderValue.toInt().toString(),
                color = if (item.enabled) colors.accent else colors.disabledText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
        LegadoMiuixSlider(
            value = sliderValue,
            onValueChange = {
                val nextValue = it.roundToInt().coerceIn(item.valueRange.first, item.valueRange.last)
                if (sliderValue.toInt() != nextValue) {
                    commit(nextValue)
                } else {
                    sliderValue = it
                }
            },
            palette = palette,
            valueRange = item.valueRange.first.toFloat()..item.valueRange.last.toFloat(),
            steps = (item.valueRange.last - item.valueRange.first - 1).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SettingText(
    item: SettingItemSpec,
    colors: AppSettingPalette,
    modifier: Modifier = Modifier
) {
    val titleColor = if (item.enabled) colors.primaryText else colors.disabledText
    val summaryColor = if (item.enabled) colors.secondaryText else colors.disabledText
    Column(modifier = modifier) {
        Text(
            text = item.title.toString(),
            color = titleColor,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        item.summary?.takeIf { it.isNotBlank() }?.let {
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
