package io.legado.app.ui.config

import android.os.Bundle
import android.net.Uri
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import io.legado.app.help.config.AppearanceKitType
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.AppSettingSectionTitle
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.appSettingRowDecoration
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.launch
import java.io.FileOutputStream

class AppearanceKitActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding: ActivityThemeManageBinding by lazy {
        ActivityThemeManageBinding.inflate(layoutInflater)
    }

    private var kitsState by mutableStateOf<List<AppearanceKit>>(emptyList())
    private var currentKitIdState by mutableStateOf("")

    private val importPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::importAppearanceKit)
    }

    private val exportPackage = registerForActivityResult(HandleFileContract()) {
        if (it.uri != null) {
            toastOnUi(R.string.export_success)
        }
    }

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
                    onEdit = ::editKit,
                    onDelete = ::confirmDeleteKit,
                    onImport = ::selectImportPackage,
                    onExport = ::exportCurrentKit,
                    onOpenSyncTasks = ::showSyncTasks,
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

    private fun confirmDeleteKit(kit: AppearanceKit) {
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = getString(R.string.appearance_kit_delete_confirm, kit.name),
            positiveText = getString(R.string.delete),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = { deleteKit(kit) }
        )
    }

    private fun editKit(kit: AppearanceKit) {
        if (kit.type != AppearanceKitType.IMPORTED_THEME) return
        startActivity<AppearanceKitEditActivity> {
            putExtra(AppearanceKitEditActivity.EXTRA_KIT_ID, kit.id)
        }
    }

    private fun deleteKit(kit: AppearanceKit) {
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.deleteImportedTheme(this@AppearanceKitActivity, kit)
            }.onSuccess { deleted ->
                toastOnUi(if (deleted) R.string.delete_success else R.string.error)
                refreshKits()
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun selectImportPackage() {
        importPackage.launch {
            mode = HandleFileContract.FILE
            title = getString(R.string.appearance_kit_import)
            allowExtensions = arrayOf("red", "zip")
        }
    }

    private fun importAppearanceKit(uri: Uri) {
        lifecycleScope.launch {
            runCatching {
                val file = externalFiles.getFile("appearanceKitImports", "import_${System.currentTimeMillis()}.zip")
                file.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                AppearanceKitManager.importPackage(file)
            }.onSuccess { result ->
                toastOnUi(getString(R.string.appearance_kit_imported, result.total))
                refreshKits()
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun exportCurrentKit() {
        lifecycleScope.launch {
            runCatching {
                AppearanceKitManager.exportCurrent(this@AppearanceKitActivity)
            }.onSuccess { file ->
                exportPackage.launch {
                    mode = HandleFileContract.EXPORT
                    showUploadUrl = false
                    fileData = HandleFileContract.FileData(
                        file.name.ifBlank { "appearance_kit.zip" },
                        file,
                        "application/zip"
                    )
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    private fun showSyncTasks() {
        showPackageSyncTaskDialog(
            setOf(
                WebDavTaskType.THEME_PACKAGE_UPLOAD,
                WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD,
                WebDavTaskType.NAVIGATION_BAR_PACKAGE_UPLOAD
            )
        )
    }
}

@Composable
private fun AppearanceKitScreen(
    kits: List<AppearanceKit>,
    currentKitId: String,
    onApply: (AppearanceKit) -> Unit,
    onEdit: (AppearanceKit) -> Unit,
    onDelete: (AppearanceKit) -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onOpenSyncTasks: () -> Unit,
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
                title = stringResourceCompat(R.string.appearance_kit_manage),
                palette = palette,
                rows = kits,
                currentKitId = currentKitId,
                onApply = onApply,
                onEdit = onEdit,
                onDelete = onDelete
            )
        }
        item("actions") {
            ActionSection(
                palette = palette,
                onImport = onImport,
                onExport = onExport,
                onOpenSyncTasks = onOpenSyncTasks
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
private fun ActionSection(
    palette: AppSettingPalette,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onOpenSyncTasks: () -> Unit
) {
    val rows = listOf(
        Triple(stringResourceCompat(R.string.appearance_kit_import), stringResourceCompat(R.string.appearance_kit_import_summary), onImport),
        Triple(stringResourceCompat(R.string.appearance_kit_export), stringResourceCompat(R.string.appearance_kit_export_summary), onExport),
        Triple(stringResourceCompat(R.string.package_sync_task_title), stringResourceCompat(R.string.appearance_kit_sync_summary), onOpenSyncTasks)
    )
    PanelRows(
        title = stringResourceCompat(R.string.appearance_kit_actions),
        palette = palette,
        rows = rows
    )
}

@Composable
private fun KitSection(
    title: String,
    palette: AppSettingPalette,
    rows: List<AppearanceKit>,
    currentKitId: String,
    onApply: (AppearanceKit) -> Unit,
    onEdit: (AppearanceKit) -> Unit,
    onDelete: (AppearanceKit) -> Unit
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
                secondaryTrailing = if (kit.type == AppearanceKitType.IMPORTED_THEME) stringResourceCompat(R.string.edit) else "",
                tertiaryTrailing = if (kit.type == AppearanceKitType.IMPORTED_THEME) stringResourceCompat(R.string.delete) else "",
                previewSeed = kit.id,
                palette = palette,
                isLast = index == rows.lastIndex,
                onClick = { onApply(kit) },
                onSecondaryClick = { onEdit(kit) },
                onTertiaryClick = { onDelete(kit) }
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
    val rows = listOf(
        Triple(stringResourceCompat(R.string.theme_manage_title), stringResourceCompat(R.string.theme_list_summary), onOpenTheme),
        Triple(stringResourceCompat(R.string.navigation_bar_manage), stringResourceCompat(R.string.navigation_bar_manage_summary), onOpenNavigation),
        Triple(stringResourceCompat(R.string.top_bar_manage), stringResourceCompat(R.string.top_bar_manage_summary), onOpenTopBar),
        Triple(stringResourceCompat(R.string.cover_collection_manage), stringResourceCompat(R.string.appearance_kit_cover_summary), onOpenCover)
    )
    PanelRows(
        title = stringResourceCompat(R.string.appearance_kit_advanced),
        palette = palette,
        rows = rows
    )
}

@Composable
private fun PanelRows(
    title: String,
    palette: AppSettingPalette,
    rows: List<Triple<String, String, () -> Unit>>
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
private fun stringResourceCompat(id: Int): String {
    return androidx.compose.ui.res.stringResource(id)
}

@Composable
private fun ActionRow(
    title: String,
    summary: String,
    trailing: String,
    secondaryTrailing: String = "",
    tertiaryTrailing: String = "",
    previewSeed: String? = null,
    palette: AppSettingPalette,
    isLast: Boolean,
    onClick: () -> Unit,
    onSecondaryClick: (() -> Unit)? = null,
    onTertiaryClick: (() -> Unit)? = null
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
        previewSeed?.let {
            KitPreview(seed = it, palette = palette)
            Spacer(modifier = Modifier.width(12.dp))
        }
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
        if (secondaryTrailing.isNotBlank() && onSecondaryClick != null) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = secondaryTrailing,
                color = palette.accent,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSecondaryClick
                )
            )
        }
        if (tertiaryTrailing.isNotBlank() && onTertiaryClick != null) {
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = tertiaryTrailing,
                color = palette.accent,
                fontSize = 14.sp,
                maxLines = 1,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTertiaryClick
                )
            )
        }
    }
}

@Composable
private fun KitPreview(seed: String, palette: AppSettingPalette) {
    val colors = remember(seed) {
        val hash = seed.hashCode()
        val first = Color(0xFF000000 or (hash and 0x00FFFFFF).toLong())
        val second = Color(0xFF000000 or ((hash * 31) and 0x00FFFFFF).toLong())
        listOf(first, second)
    }
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 42.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(colors))
            .padding(6.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.66f)
                .height(6.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(3.dp))
                .background(Color(palette.row).copy(alpha = 0.72f))
        )
    }
}
