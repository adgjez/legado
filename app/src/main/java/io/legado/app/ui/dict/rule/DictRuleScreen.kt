package io.legado.app.ui.dict.rule

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

@Composable
internal fun DictRuleScreen(
    rules: List<DictRule>,
    selectedNames: Set<String>,
    isSelectMode: Boolean,
    onToggleSelection: (DictRule) -> Unit,
    onToggleEnabled: (DictRule, Boolean) -> Unit,
    onEdit: (DictRule) -> Unit,
    onDelete: (DictRule) -> Unit
) {
    val palette = rememberAppManagementPalette()

    AppManagementLazyColumn(
        palette = palette,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        itemsIndexed(
            items = rules,
            key = { _, rule -> rule.name },
            contentType = { _, _ -> "dictRule" }
        ) { _, rule ->
            DictRuleItemRow(
                name = rule.name,
                enabled = rule.enabled,
                isSelected = rule.name in selectedNames,
                isSelectMode = isSelectMode,
                palette = palette,
                onToggleSelection = { onToggleSelection(rule) },
                onToggleEnabled = { enabled -> onToggleEnabled(rule, enabled) },
                onEdit = { onEdit(rule) },
                onDelete = { onDelete(rule) }
            )
        }
    }
}

@Composable
private fun DictRuleItemRow(
    name: String,
    enabled: Boolean,
    isSelected: Boolean,
    isSelectMode: Boolean,
    palette: AppManagementPalette,
    onToggleSelection: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    AppManagementListRow(
        title = name,
        palette = palette,
        selected = isSelected,
        selectionVisible = isSelectMode,
        animatedSelection = true,
        reserveSelectionSlot = false,
        onToggleSelection = onToggleSelection,
        switchChecked = enabled,
        onSwitchChange = onToggleEnabled,
        titleMaxLines = 1,
        minHeight = 56.dp,
        drawPanelImage = false,
        onClick = {
            if (isSelectMode) onToggleSelection() else onEdit()
        },
        onLongClick = onToggleSelection,
        onEdit = onEdit,
        onDelete = onDelete
    )
}
