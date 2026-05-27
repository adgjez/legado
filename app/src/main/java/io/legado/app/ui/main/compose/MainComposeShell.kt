package io.legado.app.ui.main.compose

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor

enum class MainComposeTab(
    val key: String,
    @StringRes val titleRes: Int,
    @DrawableRes val normalIconRes: Int,
    @DrawableRes val selectedIconRes: Int
) {
    Bookshelf("bookshelf", R.string.bookshelf, R.drawable.ic_bottom_books_e, R.drawable.ic_bottom_books_s),
    Discovery("discovery", R.string.discovery, R.drawable.ic_bottom_explore_e, R.drawable.ic_bottom_explore_s),
    Rss("rss", R.string.rss, R.drawable.ic_bottom_rss_feed_e, R.drawable.ic_bottom_rss_feed_s),
    ReadRecord("readRecord", R.string.side_nav_stats, R.drawable.ic_bottom_read_record_e, R.drawable.ic_bottom_read_record_s),
    My("my", R.string.my, R.drawable.ic_bottom_person_e, R.drawable.ic_bottom_person_s)
}

@Immutable
data class MainComposeUiState(
    val selectedTab: MainComposeTab = MainComposeTab.Bookshelf,
    val tabs: List<MainComposeTab> = MainComposeTab.entries,
    val bottomBarMode: String = "floating",
    val effectMode: String = "glass",
    val glassLevel: Float = 0.68f,
    val isNight: Boolean = false,
    val updateCount: Int = 0
)

@Immutable
data class MainComposeActions(
    val onSelectTab: (MainComposeTab) -> Unit,
    val onReselectTab: (MainComposeTab) -> Unit,
    val onSearch: () -> Unit,
    val onSearchLongClick: () -> Unit
)

@Composable
fun MainComposeRoute(
    state: MainComposeUiState,
    actions: MainComposeActions,
    modifier: Modifier = Modifier,
    pageContent: @Composable (MainComposeTab) -> Unit
) {
    val rootLayer = rememberGraphicsLayer()
    val rootBackdrop = rememberLayerBackdrop(rootLayer) {
        drawContent()
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(rootBackdrop)
        ) {
            pageContent(state.selectedTab)
        }
        if (state.bottomBarMode != "sidebar") {
            LiquidMainBottomBar(
                state = state,
                backdrop = rootBackdrop,
                actions = actions,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(
                        start = 20.dp,
                        end = 20.dp,
                        bottom = (LocalConfiguration.current.screenHeightDp.dp * 0.016f)
                    )
            )
        }
    }
}

@Composable
private fun LiquidMainBottomBar(
    state: MainComposeUiState,
    backdrop: Backdrop,
    actions: MainComposeActions,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val surfaceColor = remember(state.isNight, state.effectMode, state.glassLevel) {
        Color(context.bottomBackground)
    }
    val accent = remember(state.isNight) { Color(ThemeStore.accentColor(context)) }
    val textColor = remember(state.isNight) { Color(context.primaryTextColor) }
    val secondary = remember(state.isNight) { Color(context.secondaryTextColor) }
    val barShape = RoundedCornerShape(50)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MainBottomTabsGlass(
            state = state,
            backdrop = backdrop,
            surfaceColor = surfaceColor,
            accent = accent,
            textColor = textColor,
            secondary = secondary,
            shape = barShape,
            actions = actions,
            modifier = Modifier
                .weight(1f)
                .height(56.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        MainSearchGlassButton(
            state = state,
            backdrop = backdrop,
            accent = accent,
            textColor = textColor,
            onClick = actions.onSearch,
            onLongClick = actions.onSearchLongClick,
            modifier = Modifier.size(56.dp)
        )
    }
}

@Composable
private fun MainBottomTabsGlass(
    state: MainComposeUiState,
    backdrop: Backdrop,
    surfaceColor: Color,
    accent: Color,
    textColor: Color,
    secondary: Color,
    shape: Shape,
    actions: MainComposeActions,
    modifier: Modifier = Modifier
) {
    val tabsLayer = rememberGraphicsLayer()
    val tabsBackdrop = rememberLayerBackdrop(tabsLayer) { drawContent() }
    val selectedBackdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop)
    val selectedIndex = state.tabs.indexOf(state.selectedTab).coerceAtLeast(0)
    val itemWeight = 1f / state.tabs.size.coerceAtLeast(1)
    Box(
        modifier = modifier
            .shadow(10.dp, shape, clip = false)
            .clip(shape)
            .drawLiquidGlass(
                state = state,
                backdrop = backdrop,
                shape = shape,
                surfaceColor = surfaceColor,
                selected = false
            )
            .layerBackdrop(tabsBackdrop)
            .padding(horizontal = 6.dp, vertical = 6.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val itemWidth = maxWidth / state.tabs.size.coerceAtLeast(1)
            val targetOffset = animateDpAsState(
                targetValue = itemWidth * selectedIndex,
                label = "mainBottomSelection"
            )
            Box(
                modifier = Modifier
                    .offset(x = targetOffset.value)
                    .width(itemWidth)
                    .height(44.dp)
                    .align(Alignment.CenterStart)
                    .drawLiquidGlass(
                        state = state,
                        backdrop = selectedBackdrop,
                        shape = RoundedCornerShape(38.dp),
                        surfaceColor = accent,
                        selected = true
                    )
            )
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                state.tabs.forEach { tab ->
                    val selected = tab == state.selectedTab
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(36.dp))
                            .clickable {
                                if (selected) actions.onReselectTab(tab) else actions.onSelectTab(tab)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(if (selected) tab.selectedIconRes else tab.normalIconRes),
                            contentDescription = stringResource(tab.titleRes),
                            tint = if (selected) textColor else secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        if (tab == MainComposeTab.Bookshelf && state.updateCount > 0) {
                            Text(
                                text = state.updateCount.coerceAtMost(99).toString(),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(start = 22.dp)
                                    .background(accent, CircleShape)
                                    .padding(horizontal = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainSearchGlassButton(
    state: MainComposeUiState,
    backdrop: Backdrop,
    accent: Color,
    textColor: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .shadow(10.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .drawLiquidGlass(
                state = state,
                backdrop = backdrop,
                shape = CircleShape,
                surfaceColor = accent,
                selected = false
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_search),
            contentDescription = stringResource(R.string.search),
            tint = textColor,
            modifier = Modifier.size(24.dp)
        )
    }
}

private fun Modifier.drawLiquidGlass(
    state: MainComposeUiState,
    backdrop: Backdrop,
    shape: Shape,
    surfaceColor: Color,
    selected: Boolean
): Modifier {
    val alpha = if (selected) {
        0.16f + state.glassLevel.coerceIn(0f, 1f) * 0.12f
    } else {
        0.08f + state.glassLevel.coerceIn(0f, 1f) * 0.10f
    }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                vibrancy()
                blur(if (selected) 10.dp.toPx() else 8.dp.toPx())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && state.effectMode == "glass") {
                    lens(
                        refractionHeight = if (selected) 22.dp.toPx() else 28.dp.toPx(),
                        refractionAmount = if (selected) 24.dp.toPx() else 34.dp.toPx(),
                        depthEffect = true,
                        chromaticAberration = !state.isNight
                    )
                }
            }
        },
        onDrawSurface = {
            drawLiquidSurface(surfaceColor, alpha, selected, state.isNight)
        }
    )
}

private fun DrawScope.drawLiquidSurface(
    surfaceColor: Color,
    alpha: Float,
    selected: Boolean,
    night: Boolean
) {
    drawRect(surfaceColor.copy(alpha = alpha))
    drawRect(
        Brush.verticalGradient(
            colors = listOf(
                Color.White.copy(alpha = if (night) 0.06f else if (selected) 0.20f else 0.16f),
                Color.White.copy(alpha = if (night) 0.015f else 0.04f),
                Color.Transparent
            )
        )
    )
    drawRect(
        Brush.verticalGradient(
            colors = listOf(
                Color.Transparent,
                Color.Black.copy(alpha = if (night) 0.10f else 0.05f)
            )
        )
    )
}
