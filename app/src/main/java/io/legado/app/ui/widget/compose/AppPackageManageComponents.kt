package io.legado.app.ui.widget.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R

@Composable
fun AppPackageManageScreen(
    isNightMode: Boolean,
    summaryText: String,
    addText: String,
    onSwitchDayNight: (Boolean) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
    listContent: LazyListScope.(AppManagementPalette) -> Unit
) {
    val palette = rememberAppManagementPalette()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = palette.settings.bodyFontFamily)
    ) {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = palette.settings.page,
            contentColor = palette.settings.primaryText
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                AppPackageManageTabs(
                    isNightMode = isNightMode,
                    palette = palette,
                    onSwitch = onSwitchDayNight
                )
                Text(
                    text = summaryText,
                    color = palette.settings.secondaryText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 10.dp, end = 16.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listContent(palette)
                }
                LegadoMiuixActionButton(
                    text = addText,
                    palette = palette.miuix,
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    primary = false,
                    cornerRadius = palette.miuix.actionRadius,
                    minHeight = 46.dp
                )
            }
        }
    }
}

@Composable
fun AppPackageManageItemCard(
    title: String,
    info: String,
    isActive: Boolean,
    canEdit: Boolean,
    applyText: String,
    editText: String,
    moreActions: List<AppManagementMenuAction>,
    palette: AppManagementPalette,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null
) {
    AppManagementCard(
        palette = palette,
        modifier = modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leadingContent?.let {
                it()
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    color = palette.settings.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = info,
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegadoMiuixActionButton(
                        text = applyText,
                        palette = palette.miuix,
                        onClick = onApply,
                        cornerRadius = palette.miuix.actionRadius,
                        minWidth = 56.dp,
                        minHeight = 34.dp,
                        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    )
                    if (canEdit) {
                        LegadoMiuixActionButton(
                            text = editText,
                            palette = palette.miuix,
                            onClick = onEdit,
                            cornerRadius = palette.miuix.actionRadius,
                            minWidth = 56.dp,
                            minHeight = 34.dp,
                            insidePadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                    AppManagementMoreActionButton(
                        actionsProvider = { moreActions },
                        palette = palette,
                        contentDescription = stringResource(R.string.more)
                    )
                }
            }
        }
    }
}

@Composable
private fun AppPackageManageTabs(
    isNightMode: Boolean,
    palette: AppManagementPalette,
    onSwitch: (Boolean) -> Unit
) {
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            AppPackageManageTabButton(
                text = stringResource(R.string.theme_day),
                selected = !isNightMode,
                palette = palette,
                onClick = { onSwitch(false) },
                modifier = Modifier.weight(1f)
            )
            AppPackageManageTabButton(
                text = stringResource(R.string.theme_night),
                selected = isNightMode,
                palette = palette,
                onClick = { onSwitch(true) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AppPackageManageTabButton(
    text: String,
    selected: Boolean,
    palette: AppManagementPalette,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(34.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(palette.miuix.actionRadius ?: 12.dp),
        color = if (selected) palette.settings.accent.copy(alpha = 0.14f) else Color.Transparent,
        contentColor = if (selected) palette.settings.accent else palette.settings.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = if (selected) palette.settings.accent else palette.settings.primaryText,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
