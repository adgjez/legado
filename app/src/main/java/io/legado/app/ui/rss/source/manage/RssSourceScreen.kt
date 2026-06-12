package io.legado.app.ui.rss.source.manage

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.RssSource
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

@Composable
internal fun RssSourceScreen(
    sources: List<RssSource>,
    selectedUrls: Set<String>,
    isSelectMode: Boolean,
    onToggleSelect: (RssSource) -> Unit,
    onToggleEnabled: (RssSource, Boolean) -> Unit,
    onEdit: (RssSource) -> Unit,
    onShowMenu: (RssSource) -> Unit
) {
    val palette = rememberAppManagementPalette()

    AppManagementLazyColumn(
        palette = palette,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        itemsIndexed(
            items = sources,
            key = { _, item -> item.sourceUrl },
            contentType = { _, _ -> "rssSource" }
        ) { _, source ->
            RssSourceItemRow(
                source = source,
                palette = palette,
                isSelected = source.sourceUrl in selectedUrls,
                isSelectMode = isSelectMode,
                onToggleSelect = { onToggleSelect(source) },
                onToggleEnabled = { enabled -> onToggleEnabled(source, enabled) },
                onEdit = { onEdit(source) },
                onShowMenu = { onShowMenu(source) }
            )
        }
    }
}

@Composable
private fun RssSourceItemRow(
    source: RssSource,
    palette: AppManagementPalette,
    isSelected: Boolean,
    isSelectMode: Boolean,
    onToggleSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onShowMenu: () -> Unit
) {
    AppManagementListRow(
        title = source.sourceName,
        subtitle = source.sourceGroup,
        palette = palette,
        selected = isSelected,
        selectionVisible = isSelectMode,
        animatedSelection = true,
        reserveSelectionSlot = true,
        onToggleSelection = onToggleSelect,
        switchChecked = source.enabled,
        onSwitchChange = onToggleEnabled,
        titleMaxLines = 1,
        subtitleMaxLines = 1,
        minHeight = 56.dp,
        drawPanelImage = false,
        onClick = {
            if (isSelectMode) onToggleSelect() else onEdit()
        },
        onLongClick = onToggleSelect,
        onEdit = onEdit,
        onMore = onShowMenu
    )
}
