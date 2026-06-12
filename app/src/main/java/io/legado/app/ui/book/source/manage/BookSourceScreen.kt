package io.legado.app.ui.book.source.manage

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

@Composable
internal fun BookSourceScreen(
    sources: List<BookSourcePart>,
    selectedUrls: Set<String>,
    isSelectMode: Boolean,
    showSourceHost: Boolean,
    sourceHostHeaders: Map<String, String?>,
    debugMessages: Map<String, String>,
    isChecking: Boolean,
    onToggleSelect: (BookSourcePart) -> Unit,
    onToggleEnabled: (BookSourcePart, Boolean) -> Unit,
    onEdit: (BookSourcePart) -> Unit,
    onShowMenu: (BookSourcePart) -> Unit
) {
    val palette = rememberAppManagementPalette()

    AppManagementLazyColumn(
        palette = palette,
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        itemsIndexed(
            items = sources,
            key = { _, source -> source.bookSourceUrl }
        ) { _, source ->
            val message = debugMessages[source.bookSourceUrl].orEmpty()
            BookSourceItemRow(
                title = source.getDisPlayNameGroup(),
                enabled = source.enabled,
                hasExploreUrl = source.hasExploreUrl,
                enabledExplore = source.enabledExplore,
                hostText = if (showSourceHost) sourceHostHeaders[source.bookSourceUrl] else null,
                debugMessage = message,
                debugInProgress = message.isNotBlank() && isChecking && !message.contains(FINAL_DEBUG_MESSAGE_REGEX),
                isSelected = source.bookSourceUrl in selectedUrls,
                isSelectMode = isSelectMode,
                palette = palette,
                onToggleSelect = { onToggleSelect(source) },
                onToggleEnabled = { enabled -> onToggleEnabled(source, enabled) },
                onEdit = { onEdit(source) },
                onShowMenu = { onShowMenu(source) }
            )
        }
    }
}

@Composable
private fun BookSourceItemRow(
    title: String,
    enabled: Boolean,
    hasExploreUrl: Boolean,
    enabledExplore: Boolean,
    hostText: String?,
    debugMessage: String,
    debugInProgress: Boolean,
    isSelected: Boolean,
    isSelectMode: Boolean,
    palette: AppManagementPalette,
    onToggleSelect: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onShowMenu: () -> Unit
) {
    AppManagementListRow(
        title = title,
        palette = palette,
        subtitle = debugMessage,
        selected = isSelected,
        selectionVisible = isSelectMode,
        animatedSelection = true,
        onToggleSelection = onToggleSelect,
        switchChecked = enabled,
        onSwitchChange = onToggleEnabled,
        onClick = {
            if (isSelectMode) onToggleSelect() else onEdit()
        },
        onLongClick = onToggleSelect,
        onEdit = onEdit,
        onMore = onShowMenu,
        moreIndicatorColor = if (hasExploreUrl) {
            if (enabledExplore) palette.settings.accent else palette.settings.danger
        } else {
            null
        },
        headerContent = {
            hostText?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = palette.settings.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                )
            }
        },
        trailingBeforeSwitch = {
            if (debugInProgress) {
                CircularProgressIndicator(
                    color = palette.settings.accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .size(18.dp)
                )
            }
        }
    )
}

private val FINAL_DEBUG_MESSAGE_REGEX = Regex("成功|失败")
