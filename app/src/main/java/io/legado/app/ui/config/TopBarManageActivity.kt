package io.legado.app.ui.config

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.image.ImageCropContract
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeNumberPickerDialog
import io.legado.app.ui.widget.compose.showComposeSingleChoiceDialog
import io.legado.app.utils.ImageCropHelper
import io.legado.app.utils.dismissDialogFragment
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TopBarManageActivity : BaseActivity<ActivityThemeManageBinding>(),
    ColorPickerDialogListener {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private var entriesState by mutableStateOf<List<TopBarConfig.Entry>>(emptyList())
    private var activeDirNameState by mutableStateOf(TopBarConfig.DEFAULT_DIR_NAME)
    private var isNightMode by mutableStateOf(false)
    private var summaryTextState by mutableStateOf("")
    private var editingEntry: TopBarConfig.Entry? = null
    private var pendingConfig: TopBarConfig.Config? = null
    private var pendingColorTarget = 0
    private var pendingWallpaperCropRequest: ImageCropHelper.Request? = null
    private val handledWebDavTasks = mutableSetOf<String>()
    private var loadVersion = 0
    private var cloudContainerId: String? = null
    private var containerMenuItem: MenuItem? = null
    private var containerMenuPopup: ModernActionPopup.Handle? = null
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    private val importPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::importPackage)
    }

    private val exportPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { toastOnUi(R.string.export_success) }
    }

    private val selectWallpaper = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::startWallpaperCrop)
    }

    private val cropWallpaper = registerForActivityResult(ImageCropContract()) { result ->
        pendingWallpaperCropRequest = null
        if (result == null) return@registerForActivityResult
        if (File(result).exists()) {
            pendingConfig?.wallpaperPath = result
            showEditDialog(editingEntry)
        } else {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.top_bar_manage)
        initComposeContent()
        loadPackages()
        observeWebDavTasks()
    }

    override fun onResume() {
        super.onResume()
        invalidateOptionsMenu()
    }

    override fun observeLiveBus() {
        observeEvent<String>(EventBus.RECREATE) {
            loadPackages()
        }
    }

    private fun initComposeContent() {
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        container.removeView(binding.recyclerView)
        container.removeView(binding.tabBar)
        container.removeView(binding.tvSummary)
        container.removeView(binding.btnAdd)
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setContent {
                TopBarManageScreen(
                    entries = entriesState,
                    activeDirName = activeDirNameState,
                    isNightMode = isNightMode,
                    summaryText = summaryTextState,
                    dateFormat = dateFormat,
                    onSwitchDayNight = { night ->
                        if (night != isNightMode) {
                            isNightMode = night
                            loadPackages()
                        }
                    },
                    onAdd = ::showAddDialog,
                    onApply = ::applyPackage,
                    onEdit = { entry -> showEditDialog(entry) },
                    onShowActions = ::showActions
                )
            }
        }
        container.addView(cv, index.coerceAtMost(container.childCount))
    }

    private fun showAddDialog() {
        showComposeActionListDialog(
            title = getString(R.string.theme_add),
            labels = listOf(
                getString(R.string.theme_manual_config),
                getString(R.string.theme_import_zip)
            )
        ) { index ->
            when (index) {
                0 -> showEditDialog(null)
                1 -> importPackage.launch {
                    mode = HandleFileContract.FILE
                    title = getString(R.string.theme_import_zip)
                    allowExtensions = arrayOf("zip")
                }
            }
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        containerMenuItem = menu.add(0, MENU_CONTAINER, 0, R.string.s3_bucket).apply {
            setIcon(R.drawable.ic_outline_cloud_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            setActionView(R.layout.view_action_button)
            actionView?.let { view ->
                view.contentDescription = title
                view.findViewById<ImageButton>(R.id.item)?.setImageDrawable(icon)
                view.setOnClickListener { showContainerSelector(view) }
            }
        }
        menu.add(0, MENU_SYNC_TASKS, 1, R.string.package_sync_task_menu).apply {
            setIcon(R.drawable.ic_history)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        updateContainerMenu()
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_CONTAINER -> {
                showContainerSelector(item.actionView)
                true
            }
            MENU_SYNC_TASKS -> {
                showTopBarSyncTasks()
                true
            }
            else -> super.onCompatOptionsItemSelected(item)
        }
    }

    private fun updateContainerMenu() {
        val containers = AppCloudStorage.listContainers().filter { it.enabled }
        val item = containerMenuItem ?: return
        if (AppCloudStorage.type != CloudStorageType.S3) {
            cloudContainerId = containers.firstOrNull()?.id
            item.isVisible = false
            return
        }
        cloudContainerId = AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
            ?: containers.firstOrNull()?.id
        item.isVisible = true
        val title = containers.firstOrNull { it.id == cloudContainerId }
            ?.let(AppCloudStorage::containerDisplayLabel)
            ?: getString(R.string.s3_bucket)
        item.title = title
        item.actionView?.contentDescription = title
    }

    private fun showContainerSelector(anchor: android.view.View? = null) {
        lifecycleScope.launch {
            val containers = withContext(Dispatchers.IO) {
                AppCloudStorage.listContainers().filter { it.enabled }
            }
            if (containers.isEmpty()) {
                toastOnUi(R.string.cloud_storage_config_required)
                return@launch
            }
            val selected = cloudContainerId
                ?: AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
            val popupAnchor = anchor
                ?: containerMenuItem?.actionView
                ?: binding.titleBar.toolbar
            val actions = containers.map { container ->
                ModernActionPopup.Action(AppCloudStorage.containerDisplayLabel(container)) {
                    if (container.id == selected) return@Action
                    AppCloudStorage.selectContainer(CLOUD_SCOPE, container.id)
                    cloudContainerId = container.id
                    updateContainerMenu()
                    loadPackages()
                }
            }
            containerMenuPopup = ModernActionPopup.show(popupAnchor, actions, containerMenuPopup)
        }
    }

    private fun loadPackages() {
        val version = ++loadVersion
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    TopBarConfig.loadEntries(
                        this@TopBarManageActivity,
                        isNightMode,
                        includeRemote = true,
                        cloudContainerId,
                        CLOUD_SCOPE
                    )
                }
            }.onSuccess {
                if (version != loadVersion || isFinishing || isDestroyed) return@onSuccess
                entriesState = it
                activeDirNameState = TopBarConfig.activeDirName(isNightMode)
                summaryTextState = getString(R.string.top_bar_manage_summary)
            }.onFailure {
                if (version != loadVersion || isFinishing || isDestroyed) return@onFailure
                summaryTextState = it.localizedMessage.orEmpty()
            }
        }
    }

    private fun showEditDialog(entry: TopBarConfig.Entry?) {
        val base = entry ?: TopBarConfig.Entry(
            TopBarConfig.defaultConfig(this, isNightMode).copy(name = nextPackageName()),
            TopBarConfig.Source.LOCAL,
            ""
        )
        if (entry != null && base.dirName == TopBarConfig.DEFAULT_DIR_NAME) {
            toastOnUi(R.string.navigation_bar_default_readonly)
            return
        }
        if (base.localDir == null && entry != null && base.source == TopBarConfig.Source.REMOTE) {
            lifecycleScope.launch {
                kotlin.runCatching {
                    withContext(Dispatchers.IO) {
                        TopBarConfig.download(base, cloudContainerId, CLOUD_SCOPE)
                    }
                }.onSuccess {
                    showEditDialog(it)
                }.onFailure {
                    toastOnUi(it.localizedMessage)
                }
            }
            return
        }
        dismissDialogFragment<TopBarEditDialog>()
        editingEntry = base
        pendingConfig = base.config.copy()
        showDialogFragment(
            TopBarEditDialog.create(
                config = pendingConfig!!,
                onNameChanged = { name ->
                    pendingConfig?.name = name
                },
                onStyleChanged = { style ->
                    pendingConfig?.style = style
                    if (style == TopBarConfig.STYLE_REGULAR) {
                        pendingConfig?.let { c ->
                            if (c.backgroundColor == null) {
                                c.backgroundColor = TopBarConfig.defaultBackgroundColor(c.isNightMode)
                            }
                            if (c.cornerScale == null) {
                                c.cornerScale = 1f
                            }
                            if (c.tagBarColor == null) {
                                c.tagBarColor = Color.WHITE
                            }
                            if (c.tagBarAlpha == 100) {
                                c.tagBarAlpha = 0
                            }
                        }
                    }
                },
                onShowStyleSelector = {
                    showComposeSingleChoiceDialog(
                        title = getString(R.string.top_bar_style),
                        labels = listOf(
                            getString(R.string.top_bar_style_default),
                            getString(R.string.top_bar_style_regular)
                        ),
                        selectedIndex = if (pendingConfig?.style == TopBarConfig.STYLE_REGULAR) 1 else 0
                    ) { index ->
                        val newStyle = when (index) {
                            1 -> TopBarConfig.STYLE_REGULAR
                            else -> TopBarConfig.STYLE_DEFAULT
                        }
                        pendingConfig?.style = newStyle
                        if (newStyle == TopBarConfig.STYLE_REGULAR) {
                            pendingConfig?.let { c ->
                                if (c.backgroundColor == null) {
                                    c.backgroundColor = TopBarConfig.defaultBackgroundColor(c.isNightMode)
                                }
                                if (c.cornerScale == null) {
                                    c.cornerScale = 1f
                                }
                                if (c.tagBarColor == null) {
                                    c.tagBarColor = Color.WHITE
                                }
                                if (c.tagBarAlpha == 100) {
                                    c.tagBarAlpha = 0
                                }
                            }
                        }
                        showEditDialog(editingEntry)
                    }
                },
                onShowCornerScalePicker = { current ->
                    showComposeNumberPickerDialog(
                        title = getString(R.string.top_bar_corner_scale),
                        value = ((current ?: 1f).coerceIn(0f, 3f) * 10).toInt(),
                        minValue = 0,
                        maxValue = 30,
                        isDecimalMode = true,
                        onValue = { value ->
                            pendingConfig?.cornerScale = (value / 10f).coerceIn(0f, 3f)
                            showEditDialog(editingEntry)
                        }
                    )
                },
                onShowColorPicker = { target, color ->
                    pendingColorTarget = target
                    ColorPickerDialog.newBuilder()
                        .setDialogId(target)
                        .setColor(color)
                        .setShowAlphaSlider(false)
                        .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                        .show(this@TopBarManageActivity)
                },
                onShowWallpaperSelector = {
                    showWallpaperSelector()
                },
                onShowWallpaperAlphaPicker = { current ->
                    showComposeNumberPickerDialog(
                        title = getString(R.string.top_bar_wallpaper_alpha),
                        value = current,
                        minValue = 0,
                        maxValue = 100,
                        onValue = { value ->
                            pendingConfig?.wallpaperAlpha = value.coerceIn(0, 100)
                            showEditDialog(editingEntry)
                        }
                    )
                },
                onShowFilterDefaultSelector = { current ->
                    showComposeSingleChoiceDialog(
                        title = getString(R.string.top_bar_filter_default),
                        labels = listOf(
                            getString(R.string.top_bar_filter_default_collapsed),
                            getString(R.string.top_bar_filter_default_expanded)
                        ),
                        selectedIndex = if (current) 1 else 0
                    ) { index ->
                        pendingConfig?.expandFiltersByDefault = index == 1
                        showEditDialog(editingEntry)
                    }
                },
                onShowTagBarAlphaPicker = { current ->
                    showComposeNumberPickerDialog(
                        title = getString(R.string.top_bar_tag_bar_alpha),
                        value = current,
                        minValue = 0,
                        maxValue = 100,
                        onValue = { value ->
                            pendingConfig?.tagBarAlpha = value.coerceIn(0, 100)
                            showEditDialog(editingEntry)
                        }
                    )
                },
                onShowTagSelectedAlphaPicker = { current ->
                    showComposeNumberPickerDialog(
                        title = getString(R.string.top_bar_tag_selected_alpha),
                        value = current,
                        minValue = 0,
                        maxValue = 100,
                        onValue = { value ->
                            pendingConfig?.tagSelectedAlpha = value.coerceIn(0, 100)
                            showEditDialog(editingEntry)
                        }
                    )
                },
                onSave = { name ->
                    pendingConfig?.name = name.trim()
                    saveEditingPackage()
                },
                onCancel = {}
            )
        )
    }

    private fun showWallpaperSelector() {
        val hasWallpaper = !pendingConfig?.wallpaperPath.isNullOrBlank()
        val options = buildList {
            add(getString(R.string.theme_image_select))
            if (hasWallpaper) add(getString(R.string.theme_image_delete))
        }
        showComposeActionListDialog(
            title = getString(R.string.top_bar_wallpaper),
            labels = options
        ) { index ->
            if (index == 0) {
                selectWallpaper.launch {
                    mode = HandleFileContract.IMAGE
                    title = getString(R.string.top_bar_wallpaper)
                }
            } else {
                pendingConfig?.wallpaperPath = null
                showEditDialog(editingEntry)
            }
        }
    }

    private fun showActions(entry: TopBarConfig.Entry) {
        val actions = buildList {
            add(Action.APPLY)
            if (entry.dirName != TopBarConfig.DEFAULT_DIR_NAME) {
                add(Action.EDIT)
                add(Action.EXPORT)
                if (entry.source != TopBarConfig.Source.REMOTE) add(Action.UPLOAD)
                if (entry.source != TopBarConfig.Source.LOCAL) add(Action.DOWNLOAD)
                if (entry.source != TopBarConfig.Source.REMOTE) add(Action.DELETE_LOCAL)
                if (entry.source != TopBarConfig.Source.LOCAL) add(Action.DELETE_REMOTE)
                if (entry.source == TopBarConfig.Source.BOTH) add(Action.DELETE_BOTH)
            }
        }
        showComposeActionListDialog(
            title = entry.config.name,
            labels = actions.map { getString(it.titleRes) },
            dangerIndices = actions.indices.filter {
                actions[it] in setOf(Action.DELETE_LOCAL, Action.DELETE_REMOTE, Action.DELETE_BOTH)
            }.toSet()
        ) { index ->
            when (actions[index]) {
                Action.APPLY -> applyPackage(entry)
                Action.EDIT -> showEditDialog(entry)
                Action.EXPORT -> exportPackage(entry)
                Action.UPLOAD -> enqueueUpload(entry)
                Action.DOWNLOAD -> runAction {
                    TopBarConfig.download(entry, cloudContainerId, CLOUD_SCOPE)
                }
                Action.DELETE_LOCAL -> confirmDelete(
                    entry,
                    getString(R.string.navigation_bar_delete_local_confirm)
                ) {
                    TopBarConfig.deleteLocal(entry)
                    postEvent(EventBus.TOP_BAR_CHANGED, entry.config.isNightMode)
                }
                Action.DELETE_REMOTE -> confirmDelete(
                    entry,
                    getString(R.string.navigation_bar_delete_remote_confirm)
                ) {
                    TopBarConfig.deleteRemote(entry, cloudContainerId, CLOUD_SCOPE)
                }
                Action.DELETE_BOTH -> confirmDelete(
                    entry,
                    getString(R.string.navigation_bar_delete_both_confirm)
                ) {
                    TopBarConfig.delete(entry, cloudContainerId, CLOUD_SCOPE)
                    postEvent(EventBus.TOP_BAR_CHANGED, entry.config.isNightMode)
                }
            }
        }
    }

    private fun enqueueUpload(entry: TopBarConfig.Entry) {
        val queued = enqueueUploadTask(entry)
        toastOnUi(
            if (queued) R.string.cache_manage_upload_queued
            else R.string.cache_manage_webdav_task_duplicate
        )
        if (queued) {
            showTopBarSyncTasks()
        }
    }

    private fun enqueueUploadIfNeeded(entry: TopBarConfig.Entry): Boolean {
        if (!AppConfig.syncThemePackages) return false
        return enqueueUploadTask(entry)
    }

    private fun enqueueUploadTask(entry: TopBarConfig.Entry): Boolean {
        return WebDavTaskManager.enqueueUpload(
            key = "top_bar_upload:${entry.config.isNightMode}:${entry.dirName}",
            name = entry.config.name,
            type = WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD,
            runningMessage = getString(R.string.navigation_bar_upload)
        ) {
            TopBarConfig.upload(entry, cloudContainerId, CLOUD_SCOPE)
        }
    }

    private fun observeWebDavTasks() {
        seedHandledWebDavTasks(WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD)
        lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                var shouldReload = false
                var failedMessage: String? = null
                states.values
                    .filter { it.type == WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD }
                    .filter {
                        it.status == WebDavTaskStatus.COMPLETED ||
                            it.status == WebDavTaskStatus.FAILED
                    }
                    .forEach { state ->
                        if (handledWebDavTasks.add(
                                webDavTaskHandleKey(state.key, state.status)
                            )
                        ) {
                            shouldReload = true
                            if (state.status == WebDavTaskStatus.FAILED) {
                                failedMessage = state.message
                            }
                        }
                    }
                if (shouldReload) {
                    loadPackages()
                    failedMessage?.let {
                        toastOnUi(getString(R.string.theme_sync_failed, it))
                    }
                }
            }
        }
    }

    private fun seedHandledWebDavTasks(type: WebDavTaskType) {
        WebDavTaskManager.states.value.values
            .filter { it.type == type }
            .filter {
                it.status == WebDavTaskStatus.COMPLETED ||
                    it.status == WebDavTaskStatus.FAILED
            }
            .forEach { handledWebDavTasks.add(webDavTaskHandleKey(it.key, it.status)) }
    }

    private fun webDavTaskHandleKey(key: String, status: WebDavTaskStatus): String {
        return "$key:$status"
    }

    private fun showTopBarSyncTasks() {
        showPackageSyncTaskDialog(setOf(WebDavTaskType.TOP_BAR_PACKAGE_UPLOAD))
    }

    private fun applyPackage(entry: TopBarConfig.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    if (entry.source == TopBarConfig.Source.REMOTE) {
                        TopBarConfig.download(entry, cloudContainerId, CLOUD_SCOPE)
                    } else {
                        entry
                    }
                }
            }.onSuccess {
                TopBarConfig.apply(it)
                postEvent(EventBus.TOP_BAR_CHANGED, it.config.isNightMode)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun confirmDelete(
        entry: TopBarConfig.Entry,
        message: String,
        block: suspend () -> Unit
    ) {
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = message,
            positiveText = getString(R.string.delete),
            negativeText = getString(R.string.cancel),
            dangerPositive = true,
            onPositive = { runAction(block) }
        )
    }

    private fun runAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { toastOnUi(R.string.success) }
                .onFailure { toastOnUi(it.localizedMessage) }
            loadPackages()
        }
    }

    private fun exportPackage(entry: TopBarConfig.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) { TopBarConfig.exportZip(entry) }
            }.onSuccess { zip ->
                exportPackage.launch {
                    mode = HandleFileContract.EXPORT
                    showUploadUrl = false
                    fileData = HandleFileContract.FileData(zip.name, zip, "application/zip")
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun importPackage(uri: Uri) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val file = externalFiles.getFile(
                    "topBarImports",
                    "import_${System.currentTimeMillis()}.zip"
                )
                file.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                withContext(Dispatchers.IO) { TopBarConfig.importZip(file) }
            }.onSuccess {
                toastOnUi(R.string.success)
                loadPackages()
                if (enqueueUploadIfNeeded(it)) {
                    showTopBarSyncTasks()
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun startWallpaperCrop(uri: Uri) {
        val metrics = resources.displayMetrics
        val request = ImageCropHelper.buildRequest(
            context = this,
            sourceUri = uri,
            requestCode = REQUEST_WALLPAPER,
            aspectWidth = metrics.widthPixels.coerceAtLeast(1),
            aspectHeight = (220 * metrics.density).toInt().coerceAtLeast(1),
            dirName = "topBarWallpapers",
            prefix = "top_bar",
            targetWidth = 1600
        )
        pendingWallpaperCropRequest = request
        cropWallpaper.launch(request.params)
    }

    private fun nextPackageName(): String {
        val base = getString(R.string.top_bar_custom_name)
        val usedNames = entriesState.map { it.config.name }.toSet()
        if (base !in usedNames) return base
        for (index in 2..999) {
            val name = "$base $index"
            if (name !in usedNames) return name
        }
        return "$base ${System.currentTimeMillis()}"
    }

    private fun saveEditingPackage() {
        val config = pendingConfig ?: return
        val oldEntry = editingEntry
        lifecycleScope.launch {
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    TopBarConfig.addOrUpdate(
                        config,
                        oldEntry.takeIf { it?.dirName != TopBarConfig.DEFAULT_DIR_NAME }
                    )
                }
            }.onSuccess {
                if (oldEntry?.dirName == TopBarConfig.DEFAULT_DIR_NAME ||
                    it.dirName == TopBarConfig.activeDirName(it.config.isNightMode)
                ) {
                    TopBarConfig.apply(it)
                    postEvent(EventBus.TOP_BAR_CHANGED, it.config.isNightMode)
                }
                toastOnUi(R.string.theme_saved_local)
                loadPackages()
                if (enqueueUploadIfNeeded(it)) {
                    showTopBarSyncTasks()
                }
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        val config = pendingConfig ?: return
        when (dialogId) {
            COLOR_BACKGROUND -> config.backgroundColor = color
            COLOR_TAG_BAR -> config.tagBarColor = color
            COLOR_TAG_SELECTED -> config.tagSelectedColor = color
        }
        showEditDialog(editingEntry)
    }

    override fun onDialogDismissed(dialogId: Int) {
        pendingColorTarget = 0
    }

    private enum class Action(val titleRes: Int) {
        APPLY(R.string.theme_apply),
        EDIT(R.string.edit),
        EXPORT(R.string.export),
        UPLOAD(R.string.navigation_bar_upload),
        DOWNLOAD(R.string.action_download),
        DELETE_LOCAL(R.string.theme_delete_local),
        DELETE_REMOTE(R.string.theme_delete_remote),
        DELETE_BOTH(R.string.theme_delete_both)
    }

    private companion object {
        private const val CLOUD_SCOPE = "theme"
        private const val MENU_CONTAINER = 0x5401
        private const val MENU_SYNC_TASKS = 0x5402
        const val COLOR_TAG_BAR = 5101
        const val COLOR_TAG_SELECTED = 5102
        const val REQUEST_WALLPAPER = 5103
        const val COLOR_BACKGROUND = 5104
    }
}

// ── Compose Screen ──────────────────────────────────────────────────────────

@Composable
private fun TopBarManageScreen(
    entries: List<TopBarConfig.Entry>,
    activeDirName: String,
    isNightMode: Boolean,
    summaryText: String,
    dateFormat: SimpleDateFormat,
    onSwitchDayNight: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onApply: (TopBarConfig.Entry) -> Unit,
    onEdit: (TopBarConfig.Entry) -> Unit,
    onShowActions: (TopBarConfig.Entry) -> Unit
) {
    val palette = rememberAppManagementPalette()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = palette.settings.bodyFontFamily)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = palette.settings.page,
            contentColor = palette.settings.primaryText
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
            ) {
                TopBarTabBar(
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
                    items(
                        entries,
                        key = { "${it.dirName}_${it.config.isNightMode}" }
                    ) { entry ->
                        TopBarEntryCard(
                            entry = entry,
                            isActive = entry.dirName == activeDirName,
                            dateFormat = dateFormat,
                            palette = palette,
                            onApply = { onApply(entry) },
                            onEdit = { onEdit(entry) },
                            onShowActions = { onShowActions(entry) }
                        )
                    }
                }
                LegadoMiuixActionButton(
                    text = stringResource(R.string.theme_add),
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
private fun TopBarTabBar(
    isNightMode: Boolean,
    palette: AppManagementPalette,
    onSwitch: (Boolean) -> Unit
) {
    AppManagementCard(
        palette = palette,
        modifier = Modifier
            .fillMaxWidth(),
        insidePadding = PaddingValues(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            TopBarTabButton(
                text = stringResource(R.string.theme_day),
                selected = !isNightMode,
                palette = palette,
                onClick = { onSwitch(false) },
                modifier = Modifier.weight(1f)
            )
            TopBarTabButton(
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
private fun TopBarTabButton(
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
        color = if (selected) {
            palette.settings.accent.copy(alpha = 0.14f)
        } else {
            androidx.compose.ui.graphics.Color.Transparent
        },
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

@Composable
private fun TopBarEntryCard(
    entry: TopBarConfig.Entry,
    isActive: Boolean,
    dateFormat: SimpleDateFormat,
    palette: AppManagementPalette,
    onApply: () -> Unit,
    onEdit: () -> Unit,
    onShowActions: () -> Unit
) {
    AppManagementCard(
        palette = palette,
        modifier = Modifier
            .fillMaxWidth(),
        onClick = onShowActions,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = entry.config.name,
                color = palette.settings.primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = buildString {
                    append(topBarStyleLabel(entry.config.style))
                    append(" · ")
                    append(stringResource(R.string.top_bar_tag_bar_alpha))
                    append(" ")
                    append(entry.config.tagBarAlpha)
                    append("%")
                    if (entry.config.updatedAt > 0) {
                        append(" · ")
                        append(
                            dateFormat.format(
                                Date(maxOf(entry.config.updatedAt, entry.remoteUpdatedAt))
                            )
                        )
                    }
                },
                color = palette.settings.secondaryText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LegadoMiuixActionButton(
                    text = stringResource(
                        if (isActive) R.string.theme_applied_state
                        else R.string.theme_apply
                    ),
                    palette = palette.miuix,
                    onClick = onApply,
                    cornerRadius = palette.miuix.actionRadius,
                    minWidth = 56.dp,
                    minHeight = 34.dp,
                    insidePadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                )
                if (entry.dirName != TopBarConfig.DEFAULT_DIR_NAME) {
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.edit),
                        palette = palette.miuix,
                        onClick = onEdit,
                        cornerRadius = palette.miuix.actionRadius,
                        minWidth = 56.dp,
                        minHeight = 34.dp,
                        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
                LegadoMiuixActionButton(
                    text = stringResource(R.string.more),
                    palette = palette.miuix,
                    onClick = onShowActions,
                    cornerRadius = palette.miuix.actionRadius,
                    minWidth = 56.dp,
                    minHeight = 34.dp,
                    insidePadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun topBarStyleLabel(style: String): String {
    return stringResource(
        when (style) {
            TopBarConfig.STYLE_REGULAR -> R.string.top_bar_style_regular
            else -> R.string.top_bar_style_default
        }
    )
}
