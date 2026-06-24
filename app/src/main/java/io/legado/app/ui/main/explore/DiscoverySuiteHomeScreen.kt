package io.legado.app.ui.main.explore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.help.config.DiscoverySuite
import io.legado.app.help.config.DiscoveryWidgetConfig
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig

@Composable
fun DiscoverySuiteHomeScreen(
    suite: DiscoverySuite?,
    topPaddingPx: Int,
    onEditSuite: () -> Unit,
    modifier: Modifier = Modifier
) {
    val renderConfig = rememberBookshelfListRenderConfig()
    val palette = renderConfig.palette
    val context = LocalContext.current
    val topPadding = with(LocalDensity.current) { topPaddingPx.toDp() }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(ContextCompat.getColor(context, R.color.background_menu)))
            .padding(top = topPadding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = 18.dp,
            end = 16.dp,
            bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "suite_header") {
            DiscoverySuiteHeaderCard(
                suite = suite,
                onEditSuite = onEditSuite
            )
        }
        if (suite?.widgets.isNullOrEmpty()) {
            item(key = "suite_empty") {
                DiscoverySuiteEmptyCard(
                    hasSuite = suite != null,
                    onEditSuite = onEditSuite
                )
            }
        } else {
            items(
                items = suite!!.widgets,
                key = { it.id }
            ) { widget ->
                DiscoveryWidgetPlaceholder(widget = widget)
            }
        }
    }
}

@Composable
private fun DiscoverySuiteHeaderCard(
    suite: DiscoverySuite?,
    onEditSuite: () -> Unit
) {
    val palette = rememberBookshelfListRenderConfig().palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(palette.panelRadius))
            .background(Color(palette.rowColor))
            .clickable(onClick = onEditSuite)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = suite?.displayName ?: "默认发现",
                color = palette.primaryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = palette.titleFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = suite?.let { "${it.widgets.size} 个控件 · 长按套件可设置别名" } ?: "暂无套件 · 从编辑套件开始配置",
                color = palette.secondaryText,
                fontSize = 13.sp,
                fontFamily = palette.bodyFontFamily
            )
        }
        SuiteActionChip(text = "编辑")
    }
}

@Composable
private fun DiscoverySuiteEmptyCard(
    hasSuite: Boolean,
    onEditSuite: () -> Unit
) {
    val palette = rememberBookshelfListRenderConfig().palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(palette.panelRadius))
            .background(Color(palette.rowColor))
            .padding(horizontal = 20.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(palette.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+",
                color = palette.accent,
                fontSize = 30.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = if (hasSuite) "暂无套件控件" else "暂无发现套件",
            color = palette.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.titleFontFamily
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (hasSuite) {
                "先添加控件，后续可选择书源 tag 作为内容来源"
            } else {
                "新建套件后，发现页会按套件渲染控件流"
            },
            color = palette.secondaryText,
            fontSize = 13.sp,
            fontFamily = palette.bodyFontFamily
        )
        Spacer(modifier = Modifier.height(18.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(palette.accent)
                .clickable(onClick = onEditSuite)
                .padding(horizontal = 34.dp, vertical = 11.dp)
        ) {
            Text(
                text = if (hasSuite) "添加控件" else "编辑套件",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = palette.titleFontFamily
            )
        }
    }
}

@Composable
private fun DiscoveryWidgetPlaceholder(widget: DiscoveryWidgetConfig) {
    val palette = rememberBookshelfListRenderConfig().palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(palette.panelRadius))
            .background(Color(palette.rowColor))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(palette.accent.copy(alpha = 0.10f))
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp)
        ) {
            Text(
                text = widget.title.ifBlank { widgetTypeLabel(widget.type) },
                color = palette.primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = palette.titleFontFamily
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = widget.sourceTags.takeIf { it.isNotEmpty() }?.joinToString("、")
                    ?: "暂未选择书源 tag",
                color = palette.secondaryText,
                fontSize = 13.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        SuiteActionChip(text = "配置")
    }
}

@Composable
private fun SuiteActionChip(text: String) {
    val palette = rememberBookshelfListRenderConfig().palette
    Text(
        text = text,
        color = palette.accent,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = palette.titleFontFamily,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(palette.accent.copy(alpha = 0.12f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

private fun widgetTypeLabel(type: String): String {
    return when (type) {
        DiscoveryWidgetConfig.TYPE_BOOK_GRID -> "三列网格"
        DiscoveryWidgetConfig.TYPE_RANK_CARD -> "榜单卡片"
        DiscoveryWidgetConfig.TYPE_TAG_CHIPS -> "分类标签"
        else -> "横向推荐"
    }
}
