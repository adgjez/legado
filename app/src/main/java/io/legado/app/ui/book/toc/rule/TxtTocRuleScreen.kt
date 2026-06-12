package io.legado.app.ui.book.toc.rule

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

@Composable
internal fun TxtTocRuleScreen(
    rules: List<TxtTocRule>,
    selectedIds: Set<Long>,
    isSelectMode: Boolean,
    onToggleSelect: (TxtTocRule) -> Unit,
    onToggleEnable: (TxtTocRule, Boolean) -> Unit,
    onEdit: (TxtTocRule) -> Unit,
    onMenuMore: (TxtTocRule) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberAppManagementPalette()

    AppManagementLazyColumn(
        palette = palette,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        itemsIndexed(
            items = rules,
            key = { _, rule -> rule.id },
            contentType = { _, _ -> "txtTocRule" }
        ) { _, rule ->
            TxtTocRuleItemRow(
                name = rule.name,
                example = rule.example,
                enabled = rule.enable,
                isSelected = selectedIds.contains(rule.id),
                isSelectMode = isSelectMode,
                palette = palette,
                onToggleSelect = { onToggleSelect(rule) },
                onToggleEnable = { enabled -> onToggleEnable(rule, enabled) },
                onEdit = { onEdit(rule) },
                onMenuMore = { onMenuMore(rule) }
            )
        }
    }
}

@Composable
private fun TxtTocRuleItemRow(
    name: String,
    example: String?,
    enabled: Boolean,
    isSelected: Boolean,
    isSelectMode: Boolean,
    palette: AppManagementPalette,
    onToggleSelect: () -> Unit,
    onToggleEnable: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onMenuMore: () -> Unit
) {
    AppManagementListRow(
        title = name,
        subtitle = example,
        palette = palette,
        selected = isSelected,
        selectionVisible = isSelectMode,
        animatedSelection = true,
        reserveSelectionSlot = false,
        onToggleSelection = onToggleSelect,
        switchChecked = enabled,
        onSwitchChange = onToggleEnable,
        titleMaxLines = 1,
        subtitleMaxLines = 1,
        minHeight = 56.dp,
        drawPanelImage = false,
        onClick = {
            if (isSelectMode) onToggleSelect() else onEdit()
        },
        onLongClick = onToggleSelect,
        onEdit = onEdit,
        onMore = onMenuMore
    )
}
