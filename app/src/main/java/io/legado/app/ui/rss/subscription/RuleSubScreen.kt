package io.legado.app.ui.rss.subscription

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.RuleSub
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

@Composable
internal fun RuleSubScreen(
    subscriptions: List<RuleSub>,
    typeLabels: List<String>,
    emptyMessage: String,
    onOpen: (RuleSub) -> Unit,
    onEdit: (RuleSub) -> Unit,
    onMenuMore: (RuleSub) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberAppManagementPalette()

    Box(modifier = modifier.fillMaxSize()) {
        AppManagementLazyColumn(
            palette = palette,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            itemsIndexed(
                items = subscriptions,
                key = { _, item -> item.id },
                contentType = { _, _ -> "ruleSub" }
            ) { _, item ->
                RuleSubItemRow(
                    ruleSub = item,
                    typeLabel = typeLabels.getOrNull(item.type).orEmpty(),
                    palette = palette,
                    onOpen = { onOpen(item) },
                    onEdit = { onEdit(item) },
                    onMenuMore = { onMenuMore(item) }
                )
            }
        }
        if (subscriptions.isEmpty()) {
            Text(
                text = emptyMessage,
                color = palette.settings.secondaryText,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontFamily = palette.settings.bodyFontFamily,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
private fun RuleSubItemRow(
    ruleSub: RuleSub,
    typeLabel: String,
    palette: AppManagementPalette,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onMenuMore: () -> Unit
) {
    AppManagementListRow(
        title = ruleSub.name.ifBlank { ruleSub.url },
        subtitle = ruleSub.url,
        palette = palette,
        titleMaxLines = 1,
        subtitleMaxLines = 1,
        minHeight = 58.dp,
        drawPanelImage = false,
        onClick = onOpen,
        onEdit = onEdit,
        onMore = onMenuMore,
        trailingBeforeSwitch = {
            if (typeLabel.isNotBlank()) {
                RuleSubTypeChip(label = typeLabel, palette = palette)
                Spacer(modifier = Modifier.width(8.dp))
            }
        }
    )
}

@Composable
private fun RuleSubTypeChip(
    label: String,
    palette: AppManagementPalette
) {
    LegadoMiuixCard(
        color = palette.settings.accent.copy(alpha = 0.14f),
        contentColor = palette.settings.accent,
        cornerRadius = palette.miuix.actionRadius ?: 9.dp,
        insidePadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = palette.settings.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
