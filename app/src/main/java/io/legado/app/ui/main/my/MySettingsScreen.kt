package io.legado.app.ui.main.my

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.compose.rememberAppDialogStyle

internal enum class MySettingsRowKind {
    Action,
    ThemeMode,
    WebService
}

internal data class MySettingsThemeOption(
    val value: String,
    val label: String
)

internal data class MySettingsSubSearchItem(
    val ownerKey: String,
    val title: String,
    val summary: String,
    val key: String,
    val ownerConfigTag: String
) {
    val searchText: String = listOf(title, summary, key).joinToString(" ").lowercase()
}

internal data class MySettingsRowModel(
    val key: String,
    val title: String,
    val summary: String? = null,
    val kind: MySettingsRowKind = MySettingsRowKind.Action,
    val danger: Boolean = false
)

internal data class MySettingsSectionModel(
    val title: String,
    val rows: List<MySettingsRowModel>
)

internal data class MyWebServiceUiState(
    val checked: Boolean,
    val summary: String
)

private data class VisibleSection(
    val title: String,
    val rows: List<VisibleRow>
)

private data class VisibleRow(
    val row: MySettingsRowModel,
    val summary: String,
    val searchTarget: MySettingsSubSearchItem?
)

private data class MySettingsColors(
    val page: Color,
    val row: Int,
    val rowPressed: Int,
    val divider: Color,
    val border: Int?,
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val danger: Color,
    val onAccent: Color
)

private val SettingsHorizontalPadding = 12.dp

@Composable
internal fun MySettingsScreen(
    sections: List<MySettingsSectionModel>,
    subSearchItems: List<MySettingsSubSearchItem>,
    searchQuery: String,
    themeModeLabel: String,
    webServiceState: MyWebServiceUiState,
    onThemeModeClick: () -> Unit,
    onWebServiceCheckedChange: (Boolean) -> Unit,
    onWebServiceClick: () -> Unit,
    onRowClick: (String, MySettingsSubSearchItem?) -> Unit
) {
    val context = LocalContext.current
    val style = rememberAppDialogStyle()
    val rowBaseColor = ContextCompat.getColor(context, R.color.background_card)
    val panelRadiusPx = UiCorner.panelRadius(context)
    val colors = MySettingsColors(
        page = Color(context.backgroundColor),
        row = UiCorner.surfaceColor(rowBaseColor),
        rowPressed = UiCorner.surfaceColor(rowBaseColor, pressed = true),
        divider = Color(ContextCompat.getColor(context, R.color.bg_divider_line)),
        border = UiCorner.panelBorderColor(context),
        primaryText = Color(ContextCompat.getColor(context, R.color.primaryText)),
        secondaryText = Color(ContextCompat.getColor(context, R.color.tv_text_summary)),
        accent = Color(ContextCompat.getColor(context, R.color.accent)),
        danger = style.danger,
        onAccent = Color.White
    )
    val visibleSections = buildVisibleSections(
        sections = sections,
        subSearchItems = subSearchItems,
        searchQuery = searchQuery,
        webServiceState = webServiceState,
        themeModeLabel = themeModeLabel
    )

    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.page)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = dimensionResource(R.dimen.main_content_bottom_bar_padding) + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visibleSections, key = { it.title }) { section ->
                    SettingsSectionCard(
                        section = section,
                        colors = colors,
                        panelRadiusPx = panelRadiusPx,
                        titleFontFamily = style.titleFontFamily,
                        themeModeLabel = themeModeLabel,
                        webServiceState = webServiceState,
                        onThemeModeClick = onThemeModeClick,
                        onWebServiceCheckedChange = onWebServiceCheckedChange,
                        onWebServiceClick = onWebServiceClick,
                        onRowClick = onRowClick
                    )
                }
                if (visibleSections.isEmpty()) {
                    item("empty") {
                        EmptySettingsFrame(
                            colors = colors,
                            panelRadiusPx = panelRadiusPx,
                            titleFontFamily = style.titleFontFamily
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    section: VisibleSection,
    colors: MySettingsColors,
    panelRadiusPx: Float,
    titleFontFamily: FontFamily,
    themeModeLabel: String,
    webServiceState: MyWebServiceUiState,
    onThemeModeClick: () -> Unit,
    onWebServiceCheckedChange: (Boolean) -> Unit,
    onWebServiceClick: () -> Unit,
    onRowClick: (String, MySettingsSubSearchItem?) -> Unit
) {
    val context = LocalContext.current
    val panelImage = UiCorner.panelImageDrawable(context, panelRadiusPx)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsHorizontalPadding)
            .preferenceGroupBackground(
                normalColor = colors.row,
                panelImage = panelImage,
                borderColor = colors.border,
                radiusPx = panelRadiusPx
            )
    ) {
        Text(
            text = section.title,
            color = colors.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = titleFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    top = 16.dp,
                    end = 16.dp,
                    bottom = 8.dp
                )
        )
        section.rows.forEachIndexed { index, item ->
            val isLastRowInSection = index == section.rows.lastIndex
            val showDivider = !isLastRowInSection
            when (item.row.kind) {
                MySettingsRowKind.ThemeMode -> SettingsActionRow(
                    item = item.copy(summary = themeModeLabel),
                    colors = colors,
                    panelRadiusPx = panelRadiusPx,
                    isFirst = false,
                    isLast = isLastRowInSection,
                    showDivider = showDivider,
                    onClick = onThemeModeClick
                )

                MySettingsRowKind.WebService -> WebServiceRow(
                    item = item,
                    state = webServiceState,
                    colors = colors,
                    panelRadiusPx = panelRadiusPx,
                    isFirst = false,
                    isLast = isLastRowInSection,
                    showDivider = showDivider,
                    onCheckedChange = onWebServiceCheckedChange,
                    onClick = onWebServiceClick
                )

                MySettingsRowKind.Action -> SettingsActionRow(
                    item = item,
                    colors = colors,
                    panelRadiusPx = panelRadiusPx,
                    isFirst = false,
                    isLast = isLastRowInSection,
                    showDivider = showDivider,
                    onClick = { onRowClick(item.row.key, item.searchTarget) }
                )
            }
        }
    }
}

@Composable
private fun SettingsActionRow(
    item: VisibleRow,
    colors: MySettingsColors,
    panelRadiusPx: Float,
    isFirst: Boolean,
    isLast: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val textColor = if (item.row.danger) colors.danger else colors.primaryText
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .preferenceRowDecoration(
                pressed = pressed,
                danger = item.row.danger,
                pressedColor = colors.rowPressed,
                dangerColor = colors.danger,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isFirst = isFirst,
                isLast = isLast
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.row.title,
                color = textColor,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.summary.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = item.summary,
                    color = colors.secondaryText,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun WebServiceRow(
    item: VisibleRow,
    state: MyWebServiceUiState,
    colors: MySettingsColors,
    panelRadiusPx: Float,
    isFirst: Boolean,
    isLast: Boolean,
    showDivider: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 60.dp)
            .preferenceRowDecoration(
                pressed = pressed,
                danger = false,
                pressedColor = colors.rowPressed,
                dangerColor = colors.danger,
                dividerColor = colors.divider,
                showDivider = showDivider,
                radiusPx = panelRadiusPx,
                isFirst = isFirst,
                isLast = isLast
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.row.title,
                color = colors.primaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.summary,
                color = colors.secondaryText,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = state.checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                uncheckedThumbColor = colors.secondaryText.copy(alpha = 0.72f),
                uncheckedTrackColor = Color(colors.row),
                uncheckedBorderColor = colors.secondaryText.copy(alpha = 0.28f)
            )
        )
    }
}

@Composable
private fun EmptySettingsFrame(
    colors: MySettingsColors,
    panelRadiusPx: Float,
    titleFontFamily: FontFamily
) {
    val context = LocalContext.current
    val panelImage = UiCorner.panelImageDrawable(context, panelRadiusPx)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsHorizontalPadding)
            .preferenceGroupBackground(
                normalColor = colors.row,
                panelImage = panelImage,
                borderColor = colors.border,
                radiusPx = panelRadiusPx
            )
    ) {
        Text(
            text = "搜索结果",
            color = colors.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = titleFontFamily,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 8.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 60.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text(
                text = "没有匹配的设置",
                color = colors.secondaryText,
                fontSize = 14.sp
            )
        }
    }
}

private fun Modifier.preferenceGroupBackground(
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
                    drawable.bounds = Rect(
                        0,
                        0,
                        size.width.toInt(),
                        size.height.toInt()
                    )
                    drawable.draw(nativeCanvas)
                }
                strokePaint?.let { nativeCanvas.drawPath(path, it) }
            }
        }
    }
}

private fun Modifier.preferenceRowDecoration(
    pressed: Boolean,
    danger: Boolean,
    pressedColor: Int,
    dangerColor: Color,
    dividerColor: Color,
    showDivider: Boolean,
    radiusPx: Float,
    isFirst: Boolean,
    isLast: Boolean
): Modifier {
    return drawWithCache {
        val path = Path()
        val rect = RectF(0f, 0f, size.width, size.height)
        val top = if (isFirst) radiusPx else 0f
        val bottom = if (isLast) radiusPx else 0f
        path.addRoundRect(
            rect,
            floatArrayOf(top, top, top, top, bottom, bottom, bottom, bottom),
            Path.Direction.CW
        )
        val dangerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = dangerColor.copy(alpha = 0.10f).toArgb()
        }
        val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = pressedColor
        }
        val dividerInset = 16.dp.toPx()
        onDrawBehind {
            drawIntoCanvas { canvas ->
                val nativeCanvas = canvas.nativeCanvas
                if (danger) {
                    nativeCanvas.drawPath(path, dangerPaint)
                }
                if (pressed) {
                    nativeCanvas.drawPath(path, pressedPaint)
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

private fun buildVisibleSections(
    sections: List<MySettingsSectionModel>,
    subSearchItems: List<MySettingsSubSearchItem>,
    searchQuery: String,
    webServiceState: MyWebServiceUiState,
    themeModeLabel: String
): List<VisibleSection> {
    val query = searchQuery.trim().lowercase()
    return sections.mapNotNull { section ->
        val rows = section.rows.mapNotNull { row ->
            val summary = row.effectiveSummary(webServiceState, themeModeLabel)
            val matchedSubItems = if (query.isBlank()) {
                emptyList()
            } else {
                subSearchItems.filter { item ->
                    item.ownerKey == row.key && item.searchText.contains(query)
                }
            }
            val visible = query.isBlank()
                || row.title.lowercase().contains(query)
                || summary.lowercase().contains(query)
                || row.key.lowercase().contains(query)
                || matchedSubItems.isNotEmpty()
            if (visible) {
                VisibleRow(
                    row = row,
                    summary = matchedSubItems.firstOrNull()?.title ?: summary,
                    searchTarget = matchedSubItems.firstOrNull()
                )
            } else {
                null
            }
        }
        rows.takeIf { it.isNotEmpty() }?.let { VisibleSection(section.title, it) }
    }
}

private fun MySettingsRowModel.effectiveSummary(
    webServiceState: MyWebServiceUiState,
    themeModeLabel: String
): String {
    return when (kind) {
        MySettingsRowKind.ThemeMode -> themeModeLabel
        MySettingsRowKind.WebService -> webServiceState.summary
        MySettingsRowKind.Action -> summary.orEmpty()
    }
}
