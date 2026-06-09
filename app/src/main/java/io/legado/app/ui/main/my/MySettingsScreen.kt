package io.legado.app.ui.main.my

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixSelectField
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
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

@Composable
internal fun MySettingsScreen(
    sections: List<MySettingsSectionModel>,
    subSearchItems: List<MySettingsSubSearchItem>,
    searchQuery: String,
    themeModeValue: String,
    themeOptions: List<MySettingsThemeOption>,
    webServiceState: MyWebServiceUiState,
    onThemeModeSelected: (String) -> Unit,
    onWebServiceCheckedChange: (Boolean) -> Unit,
    onWebServiceLongClick: () -> Unit,
    onRowClick: (String, MySettingsSubSearchItem?) -> Unit
) {
    val context = LocalContext.current
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val pageBackground = Color(context.backgroundColor)
    val sectionColor = if (AppConfig.isNightTheme) {
        Color(ColorUtils.blendColors(0xff24262b.toInt(), context.backgroundColor, 0.18f))
    } else {
        Color(ColorUtils.blendColors(0xfffbfcfe.toInt(), context.backgroundColor, 0.22f))
    }
    val query = searchQuery.trim().lowercase()
    val visibleSections = sections.mapNotNull { section ->
        val rows = section.rows.mapNotNull { row ->
            val summary = row.effectiveSummary(webServiceState)
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
            if (!visible) {
                null
            } else {
                VisibleMySettingsRow(
                    row = row,
                    summary = matchedSubItems.firstOrNull()?.title ?: summary,
                    searchTarget = matchedSubItems.firstOrNull()
                )
            }
        }
        rows.takeIf { it.isNotEmpty() }?.let {
            VisibleMySettingsSection(section.title, it)
        }
    }

    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBackground)
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
                items(
                    items = visibleSections,
                    key = { it.title }
                ) { section ->
                    LegadoMiuixCard(
                        modifier = Modifier.fillMaxWidth(),
                        color = sectionColor,
                        contentColor = palette.primaryText,
                        cornerRadius = style.panelRadius,
                        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = section.title,
                            color = palette.accent,
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
                                    MySettingsRowKind.ThemeMode -> MyThemeModeRow(
                                        item = item,
                                        themeModeValue = themeModeValue,
                                        themeOptions = themeOptions,
                                        onThemeModeSelected = onThemeModeSelected
                                    )

                                    MySettingsRowKind.WebService -> MyWebServiceRow(
                                        item = item,
                                        webServiceState = webServiceState,
                                        onCheckedChange = onWebServiceCheckedChange,
                                        onLongClick = onWebServiceLongClick,
                                        onClick = {
                                            onWebServiceCheckedChange(!webServiceState.checked)
                                        }
                                    )

                                    MySettingsRowKind.Action -> MyActionRow(
                                        item = item,
                                        onClick = {
                                            onRowClick(item.row.key, item.searchTarget)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                if (visibleSections.isEmpty()) {
                    item(key = "empty") {
                        LegadoMiuixCard(
                            modifier = Modifier.fillMaxWidth(),
                            color = sectionColor,
                            contentColor = palette.primaryText,
                            cornerRadius = style.panelRadius,
                            insidePadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp)
                        ) {
                            Text(
                                text = "没有匹配的设置",
                                color = palette.secondaryText,
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
private fun MyThemeModeRow(
    item: VisibleMySettingsRow,
    themeModeValue: String,
    themeOptions: List<MySettingsThemeOption>,
    onThemeModeSelected: (String) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val selected = themeOptions.firstOrNull { it.value == themeModeValue }
        ?: themeOptions.first()
    LegadoMiuixSelectField(
        label = item.row.title,
        options = themeOptions,
        selected = selected,
        optionLabel = { it.label },
        optionDescription = { item.summary },
        onSelected = { onThemeModeSelected(it.value) },
        palette = palette,
        cornerRadius = style.actionRadius,
        compact = true,
        showSelectedMark = false,
        popupTitle = item.row.title,
        popupWidth = 320.dp
    )
}

@Composable
private fun MyWebServiceRow(
    item: VisibleMySettingsRow,
    webServiceState: MyWebServiceUiState,
    onCheckedChange: (Boolean) -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(webServiceState.checked, webServiceState.summary) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = {
                        if (webServiceState.checked) {
                            onLongClick()
                        }
                    }
                )
            },
        color = palette.surface,
        contentColor = palette.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MySettingsDot(color = palette.accent)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.row.title,
                    color = palette.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = webServiceState.summary,
                    color = palette.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            LegadoMiuixSwitch(
                checked = webServiceState.checked,
                onCheckedChange = onCheckedChange,
                palette = palette
            )
        }
    }
}

@Composable
private fun MyActionRow(
    item: VisibleMySettingsRow,
    onClick: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(item.row.key) {
                detectTapGestures(onTap = { onClick() })
            },
        color = if (item.row.danger) {
            palette.danger.copy(alpha = 0.12f)
        } else {
            palette.surface
        },
        contentColor = if (item.row.danger) palette.danger else palette.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MySettingsDot(
                color = if (item.row.danger) palette.danger else palette.accent
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.row.title,
                    color = if (item.row.danger) palette.danger else palette.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.summary.takeIf { it.isNotBlank() }?.let {
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = it,
                        color = palette.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = ">",
                color = palette.secondaryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun MySettingsDot(color: Color) {
    Surface(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape),
        color = color.copy(alpha = 0.13f),
        contentColor = color
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

private data class VisibleMySettingsSection(
    val title: String,
    val rows: List<VisibleMySettingsRow>
)

private data class VisibleMySettingsRow(
    val row: MySettingsRowModel,
    val summary: String,
    val searchTarget: MySettingsSubSearchItem?
)

private fun MySettingsRowModel.effectiveSummary(
    webServiceState: MyWebServiceUiState
): String {
    return when (kind) {
        MySettingsRowKind.WebService -> webServiceState.summary
        else -> summary.orEmpty()
    }
}
