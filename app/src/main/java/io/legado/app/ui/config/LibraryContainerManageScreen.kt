package io.legado.app.ui.config

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.book.library.LibraryContainerConfig
import io.legado.app.help.book.library.LibraryContainerManager
import io.legado.app.lib.theme.backgroundColor
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette

@Composable
internal fun LibraryContainerManageScreen(
    containers: List<LibraryContainerConfig>,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onItemClick: (LibraryContainerConfig) -> Unit,
    onMoreClick: (LibraryContainerConfig) -> Unit
) {
    val context = LocalContext.current
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(context.backgroundColor),
            contentColor = style.primaryText
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                LibraryContainerTopBar(onBack = onBack)
                Text(
                    text = "书库容器只用于同步阅读章节缓存，不参与备份、主题、气泡或缓存包同步。阅读时会先读取目录索引，只有命中缓存章节才请求正文。",
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 6.dp, end = 16.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(containers, key = { it.id }) { item ->
                        LibraryContainerCard(
                            item = item,
                            isDefault = LibraryContainerManager.selectedId() == item.id,
                            onClick = { onItemClick(item) },
                            onMore = { onMoreClick(item) }
                        )
                    }
                }
                LegadoMiuixActionButton(
                    text = "添加书库容器",
                    palette = palette,
                    onClick = onAdd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    primary = true,
                    cornerRadius = style.actionRadius,
                    minHeight = 46.dp
                )
            }
        }
    }
}

@Composable
private fun LibraryContainerTopBar(onBack: () -> Unit) {
    val style = rememberAppDialogStyle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .size(42.dp)
                .clickable(onClick = onBack),
            shape = RoundedCornerShape(style.actionRadius),
            color = Color.Transparent,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = null,
                    tint = style.primaryText,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            text = "书库容器",
            color = style.primaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = style.titleFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(42.dp))
    }
}

@Composable
private fun LibraryContainerCard(
    item: LibraryContainerConfig,
    isDefault: Boolean,
    onClick: () -> Unit,
    onMore: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val container = item.container
    val displayName = LibraryContainerManager.displayLabel(item)
    val capacityMb = container.capacityMb.coerceAtLeast(0)
    val usedBytes = container.usedBytes.coerceAtLeast(0)
    val capacityText = if (capacityMb > 0) {
        val capacityBytes = LibraryContainerManageActivity.mbToBytes(capacityMb)
        "容量：${LibraryContainerManageActivity.formatBytes(capacityBytes)} / 已用：${LibraryContainerManageActivity.formatBytes(usedBytes)} / 剩余：${LibraryContainerManageActivity.formatBytes((capacityBytes - usedBytes).coerceAtLeast(0))} / 已满：${if (container.isFull) "是" else "否"}"
    } else {
        "已用：${LibraryContainerManageActivity.formatBytes(usedBytes)}（不限容量）"
    }
    val minUpload = if (item.minUploadChars > 0) "最少${item.minUploadChars}字" else "不过滤短章"
    val dailyLimit = if (item.dailyUploadLimit > 0) "每日${item.dailyUploadLimit}章" else "每日不限"
    val lockState = if (item.lockedImported) " · 加密导入" else ""
    val stateText = "状态：${if (container.enabled) "启用" else "禁用"}$lockState · 书源优先 · ${item.sourceUrls.size} 个书源 · $minUpload · $dailyLimit"

    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.panelRadius,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    color = style.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(5.dp))
                Text(
                    text = "${container.bucket}/${container.prefix.trim('/')}",
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = capacityText,
                    color = style.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stateText,
                    color = if (container.enabled) palette.accent else style.secondaryText,
                    fontSize = 12.sp,
                    fontWeight = if (container.enabled) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (isDefault) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "✓",
                    color = palette.accent,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Surface(
                modifier = Modifier
                    .size(40.dp)
                    .clickable(onClick = onMore),
                shape = RoundedCornerShape(style.actionRadius),
                color = palette.surfaceVariant,
                contentColor = style.primaryText,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = "更多",
                        tint = style.primaryText,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
    }
}
