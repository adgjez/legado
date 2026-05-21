package io.legado.app.ui.config

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
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.ItemThemePackageBinding
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BubblePackageManager
import io.legado.app.lib.cloud.CloudStorageType
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
import io.legado.app.ui.book.cache.WebDavTaskManager
import io.legado.app.ui.book.cache.WebDavTaskStatus
import io.legado.app.ui.book.cache.WebDavTaskType
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
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

class BubbleManageActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private val adapter = Adapter()
    private var cloudContainerId: String? = null
    private var containerMenuItem: MenuItem? = null
    private val handledWebDavTasks = mutableSetOf<String>()
    private val dateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    private val importPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let(::importZip)
    }
    private val exportPackage = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { toastOnUi(R.string.export_success) }
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
        updateContainerMenu()
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_CONTAINER -> {
                showContainerSelector()
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
                }
            }
        }
    }

    private fun showActions(entry: BubblePackageManager.Entry) {
        val actions = buildList {
            add(Action.APPLY)
            if (entry.source == BubblePackageManager.Source.BUILTIN) {
                add(Action.COPY)
            } else {
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
                Action.COPY -> runAction { BubblePackageManager.copyBuiltin() }
                Action.EDIT -> showEditDialog(entry)
                Action.EXPORT -> exportZip(entry)
                Action.UPLOAD -> enqueueUpload(entry)
                Action.DOWNLOAD -> runAction { BubblePackageManager.download(entry, cloudContainerId, CLOUD_SCOPE) }
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
        if (entry?.source == BubblePackageManager.Source.BUILTIN) return
        val config = entry?.config ?: BubblePackageManager.builtinConfig().copy(
            name = "自定义段评气泡",
            dirName = "",
            updatedAt = System.currentTimeMillis()
        )
        val root = buildEditView(config)
        alert(if (entry == null) R.string.add else R.string.edit) {
            customView { root }
            okButton {
                val views = root.tag as EditViews
                val next = BubblePackageManager.Config(
                    name = views.name.text?.toString().orEmpty(),
                    dirName = entry?.dirName.orEmpty(),
                    svgTemplate = views.svg.text?.toString().orEmpty(),
                    sizeScale = views.size.text?.toString()?.toFloatOrNull() ?: 1f,
                    dayNormalColor = views.dayNormal.text?.toString(),
                    dayEmphasisColor = views.dayEmphasis.text?.toString(),
                    nightNormalColor = views.nightNormal.text?.toString(),
                    nightEmphasisColor = views.nightEmphasis.text?.toString()
                )
                runAction { BubblePackageManager.addOrUpdate(next, entry) }
            }
            cancelButton()
        }
    }

    private fun buildEditView(config: BubblePackageManager.Config): LinearLayout {
        val name = editText(config.name, "名称", singleLine = true)
        val size = editText(config.sizeScale.toString(), "大小倍率 0.5-1.5", singleLine = true)
        val dayNormal = editText(config.dayNormalColor.orEmpty(), "日间常规色，空则红色", singleLine = true)
        val dayEmphasis = editText(config.dayEmphasisColor.orEmpty(), "日间强调色，空则红色", singleLine = true)
        val nightNormal = editText(config.nightNormalColor.orEmpty(), "夜间常规色，空则红色", singleLine = true)
        val nightEmphasis = editText(config.nightEmphasisColor.orEmpty(), "夜间强调色，空则红色", singleLine = true)
        val svg = editText(config.svgTemplate, "SVG 模板，支持 \${color} 和 \${num}", singleLine = false)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(2, 2, 2, 4)
            addView(name)
            addView(size)
            addView(dayNormal)
            addView(dayEmphasis)
            addView(nightNormal)
            addView(nightEmphasis)
            addView(svg)
            tag = EditViews(name, size, dayNormal, dayEmphasis, nightNormal, nightEmphasis, svg)
        }
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
                        showUploadUrl = false
                        fileData = HandleFileContract.FileData(zip.name, zip, "application/zip")
                    }
                }
                .onFailure { toastOnUi(it.localizedMessage) }
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
            yesButton { runAction(block) }
            noButton()
        }
    }

    private fun runAction(block: suspend () -> Unit) {
        lifecycleScope.launch {
            kotlin.runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess { toastOnUi(R.string.success) }
                .onFailure { toastOnUi(it.localizedMessage) }
            loadPackages()
        }
    }

    private fun previewBitmap(config: BubblePackageManager.Config) = runCatching {
        val color = when {
            AppConfig.isNightTheme -> config.nightNormalColor
            else -> config.dayNormalColor
        }?.takeIf { it.isNotBlank() } ?: BubblePackageManager.DEFAULT_COLOR
        val svg = config.svgTemplate
            .replace("\${color}", color)
            .replace("\${num}", "12")
        SvgUtils.createBitmap(ByteArrayInputStream(svg.toByteArray()), 128, 128)
    }.getOrNull()

    private inner class Adapter : RecyclerView.Adapter<Adapter.Holder>() {

        var items: List<BubblePackageManager.Entry> = emptyList()
            set(value) {
                val old = field
                field = value
                DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                    override fun getOldListSize() = old.size
                    override fun getNewListSize() = value.size
                    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                        return old[oldItemPosition].dirName == value[newItemPosition].dirName
                    }
                    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                        return old[oldItemPosition] == value[newItemPosition]
                    }
                }).dispatchUpdatesTo(this)
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
                val active = BubblePackageManager.activeDirName() == entry.dirName
                root.background = UiCorner.panelRounded(
                    this@BubbleManageActivity,
                    ContextCompat.getColor(this@BubbleManageActivity, R.color.background_card),
                    UiCorner.panelRadius(this@BubbleManageActivity)
                )
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
                btnEdit.text = if (entry.source == BubblePackageManager.Source.BUILTIN) "复制" else getString(R.string.edit)
                btnEdit.visibility = if (entry.source == BubblePackageManager.Source.REMOTE) View.GONE else View.VISIBLE
                btnApply.setOnClickListener {
                    applyEntry(entry)
                }
                btnEdit.setOnClickListener {
                    if (entry.source == BubblePackageManager.Source.BUILTIN) {
                        runAction { BubblePackageManager.copyBuiltin() }
                    } else {
                        showEditDialog(entry)
                    }
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

    private data class EditViews(
        val name: EditText,
        val size: EditText,
        val dayNormal: EditText,
        val dayEmphasis: EditText,
        val nightNormal: EditText,
        val nightEmphasis: EditText,
        val svg: EditText
    )

    private enum class Action(val title: String) {
        APPLY("应用"),
        COPY("复制为自定义"),
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
    }
}
