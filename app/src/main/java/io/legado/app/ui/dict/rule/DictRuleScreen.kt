package io.legado.app.ui.dict.rule

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.entities.DictRule
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

@Composable
internal fun DictRuleScreen(
    rules: List<DictRule>,
    selectedNames: Set<String>,
    onToggleSelection: (DictRule) -> Unit,
    onToggleEnabled: (DictRule, Boolean) -> Unit,
    onEdit: (DictRule) -> Unit,
    onDelete: (DictRule) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.surface)
            .windowInsetsPadding(WindowInsets.navigationBars),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        itemsIndexed(
            items = rules,
            key = { _, rule -> rule.name }
        ) { _, rule ->
            DictRuleItemRow(
                rule = rule,
                isSelected = rule.name in selectedNames,
                style = style,
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
    rule: DictRule,
    isSelected: Boolean,
    style: AppDialogStyle,
    palette: LegadoMiuixPalette,
    onToggleSelection: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        color = palette.surfaceVariant,
        contentColor = palette.primaryText,
        cornerRadius = 16.dp,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() },
                colors = CheckboxDefaults.colors(
                    checkedColor = palette.accent,
                    uncheckedColor = palette.secondaryText,
                    checkmarkColor = palette.onAccent
                )
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp)
            ) {
                Text(
                    text = rule.name,
                    color = palette.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            LegadoMiuixSwitch(
                checked = rule.enabled,
                onCheckedChange = onToggleEnabled,
                palette = palette
            )
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onEdit),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_edit),
                    contentDescription = stringResource(R.string.edit),
                    tint = palette.primaryText,
                    modifier = Modifier.size(20.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_outline_delete),
                    contentDescription = stringResource(R.string.delete),
                    tint = palette.danger,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
