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
    onToggleSelect: (TxtTocRule) -> Unit,
    onToggleEnable: (TxtTocRule) -> Unit,
    onEdit: (TxtTocRule) -> Unit,
    onMenuMore: (TxtTocRule) -> Unit,
    onSelectAll: () -> Unit,
    onRevertSelection: () -> Unit,
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
            key = { _, rule -> rule.id }
        ) { _, rule ->
            TxtTocRuleItemRow(
                name = rule.name,
                example = rule.example,
                enabled = rule.enable,
                isSelected = selectedIds.contains(rule.id),
                palette = palette,
                onToggleSelect = { onToggleSelect(rule) },
                onToggleEnable = { onToggleEnable(rule) },
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
    palette: AppManagementPalette,
    onToggleSelect: () -> Unit,
    onToggleEnable: () -> Unit,
    onEdit: () -> Unit,
    onMenuMore: () -> Unit
) {
    AppManagementListRow(
        title = name,
        subtitle = example,
        palette = palette,
        selected = isSelected,
        selectionVisible = true,
        onToggleSelection = onToggleSelect,
        switchChecked = enabled,
        onSwitchChange = { onToggleEnable() },
        onEdit = onEdit,
        onMore = onMenuMore
    )
}
