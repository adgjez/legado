package io.legado.app.ui.replace

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

@Composable
internal fun ReplaceRuleScreen(
    rules: List<ReplaceRule>,
    selected: Set<Long>,
    isSelectMode: Boolean,
    onSelectToggle: (ReplaceRule) -> Unit,
    onToggleEnabled: (ReplaceRule, Boolean) -> Unit,
    onEdit: (ReplaceRule) -> Unit,
    onShowMenu: (ReplaceRule) -> Unit
) {
    val palette = rememberAppManagementPalette()

    AppManagementLazyColumn(
        palette = palette,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        itemsIndexed(
            rules,
            key = { _, rule -> rule.id },
            contentType = { _, _ -> "replaceRule" }
        ) { _, rule ->
            ReplaceRuleItemRow(
                title = rule.getDisplayNameGroup(),
                enabled = rule.isEnabled,
                isSelected = rule.id in selected,
                isSelectMode = isSelectMode,
                palette = palette,
                onSelectToggle = { onSelectToggle(rule) },
                onToggleEnabled = { onToggleEnabled(rule, it) },
                onEdit = { onEdit(rule) },
                onShowMenu = { onShowMenu(rule) }
            )
        }
    }
}

@Composable
private fun ReplaceRuleItemRow(
    title: String,
    enabled: Boolean,
    isSelected: Boolean,
    isSelectMode: Boolean,
    palette: AppManagementPalette,
    onSelectToggle: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onShowMenu: () -> Unit
) {
    AppManagementListRow(
        title = title,
        palette = palette,
        selected = isSelected,
        selectionVisible = isSelectMode,
        animatedSelection = true,
        reserveSelectionSlot = false,
        onToggleSelection = onSelectToggle,
        switchChecked = enabled,
        onSwitchChange = onToggleEnabled,
        titleMaxLines = 1,
        minHeight = 56.dp,
        drawPanelImage = false,
        onClick = {
            if (isSelectMode) onSelectToggle() else onEdit()
        },
        onLongClick = onSelectToggle,
        onEdit = onEdit,
        onMore = onShowMenu
    )
}
