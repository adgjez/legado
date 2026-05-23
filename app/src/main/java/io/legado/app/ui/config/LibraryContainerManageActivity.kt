package io.legado.app.ui.config

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.databinding.ActivityS3ContainerManageBinding
import io.legado.app.databinding.DialogS3ContainerEditBinding
import io.legado.app.databinding.ItemS3ContainerBinding
import io.legado.app.help.book.library.LibraryCloudBackend
import io.legado.app.help.book.library.LibraryContainerConfig
import io.legado.app.help.book.library.LibraryContainerManager
import io.legado.app.lib.cloud.S3Config
import io.legado.app.lib.cloud.S3Container
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.widget.SourceSelectDialog
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

class LibraryContainerManageActivity : BaseActivity<ActivityS3ContainerManageBinding>() {

    override val binding by viewBinding(ActivityS3ContainerManageBinding::inflate)

    private val adapter by lazy { Adapter() }
    private val waitDialog by lazy { WaitDialog(this) }
    private var editingSourceUrls: MutableSet<String> = mutableSetOf()
    private var editingSourceSummary: TextView? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = "书库容器"
        binding.tvSummary.text = "书库容器只用于同步阅读章节缓存，不参与备份、主题、气泡或缓存包同步。阅读时会先读取目录索引，只有命中缓存章节才请求正文。"
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        (binding.recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.btnAdd.text = "添加书库容器"
        binding.btnAdd.background = UiCorner.actionSelector(
            ContextCompat.getColor(this, R.color.background_card),
            ContextCompat.getColor(this, R.color.background_menu),
            UiCorner.actionRadius(this)
        )
        binding.btnAdd.setOnClickListener { showEditDialog(null) }
        binding.tvSummary.applyUiLabelStyle(this)
        binding.tvSummary.setTextColor(secondaryTextColor)
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        waitDialog.dismiss()
    }

    private fun reload() {
        adapter.setItems(LibraryContainerManager.containers())
    }

    private fun showEditDialog(item: LibraryContainerConfig?) {
        editingSourceUrls = item?.sourceUrls.orEmpty().toMutableSet()
        val dialogBinding = DialogS3ContainerEditBinding.inflate(LayoutInflater.from(this))
        dialogBinding.bind(item)
        val settingsView = buildLibrarySettings(item)
        dialogBinding.layoutAdvanced.addView(settingsView)
        dialogBinding.tvAdvancedToggle.setOnClickListener {
            val show = !dialogBinding.layoutAdvanced.isVisible
            dialogBinding.layoutAdvanced.isVisible = show
            dialogBinding.tvAdvancedToggle.setText(
                if (show) R.string.s3_container_advanced_hide else R.string.s3_container_advanced_show
            )
        }
        dialogBinding.layoutAdvanced.isVisible = true
        dialogBinding.tvAdvancedToggle.setText(R.string.s3_container_advanced_hide)
        alert(if (item == null) "添加书库容器" else "编辑书库容器") {
            customView { dialogBinding.root }
            okButton {
                saveDialogItem(item, dialogBinding)?.let { saved ->
                    if (item == null) refreshCapacity(saved, showWait = false)
                }
            }
            cancelButton()
        }
    }

    private fun DialogS3ContainerEditBinding.bind(item: LibraryContainerConfig?) {
        val container = item?.container
        editName.setText(container?.name.orEmpty())
        editEndpoint.setText(container?.endpoint.orEmpty())
        editBucket.setText(container?.bucket.orEmpty())
        editPrefix.setText(container?.prefix ?: "Library")
        editRegion.setText(container?.region ?: "auto")
        editAccessKey.setText(container?.accessKey.orEmpty())
        editSecretKey.setText(container?.secretKey.orEmpty())
        editSessionToken.setText(container?.sessionToken.orEmpty())
        editCapacity.setText(capacityMbToGbText(container?.capacityMb ?: DEFAULT_CAPACITY_MB))
        cbPathStyle.isChecked = container?.pathStyle ?: true
        cbEnabled.isChecked = container?.enabled ?: true
    }

    private fun buildLibrarySettings(item: LibraryContainerConfig?): LinearLayout {
        val context = this
        val passwordInput = PackageManageUi.nameInput(context, item?.password.orEmpty(), "书库加密密码，可留空").apply {
            tag = TAG_PASSWORD
        }
        val minUploadInput = PackageManageUi.nameInput(
            context,
            (item?.minUploadChars ?: 1500).toString(),
            "最少自动上传字数，默认1500，0为不过滤"
        ).apply {
            tag = TAG_MIN_UPLOAD_CHARS
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        editingSourceSummary = TextView(context).apply {
            textSize = 13f
            setTextColor(secondaryTextColor)
            applyUiLabelStyle(context)
            setPadding(0, 6.dp, 0, 0)
        }
        updateSourceSummary()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = "书库设置"
                applyUiSectionTitleStyle(context)
                setPadding(0, 12.dp, 0, 6.dp)
            })
            addView(passwordInput)
            addView(minUploadInput)
            addView(TextView(context).apply {
                text = "读取策略"
                applyUiLabelStyle(context)
                setPadding(0, 10.dp, 0, 4.dp)
            })
            addView(PackageManageUi.optionRow(context, "指定书源", "选择允许同步的书源") {
                showSourcePicker()
            })
            addView(editingSourceSummary)
        }
    }

    private fun updateSourceSummary() {
        editingSourceSummary?.text = if (editingSourceUrls.isEmpty()) {
            "未指定书源，不会自动上传或回退云端章节"
        } else {
            "已指定 ${editingSourceUrls.size} 个书源"
        }
    }

    private fun showSourcePicker() {
        lifecycleScope.launch {
            val sources = withContext(Dispatchers.IO) { appDb.bookSourceDao.allEnabled }
            SourceSelectDialog.show(
                context = this@LibraryContainerManageActivity,
                title = "指定书源",
                items = sources,
                selectedKey = null,
                displayName = ::sourceName,
                searchTexts = { listOf(it.bookSourceName, it.bookSourceUrl, it.bookSourceGroup.orEmpty()) },
                itemKey = { it.bookSourceUrl }
            ) { source ->
                if (!editingSourceUrls.add(source.bookSourceUrl)) {
                    editingSourceUrls.remove(source.bookSourceUrl)
                }
                updateSourceSummary()
            }
        }
    }

    private fun sourceName(source: BookSource): String {
        val group = source.bookSourceGroup?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        val checked = if (editingSourceUrls.contains(source.bookSourceUrl)) "✓ " else ""
        return "$checked${source.bookSourceName}$group"
    }

    private fun saveDialogItem(oldItem: LibraryContainerConfig?, binding: DialogS3ContainerEditBinding): LibraryContainerConfig? {
        val parsed = S3Config.parseAddress(
            binding.editEndpoint.text?.toString().orEmpty(),
            binding.editBucket.text?.toString().orEmpty(),
            binding.editRegion.text?.toString().orEmpty(),
            binding.cbPathStyle.isChecked
        )
        val capacityMb = gbTextToCapacityMb(binding.editCapacity.text?.toString().orEmpty())
        val usedBytes = oldItem?.container?.usedBytes?.coerceAtLeast(0L) ?: 0L
        if (parsed.endpoint.isBlank() || parsed.bucket.isBlank()) {
            toastOnUi(R.string.s3_container_endpoint_bucket_required)
            return null
        }
        if (binding.editAccessKey.text?.toString().orEmpty().isBlank()
            || binding.editSecretKey.text?.toString().orEmpty().isBlank()
        ) {
            toastOnUi(R.string.s3_container_key_required)
            return null
        }
        val container = S3Container(
            id = oldItem?.id ?: S3Container.newId(),
            name = binding.editName.text?.toString()?.trim().orEmpty().ifBlank { parsed.bucket },
            endpoint = parsed.endpoint,
            bucket = parsed.bucket,
            prefix = binding.editPrefix.text?.toString()?.trim().orEmpty().ifBlank { "Library" },
            region = parsed.region.ifBlank { "auto" },
            accessKey = binding.editAccessKey.text?.toString()?.trim().orEmpty(),
            secretKey = binding.editSecretKey.text?.toString()?.trim().orEmpty(),
            sessionToken = binding.editSessionToken.text?.toString()?.trim().orEmpty().ifBlank { null },
            pathStyle = parsed.pathStyle,
            capacityMb = capacityMb,
            usedBytes = if (capacityMb > 0) usedBytes.coerceAtMost(mbToBytes(capacityMb)) else usedBytes,
            lastRefreshTime = oldItem?.container?.lastRefreshTime ?: 0L,
            isFull = capacityMb > 0 && usedBytes >= mbToBytes(capacityMb),
            enabled = binding.cbEnabled.isChecked
        )
        val password = binding.root.findViewWithTag<android.widget.EditText>(TAG_PASSWORD)
            ?.text?.toString()?.takeIf { it.isNotBlank() }
        val minUploadChars = binding.root.findViewWithTag<android.widget.EditText>(TAG_MIN_UPLOAD_CHARS)
            ?.text?.toString()?.toIntOrNull()?.coerceAtLeast(0)
            ?: 1500
        val saved = LibraryContainerManager.upsert(
            LibraryContainerConfig(
                container = container,
                password = password,
                sourceUrls = editingSourceUrls.toSet(),
                minUploadChars = minUploadChars,
            )
        )
        reload()
        return saved
    }

    private fun showActions(item: LibraryContainerConfig) {
        val actions = listOf(
            Action.EDIT,
            Action.TEST,
            Action.REFRESH,
            Action.SET_DEFAULT,
            if (item.container.enabled) Action.DISABLE else Action.ENABLE,
            Action.DELETE
        )
        selector(LibraryContainerManager.displayLabel(item), actions.map { it.title }) { _, index ->
            when (actions[index]) {
                Action.EDIT -> showEditDialog(item)
                Action.TEST -> testConnection(item)
                Action.REFRESH -> refreshCapacity(item)
                Action.SET_DEFAULT -> {
                    if (!item.container.enabled) {
                        toastOnUi(R.string.s3_container_disabled)
                        return@selector
                    }
                    LibraryContainerManager.select(item.id)
                    toastOnUi(R.string.s3_container_set_default_success)
                    reload()
                }
                Action.ENABLE -> updateItem(item.copy(container = item.container.copy(enabled = true, isFull = false)))
                Action.DISABLE -> updateItem(item.copy(container = item.container.copy(enabled = false)))
                Action.DELETE -> confirmDelete(item)
            }
        }
    }

    private fun updateItem(item: LibraryContainerConfig) {
        LibraryContainerManager.upsert(item)
        reload()
    }

    private fun confirmDelete(item: LibraryContainerConfig) {
        alert("删除书库容器") {
            setMessage("确认删除 ${LibraryContainerManager.displayLabel(item)}？")
            okButton {
                LibraryContainerManager.delete(item.id)
                reload()
            }
            cancelButton()
        }
    }

    private fun testConnection(item: LibraryContainerConfig) {
        waitDialog.setText(R.string.loading)
        waitDialog.show()
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { LibraryCloudBackend(item).check() }
            }.onSuccess {
                toastOnUi(R.string.s3_container_test_success)
            }.onFailure {
                toastOnUi(getString(R.string.s3_container_test_failed, it.localizedMessage.orEmpty()))
            }
            waitDialog.dismiss()
        }
    }

    private fun refreshCapacity(item: LibraryContainerConfig, showWait: Boolean = true) {
        if (showWait) {
            waitDialog.setText(R.string.loading)
            waitDialog.show()
        }
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { LibraryCloudBackend(item).refreshUsage() }
            }.onSuccess {
                LibraryContainerManager.updateUsage(item.id, it)
                toastOnUi(R.string.s3_container_capacity_refreshed)
                reload()
            }.onFailure {
                toastOnUi(it.localizedMessage.orEmpty())
            }
            if (showWait) waitDialog.dismiss()
        }
    }

    private inner class Adapter :
        RecyclerAdapter<LibraryContainerConfig, ItemS3ContainerBinding>(this@LibraryContainerManageActivity) {

        override fun getViewBinding(parent: ViewGroup): ItemS3ContainerBinding {
            return ItemS3ContainerBinding.inflate(inflater, parent, false).apply {
                root.background = UiCorner.panelRounded(
                    root.context,
                    ContextCompat.getColor(root.context, R.color.background_card),
                    UiCorner.panelRadius(root.context)
                )
                btnMore.background = UiCorner.actionSelector(
                    ContextCompat.getColor(root.context, R.color.background_card),
                    ContextCompat.getColor(root.context, R.color.background_menu),
                    UiCorner.actionRadius(root.context)
                )
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemS3ContainerBinding,
            item: LibraryContainerConfig,
            payloads: MutableList<Any>
        ) = binding.run {
            val container = item.container
            val isDefault = LibraryContainerManager.selectedId() == item.id
            tvName.text = LibraryContainerManager.displayLabel(item)
            tvSelected.isVisible = isDefault
            tvSelected.setTextColor(ContextCompat.getColor(this@LibraryContainerManageActivity, R.color.accent))
            tvPath.text = "${container.bucket}/${container.prefix.trim('/')}"
            val capacityMb = container.capacityMb.coerceAtLeast(0)
            val usedBytes = container.usedBytes.coerceAtLeast(0)
            tvCapacity.text = if (capacityMb > 0) {
                val capacityBytes = mbToBytes(capacityMb)
                getString(
                    R.string.s3_container_capacity_line,
                    formatBytes(capacityBytes),
                    formatBytes(usedBytes),
                    formatBytes((capacityBytes - usedBytes).coerceAtLeast(0)),
                    if (container.isFull) getString(R.string.yes) else getString(R.string.no)
                )
            } else {
                getString(R.string.s3_container_capacity_unlimited_line, formatBytes(usedBytes))
            }
            val minUpload = if (item.minUploadChars > 0) "最少${item.minUploadChars}字" else "不过滤短章"
            tvState.text = "状态：${if (container.enabled) "启用" else "禁用"} · 书源优先 · ${item.sourceUrls.size} 个书源 · $minUpload"
            tvName.applyUiSectionTitleStyle(this@LibraryContainerManageActivity)
            tvPath.applyUiLabelStyle(this@LibraryContainerManageActivity)
            tvCapacity.applyUiLabelStyle(this@LibraryContainerManageActivity)
            tvState.applyUiLabelStyle(this@LibraryContainerManageActivity)
            listOf(tvPath, tvCapacity, tvState).forEach { it.setTextColor(secondaryTextColor) }
            btnMore.setOnClickListener { showActions(item) }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemS3ContainerBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.bindingAdapterPosition - getHeaderCount())?.let { showEditDialog(it) }
            }
        }
    }

    private enum class Action(val title: String) {
        EDIT("编辑"),
        TEST("测试连接"),
        REFRESH("刷新容量"),
        SET_DEFAULT("设为默认"),
        ENABLE("启用"),
        DISABLE("禁用"),
        DELETE("删除")
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val DEFAULT_CAPACITY_MB = 5L * 1024L
        const val TAG_PASSWORD = "library_password"
        const val TAG_MIN_UPLOAD_CHARS = "library_min_upload_chars"

        fun mbToBytes(value: Long): Long = value.coerceAtLeast(0L) * 1024L * 1024L

        fun gbTextToCapacityMb(value: String): Long {
            val gb = value.trim().toDoubleOrNull() ?: return 0L
            if (gb <= 0.0) return 0L
            return max(ceil(gb * 1024.0).toLong(), 1L)
        }

        fun capacityMbToGbText(value: Long): String {
            if (value <= 0L) return ""
            val gb = value / 1024.0
            return if (value % 1024L == 0L) {
                (value / 1024L).toString()
            } else {
                String.format(Locale.US, "%.2f", gb).trimEnd('0').trimEnd('.')
            }
        }

        fun formatBytes(value: Long): String {
            val bytes = value.coerceAtLeast(0L).toDouble()
            val gb = bytes / 1024.0 / 1024.0 / 1024.0
            return if (gb >= 1.0) {
                "${formatDecimal(gb)} GB"
            } else {
                val mb = bytes / 1024.0 / 1024.0
                "${max(mb.toLong(), 0L)} MB"
            }
        }

        fun formatDecimal(value: Double): String {
            return String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
        }
    }
}
