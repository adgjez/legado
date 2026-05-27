package io.legado.app.ui.main.compose.my

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.utils.ColorUtils

@Composable
fun MyScreen(
    state: MyPageUiState,
    modifier: Modifier = Modifier,
    actions: MyPageActions = MyPageActions(),
    contentPadding: PaddingValues = PaddingValues(bottom = 92.dp)
) {
    val style = myComposeStyle()
    val visibleSections = remember(state.sections, state.searchQuery) {
        state.sections.mapNotNull { section ->
            val items = section.items.filter { it.matches(state.searchQuery) }
            if (items.isEmpty()) null else section.copy(items = items)
        }
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(style.colors.pageBackground)
            .statusBarsPadding(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "header") {
            MyHeader(state = state, style = style, actions = actions)
        }
        if (visibleSections.isEmpty()) {
            item(key = "empty") {
                EmptySettingsCard(text = state.emptyHint, style = style)
            }
        } else {
            visibleSections.forEach { section ->
                item(key = "section-${section.id}") {
                    SettingSection(section = section, style = style, onClick = actions.onItemClick)
                }
            }
        }
    }
}

@Composable
private fun MyHeader(
    state: MyPageUiState,
    style: MyComposeStyle,
    actions: MyPageActions
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.title,
                color = style.colors.primaryText,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "帮助",
                color = style.colors.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = actions.onHelpClick)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
        SearchBox(
            query = state.searchQuery,
            style = style,
            onQueryChange = actions.onSearchQueryChange,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun SearchBox(
    query: String,
    style: MyComposeStyle,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = style.colors.inputSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, style.colors.stroke)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                tint = style.colors.secondaryText,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = style.colors.primaryText,
                    fontSize = 14.sp
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {}),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isBlank()) {
                            Text(
                                text = "搜索设置",
                                color = style.colors.secondaryText,
                                fontSize = 14.sp,
                                maxLines = 1
                            )
                        }
                        innerTextField()
                    }
                }
            )
            if (query.isNotBlank()) {
                Icon(
                    painter = painterResource(R.drawable.ic_close_x),
                    contentDescription = "清空搜索",
                    tint = style.colors.secondaryText,
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .clickable { onQueryChange("") }
                        .padding(4.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingSection(
    section: MySettingSectionUi,
    style: MyComposeStyle,
    onClick: (MySettingItemUi) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = section.title,
            color = style.colors.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(style.metrics.cardRadius),
            color = style.colors.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, style.colors.stroke)
        ) {
            Column {
                section.items.forEachIndexed { index, item ->
                    SettingRow(item = item, style = style, onClick = { onClick(item) })
                    if (index != section.items.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .padding(start = 58.dp)
                                .background(style.colors.stroke)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    item: MySettingItemUi,
    style: MyComposeStyle,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(style.colors.accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = item.title.take(1),
                color = style.colors.accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title,
                    color = style.colors.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (item.badge.isNotBlank()) {
                    Text(
                        text = item.badge,
                        color = style.colors.accent,
                        fontSize = 11.sp,
                        maxLines = 1,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(style.colors.accent.copy(alpha = 0.10f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            if (item.summary.isNotBlank()) {
                Text(
                    text = item.summary,
                    color = style.colors.secondaryText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        Icon(
            painter = painterResource(R.drawable.ic_arrow_right),
            contentDescription = null,
            tint = style.colors.secondaryText,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun EmptySettingsCard(text: String, style: MyComposeStyle) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(style.metrics.cardRadius),
        color = style.colors.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, style.colors.stroke)
    ) {
        Text(
            text = text,
            color = style.colors.secondaryText,
            fontSize = 14.sp,
            modifier = Modifier.padding(22.dp)
        )
    }
}

@Immutable
private data class MyColors(
    val accent: Color,
    val pageBackground: Color,
    val surface: Color,
    val inputSurface: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val stroke: Color
)

@Immutable
private data class MyMetrics(
    val cardRadius: Dp
)

@Immutable
private data class MyComposeStyle(
    val colors: MyColors,
    val metrics: MyMetrics
)

@Stable
@Composable
private fun myComposeStyle(): MyComposeStyle {
    val context = LocalContext.current
    val night = AppConfig.isNightTheme
    val accent = context.accentColor
    val background = ContextCompat.getColor(context, if (night) R.color.md_grey_900 else R.color.white)
    val surface = if (night) 0xff202329.toInt() else 0xffffffff.toInt()
    val inputSurface = if (night) 0xff282c32.toInt() else 0xfff6f8fa.toInt()
    val primaryText = if (night) 0xfff2f3f5.toInt() else 0xff202124.toInt()
    val secondaryText = if (night) 0xffaeb4bc.toInt() else 0xff66707a.toInt()
    return MyComposeStyle(
        colors = MyColors(
            accent = Color(accent),
            pageBackground = Color(background),
            surface = Color(surface),
            inputSurface = Color(inputSurface),
            primaryText = Color(primaryText),
            secondaryText = Color(secondaryText),
            stroke = Color(ColorUtils.adjustAlpha(if (night) 0xffffffff.toInt() else 0xff000000.toInt(), 0.10f))
        ),
        metrics = MyMetrics(cardRadius = 8.dp)
    )
}
