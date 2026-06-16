package io.legado.app.ui.config

import android.os.Bundle
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.config.AppearanceKit
import io.legado.app.help.config.AppearanceKitManager
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.appSettingRowDecoration
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch

class AppearanceKitActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding: ActivityThemeManageBinding by lazy {
        ActivityThemeManageBinding.inflate(layoutInflater)
    }

    private var kitsState by mutableStateOf<List<AppearanceKit>>(emptyList())
    private var currentKitIdState by mutableStateOf("")

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.appearance_kit_manage)
        binding.tabBar.visibility = View.GONE
        binding.tvSummary.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.btnAdd.visibility = View.GONE
        installComposeContent()
        refreshKits()
    }

    override fun onResume() {
        super.onResume()
        refreshKits()
    }

    private fun installComposeContent() {
        val composeView = ComposeView(this).apply {
            layoutParams = binding.recyclerView.layoutParams
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                AppearanceKitScreen(
                    kits = kitsState,
                    currentKitId = currentKitIdState,
                    onApply = ::applyKit,
                    onOpenTheme = { startActivity<ThemeManageActivity>() },
                    onOpenNavigation = { startActivity<NavigationBarManageActivity>() },
                    onOpenTopBar = { startActivity<TopBarManageActivity>() },
                    onOpenCover = { startActivity<CoverCollectionManageActivity>() }
                )
            }
        }
        binding.root.addView(composeView, binding.root.indexOfChild(binding.recyclerView))
    }

    private fun refreshKits() {
        lifecycleScope.launch {
            kitsState = AppearanceKitManager.builtinKits() + AppearanceKitManager.importedThemeKits()
            currentKitIdState = AppearanceKitManager.currentKitId()
        }
    }

    private fun applyKit(kit: AppearanceKit) {
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.apply(this@AppearanceKitActivity, kit)
            }.onSuccess {
                currentKitIdState = kit.id
                toastOnUi(R.string.success)
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }
}

@Composable
private fun AppearanceKitScreen(
    kits: List<AppearanceKit>,
    currentKitId: String,
    onApply: (AppearanceKit) -> Unit,
    onOpenTheme: () -> Unit,
    onOpenNavigation: () -> Unit,
    onOpenTopBar: () -> Unit,
    onOpenCover: () -> Unit
) {
    val palette = rememberAppSettingPalette()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item("kits") {
            KitSection(
                title = "界面套件",
                palette = palette,
                rows = kits,
                currentKitId = currentKitId,
                onApply = onApply
            )
        }
        item("advanced") {
            AdvancedSection(
                palette = palette,
                onOpenTheme = onOpenTheme,
                onOpenNavigation = onOpenNavigation,
                onOpenTopBar = onOpenTopBar,
                onOpenCover = onOpenCover
            )
        }
    }
}

@Composable
private fun KitSection(
    title: String,
    palette: AppSettingPalette,
    rows: List<AppearanceKit>,
    currentKitId: String,
    onApply: (AppearanceKit) -> Unit
) {
    val context = LocalContext.current
    val radiusPx = palette.panelRadiusPx
    val panelImage = UiCorner.panelImageDrawable(context, radiusPx)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .appSettingPanelBackground(
                normalColor = palette.row,
                panelImage = panelImage,
                borderColor = palette.border,
                radiusPx = radiusPx
            )
    ) {
        AppSettingSectionTitle(title = title, palette = palette)
        rows.forEachIndexed { index, kit ->
            ActionRow(
                title = kit.name,
                summary = kit.summary,
                trailing = if (kit.id == currentKitId) "已应用" else "应用",
                palette = palette,
                isLast = index == rows.lastIndex,
                onClick = { onApply(kit) }
            )
        }
    }
}

@Composable
private fun AdvancedSection(
    palette: AppSettingPalette,
    onOpenTheme: () -> Unit,
    onOpenNavigation: () -> Unit,
    onOpenTopBar: () -> Unit,
    onOpenCover: () -> Unit
) {
    val context = LocalContext.current
    val radiusPx = palette.panelRadiusPx
    val panelImage = UiCorner.panelImageDrawable(context, radiusPx)
    val rows = listOf(
        Triple("界面管理", "管理日间、夜间界面颜色、背景、圆角和透明度", onOpenTheme),
        Triple("底栏管理", "管理底栏图标、样式、侧栏和同步", onOpenNavigation),
        Triple("顶栏管理", "管理顶栏样式、背景和同步", onOpenTopBar),
        Triple("封面图集", "管理书籍封面图集资源", onOpenCover)
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .appSettingPanelBackground(
                normalColor = palette.row,
                panelImage = panelImage,
                borderColor = palette.border,
                radiusPx = radiusPx
            )
    ) {
        AppSettingSectionTitle(title = "高级管理", palette = palette)
        rows.forEachIndexed { index, row ->
            ActionRow(
                title = row.first,
                summary = row.second,
                trailing = "",
                palette = palette,
                isLast = index == rows.lastIndex,
                onClick = row.third
            )
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    summary: String,
    trailing: String,
    palette: AppSettingPalette,
    isLast: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 62.dp)
            .appSettingRowDecoration(
                pressed = pressed,
                pressedColor = palette.rowPressed,
                dividerColor = palette.divider,
                showDivider = !isLast,
                radiusPx = palette.panelRadiusPx,
                isFirst = false,
                isLast = isLast
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = palette.primaryText,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = summary,
                color = palette.secondaryText,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing.isNotBlank()) {
            Text(
                text = trailing,
                color = palette.accent,
                fontSize = 14.sp,
                maxLines = 1
            )
        }
    }
}
