package io.legado.app.ui.config

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.DialogImageBlurringBinding
import io.legado.app.databinding.DialogThemePackageEditBinding
import io.legado.app.databinding.ItemThemePackageOptionBinding
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.dialogs.AndroidAlertBuilder
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.loadUiTypeface
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.font.FontSelectDialog
import io.legado.app.ui.image.ImageCropContract
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppPackageManageItemCard
import io.legado.app.ui.widget.compose.AppPackageManageScreen
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.ui.widget.seekbar.SeekBarChangeListener
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.ImageCropHelper
import io.legado.app.utils.applyTint
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.setLayout
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ThemeManageActivity : BaseActivity<ActivityThemeManageBinding>(),
    ColorPickerDialogListener,
    FontSelectDialog.CallBack {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private var entriesState by mutableStateOf<List<ThemePackageManager.Entry>>(emptyList())
    private var summaryTextState by mutableStateOf("")
    private var isNightTheme by mutableStateOf(false)
    private var editDialogBinding: DialogThemePackageEditBinding? = null
    private var editingEntry: ThemePackageManager.Entry? = null
    private var pendingBlur = 0
    private var pendingMainBackgroundPath: String? = null
    private var pendingBookInfoBackgroundPath: String? = null
    private var pendingPanelBackgroundPath: String? = null
    private var pendingPanelBackgroundScaleType = ThemeConfig.PANEL_BG_CROP
    private var pendingPanelBorderColor: String? = null
    private var pendingPanelBorderAlpha = 100
    private var pendingUiCornerScale = 1f
    private var pendingUiLayoutAlpha = 100
    private var pendingDialogAlpha = 100
    private var pendingFontScale = 0
    private var pendingUiCornerSearchFollow = false
    private var pendingUiCornerReplyFollow = false
    private var pendingUiFontPath: String? = null
    private var pendingTitleFontPath: String? = null
    private var pendingFontTarget = FontTarget.UI
    private var loadVersion = 0
    private var cloudContainerId: String? = null
    private var containerMenuItem: MenuItem? = null
    private var containerMenuPopup: ModernActionPopup.Handle? = null
    private val pendingRemoteSyncTasks = linkedMapOf<String, RemoteSyncTask>()
    @Volatile
    private var syncingRemoteTasks = false
    private var appliedDayThemeOverride by mutableStateOf<String?>(null)
    private var appliedNightThemeOverride by mutableStateOf<String?>(null)
    private var pendingImageCropRequest: ImageCropHelper.Request? = null
    private val handledWebDavTasks = mutableSetOf<String>()
    private val selectImage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            startImageCrop(uri, it.requestCode)
        }
    }
    private val cropImage = registerForActivityResult(ImageCropContract()) { result ->
        val request = pendingImageCropRequest ?: return@registerForActivityResult
        pendingImageCropRequest = null
        if (result == null) {
            return@registerForActivityResult
        }
        if (java.io.File(result).exists()) {
            when (request.requestCode) {
                requestMainBackground -> {
                    pendingMainBackgroundPath = result
                    editDialogBinding?.let { binding -> updateImageRow(binding.rowMainBackground, ThemeImageTarget.MAIN) }
                }
                requestBookInfoBackground -> {
                    pendingBookInfoBackgroundPath = result
                    editDialogBinding?.let { binding -> updateImageRow(binding.rowBookInfoBackground, ThemeImageTarget.BOOK_INFO) }
                }
                requestPanelBackground -> {
                    pendingPanelBackgroundPath = result
                    editDialogBinding?.let { binding -> updateImageRow(binding.rowPanelBackground, ThemeImageTarget.PANEL) }
                }
            }
        } else {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
        }
    }
    private val importThemePackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            importThemeZip(uri)
        }
    }
    private val exportThemePackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let {
            toastOnUi(getString(R.string.theme_zip_exported))
        }
    }
    private val dateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        restorePendingRemoteSyncTasks()
        initView()
        lifecycleScope.launch {
            kotlin.runCatching {
                ThemePackageManager.ensureLocalAppliedTheme(this@ThemeManageActivity, false)
                ThemePackageManager.ensureLocalAppliedTheme(this@ThemeManageActivity, true)
            }
            loadThemes()
        }
        schedulePendingRemoteSyncTasks()
        observeWebDavTasks()
    }

    override fun onResume() {
        super.onResume()
        invalidateOptionsMenu()
    }

    private fun initView() {
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
                ThemePackageManageScreen(
                    entries = entriesState,
                    isNightTheme = isNightTheme,
                    summaryText = summaryTextState,
                    onSwitchDayNight = { night ->
                        if (night != isNightTheme) {
                            isNightTheme = night
                            loadThemes()
                        }
                    },
                    onAdd = ::showAddDialog,
                    onApply = ::applyTheme,
                    onEdit = { entry -> showEditDialog(entry) },
                    isApplied = ::isApplied,
                    entryInfo = ::entryInfo,
                    entryActions = ::entryActions,
                    previewData = ::previewData
                )
            }
        }
        container.addView(cv, index.coerceAtMost(container.childCount))
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        containerMenuItem = menu.add(0, MENU_CONTAINER, 0, R.string.theme_s3_container_switch).apply {
            setIcon(R.drawable.ic_outline_cloud_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(0, MENU_SYNC_TASKS, 1, R.string.package_sync_task_menu).apply {
            setIcon(R.drawable.ic_history)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        updateContainerButton()
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_CONTAINER) {
            showContainerSelector()
            return true
        }
        if (item.itemId == MENU_SYNC_TASKS) {
            showThemeSyncTasks()
            return true
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun updateContainerButton() {
        val containers = AppCloudStorage.listContainers().filter { it.enabled }
        if (AppCloudStorage.type != CloudStorageType.S3) {
            cloudContainerId = containers.firstOrNull()?.id
            containerMenuItem?.isVisible = false
            return
        }
        cloudContainerId = AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
            ?: containers.firstOrNull()?.id
        containerMenuItem?.isVisible = true
        containerMenuItem?.title = containers.firstOrNull { it.id == cloudContainerId }
            ?.let(AppCloudStorage::containerDisplayLabel)
            ?: getString(R.string.s3_bucket)
    }

    private fun showContainerSelector() {
        lifecycleScope.launch {
            val containers = withContext(Dispatchers.IO) { AppCloudStorage.listContainers().filter { it.enabled } }
            if (containers.isEmpty()) {
                toastOnUi(R.string.cloud_storage_config_required)
                return@launch
            }
            val selected = cloudContainerId ?: AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id
            val actions = containers.map { container ->
                ModernActionPopup.Action(AppCloudStorage.containerDisplayLabel(container)) {
                    if (container.id == selected) return@Action
                    AppCloudStorage.selectContainer(CLOUD_SCOPE, container.id)
                    cloudContainerId = container.id
                    updateContainerButton()
                    loadThemes()
                }
            }
            containerMenuPopup = ModernActionPopup.show(
                anchor = binding.titleBar.toolbar,
                actions = actions,
                previousPopup = containerMenuPopup
            )
        }
    }
    private fun loadThemes() {
        val version = ++loadVersion
        val useCloud = AppConfig.syncThemePackages
        summaryTextState = appendPendingRemoteSummary(getString(R.string.theme_package_summary_default))
        lifecycleScope.launch {
            kotlin.runCatching {
                ThemePackageManager.load(isNightTheme, cloudContainerId, CLOUD_SCOPE)
            }.onSuccess {
                if (version != loadVersion) return@onSuccess
                if (isFinishing || isDestroyed) return@onSuccess
                entriesState = it
                summaryTextState = appendPendingRemoteSummary(
                    if (it.isEmpty()) {
                        getString(
                            R.string.theme_package_empty,
                            getString(if (isNightTheme) R.string.theme_night_short else R.string.theme_day_short)
                        )
                    } else {
                        getString(R.string.theme_package_summary_default)
                    }
                )
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                if (version != loadVersion) return@onFailure
                if (isFinishing || isDestroyed) return@onFailure
                summaryTextState = if (useCloud) {
                    getString(R.string.theme_package_cloud_load_failed, it.localizedMessage)
                } else {
                    getString(R.string.theme_package_load_failed, it.localizedMessage)
                }
            }
        }
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
                0 -> showManualAddDialog()
                1 -> importThemePackage.launch {
                    mode = HandleFileContract.FILE
                    title = getString(R.string.theme_import_zip)
                    allowExtensions = arrayOf("zip")
                }
            }
        }
    }

    private fun showManualAddDialog() {
        val dialogBinding = createEditBinding(currentConfig(), null)
        editDialogBinding = dialogBinding
        editingEntry = null
        val dialog = AndroidAlertBuilder(this).apply {
            setTitle(getString(R.string.theme_manual_add))
            customView { dialogBinding.root }
            onDismiss {
                editDialogBinding = null
                editingEntry = null
            }
        }.build()
        dialog.setOnShowListener {
            dialog.applyTint()
            applyThemeEditDialogSize(dialog)
        }
        applyThemeEditFonts(dialogBinding)
        bindThemeEditDialogActions(dialog, dialogBinding)
        dialog.show()
    }

    private fun showEditDialog(entry: ThemePackageManager.Entry) {
        if (entry.source == ThemePackageManager.Source.BUILTIN) {
            toastOnUi(R.string.theme_builtin_export_forbidden)
            return
        }
        lifecycleScope.launch {
            kotlin.runCatching {
                if (entry.source == ThemePackageManager.Source.REMOTE) {
                    ThemePackageManager.download(entry, cloudContainerId, CLOUD_SCOPE)
                } else {
                    entry
                }
            }.onSuccess { localEntry ->
                val dialogBinding = createEditBinding(ThemePackageManager.getConfig(localEntry), localEntry)
                editDialogBinding = dialogBinding
                editingEntry = localEntry
                val dialog = AndroidAlertBuilder(this@ThemeManageActivity).apply {
                    setTitle(getString(R.string.theme_edit))
                    customView { dialogBinding.root }
                    onDismiss {
                        editDialogBinding = null
                        editingEntry = null
                    }
                }.build()
                dialog.setOnShowListener {
                    dialog.applyTint()
                    applyThemeEditDialogSize(dialog)
                }
                applyThemeEditFonts(dialogBinding)
                bindThemeEditDialogActions(dialog, dialogBinding)
                dialog.show()
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(getString(R.string.theme_package_read_failed, it.localizedMessage))
            }
        }
    }

    private fun createEditBinding(
        current: ThemeConfig.Config,
        entry: ThemePackageManager.Entry?
    ): DialogThemePackageEditBinding {
        pendingMainBackgroundPath = current.backgroundImgPath
        pendingBookInfoBackgroundPath = current.bookInfoBackgroundImgPath
        pendingPanelBackgroundPath = current.panelBackgroundImgPath
        pendingPanelBackgroundScaleType = current.panelBackgroundScaleType ?: ThemeConfig.PANEL_BG_CROP
        pendingPanelBorderColor = current.panelBorderColor
        pendingPanelBorderAlpha = current.panelBorderAlpha ?: 100
        pendingBlur = current.backgroundImgBlur
        pendingUiCornerScale = current.uiCornerScale ?: AppConfig.uiCornerScale
        pendingUiLayoutAlpha = current.uiLayoutAlpha ?: AppConfig.uiLayoutAlpha
        pendingDialogAlpha = current.dialogAlpha ?: AppConfig.dialogAlpha
        pendingFontScale = current.fontScale ?: getPrefInt(PreferKey.fontScale, 0)
        pendingUiFontPath = current.uiFontPath ?: AppConfig.uiFontPath
        pendingTitleFontPath = current.titleFontPath ?: AppConfig.titleFontPath
        pendingUiCornerSearchFollow = current.uiCornerSearchFollow ?: AppConfig.uiCornerSearchFollow
        pendingUiCornerReplyFollow = current.uiCornerReplyFollow ?: AppConfig.uiCornerReplyFollow
        return DialogThemePackageEditBinding.inflate(layoutInflater).apply {
            etName.setText(current.themeName)
            setupColorRow(rowPrimary, R.string.theme_color_primary, current.primaryColor, colorPrimary)
            setupColorRow(rowAccent, R.string.theme_color_accent, current.accentColor, colorAccent)
            setupColorRow(rowBackground, R.string.theme_color_background, current.backgroundColor, colorBackground)
            setupColorRow(rowBottomBackground, R.string.theme_color_bottom_background, current.bottomBackground, colorBottomBackground)
            setupImageRow(rowMainBackground, R.string.theme_image_main_background, ThemeImageTarget.MAIN)
            setupImageRow(rowBookInfoBackground, R.string.theme_image_book_info_background, ThemeImageTarget.BOOK_INFO)
            setupImageRow(rowPanelBackground, R.string.theme_image_panel_background, ThemeImageTarget.PANEL)
            setupPanelBackgroundModeRow(rowPanelBackgroundMode)
            setupInterfaceRows(this)
            setupEditGroups(this)
            etName.isEnabled = entry?.source != ThemePackageManager.Source.REMOTE
        }
    }

    private fun bindThemeEditDialogActions(
        dialog: androidx.appcompat.app.AlertDialog,
        binding: DialogThemePackageEditBinding
    ) {
        binding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        binding.btnConfirm.setOnClickListener {
            if (saveTheme(binding)) {
                dialog.dismiss()
            }
        }
    }

    private fun setupEditGroups(binding: DialogThemePackageEditBinding) = binding.run {
        val tabs = listOf(
            btnColorGroup to llColorGroup,
            btnImageGroup to llImageGroup,
            btnInterfaceGroup to llInterfaceGroup,
            btnFontGroup to llFontGroup
        )
        tabs.forEach { (button, _) ->
            button.background = UiCorner.actionSelector(
                Color.TRANSPARENT,
                ContextCompat.getColor(this@ThemeManageActivity, R.color.background_card),
                UiCorner.actionRadius(this@ThemeManageActivity)
            )
            button.applyUiTitleTypeface(this@ThemeManageActivity)
        }
        fun select(index: Int) {
            tabs.forEachIndexed { tabIndex, (button, group) ->
                val selected = tabIndex == index
                button.isSelected = selected
                button.setTextColor(if (selected) accentColor else primaryTextColor)
                group.visibility = if (selected) View.VISIBLE else View.GONE
            }
        }
        tabs.forEachIndexed { index, (button, _) ->
            button.setOnClickListener { select(index) }
        }
        select(0)
    }

    private fun setupInterfaceRows(binding: DialogThemePackageEditBinding) = binding.run {
        setupCornerScaleRow(rowCornerScale)
        setupLayoutAlphaRow(rowLayoutAlpha)
        setupDialogAlphaRow(rowDialogAlpha)
        setupPanelBorderColorRow(rowPanelBorderColor)
        setupPanelBorderAlphaRow(rowPanelBorderAlpha)
        setupFontScaleRow(rowFontScale)
        setupUiFontRow(rowUiFont)
        setupTitleFontRow(rowTitleFont)
        setupSwitchRow(rowSearchFollow, R.string.ui_corner_search_follow) {
            pendingUiCornerSearchFollow = !pendingUiCornerSearchFollow
            updateSwitchRow(rowSearchFollow, pendingUiCornerSearchFollow)
        }
        setupSwitchRow(rowReplyFollow, R.string.ui_corner_reply_follow) {
            pendingUiCornerReplyFollow = !pendingUiCornerReplyFollow
            updateSwitchRow(rowReplyFollow, pendingUiCornerReplyFollow)
        }
        updateSwitchRow(rowSearchFollow, pendingUiCornerSearchFollow)
        updateSwitchRow(rowReplyFollow, pendingUiCornerReplyFollow)
        applyThemeEditFonts(this)
    }

    private fun applyThemeEditDialogSize(dialog: androidx.appcompat.app.AlertDialog) {
        val metrics = resources.displayMetrics
        val heightRatio = if (metrics.heightPixels < 1600) {
            EDIT_DIALOG_HEIGHT_RATIO_COMPACT
        } else {
            EDIT_DIALOG_HEIGHT_RATIO
        }
        dialog.setLayout(
            (metrics.widthPixels * EDIT_DIALOG_WIDTH_RATIO).toInt(),
            (metrics.heightPixels * heightRatio).toInt()
        )
    }

    private fun applyThemeEditFonts(binding: DialogThemePackageEditBinding) {
        val uiTf = loadUiTypeface(pendingUiFontPath.orEmpty()) ?: uiTypeface()
        binding.root.applyUiBodyTypefaceDeep(uiTf)
        val titleTf = loadUiTypeface(pendingTitleFontPath.orEmpty()) ?: titleTypeface()
        listOf(
            binding.rowPrimary.tvTitle,
            binding.rowAccent.tvTitle,
            binding.rowBackground.tvTitle,
            binding.rowBottomBackground.tvTitle,
            binding.rowMainBackground.tvTitle,
            binding.rowBookInfoBackground.tvTitle,
            binding.rowPanelBackground.tvTitle,
            binding.rowPanelBackgroundMode.tvTitle,
            binding.rowCornerScale.tvTitle,
            binding.rowLayoutAlpha.tvTitle,
            binding.rowDialogAlpha.tvTitle,
            binding.rowPanelBorderColor.tvTitle,
            binding.rowPanelBorderAlpha.tvTitle,
            binding.rowFontScale.tvTitle,
            binding.rowUiFont.tvTitle,
            binding.rowTitleFont.tvTitle,
            binding.rowSearchFollow.tvTitle,
            binding.rowReplyFollow.tvTitle,
            binding.btnColorGroup,
            binding.btnImageGroup,
            binding.btnInterfaceGroup,
            binding.btnFontGroup,
            binding.btnCancel,
            binding.btnConfirm
        ).forEach {
            it.applyUiTitleTypeface(this)
            it.typeface = titleTf
        }
        val actionRadius = UiCorner.actionRadius(this)
        binding.btnCancel.background = UiCorner.actionSelector(
            Color.TRANSPARENT,
            ContextCompat.getColor(this, R.color.background_card),
            actionRadius
        )
        binding.btnConfirm.background = UiCorner.actionSelector(
            Color.TRANSPARENT,
            ContextCompat.getColor(this, R.color.background_card),
            actionRadius
        )
    }

    private fun setupCornerScaleRow(row: ItemThemePackageOptionBinding) {
        applyOptionRowBackground(row)
        row.tvTitle.text = getString(R.string.ui_corner_scale)
        row.viewSwatch.visibility = View.INVISIBLE
        row.tvValue.text = pendingUiCornerScale.toScaleText()
        row.root.setOnClickListener {
            NumberPickerDialog(this, isDecimalMode = true)
                .setTitle(getString(R.string.ui_corner_scale))
                .setMaxValue(30)
                .setMinValue(0)
                .setValue((pendingUiCornerScale * 10).toInt())
                .setCustomButton(R.string.btn_default_s) {
                    pendingUiCornerScale = 1f
                    row.tvValue.text = pendingUiCornerScale.toScaleText()
                }
                .show {
                    pendingUiCornerScale = (it / 10f).coerceIn(0f, 3f)
                    row.tvValue.text = pendingUiCornerScale.toScaleText()
                }
        }
    }

    private fun setupLayoutAlphaRow(row: ItemThemePackageOptionBinding) {
        applyOptionRowBackground(row)
        row.tvTitle.text = getString(R.string.ui_layout_alpha)
        row.viewSwatch.visibility = View.INVISIBLE
        row.tvValue.text = getString(R.string.ui_layout_alpha_value, pendingUiLayoutAlpha)
        row.root.setOnClickListener {
            NumberPickerDialog(this)
                .setTitle(getString(R.string.ui_layout_alpha))
                .setMaxValue(100)
                .setMinValue(0)
                .setValue(pendingUiLayoutAlpha)
                .setCustomButton(R.string.btn_default_s) {
                    pendingUiLayoutAlpha = 100
                    row.tvValue.text = getString(R.string.ui_layout_alpha_value, pendingUiLayoutAlpha)
                }
                .show {
                    pendingUiLayoutAlpha = it.coerceIn(0, 100)
                    row.tvValue.text = getString(R.string.ui_layout_alpha_value, pendingUiLayoutAlpha)
                }
        }
    }

    private fun setupDialogAlphaRow(row: ItemThemePackageOptionBinding) {
        applyOptionRowBackground(row)
        row.tvTitle.text = getString(R.string.dialog_alpha)
        row.viewSwatch.visibility = View.INVISIBLE
        row.tvValue.text = getString(R.string.ui_layout_alpha_value, pendingDialogAlpha)
        row.root.setOnClickListener {
            NumberPickerDialog(this)
                .setTitle(getString(R.string.dialog_alpha))
                .setMaxValue(100)
                .setMinValue(0)
                .setValue(pendingDialogAlpha)
                .setCustomButton(R.string.btn_default_s) {
                    pendingDialogAlpha = 100
                    row.tvValue.text = getString(R.string.ui_layout_alpha_value, pendingDialogAlpha)
                }
                .show {
                    pendingDialogAlpha = it.coerceIn(0, 100)
                    row.tvValue.text = getString(R.string.ui_layout_alpha_value, pendingDialogAlpha)
                }
        }
    }

    private fun setupFontScaleRow(row: ItemThemePackageOptionBinding) {
        applyOptionRowBackground(row)
        row.tvTitle.text = getString(R.string.font_scale)
        row.viewSwatch.visibility = View.INVISIBLE
        row.tvValue.text = if (pendingFontScale == 0) {
            getString(R.string.btn_default_s)
        } else {
            "%.1f".format(Locale.US, pendingFontScale / 10f)
        }
        row.root.setOnClickListener {
            NumberPickerDialog(this)
                .setTitle(getString(R.string.font_scale))
                .setMaxValue(16)
                .setMinValue(8)
                .setValue(if (pendingFontScale == 0) 10 else pendingFontScale)
                .setCustomButton(R.string.btn_default_s) {
                    pendingFontScale = 0
                    setupFontScaleRow(row)
                    editDialogBinding?.let { applyThemeEditFonts(it) }
                }
                .show {
                    pendingFontScale = it.coerceIn(8, 16)
                    setupFontScaleRow(row)
                    editDialogBinding?.let { applyThemeEditFonts(it) }
                }
        }
    }

    private fun setupUiFontRow(row: ItemThemePackageOptionBinding) {
        applyOptionRowBackground(row)
        row.tvTitle.text = getString(R.string.ui_font)
        row.viewSwatch.visibility = View.INVISIBLE
        row.tvValue.text = uiFontDisplayName(pendingUiFontPath)
        row.root.setOnClickListener {
            pendingFontTarget = FontTarget.UI
            showDialogFragment<FontSelectDialog>()
        }
    }

    private fun setupTitleFontRow(row: ItemThemePackageOptionBinding) {
        applyOptionRowBackground(row)
        row.tvTitle.text = getString(R.string.title_font)
        row.viewSwatch.visibility = View.INVISIBLE
        row.tvValue.text = uiFontDisplayName(pendingTitleFontPath)
        row.root.setOnClickListener {
            pendingFontTarget = FontTarget.TITLE
            showDialogFragment<FontSelectDialog>()
        }
    }

    private fun setupSwitchRow(row: ItemThemePackageOptionBinding, titleRes: Int, onClick: () -> Unit) {
        applyOptionRowBackground(row)
        row.tvTitle.text = getString(titleRes)
        row.viewSwatch.visibility = View.INVISIBLE
        row.root.setOnClickListener { onClick() }
    }

    private fun updateSwitchRow(row: ItemThemePackageOptionBinding, checked: Boolean) {
        row.tvValue.text = getString(if (checked) R.string.enable else R.string.disable)
    }

    private fun setupColorRow(
        row: ItemThemePackageOptionBinding,
        titleRes: Int,
        colorText: String,
        target: Int
    ) {
        applyOptionRowBackground(row)
        row.tvTitle.text = getString(titleRes)
        row.viewSwatch.visibility = View.VISIBLE
        row.tvValue.text = normalizeColor(colorText).uppercase(Locale.ROOT)
        updateSwatch(row, normalizeColor(colorText).toColorInt())
        row.root.setOnClickListener {
            ColorPickerDialog.newBuilder()
                .setColor(normalizeColor(row.tvValue.text?.toString()).toColorInt())
                .setShowAlphaSlider(false)
                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                .setDialogId(target)
                .show(this)
        }
    }

    private fun updateSwatch(row: ItemThemePackageOptionBinding, color: Int) {
        row.viewSwatch.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = UiCorner.scaledDp(11f)
            setColor(color)
            setStroke((1f * resources.displayMetrics.density).toInt().coerceAtLeast(1), ColorUtils.adjustAlpha(primaryTextColor, 0.16f))
        }
    }

    private fun setupImageRow(row: ItemThemePackageOptionBinding, titleRes: Int, target: ThemeImageTarget) {
        applyOptionRowBackground(row)
        row.tvTitle.text = getString(titleRes)
        row.viewSwatch.visibility = View.INVISIBLE
        updateImageRow(row, target)
        row.root.setOnClickListener {
            showImageActions(target)
        }
    }

    private fun setupPanelBorderColorRow(row: ItemThemePackageOptionBinding) {
        applyOptionRowBackground(row)
        row.tvTitle.text = getString(R.string.theme_panel_border_color)
        val colorText = pendingPanelBorderColor?.takeIf { it.isNotBlank() }
        row.viewSwatch.visibility = if (colorText == null) View.INVISIBLE else View.VISIBLE
        row.tvValue.text = colorText?.uppercase(Locale.ROOT) ?: getString(R.string.disable)
        colorText?.let { runCatching { updateSwatch(row, normalizeColor(it).toColorInt()) } }
        row.root.setOnClickListener {
            selector(
                getString(R.string.theme_panel_border_color),
                listOf(getString(R.string.disable), getString(R.string.select_color))
            ) { _, index ->
                if (index == 0) {
                    pendingPanelBorderColor = null
                    setupPanelBorderColorRow(row)
                } else {
                    ColorPickerDialog.newBuilder()
                        .setColor((pendingPanelBorderColor ?: "#${accentColor.hexString}").toColorInt())
                        .setShowAlphaSlider(false)
                        .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                        .setDialogId(colorPanelBorder)
                        .show(this)
                }
            }
        }
    }
    private fun setupPanelBorderAlphaRow(row: ItemThemePackageOptionBinding) {
        applyOptionRowBackground(row)
        row.tvTitle.text = getString(R.string.theme_panel_border_alpha)
        row.viewSwatch.visibility = View.INVISIBLE
        row.tvValue.text = getString(R.string.ui_layout_alpha_value, pendingPanelBorderAlpha)
        row.root.setOnClickListener {
            NumberPickerDialog(this)
                .setTitle(getString(R.string.theme_panel_border_alpha))
                .setMaxValue(100)
                .setMinValue(0)
                .setValue(pendingPanelBorderAlpha)
                .setCustomButton(R.string.btn_default_s) {
                    pendingPanelBorderAlpha = 100
                    row.tvValue.text = getString(R.string.ui_layout_alpha_value, pendingPanelBorderAlpha)
                }
                .show {
                    pendingPanelBorderAlpha = it.coerceIn(0, 100)
                    row.tvValue.text = getString(R.string.ui_layout_alpha_value, pendingPanelBorderAlpha)
                }
        }
    }

    private fun updateImageRow(row: ItemThemePackageOptionBinding, target: ThemeImageTarget) {
        val path = when (target) {
            ThemeImageTarget.MAIN -> pendingMainBackgroundPath
            ThemeImageTarget.BOOK_INFO -> pendingBookInfoBackgroundPath
            ThemeImageTarget.PANEL -> pendingPanelBackgroundPath
        }
        row.tvValue.text = when {
            path.isNullOrBlank() && target == ThemeImageTarget.MAIN -> getString(R.string.theme_image_value_unselected_blur, pendingBlur)
            path.isNullOrBlank() -> getString(R.string.theme_image_value_unselected)
            target == ThemeImageTarget.MAIN -> getString(R.string.theme_image_value_file_blur, File(path).name, pendingBlur)
            else -> File(path).name
        }
    }

    private fun showImageActions(target: ThemeImageTarget) {
        val hasImage = when (target) {
            ThemeImageTarget.MAIN -> !pendingMainBackgroundPath.isNullOrBlank()
            ThemeImageTarget.BOOK_INFO -> !pendingBookInfoBackgroundPath.isNullOrBlank()
            ThemeImageTarget.PANEL -> !pendingPanelBackgroundPath.isNullOrBlank()
        }
        val actions = buildList {
            if (target == ThemeImageTarget.MAIN) add(ThemeImageAction.BLUR)
            add(ThemeImageAction.SELECT)
            if (hasImage) add(ThemeImageAction.DELETE)
        }
        val title = when (target) {
            ThemeImageTarget.MAIN -> R.string.theme_image_main_background
            ThemeImageTarget.BOOK_INFO -> R.string.theme_image_book_info_background
            ThemeImageTarget.PANEL -> R.string.theme_image_panel_background
        }
        selector(
            getString(title),
            actions.map { getString(it.titleRes) }
        ) { _, index ->
            when (actions[index]) {
                ThemeImageAction.BLUR -> showBlurDialog()
                ThemeImageAction.SELECT -> selectImage.launch {
                    requestCode = when (target) {
                        ThemeImageTarget.MAIN -> requestMainBackground
                        ThemeImageTarget.BOOK_INFO -> requestBookInfoBackground
                        ThemeImageTarget.PANEL -> requestPanelBackground
                    }
                    mode = HandleFileContract.IMAGE
                }
                ThemeImageAction.DELETE -> {
                    when (target) {
                        ThemeImageTarget.MAIN -> {
                            pendingMainBackgroundPath = ""
                            editDialogBinding?.let { updateImageRow(it.rowMainBackground, ThemeImageTarget.MAIN) }
                        }
                        ThemeImageTarget.BOOK_INFO -> {
                            pendingBookInfoBackgroundPath = ""
                            editDialogBinding?.let { updateImageRow(it.rowBookInfoBackground, ThemeImageTarget.BOOK_INFO) }
                        }
                        ThemeImageTarget.PANEL -> {
                            pendingPanelBackgroundPath = ""
                            editDialogBinding?.let { updateImageRow(it.rowPanelBackground, ThemeImageTarget.PANEL) }
                        }
                    }
                }
            }
        }
    }

    private fun setupPanelBackgroundModeRow(row: ItemThemePackageOptionBinding) {
        applyOptionRowBackground(row)
        row.tvTitle.text = getString(R.string.theme_image_panel_background_mode)
        row.viewSwatch.visibility = View.INVISIBLE
        updatePanelBackgroundModeRow(row)
        row.root.setOnClickListener {
            val modes = listOf(ThemeConfig.PANEL_BG_CROP, ThemeConfig.PANEL_BG_FIT)
            selector(
                getString(R.string.theme_image_panel_background_mode),
                modes.map { panelBackgroundModeText(it) }
            ) { _, index ->
                pendingPanelBackgroundScaleType = modes[index]
                updatePanelBackgroundModeRow(row)
            }
        }
    }

    private fun updatePanelBackgroundModeRow(row: ItemThemePackageOptionBinding) {
        row.tvValue.text = panelBackgroundModeText(pendingPanelBackgroundScaleType)
    }

    private fun panelBackgroundModeText(mode: String): String {
        return getString(
            if (mode == ThemeConfig.PANEL_BG_FIT) R.string.theme_image_mode_fit
            else R.string.theme_image_mode_crop
        )
    }

    private fun applyOptionRowBackground(row: ItemThemePackageOptionBinding) {
        row.root.background = UiCorner.opaqueRounded(
            ContextCompat.getColor(this, R.color.background_card),
            UiCorner.panelRadius(this)
        )
    }

    private fun showBlurDialog() {
        alert(R.string.theme_image_blur) {
            val blurBinding = DialogImageBlurringBinding.inflate(layoutInflater).apply {
                seekBar.progress = pendingBlur
                textViewValue.text = pendingBlur.toString()
                seekBar.setOnSeekBarChangeListener(object : SeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: android.widget.SeekBar,
                        progress: Int,
                        fromUser: Boolean
                    ) {
                        textViewValue.text = progress.toString()
                    }
                })
            }
            customView { blurBinding.root }
            okButton {
                pendingBlur = blurBinding.seekBar.progress.coerceIn(0, 25)
                editDialogBinding?.let { updateImageRow(it.rowMainBackground, ThemeImageTarget.MAIN) }
            }
            cancelButton()
        }
    }

    private fun saveTheme(dialogBinding: DialogThemePackageEditBinding): Boolean {
        val name = dialogBinding.etName.text?.toString()?.trim().orEmpty()
            .ifBlank { getString(if (isNightTheme) R.string.theme_night else R.string.theme_day) }
        val baseConfig = editingEntry?.let {
            kotlin.runCatching { ThemePackageManager.getConfig(it) }.getOrNull()
        } ?: currentConfig()
        val config = kotlin.runCatching {
            baseConfig.copy(
                themeName = name,
                isNightTheme = isNightTheme,
                primaryColor = normalizeColor(dialogBinding.rowPrimary.tvValue.text?.toString()),
                accentColor = normalizeColor(dialogBinding.rowAccent.tvValue.text?.toString()),
                backgroundColor = normalizeColor(dialogBinding.rowBackground.tvValue.text?.toString()),
                bottomBackground = normalizeColor(dialogBinding.rowBottomBackground.tvValue.text?.toString()),
                transparentNavBar = true,
                backgroundImgPath = pendingMainBackgroundPath,
                backgroundImgBlur = pendingBlur,
                bookInfoBackgroundImgPath = pendingBookInfoBackgroundPath,
                panelBackgroundImgPath = pendingPanelBackgroundPath,
                panelBackgroundScaleType = pendingPanelBackgroundScaleType,
                panelBorderColor = pendingPanelBorderColor,
                panelBorderAlpha = pendingPanelBorderAlpha,
                uiCornerScale = pendingUiCornerScale,
                uiLayoutAlpha = pendingUiLayoutAlpha,
                dialogAlpha = pendingDialogAlpha,
                uiCornerSearchFollow = pendingUiCornerSearchFollow,
                uiCornerReplyFollow = pendingUiCornerReplyFollow,
                fontScale = pendingFontScale,
                uiFontPath = pendingUiFontPath,
                titleFontPath = pendingTitleFontPath
            )
        }.onFailure {
            toastOnUi(R.string.color_format_error)
        }.getOrNull() ?: return false
        addTheme(config)
        return true
    }

    private fun addTheme(config: ThemeConfig.Config) {
        val oldEntry = editingEntry
        lifecycleScope.launch {
            kotlin.runCatching {
                val wasApplied = oldEntry?.let { isApplied(it) } == true
                val exists = ThemePackageManager.localThemeExists(
                    config.isNightTheme,
                    config.themeName,
                    oldEntry?.dirName
                )
                if (exists) {
                    throw IllegalArgumentException(getString(R.string.theme_name_exists))
                }
                val entry = ThemePackageManager.addFromConfig(config)
                if (oldEntry != null && oldEntry.dirName != entry.dirName) {
                    if (oldEntry.source != ThemePackageManager.Source.REMOTE) {
                        ThemePackageManager.deleteLocal(oldEntry)
                    }
                    if (AppConfig.syncThemePackages && oldEntry.source != ThemePackageManager.Source.LOCAL) {
                        enqueueRemoteDelete(oldEntry)
                    }
                }
                if (wasApplied) {
                    ThemePackageManager.apply(this@ThemeManageActivity, entry, switchNightMode = false)
                }
                entry
            }.onSuccess {
                toastOnUi(getString(R.string.theme_saved_local))
                loadThemes()
                if (enqueueUploadIfNeeded(it)) {
                    showThemeSyncTasks()
                }
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(getString(R.string.theme_save_failed, it.localizedMessage))
            }
        }
    }

    private fun enqueueUploadIfNeeded(entry: ThemePackageManager.Entry): Boolean {
        if (!AppConfig.syncThemePackages) return false
        return enqueueThemeUpload(entry)
    }

    private fun enqueueThemeUpload(entry: ThemePackageManager.Entry): Boolean {
        return WebDavTaskManager.enqueueUpload(
            key = "theme_upload:${entry.packageInfo.isNightTheme}:${entry.dirName}",
            name = entry.packageInfo.name,
            type = WebDavTaskType.THEME_PACKAGE_UPLOAD,
            runningMessage = getString(R.string.theme_upload_remote),
            successMessage = getString(R.string.theme_sync_done)
        ) {
            ThemePackageManager.upload(entry, cloudContainerId, CLOUD_SCOPE)
        }
    }

    private fun observeWebDavTasks() {
        seedHandledWebDavTasks(WebDavTaskType.THEME_PACKAGE_UPLOAD)
        lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                var shouldReload = false
                var failedMessage: String? = null
                states.values
                    .filter { it.type == WebDavTaskType.THEME_PACKAGE_UPLOAD }
                    .filter { it.status == WebDavTaskStatus.COMPLETED || it.status == WebDavTaskStatus.FAILED }
                    .forEach { state ->
                        if (handledWebDavTasks.add(webDavTaskHandleKey(state.key, state.status))) {
                            shouldReload = true
                            if (state.status == WebDavTaskStatus.FAILED) {
                                failedMessage = state.message
                            }
                        }
                    }
                if (shouldReload) {
                    loadThemes()
                    failedMessage?.let { toastOnUi(getString(R.string.theme_sync_failed, it)) }
                }
            }
        }
    }

    private fun seedHandledWebDavTasks(type: WebDavTaskType) {
        WebDavTaskManager.states.value.values
            .filter { it.type == type }
            .filter { it.status == WebDavTaskStatus.COMPLETED || it.status == WebDavTaskStatus.FAILED }
            .forEach { handledWebDavTasks.add(webDavTaskHandleKey(it.key, it.status)) }
    }

    private fun webDavTaskHandleKey(key: String, status: WebDavTaskStatus): String {
        return "$key:$status"
    }

    private fun showThemeSyncTasks() {
        showPackageSyncTaskDialog(setOf(WebDavTaskType.THEME_PACKAGE_UPLOAD))
    }

    private fun currentConfig(): ThemeConfig.Config {
        val name = getString(if (isNightTheme) R.string.theme_night else R.string.theme_day)
        val primary = getPrefInt(
            if (isNightTheme) PreferKey.cNPrimary else PreferKey.cPrimary,
            getCompatColor(if (isNightTheme) R.color.md_blue_grey_600 else R.color.md_brown_500)
        )
        val accent = getPrefInt(
            if (isNightTheme) PreferKey.cNAccent else PreferKey.cAccent,
            getCompatColor(if (isNightTheme) R.color.md_deep_orange_800 else R.color.md_red_600)
        )
        val background = getPrefInt(
            if (isNightTheme) PreferKey.cNBackground else PreferKey.cBackground,
            getCompatColor(if (isNightTheme) R.color.md_grey_900 else R.color.md_grey_100)
        )
        val bottom = getPrefInt(
            if (isNightTheme) PreferKey.cNBBackground else PreferKey.cBBackground,
            getCompatColor(if (isNightTheme) R.color.md_grey_850 else R.color.md_grey_200)
        )
        return ThemeConfig.Config(
            themeName = name,
            isNightTheme = isNightTheme,
            primaryColor = "#${primary.hexString}",
            accentColor = "#${accent.hexString}",
            backgroundColor = "#${background.hexString}",
            bottomBackground = "#${bottom.hexString}",
            transparentNavBar = true,
            backgroundImgPath = getPrefString(if (isNightTheme) PreferKey.bgImageN else PreferKey.bgImage),
            backgroundImgBlur = getPrefInt(if (isNightTheme) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring, 0),
            bookInfoBackgroundImgPath = getPrefString(if (isNightTheme) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage),
            panelBackgroundImgPath = getPrefString(if (isNightTheme) PreferKey.panelBgImageN else PreferKey.panelBgImage),
            panelBackgroundScaleType = getPrefString(if (isNightTheme) PreferKey.panelBgScaleTypeN else PreferKey.panelBgScaleType)
                ?: ThemeConfig.PANEL_BG_CROP,
            panelBorderColor = getPrefString(if (isNightTheme) PreferKey.panelBorderColorN else PreferKey.panelBorderColor),
            panelBorderAlpha = getPrefInt(if (isNightTheme) PreferKey.panelBorderAlphaN else PreferKey.panelBorderAlpha, 100),
            uiCornerScale = AppConfig.uiCornerScale,
            uiLayoutAlpha = AppConfig.uiLayoutAlpha,
            dialogAlpha = AppConfig.dialogAlpha,
            uiCornerSearchFollow = AppConfig.uiCornerSearchFollow,
            uiCornerReplyFollow = AppConfig.uiCornerReplyFollow,
            fontScale = getPrefInt(PreferKey.fontScale, 0),
            uiFontPath = AppConfig.uiFontPath,
            titleFontPath = AppConfig.titleFontPath
        )
    }

    override val curFontPath: String
        get() = when (pendingFontTarget) {
            FontTarget.TITLE -> pendingTitleFontPath
            FontTarget.UI -> pendingUiFontPath
        }.orEmpty()

    override val applySystemTypefaceOnDefault: Boolean
        get() = false

    override fun selectFont(path: String) {
        when (pendingFontTarget) {
            FontTarget.UI -> {
                pendingUiFontPath = path
                editDialogBinding?.let {
                    setupUiFontRow(it.rowUiFont)
                    applyThemeEditFonts(it)
                }
            }

            FontTarget.TITLE -> {
                pendingTitleFontPath = path
                editDialogBinding?.let {
                    setupTitleFontRow(it.rowTitleFont)
                    applyThemeEditFonts(it)
                }
            }
        }
    }

    private fun uiFontDisplayName(path: String?): String {
        if (path.isNullOrBlank()) {
            return getString(R.string.default_font)
        }
        val rawName = runCatching {
            val uri = Uri.parse(path)
            when {
                uri.scheme == "content" -> androidx.documentfile.provider.DocumentFile
                    .fromSingleUri(this, uri)
                    ?.name
                uri.scheme == "file" -> File(uri.path.orEmpty()).name
                else -> null
            }
        }.getOrNull()
            ?: path.substringAfterLast(File.separator)
                .substringAfterLast("/")
                .substringAfterLast(":")
        val displayName = when {
            rawName.startsWith("ui_font.") -> rawName.replaceFirst("ui_font", getString(R.string.ui_font))
            rawName.startsWith("title_font.") -> rawName.replaceFirst("title_font", getString(R.string.title_font))
            else -> rawName
                .removePrefix("ui_font_")
                .removePrefix("title_font_")
        }
        return runCatching {
            URLDecoder.decode(displayName, "utf-8")
        }.getOrDefault(displayName).ifBlank {
            getString(R.string.default_font)
        }
    }

    private enum class FontTarget {
        UI,
        TITLE
    }

    private fun Float.toScaleText(): String {
        return if (this % 1f == 0f) {
            this.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')
        }
    }

    private fun normalizeColor(value: String?): String {
        val color = value?.trim().orEmpty().let {
            if (it.startsWith("#")) it else "#$it"
        }
        color.toColorInt()
        return color
    }

    private fun startImageCrop(uri: Uri, requestCode: Int) {
        val aspect = if (requestCode == requestPanelBackground) 1 to 1 else ImageCropHelper.screenAspect(this)
        val prefix = when (requestCode) {
            requestMainBackground -> "main"
            requestPanelBackground -> "panel"
            else -> "book_info"
        }
        val request = ImageCropHelper.buildRequest(
            context = this,
            sourceUri = uri,
            requestCode = requestCode,
            aspectWidth = aspect.first,
            aspectHeight = aspect.second,
            dirName = "themePackageTemp",
            prefix = prefix,
            targetWidth = 1600
        )
        pendingImageCropRequest = request
        cropImage.launch(request.params)
    }

    private fun entryActions(entry: ThemePackageManager.Entry): List<AppManagementMenuAction> {
        val actions = buildList {
            add(ThemeAction.APPLY)
            if (entry.source != ThemePackageManager.Source.REMOTE &&
                entry.source != ThemePackageManager.Source.BUILTIN
            ) {
                add(ThemeAction.EDIT)
                add(ThemeAction.EXPORT)
            }
            if (entry.source == ThemePackageManager.Source.REMOTE ||
                entry.source == ThemePackageManager.Source.BOTH
            ) add(ThemeAction.DOWNLOAD)
            if (entry.source != ThemePackageManager.Source.REMOTE &&
                entry.source != ThemePackageManager.Source.BUILTIN
            ) add(ThemeAction.UPLOAD)
            if (!isApplied(entry)) {
                if (entry.source != ThemePackageManager.Source.REMOTE &&
                    entry.source != ThemePackageManager.Source.BUILTIN
                ) add(ThemeAction.DELETE_LOCAL)
                if (entry.source != ThemePackageManager.Source.LOCAL &&
                    entry.source != ThemePackageManager.Source.BUILTIN
                ) add(ThemeAction.DELETE_REMOTE)
                if (entry.source == ThemePackageManager.Source.BOTH) add(ThemeAction.DELETE_BOTH)
            }
        }
        return actions.map { action ->
            AppManagementMenuAction(
                text = getString(action.titleRes),
                danger = action.name.startsWith("DELETE")
            ) {
                when (action) {
                    ThemeAction.APPLY -> applyTheme(entry)
                    ThemeAction.EDIT -> showEditDialog(entry)
                    ThemeAction.EXPORT -> exportThemeZip(entry)
                    ThemeAction.DOWNLOAD -> runAction(getString(R.string.theme_downloaded)) {
                        ThemePackageManager.download(entry, cloudContainerId, CLOUD_SCOPE)
                    }
                    ThemeAction.UPLOAD -> uploadThemeNow(entry)
                    ThemeAction.DELETE_LOCAL -> confirmDeleteTheme(
                        entry,
                        getString(R.string.theme_delete_local_confirm)
                    ) {
                        ThemePackageManager.deleteLocal(entry)
                    }
                    ThemeAction.DELETE_REMOTE -> confirmDeleteTheme(
                        entry,
                        getString(R.string.theme_delete_remote_confirm)
                    ) {
                        enqueueRemoteDelete(entry)
                    }
                    ThemeAction.DELETE_BOTH -> confirmDeleteTheme(
                        entry,
                        getString(R.string.theme_delete_both_confirm)
                    ) {
                        ThemePackageManager.deleteLocal(entry)
                        enqueueRemoteDelete(entry)
                    }
                }
            }
        }
    }

    private fun uploadThemeNow(entry: ThemePackageManager.Entry) {
        val queued = enqueueThemeUpload(entry)
        toastOnUi(if (queued) R.string.cache_manage_upload_queued else R.string.cache_manage_webdav_task_duplicate)
        if (queued) {
            showThemeSyncTasks()
        }
    }

    private fun exportThemeZip(entry: ThemePackageManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                ThemePackageManager.exportZip(entry)
            }.onSuccess { zipFile ->
                exportThemePackage.launch {
                    mode = HandleFileContract.EXPORT
                    showUploadUrl = false
                    fileData = HandleFileContract.FileData(
                        zipFile.name,
                        zipFile,
                        "application/zip"
                    )
                }
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(getString(R.string.theme_export_failed, it.localizedMessage))
            }
        }
    }

    private fun importThemeZip(uri: Uri) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val dir = externalFiles.getFile("themePackageImports").apply { mkdirs() }
                val file = File(dir, "import_${System.currentTimeMillis()}.zip")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                ThemePackageManager.importZip(file)
            }.onSuccess {
                toastOnUi(getString(R.string.theme_imported))
                loadThemes()
                if (enqueueUploadIfNeeded(it)) {
                    showThemeSyncTasks()
                }
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(getString(R.string.theme_import_failed, it.localizedMessage))
            }
        }
    }

    private fun applyTheme(entry: ThemePackageManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val localEntry = if (entry.source == ThemePackageManager.Source.REMOTE) {
                    ThemePackageManager.download(entry, cloudContainerId, CLOUD_SCOPE)
                } else {
                    entry
                }
                ThemePackageManager.apply(this@ThemeManageActivity, localEntry, switchNightMode = false)
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(getString(R.string.theme_apply_failed, it.localizedMessage))
            }.onSuccess {
                if (entry.packageInfo.isNightTheme) {
                    appliedNightThemeOverride = entry.packageInfo.name
                } else {
                    appliedDayThemeOverride = entry.packageInfo.name
                }
                toastOnUi(getString(R.string.theme_applied))
                loadThemes()
            }
        }
    }

    private fun isApplied(entry: ThemePackageManager.Entry): Boolean {
        val overrideName = if (entry.packageInfo.isNightTheme) {
            appliedNightThemeOverride
        } else {
            appliedDayThemeOverride
        }
        if (overrideName != null) {
            return overrideName == entry.packageInfo.name
        }
        val key = if (entry.packageInfo.isNightTheme) PreferKey.dNThemeName else PreferKey.dThemeName
        return getPrefString(key) == entry.packageInfo.name
    }

    private fun entryInfo(entry: ThemePackageManager.Entry): String {
        val pkg = entry.packageInfo
        return buildString {
            if (isApplied(entry)) {
                append(getString(R.string.theme_current_applied))
                append(" \u00B7 ")
            }
            append(getString(if (pkg.isNightTheme) R.string.theme_night_short else R.string.theme_day_short))
            append(" \u00B7 ")
            val time = maxOf(pkg.updatedAt, entry.remoteUpdatedAt)
            append(if (time > 0) dateFormat.format(Date(time)) else getString(R.string.theme_time_unknown))
        }
    }

    private fun previewData(entry: ThemePackageManager.Entry): ThemePreviewData {
        val config = kotlin.runCatching { ThemePackageManager.getConfig(entry) }
            .getOrElse { entry.packageInfo.config }
        val fallbackColor = config?.backgroundColor.toPreviewColor(entry.packageInfo.isNightTheme)
        val backgroundPath = config?.backgroundImgPath?.takeIf { it.isNotBlank() }
        val previewSignature = backgroundPath
            ?.takeIf { !it.startsWith("http", ignoreCase = true) }
            ?.let { path ->
                val file = File(path)
                if (file.exists()) {
                    ObjectKey("${file.absolutePath}:${file.length()}:${file.lastModified()}")
                } else {
                    null
                }
            }
        return ThemePreviewData(
            fallbackColor = fallbackColor,
            backgroundPath = backgroundPath,
            signature = previewSignature
        )
    }

    private fun String?.toPreviewColor(isNightTheme: Boolean): Int {
        return kotlin.runCatching {
            val color = this?.trim().orEmpty()
            val normalized = if (color.startsWith("#")) color else "#$color"
            normalized.toColorInt()
        }.getOrElse {
            if (isNightTheme) Color.BLACK else Color.WHITE
        }
    }

    private fun runAction(successMessage: String, block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching {
                block()
            }.onSuccess {
                toastOnUi(successMessage)
                loadThemes()
            }.onFailure {
                if (it.isJobCancellation()) return@onFailure
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun enqueueRemoteDelete(entry: ThemePackageManager.Entry) {
        if (!AppConfig.syncThemePackages) return
        enqueueRemoteSync(
            RemoteSyncTask(
                key = "delete:${entry.packageInfo.isNightTheme}:${entry.dirName}",
                type = RemoteSyncTask.Type.DELETE,
                isNightTheme = entry.packageInfo.isNightTheme,
                dirName = entry.dirName
            )
        )
    }

    private fun enqueueRemoteSync(task: RemoteSyncTask) {
        synchronized(pendingRemoteSyncTasks) {
            pendingRemoteSyncTasks[task.key] = task
            savePendingRemoteSyncTasksLocked()
        }
        flushPendingRemoteSyncTasks()
    }

    private fun schedulePendingRemoteSyncTasks() {
        lifecycleScope.launch {
            delay(1_500L)
            if (!isFinishing && !isDestroyed) {
                flushPendingRemoteSyncTasks()
            }
        }
    }

    private fun restorePendingRemoteSyncTasks() {
        val tasks = getPrefString(PreferKey.themePackageSyncTasks).orEmpty()
            .takeIf { it.isNotBlank() }
            ?.let { GSON.fromJsonArray<RemoteSyncTask>(it).getOrNull() }
            .orEmpty()
        if (tasks.isEmpty()) return
        synchronized(pendingRemoteSyncTasks) {
            pendingRemoteSyncTasks.clear()
            tasks.forEach { task ->
                pendingRemoteSyncTasks[task.key] = task.copy(lastError = "")
            }
        }
    }

    private fun savePendingRemoteSyncTasksLocked() {
        val tasks = pendingRemoteSyncTasks.values.toList()
        if (tasks.isEmpty()) {
            removePref(PreferKey.themePackageSyncTasks)
        } else {
            putPrefString(PreferKey.themePackageSyncTasks, GSON.toJson(tasks))
        }
    }

    private fun flushPendingRemoteSyncTasks() {
        val hasPending = synchronized(pendingRemoteSyncTasks) { pendingRemoteSyncTasks.isNotEmpty() }
        if (syncingRemoteTasks || !hasPending || !AppConfig.syncThemePackages) return
        syncingRemoteTasks = true
        themeRemoteSyncScope.launch {
            val failed = linkedMapOf<String, RemoteSyncTask>()
            val tasks = synchronized(pendingRemoteSyncTasks) { pendingRemoteSyncTasks.values.toList() }
            tasks.forEach { task ->
                kotlin.runCatching {
                    task.execute()
                }.onSuccess {
                    synchronized(pendingRemoteSyncTasks) {
                        if (pendingRemoteSyncTasks[task.key] == task) {
                            pendingRemoteSyncTasks.remove(task.key)
                            savePendingRemoteSyncTasksLocked()
                        }
                    }
                }.onFailure {
                    if (!it.isJobCancellation()) {
                        failed[task.key] = task
                    }
                }
            }
            syncingRemoteTasks = false
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (failed.isEmpty()) {
                    toastOnUi(getString(R.string.theme_sync_done))
                    loadThemes()
                } else {
                    summaryTextState = appendPendingRemoteSummary(getString(R.string.theme_sync_failed_retry))
                    toastOnUi(getString(R.string.theme_sync_failed, failed.values.first().lastError))
                }
            }
            val pendingKeys = synchronized(pendingRemoteSyncTasks) { pendingRemoteSyncTasks.keys.toSet() }
            if (pendingKeys.any { it !in failed.keys }) {
                flushPendingRemoteSyncTasks()
            }
        }
    }

    private fun appendPendingRemoteSummary(base: String): String {
        val pendingCount = synchronized(pendingRemoteSyncTasks) { pendingRemoteSyncTasks.size }
        return if (pendingCount > 0) {
            "$base\n${getString(R.string.theme_sync_pending, pendingCount)}"
        } else {
            base
        }
    }

    private fun confirmDelete(message: String, block: suspend () -> Unit) {
        alert(getString(R.string.delete), message) {
            yesButton {
                runAction(getString(R.string.delete_success), block)
            }
            noButton()
        }
    }

    private fun confirmDeleteTheme(
        entry: ThemePackageManager.Entry,
        message: String,
        block: suspend () -> Unit
    ) {
        if (isApplied(entry)) {
            toastOnUi(getString(R.string.theme_delete_applied_forbidden))
            return
        }
        confirmDelete(message, block)
    }
override fun onColorSelected(dialogId: Int, color: Int) {
        val binding = editDialogBinding ?: return
        val hex = "#${color.hexString}".uppercase(Locale.ROOT)
        val row = when (dialogId) {
            colorPrimary -> binding.rowPrimary
            colorAccent -> binding.rowAccent
            colorBackground -> binding.rowBackground
            colorBottomBackground -> binding.rowBottomBackground
            colorPanelBorder -> binding.rowPanelBorderColor
            else -> null
        } ?: return
        if (dialogId == colorPanelBorder) {
            pendingPanelBorderColor = hex
        }
        row.tvValue.text = hex
        updateSwatch(row, color)
    }

    override fun onDialogDismissed(dialogId: Int) = Unit

    private fun Throwable.isJobCancellation(): Boolean {
        return this is CancellationException || cause?.isJobCancellation() == true
    }

    companion object {
        private val themeRemoteSyncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private const val EDIT_DIALOG_WIDTH_RATIO = 0.94f
        private const val EDIT_DIALOG_HEIGHT_RATIO = 0.68f
        private const val EDIT_DIALOG_HEIGHT_RATIO_COMPACT = 0.74f
        private const val requestMainBackground = 301
        private const val requestBookInfoBackground = 302
        private const val requestPanelBackground = 303
        private const val colorPrimary = 401
        private const val colorAccent = 402
        private const val colorBackground = 403
        private const val colorBottomBackground = 404
        private const val colorPanelBorder = 405
        private const val CLOUD_SCOPE = "theme"
        private const val MENU_CONTAINER = 0x5401
        private const val MENU_SYNC_TASKS = 0x5402
    }

    private enum class ThemeAction(val titleRes: Int) {
        APPLY(R.string.theme_apply),
        EDIT(R.string.edit),
        EXPORT(R.string.theme_export_zip),
        DOWNLOAD(R.string.theme_download_local),
        UPLOAD(R.string.theme_upload_remote),
        DELETE_LOCAL(R.string.theme_delete_local),
        DELETE_REMOTE(R.string.theme_delete_remote),
        DELETE_BOTH(R.string.theme_delete_both)
    }

    private enum class ThemeImageAction(val titleRes: Int) {
        BLUR(R.string.theme_image_blur),
        SELECT(R.string.theme_image_select),
        DELETE(R.string.theme_image_delete)
    }

    private enum class ThemeImageTarget {
        MAIN,
        BOOK_INFO,
        PANEL
    }

    private data class RemoteSyncTask(
        val key: String,
        val type: Type,
        val isNightTheme: Boolean,
        val dirName: String,
        val containerId: String? = null,
        val scope: String? = null,
        var lastError: String = ""
    ) {
        suspend fun execute() {
            val entry = ThemePackageManager.Entry(
                packageInfo = ThemePackageManager.Package(
                    name = dirName,
                    dirName = dirName,
                    isNightTheme = isNightTheme,
                    updatedAt = 0L,
                    config = null
                ),
                source = ThemePackageManager.Source.LOCAL,
                localDir = ThemePackageManager.localDir(isNightTheme, dirName)
            )
            runCatching {
                when (type) {
                    Type.UPLOAD -> ThemePackageManager.upload(entry, containerId, scope)
                    Type.DELETE -> ThemePackageManager.deleteRemote(entry, containerId, scope)
                }
            }.onFailure {
                lastError = it.localizedMessage ?: it.toString()
                throw it
            }.getOrThrow()
        }

        enum class Type {
            UPLOAD,
            DELETE
        }
    }
}

private data class ThemePreviewData(
    val fallbackColor: Int,
    val backgroundPath: String?,
    val signature: ObjectKey?
)

@Composable
private fun ThemePackageManageScreen(
    entries: List<ThemePackageManager.Entry>,
    isNightTheme: Boolean,
    summaryText: String,
    onSwitchDayNight: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onApply: (ThemePackageManager.Entry) -> Unit,
    onEdit: (ThemePackageManager.Entry) -> Unit,
    isApplied: (ThemePackageManager.Entry) -> Boolean,
    entryInfo: (ThemePackageManager.Entry) -> String,
    entryActions: (ThemePackageManager.Entry) -> List<AppManagementMenuAction>,
    previewData: (ThemePackageManager.Entry) -> ThemePreviewData
) {
    val applyText = stringResource(R.string.theme_apply)
    val appliedText = stringResource(R.string.theme_applied_state)
    val editText = stringResource(R.string.edit)
    AppPackageManageScreen(
        isNightMode = isNightTheme,
        summaryText = summaryText,
        addText = stringResource(R.string.theme_add),
        onSwitchDayNight = onSwitchDayNight,
        onAdd = onAdd
    ) { palette ->
        items(
            entries,
            key = { "${it.packageInfo.isNightTheme}_${it.dirName}" }
        ) { entry ->
            val active = isApplied(entry)
            AppPackageManageItemCard(
                title = entry.packageInfo.name,
                info = entryInfo(entry),
                isActive = active,
                canEdit = entry.source != ThemePackageManager.Source.BUILTIN &&
                    entry.source != ThemePackageManager.Source.REMOTE,
                applyText = if (active) appliedText else applyText,
                editText = editText,
                moreActions = entryActions(entry),
                palette = palette,
                onApply = { onApply(entry) },
                onEdit = { onEdit(entry) },
                leadingContent = {
                    ThemePackagePreview(
                        preview = previewData(entry),
                        radius = palette.miuix.panelRadius ?: 12.dp
                    )
                }
            )
        }
    }
}

@Composable
private fun ThemePackagePreview(
    preview: ThemePreviewData,
    radius: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = Modifier
            .size(width = 74.dp, height = 102.dp)
            .clip(RoundedCornerShape(radius))
            .background(ComposeColor(preview.fallbackColor))
    ) {
        AndroidView(
            factory = { context ->
                ImageView(context).apply {
                    contentDescription = context.getString(R.string.background_image)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            },
            update = { imageView ->
                Glide.with(imageView.context).clear(imageView)
                imageView.setBackgroundColor(preview.fallbackColor)
                imageView.setImageDrawable(null)
                val path = preview.backgroundPath
                if (!path.isNullOrBlank()) {
                    val request = ImageLoader.load(imageView.context, path)
                        .centerCrop()
                        .error(ColorDrawable(preview.fallbackColor))
                    preview.signature?.let { request.signature(it) }
                    request.into(imageView)
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
