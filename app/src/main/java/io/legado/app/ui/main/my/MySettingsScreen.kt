package io.legado.app.ui.main.my

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.utils.ColorUtils

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
    val section: Color,
    val row: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val danger: Color,
    val onAccent: Color
)

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
    val colors = MySettingsColors(
        page = Color(context.backgroundColor),
        section = if (AppConfig.isNightTheme) {
            Color(ColorUtils.blendColors(0xff24262b.toInt(), context.backgroundColor, 0.18f))
        } else {
            Color(ColorUtils.blendColors(0xfffbfcfe.toInt(), context.backgroundColor, 0.22f))
        },
        row = style.surface,
        primaryText = style.primaryText,
        secondaryText = style.secondaryText,
        accent = style.accent,
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.page)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    top = 12.dp,
                    end = 16.dp,
                    bottom = dimensionResource(R.dimen.main_content_bottom_bar_padding) + 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(visibleSections, key = { it.title }) { section ->
                    SettingsSectionCard(
                        section = section,
                        colors = colors,
                        panelRadius = style.panelRadius,
                        actionRadius = style.actionRadius,
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
                        SettingsSurface(
                            color = colors.section,
                            radius = style.panelRadius,
                            modifier = Modifier.fillMaxWidth(),
                            padding = PaddingValues(horizontal = 18.dp, vertical = 18.dp)
                        ) {
                            Text(
                                text = "没有匹配的设置",
                                color = colors.secondaryText,
                                fontSize = 14.sp
                            )
                        }
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
    panelRadius: Dp,
    actionRadius: Dp,
    themeModeLabel: String,
    webServiceState: MyWebServiceUiState,
    onThemeModeClick: () -> Unit,
    onWebServiceCheckedChange: (Boolean) -> Unit,
    onWebServiceClick: () -> Unit,
    onRowClick: (String, MySettingsSubSearchItem?) -> Unit
) {
    SettingsSurface(
        color = colors.section,
        radius = panelRadius,
        modifier = Modifier.fillMaxWidth(),
        padding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            text = section.title,
            color = colors.accent,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            section.rows.forEach { item ->
                when (item.row.kind) {
                    MySettingsRowKind.ThemeMode -> SettingsActionRow(
                        item = item.copy(summary = themeModeLabel),
                        colors = colors,
                        actionRadius = actionRadius,
                        onClick = onThemeModeClick
                    )
                    MySettingsRowKind.WebService -> WebServiceRow(
                        item = item,
                        state = webServiceState,
                        colors = colors,
                        actionRadius = actionRadius,
                        onCheckedChange = onWebServiceCheckedChange,
                        onClick = onWebServiceClick
                    )
                    MySettingsRowKind.Action -> SettingsActionRow(
                        item = item,
                        colors = colors,
                        actionRadius = actionRadius,
                        onClick = { onRowClick(item.row.key, item.searchTarget) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsActionRow(
    item: VisibleRow,
    colors: MySettingsColors,
    actionRadius: Dp,
    onClick: () -> Unit
) {
    val textColor = if (item.row.danger) colors.danger else colors.primaryText
    SettingsSurface(
        color = if (item.row.danger) colors.danger.copy(alpha = 0.12f) else colors.row,
        radius = actionRadius,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        padding = PaddingValues(horizontal = 15.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.row.title,
                    color = textColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.summary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = item.summary,
                        color = colors.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = ">",
                color = colors.secondaryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun WebServiceRow(
    item: VisibleRow,
    state: MyWebServiceUiState,
    colors: MySettingsColors,
    actionRadius: Dp,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    SettingsSurface(
        color = colors.row,
        radius = actionRadius,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        padding = PaddingValues(horizontal = 15.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.row.title,
                    color = colors.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = state.summary,
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = state.checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                    uncheckedThumbColor = colors.secondaryText.copy(alpha = 0.72f),
                    uncheckedTrackColor = colors.section
                )
            )
        }
    }
}

@Composable
private fun SettingsSurface(
    color: Color,
    radius: Dp,
    modifier: Modifier,
    padding: PaddingValues,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(radius),
        color = color,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(padding)) {
            content()
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
