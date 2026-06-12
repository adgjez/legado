package io.legado.app.ui.widget.compose

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R

data class AppManagementAction(
    val text: String,
    @param:DrawableRes val iconRes: Int? = null,
    val primary: Boolean = false,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

@Composable
fun AppManagementScaffold(
    title: String,
    selectedCount: Int,
    totalCount: Int,
    modifier: Modifier = Modifier,
    palette: AppManagementPalette = rememberAppManagementPalette(),
    searchQuery: String? = null,
    searchHint: String? = null,
    onSearchChange: ((String) -> Unit)? = null,
    topActions: List<AppManagementAction> = emptyList(),
    bottomActions: List<AppManagementAction> = emptyList(),
    onBack: (() -> Unit)? = null,
    onSelectAll: (() -> Unit)? = null,
    onInvertSelection: (() -> Unit)? = null,
    content: @Composable (AppManagementPalette) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Transparent)
    ) {
        AppManagementTopBar(
            title = title,
            palette = palette,
            searchQuery = searchQuery,
            searchHint = searchHint,
            onSearchChange = onSearchChange,
            actions = topActions,
            onBack = onBack
        )
        Box(modifier = Modifier.weight(1f)) {
            content(palette)
        }
        AppManagementSelectionBottomBar(
            selectedCount = selectedCount,
            totalCount = totalCount,
            palette = palette,
            actions = bottomActions,
            onSelectAll = onSelectAll,
            onInvertSelection = onInvertSelection
        )
    }
}

@Composable
private fun AppManagementTopBar(
    title: String,
    palette: AppManagementPalette,
    searchQuery: String?,
    searchHint: String?,
    onSearchChange: ((String) -> Unit)?,
    actions: List<AppManagementAction>,
    onBack: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .appSettingPanelBackground(
                normalColor = palette.settings.row,
                panelImage = null,
                borderColor = palette.settings.border,
                radiusPx = palette.settings.panelRadiusPx
            )
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            onBack?.let {
                AppManagementIconAction(
                    iconRes = R.drawable.ic_arrow_back,
                    contentDescription = null,
                    tint = palette.settings.primaryText,
                    onClick = it
                )
            }
            Text(
                text = title,
                color = palette.settings.primaryText,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = palette.settings.titleFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            actions.forEach { action ->
                AppManagementTopAction(action = action, palette = palette)
            }
        }
        if (onSearchChange != null) {
            AppManagementSearchField(
                query = searchQuery.orEmpty(),
                hint = searchHint.orEmpty(),
                palette = palette,
                onQueryChange = onSearchChange,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun AppManagementTopAction(
    action: AppManagementAction,
    palette: AppManagementPalette
) {
    AppManagementIconAction(
        iconRes = action.iconRes ?: R.drawable.ic_more_vert,
        contentDescription = action.text,
        tint = if (action.danger) palette.settings.danger else palette.settings.primaryText,
        onClick = action.onClick
    )
}

@Composable
private fun AppManagementSearchField(
    query: String,
    hint: String,
    palette: AppManagementPalette,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LegadoMiuixCard(
        modifier = modifier.fillMaxWidth(),
        color = palette.settings.primaryText.copy(alpha = 0.06f),
        contentColor = palette.settings.primaryText,
        cornerRadius = palette.miuix.actionRadius ?: 12.dp,
        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_search),
                contentDescription = null,
                tint = palette.settings.secondaryText,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = palette.settings.primaryText,
                    fontSize = 14.sp,
                    fontFamily = palette.settings.bodyFontFamily
                ),
                cursorBrush = SolidColor(palette.settings.accent),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box {
                        if (query.isBlank()) {
                            Text(
                                text = hint,
                                color = palette.settings.secondaryText,
                                fontSize = 14.sp,
                                fontFamily = palette.settings.bodyFontFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (query.isNotEmpty()) {
                AppManagementIconAction(
                    iconRes = R.drawable.ic_baseline_close,
                    contentDescription = null,
                    tint = palette.settings.secondaryText,
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun AppManagementSelectionBottomBar(
    selectedCount: Int,
    totalCount: Int,
    palette: AppManagementPalette,
    actions: List<AppManagementAction>,
    onSelectAll: (() -> Unit)?,
    onInvertSelection: (() -> Unit)?
) {
    AnimatedVisibility(visible = selectedCount > 0) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .appSettingPanelBackground(
                    normalColor = palette.settings.row,
                    panelImage = null,
                    borderColor = palette.settings.border,
                    radiusPx = palette.settings.panelRadiusPx
                )
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$selectedCount / $totalCount",
                    color = palette.settings.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = palette.settings.bodyFontFamily,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                onSelectAll?.let {
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.select_all),
                        palette = palette.miuix,
                        onClick = it,
                        minWidth = 58.dp,
                        minHeight = 34.dp,
                        insidePadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
                onInvertSelection?.let {
                    Spacer(modifier = Modifier.width(8.dp))
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.revert_selection),
                        palette = palette.miuix,
                        onClick = it,
                        minWidth = 58.dp,
                        minHeight = 34.dp,
                        insidePadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                    )
                }
            }
            if (actions.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    actions.forEach { action ->
                        LegadoMiuixActionButton(
                            text = action.text,
                            palette = palette.miuix,
                            onClick = action.onClick,
                            primary = action.primary,
                            danger = action.danger,
                            minWidth = 72.dp,
                            minHeight = 36.dp,
                            insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
