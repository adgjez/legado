package io.legado.app.ui.config

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import com.jaredrummler.android.colorpicker.ColorPickerDialogListener
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.ItemThemePackageBinding
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.help.http.newCallResponseBody
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiInputStyle
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ImageProvider
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.postEvent
import io.legado.app.utils.sendToClip
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BubbleManageActivity : BaseActivity<ActivityThemeManageBinding>(), ColorPickerDialogListener {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val adapter = Adapter()
    private var cloudContainerId: String? = null
    private var containerMenuItem: MenuItem? = null
    private var editingConfig: BubblePackageManager.Config? = null
    private var editingRoot: LinearLayout? = null
    private var svgCursorPosition: Int = 0
    private val handledWebDavTasks = mutableSetOf<String>()
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    private val importFromNet by lazy { "网络导入" }
    private val importPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            if (uri.path == "/$importFromNet") {
                importNetZipAlert()
            } else {
                importZip(uri)
            }
        }
    }
    private val exportPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val value = uri.toString()
            if (value.startsWith("http://", true) || value.startsWith("https://", true)) {
                alert("上传成功") {
                    setMessage(value)
                    positiveButton(R.string.copy_text) {
                        sendToClip(value)
                        toastOnUi(R.string.copy_complete)
                    }
                    negativeButton(R.string.cancel)
                }
            } else {
                toastOnUi(R.string.export_success)
            }
        }
    }
    private val svgEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.getStringExtra("text")?.let { text ->
                editingConfig = editingConfig?.copy(svgTemplate = text)
                svgCursorPosition = result.data?.getIntExtra("cursorPosition", text.length) ?: text.length
                refreshEditDialog()
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initView()
        loadPackages()
        observeWebDavTasks()
    }

    override fun onResume() {
        super.onResume()
        invalidateOptionsMenu()
        loadPackages()
    }

    private fun initView() = binding.run {
        titleBar.title = getString(R.string.bubble_manage)
        tabBar.visibility = View.GONE
        tvSummary.text = getString(R.string.bubble_manage_summary)
        recyclerView.layoutManager = LinearLayoutManager(this@BubbleManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        btnAdd.text = getString(R.string.add)
        btnAdd.background = UiCorner.actionSelector(
            ContextCompat.getColor(this@BubbleManageActivity, R.color.background_card),
            ContextCompat.getColor(this@BubbleManageActivity, R.color.background_menu),
            UiCorner.actionRadius(this@BubbleManageActivity)
        )
        btnAdd.setOnClickListener { showAddActions() }
        root.applyUiBodyTypefaceDeep(uiTypeface())
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        containerMenuItem = menu.add(0, MENU_CONTAINER, 0, R.string.s3_bucket).apply {
            setIcon(R.drawable.ic_outline_cloud_24)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        menu.add(0, MENU_HELP, 1, R.string.help).apply {
            setIcon(R.drawable.ic_help)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        }
        updateContainerMenu()
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_CONTAINER -> {
                showContainerSelector()
                true
            }
            MENU_HELP -> {
                showBubbleHelp()
                true
            }
            else -> super.onCompatOptionsItemSelected(item)
        }
    }

    private fun updateContainerMenu() {
        val item = containerMenuItem ?: return
        val containers = AppCloudStorage.listContainers().filter { it.enabled }
        if (AppCloudStorage.type != CloudStorageType.S3) {
            cloudContainerId = containers.firstOrNull()?.id
            item.isVisible = false
            return
        }
        cloudContainerId = AppCloudStorage.selectedContainer(CLOUD_SCOPE)?.id ?: containers.firstOrNull()?.id
        item.isVisible = true
        item.title = containers.firstOrNull { it.id == cloudContainerId }
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
            selector(getString(R.string.s3_bucket), containers.map(AppCloudStorage::containerDisplayLabel)) { _, index ->
                val container = containers[index]
                if (container.id == selected) return@selector
                AppCloudStorage.selectContainer(CLOUD_SCOPE, container.id)
                cloudContainerId = container.id
                updateContainerMenu()
                loadPackages()
            }
        }
    }

    private fun loadPackages() {
        lifecycleScope.launch {
            kotlin.runCatching {
                BubblePackageManager.loadEntries(cloudContainerId, CLOUD_SCOPE)
            }.onSuccess {
                adapter.items = it
                binding.tvSummary.text = getString(R.string.bubble_manage_summary)
            }.onFailure {
                binding.tvSummary.text = it.localizedMessage
            }
        }
    }

    private fun showAddActions() {
        selector(getString(R.string.add), listOf("手动创建", "导入 zip")) { _, index ->
            when (index) {
                0 -> showEditDialog(null)
                1 -> importPackage.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("zip")
                    otherActions = arrayListOf(SelectItem(importFromNet, -1))
                }
            }
        }
    }

    private fun showActions(entry: BubblePackageManager.Entry) {
        val actions = buildList {
            add(Action.APPLY)
            if (entry.source != BubblePackageManager.Source.BUILTIN) {
                if (entry.source != BubblePackageManager.Source.REMOTE) add(Action.EDIT)
                if (entry.source != BubblePackageManager.Source.REMOTE) add(Action.EXPORT)
                if (entry.source != BubblePackageManager.Source.REMOTE) add(Action.UPLOAD)
                if (entry.source != BubblePackageManager.Source.LOCAL) add(Action.DOWNLOAD)
                if (entry.source != BubblePackageManager.Source.REMOTE) add(Action.DELETE_LOCAL)
                if (entry.source != BubblePackageManager.Source.LOCAL) add(Action.DELETE_REMOTE)
                if (entry.source == BubblePackageManager.Source.BOTH) add(Action.DELETE_BOTH)
            }
        }
        selector(entry.config.name, actions.map { it.title }) { _, index ->
            when (actions[index]) {
                Action.APPLY -> {
                    applyEntry(entry)
                }
                Action.EDIT -> showEditDialog(entry)
                Action.EXPORT -> exportZip(entry)
                Action.UPLOAD -> enqueueUpload(entry)
                Action.DOWNLOAD -> runAction(refreshReading = false) {
                    BubblePackageManager.download(entry, cloudContainerId, CLOUD_SCOPE)
                }
                Action.DELETE_LOCAL -> confirmDelete { BubblePackageManager.deleteLocal(entry) }
                Action.DELETE_REMOTE -> confirmDelete { BubblePackageManager.deleteRemote(entry, cloudContainerId, CLOUD_SCOPE) }
                Action.DELETE_BOTH -> confirmDelete {
                    BubblePackageManager.deleteLocal(entry)
                    BubblePackageManager.deleteRemote(entry, cloudContainerId, CLOUD_SCOPE)
                }
            }
        }
    }

    private fun showEditDialog(entry: BubblePackageManager.Entry?) {
        if (entry?.source == BubblePackageManager.Source.BUILTIN || entry?.source == BubblePackageManager.Source.REMOTE) return
        editingConfig = (entry?.config ?: BubblePackageManager.builtinConfig().copy(
            name = "自定义段评气泡",
            dirName = "",
            updatedAt = System.currentTimeMillis()
        )).copy(dirName = entry?.dirName.orEmpty())
        val root = buildEditView()
        editingRoot = root
        alert(if (entry == null) R.string.add else R.string.edit) {
            customView { root }
            okButton {
                captureEditFields()
                val config = editingConfig ?: return@okButton
                val next = config.copy(
                    name = root.findViewWithTag<EditText>(TAG_NAME)?.text?.toString().orEmpty(),
                    dirName = entry?.dirName.orEmpty(),
                    svgTemplate = config.svgTemplate
                )
                runAction(refreshReading = entry?.dirName == BubblePackageManager.activeDirName()) {
                    BubblePackageManager.addOrUpdate(next, entry)
                }
            }
            cancelButton()
        }
    }

    private fun buildEditView(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(2, 2, 2, 4)
            populateEditView(this)
            applyUiBodyTypefaceDeep(this@BubbleManageActivity.uiTypeface())
        }
    }

    private fun populateEditView(root: LinearLayout) {
        val config = editingConfig ?: return
        root.addView(PackageManageUi.nameInput(this, config.name, "名称").apply { tag = TAG_NAME })
        root.addView(PackageManageUi.optionRow(this, "大小倍率", "%.1f".format(Locale.ROOT, config.sizeScale)) {
            showSizeScalePicker()
        })
        root.addView(colorRow("日间常规色", colorOrDefault(config.dayNormalColor, false), COLOR_DAY_NORMAL))
        root.addView(colorRow("日间强调色", colorOrDefault(config.dayEmphasisColor, true), COLOR_DAY_EMPHASIS))
        root.addView(colorRow("夜间常规色", colorOrDefault(config.nightNormalColor, false), COLOR_NIGHT_NORMAL))
        root.addView(colorRow("夜间强调色", colorOrDefault(config.nightEmphasisColor, true), COLOR_NIGHT_EMPHASIS))
        root.addView(
            PackageManageUi.optionRow(
                this,
                "SVG 模板",
                "点击编辑，支持 ${'$'}{color} 和 ${'$'}{num}"
            ) {
                openSvgEditor()
            }
        )
    }

    private fun captureEditFields() {
        val config = editingConfig ?: return
        val root = editingRoot ?: return
        editingConfig = config.copy(
            name = root.findViewWithTag<EditText>(TAG_NAME)?.text?.toString() ?: config.name,
            svgTemplate = config.svgTemplate
        )
    }

    private fun openSvgEditor() {
        captureEditFields()
        val svg = editingConfig?.svgTemplate.orEmpty()
        svgEditLauncher.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("text", svg)
            putExtra("title", "SVG 模板")
            putExtra("cursorPosition", svgCursorPosition.coerceIn(0, svg.length))
        })
    }

    private fun colorRow(title: String, value: String, target: Int): View {
        return PackageManageUi.optionRow(this, title, value.uppercase(Locale.ROOT), value.toColorInt()) {
            ColorPickerDialog.newBuilder()
                .setColor(value.toColorInt())
                .setShowAlphaSlider(false)
                .setDialogType(ColorPickerDialog.TYPE_CUSTOM)
                .setDialogId(target)
                .show(this)
        }
    }

    private fun showSizeScalePicker() {
        captureEditFields()
        val config = editingConfig ?: return
        NumberPickerDialog(this, isDecimalMode = true)
            .setTitle("大小倍率")
            .setMinValue((BubblePackageManager.MIN_SIZE_SCALE * 10).toInt())
            .setMaxValue((BubblePackageManager.MAX_SIZE_SCALE * 10).toInt())
            .setValue((config.sizeScale.coerceIn(BubblePackageManager.MIN_SIZE_SCALE, BubblePackageManager.MAX_SIZE_SCALE) * 10).toInt())
            .show {
                captureEditFields()
                val latest = editingConfig ?: config
                editingConfig = latest.copy(sizeScale = (it / 10f).coerceIn(BubblePackageManager.MIN_SIZE_SCALE, BubblePackageManager.MAX_SIZE_SCALE))
                refreshEditDialog()
            }
    }

    private fun refreshEditDialog() {
        val root = editingRoot ?: return
        root.removeAllViews()
        populateEditView(root)
    }
    private fun editText(text: String, hint: String, singleLine: Boolean): EditText {
        return EditText(this).apply {
            setText(text)
            this.hint = hint
            setSingleLine(singleLine)
            minLines = if (singleLine) 1 else 5
            applyUiInputStyle(this@BubbleManageActivity)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                if (singleLine) 46.dp else ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dp
            }
        }
    }

    private fun enqueueUpload(entry: BubblePackageManager.Entry) {
        val queued = WebDavTaskManager.enqueueUpload(
            key = "bubble_upload:${entry.dirName}",
            name = entry.config.name,
            type = WebDavTaskType.BUBBLE_PACKAGE_UPLOAD,
            runningMessage = "正在上传气泡"
        ) {
            BubblePackageManager.upload(entry, cloudContainerId, CLOUD_SCOPE)
        }
        toastOnUi(if (queued) R.string.cache_manage_upload_queued else R.string.cache_manage_webdav_task_duplicate)
    }

    private fun observeWebDavTasks() {
        lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                states.values
                    .filter { it.type == WebDavTaskType.BUBBLE_PACKAGE_UPLOAD }
                    .filter { it.status == WebDavTaskStatus.COMPLETED }
                    .forEach { state ->
                        if (handledWebDavTasks.add("${state.key}:${state.status}")) {
                            loadPackages()
                        }
                    }
            }
        }
    }

    private fun exportZip(entry: BubblePackageManager.Entry) {
        lifecycleScope.launch {
            kotlin.runCatching { BubblePackageManager.exportZip(entry) }
                .onSuccess { zip ->
                    exportPackage.launch {
                        mode = HandleFileContract.EXPORT
                        fileData = HandleFileContract.FileData(zip.name, zip, "application/zip")
                    }
                }
                .onFailure { toastOnUi(it.localizedMessage) }
        }
    }

    private fun importNetZipAlert() {
        alert("网络导入") {
            val input = EditText(this@BubbleManageActivity).apply { hint = "https://..." }
            customView { input }
            okButton {
                val url = input.text?.toString().orEmpty().trim()
                if (url.isNotEmpty()) importNetZip(url)
            }
            cancelButton()
        }
    }

    private fun importNetZip(url: String) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val file = withContext(Dispatchers.IO) {
                    externalFiles.getFile("bubbleImports", "import_${System.currentTimeMillis()}.zip").also { target ->
                        target.parentFile?.mkdirs()
                        okHttpClient.newCallResponseBody {
                            url(url)
                        }.use { body ->
                            FileOutputStream(target).use { output -> body.byteStream().copyTo(output) }
                        }
                    }
                }
                BubblePackageManager.importZip(file)
            }.onSuccess {
                toastOnUi(R.string.success)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun importZip(uri: Uri) {
        lifecycleScope.launch {
            kotlin.runCatching {
                val file = externalFiles.getFile("bubbleImports", "import_${System.currentTimeMillis()}.zip")
                file.parentFile?.mkdirs()
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(file).use { output -> input.copyTo(output) }
                } ?: throw IllegalArgumentException(getString(R.string.theme_zip_read_failed))
                BubblePackageManager.importZip(file)
            }.onSuccess {
                toastOnUi(R.string.success)
                loadPackages()
            }.onFailure {
                toastOnUi(it.localizedMessage)
            }
        }
    }

    private fun confirmDelete(block: suspend () -> Unit) {
        alert(getString(R.string.delete), getString(R.string.sure_del)) {
            yesButton { runAction(block = block) }
            noButton()
        }
    }

    private fun runAction(refreshReading: Boolean = true, block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    toastOnUi(R.string.success)
                    adapter.refreshActiveDirName()
                    if (refreshReading) notifyBubbleChanged()
                }
                .onFailure { toastOnUi(it.localizedMessage) }
            loadPackages()
        }
    }

    private fun notifyBubbleChanged() {
        ImageProvider.clear()
        postEvent(EventBus.UP_CONFIG, arrayListOf(5))
    }

    private fun previewBitmap(config: BubblePackageManager.Config) = runCatching {
        val color = when {
            AppConfig.isNightTheme -> config.nightNormalColor
            else -> config.dayNormalColor
        }?.takeIf { it.isNotBlank() } ?: BubblePackageManager.DEFAULT_NORMAL_COLOR
        val svg = config.svgTemplate
            .replace("\${color}", color)
            .replace("\${num}", "12")
        val side = BUBBLE_PREVIEW_BITMAP_DP.dp
        SvgUtils.createBitmap(ByteArrayInputStream(svg.toByteArray()), side, side)
    }.getOrNull()

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        private var activeDirName: String = BubblePackageManager.activeDirName()

        var items: List<BubblePackageManager.Entry> = emptyList()
            set(value) {
                val old = field
                val oldActiveDirName = activeDirName
                val newActiveDirName = BubblePackageManager.activeDirName()
                field = value
                activeDirName = newActiveDirName
                DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = old.size
                    override fun getNewListSize() = value.size
                    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                        return old[oldItemPosition].dirName == value[newItemPosition].dirName
                    }
                    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                        val oldEntry = old[oldItemPosition]
                        val newEntry = value[newItemPosition]
                        return oldEntry == newEntry &&
                            (oldEntry.dirName == oldActiveDirName) == (newEntry.dirName == newActiveDirName)
                    }
                }).dispatchUpdatesTo(this)
            }

        fun refreshActiveDirName() {
            val oldActiveDirName = activeDirName
            val newActiveDirName = BubblePackageManager.activeDirName()
            if (oldActiveDirName == newActiveDirName) return
            activeDirName = newActiveDirName
            val oldIndex = items.indexOfFirst { it.dirName == oldActiveDirName }
            val newIndex = items.indexOfFirst { it.dirName == newActiveDirName }
            if (oldIndex >= 0) notifyItemChanged(oldIndex)
            if (newIndex >= 0 && newIndex != oldIndex) notifyItemChanged(newIndex)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(ItemThemePackageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        inner class Holder(private val itemBinding: ItemThemePackageBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(entry: BubblePackageManager.Entry) = itemBinding.run {
                val active = activeDirName == entry.dirName
                root.background = UiCorner.panelRounded(
                    this@BubbleManageActivity,
                    ContextCompat.getColor(this@BubbleManageActivity, R.color.background_card),
                    UiCorner.panelRadius(this@BubbleManageActivity)
                )
                root.minimumHeight = BUBBLE_ITEM_MIN_HEIGHT_DP.dp
                cardPreview.layoutParams = cardPreview.layoutParams.apply {
                    width = BUBBLE_PREVIEW_BOX_DP.dp
                    height = BUBBLE_PREVIEW_BOX_DP.dp
                }
                cardPreview.radius = UiCorner.panelRadius(this@BubbleManageActivity)
                tvName.text = entry.config.name
                tvInfo.text = buildString {
                    if (active) append("已应用 · ")
                    append(sourceLabel(entry.source))
                    append(" · ")
                    val time = maxOf(entry.config.updatedAt, entry.remoteUpdatedAt)
                    append(if (time > 0L) dateFormat.format(Date(time)) else "内置")
                }
                tvSource.visibility = View.GONE
                tvName.applyUiSectionTitleStyle(this@BubbleManageActivity)
                tvInfo.applyUiLabelStyle(this@BubbleManageActivity)
                tvInfo.setTextColor(secondaryTextColor)
                ivPreview.setBackgroundColor(Color.TRANSPARENT)
                ivPreview.scaleType = ImageView.ScaleType.CENTER_INSIDE
                ivPreview.setImageBitmap(previewBitmap(entry.config))
                if (ivPreview.drawable == null) {
                    ivPreview.setImageDrawable(ColorDrawable(Color.TRANSPARENT))
                }
                listOf(btnApply, btnEdit, btnMore).forEach {
                    it.background = UiCorner.actionSelector(
                        Color.TRANSPARENT,
                        ContextCompat.getColor(this@BubbleManageActivity, R.color.background_menu),
                        UiCorner.actionRadius(this@BubbleManageActivity)
                    )
                    it.setTextColor(primaryTextColor)
                    it.typeface = this@BubbleManageActivity.uiTypeface()
                }
                btnApply.text = if (active) "已应用" else getString(R.string.theme_apply)
                btnApply.setTextColor(accentColor)
                btnEdit.text = getString(R.string.edit)
                btnEdit.visibility = if (
                    entry.source == BubblePackageManager.Source.LOCAL ||
                    entry.source == BubblePackageManager.Source.BOTH
                ) View.VISIBLE else View.GONE
                btnApply.setOnClickListener {
                    applyEntry(entry)
                }
                btnEdit.setOnClickListener {
                    showEditDialog(entry)
                }
                btnMore.setOnClickListener { showActions(entry) }
                root.setOnClickListener { showActions(entry) }
            }
        }
    }

    private fun sourceLabel(source: BubblePackageManager.Source): String {
        return when (source) {
            BubblePackageManager.Source.BUILTIN -> "内置"
            BubblePackageManager.Source.LOCAL -> getString(R.string.theme_source_local)
            BubblePackageManager.Source.REMOTE -> getString(R.string.theme_source_remote)
            BubblePackageManager.Source.BOTH -> getString(R.string.theme_source_both)
        }
    }

    private fun applyEntry(entry: BubblePackageManager.Entry) {
        runAction {
            val localEntry = if (entry.source == BubblePackageManager.Source.REMOTE) {
                BubblePackageManager.download(entry, cloudContainerId, CLOUD_SCOPE)
            } else {
                entry
            }
            BubblePackageManager.apply(localEntry)
        }
    }

    override fun onColorSelected(dialogId: Int, color: Int) {
        captureEditFields()
        val config = editingConfig ?: return
        val hex = String.format(Locale.ROOT, "#%06X", color and 0x00FFFFFF)
        editingConfig = when (dialogId) {
            COLOR_DAY_NORMAL -> config.copy(dayNormalColor = hex)
            COLOR_DAY_EMPHASIS -> config.copy(dayEmphasisColor = hex)
            COLOR_NIGHT_NORMAL -> config.copy(nightNormalColor = hex)
            COLOR_NIGHT_EMPHASIS -> config.copy(nightEmphasisColor = hex)
            else -> config
        }
        refreshEditDialog()
    }

    override fun onDialogDismissed(dialogId: Int) = Unit

    private fun colorOrDefault(value: String?, emphasis: Boolean): String {
        val fallback = if (emphasis) {
            BubblePackageManager.DEFAULT_EMPHASIS_COLOR
        } else {
            BubblePackageManager.DEFAULT_NORMAL_COLOR
        }
        val normalized = value?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { if (it.startsWith("#")) it else "#$it" }
            ?: fallback
        return runCatching {
            normalized.toColorInt()
            normalized
        }.getOrDefault(fallback)
    }

    private fun showBubbleHelp() {
        alert(getString(R.string.help), """
            段评气泡用于把规则里的 dp 图片转成原生 SVG 气泡。

            接入格式：
            <img src="dp:12,{&quot;pclick&quot;:&quot;...&quot;,&quot;status&quot;:&quot;normal&quot;}">

            dp: 后面的数字会替换 SVG 模板里的 ${'$'}{num}。
            status 可选：normal 使用常规色，emphasis 使用强调色；不写 status 时默认 normal。
            SVG 模板支持 ${'$'}{color} 和 ${'$'}{num} 两个占位。
            内置气泡只读；需要自定义时请通过添加或导入创建新气泡。
        """.trimIndent()) {
            okButton()
        }
    }

    private enum class Action(val title: String) {
        APPLY("应用"),
        EDIT("编辑"),
        EXPORT("导出 zip"),
        UPLOAD("上传"),
        DOWNLOAD("下载"),
        DELETE_LOCAL("删除本地"),
        DELETE_REMOTE("删除云端"),
        DELETE_BOTH("同时删除")
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        private const val CLOUD_SCOPE = "bubble"
        private const val MENU_CONTAINER = 0x6801
        private const val MENU_HELP = 0x6802
        private const val COLOR_DAY_NORMAL = 0x6811
        private const val COLOR_DAY_EMPHASIS = 0x6812
        private const val COLOR_NIGHT_NORMAL = 0x6813
        private const val COLOR_NIGHT_EMPHASIS = 0x6814
        private const val TAG_NAME = "name"
        private const val BUBBLE_PREVIEW_BOX_DP = 64
        private const val BUBBLE_PREVIEW_BITMAP_DP = 128
        private const val BUBBLE_ITEM_MIN_HEIGHT_DP = 86
    }
}
