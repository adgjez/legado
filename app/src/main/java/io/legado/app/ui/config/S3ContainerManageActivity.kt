package io.legado.app.ui.config

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ActivityS3ContainerManageBinding
import io.legado.app.databinding.DialogS3ContainerEditBinding
import io.legado.app.databinding.ItemS3ContainerBinding
import io.legado.app.help.AppCloudStorage
import io.legado.app.lib.cloud.S3CloudStorageBackend
import io.legado.app.lib.cloud.S3Config
import io.legado.app.lib.cloud.S3Container
import io.legado.app.lib.cloud.S3ContainerScope
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

class S3ContainerManageActivity : BaseActivity<ActivityS3ContainerManageBinding>() {

    override val binding by viewBinding(ActivityS3ContainerManageBinding::inflate)

    private val adapter by lazy { Adapter() }
    private val waitDialog by lazy { WaitDialog(this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        (binding.recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
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
        adapter.setItems(AppCloudStorage.listContainers())
    }

    private fun showEditDialog(item: S3Container? = null) {
        val dialogBinding = DialogS3ContainerEditBinding.inflate(LayoutInflater.from(this))
        dialogBinding.bind(item)
        var applyingAddress = false
        dialogBinding.editAddress.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (applyingAddress) return
                val text = s?.toString().orEmpty()
                if (text.isBlank()) return
                val parsed = S3Config.parseAddress(
                    text,
                    dialogBinding.editBucket.text?.toString().orEmpty(),
                    dialogBinding.editRegion.text?.toString().orEmpty(),
                    dialogBinding.cbPathStyle.isChecked
                )
                applyingAddress = true
                dialogBinding.editEndpoint.setText(parsed.endpoint)
                if (parsed.bucket.isNotBlank()) dialogBinding.editBucket.setText(parsed.bucket)
                if (parsed.region.isNotBlank()) dialogBinding.editRegion.setText(parsed.region)
                dialogBinding.cbPathStyle.isChecked = parsed.pathStyle
                applyingAddress = false
            }
        })
        alert(if (item == null) R.string.s3_container_add else R.string.s3_container_edit) {
            customView { dialogBinding.root }
            okButton {
                saveDialogItem(item, dialogBinding)
            }
            cancelButton()
        }
    }

    private fun DialogS3ContainerEditBinding.bind(item: S3Container?) {
        editName.setText(item?.name.orEmpty())
        editEndpoint.setText(item?.endpoint.orEmpty())
        editBucket.setText(item?.bucket.orEmpty())
        editPrefix.setText(item?.prefix ?: "legado")
        editRegion.setText(item?.region ?: "auto")
        editAccessKey.setText(item?.accessKey.orEmpty())
        editSecretKey.setText(item?.secretKey.orEmpty())
        editSessionToken.setText(item?.sessionToken.orEmpty())
        editCapacity.setText((item?.capacityMb ?: 1024L).coerceAtLeast(0L).toString())
        editUsed.setText(bytesToMb(item?.usedBytes ?: 0L).toString())
        cbPathStyle.isChecked = item?.pathStyle ?: true
        cbEnabled.isChecked = item?.enabled ?: true
    }

    private fun saveDialogItem(oldItem: S3Container?, binding: DialogS3ContainerEditBinding) {
        val parsed = S3Config.parseAddress(
            binding.editEndpoint.text?.toString().orEmpty(),
            binding.editBucket.text?.toString().orEmpty(),
            binding.editRegion.text?.toString().orEmpty(),
            binding.cbPathStyle.isChecked
        )
        val capacityMb = max(binding.editCapacity.text?.toString()?.toLongOrNull() ?: 0L, 0L)
        val usedBytes = mbToBytes(
            max(binding.editUsed.text?.toString()?.toLongOrNull() ?: bytesToMb(oldItem?.usedBytes ?: 0L), 0L)
        ).let { used ->
            if (capacityMb > 0) used.coerceAtMost(mbToBytes(capacityMb)) else used
        }
        if (parsed.endpoint.isBlank() || parsed.bucket.isBlank()) {
            toastOnUi(R.string.s3_container_endpoint_bucket_required)
            return
        }
        val newItem = S3Container(
            id = oldItem?.id ?: S3Container.newId(),
            name = binding.editName.text?.toString()?.trim().orEmpty().ifBlank { parsed.bucket },
            endpoint = parsed.endpoint,
            bucket = parsed.bucket,
            prefix = binding.editPrefix.text?.toString()?.trim().orEmpty().ifBlank { "legado" },
            region = parsed.region.ifBlank { "auto" },
            accessKey = binding.editAccessKey.text?.toString()?.trim().orEmpty(),
            secretKey = binding.editSecretKey.text?.toString()?.trim().orEmpty(),
            sessionToken = binding.editSessionToken.text?.toString()?.trim().orEmpty().ifBlank { null },
            pathStyle = parsed.pathStyle,
            capacityMb = capacityMb,
            usedBytes = usedBytes,
            lastRefreshTime = oldItem?.lastRefreshTime ?: 0L,
            isFull = capacityMb > 0 && usedBytes >= mbToBytes(capacityMb),
            enabled = binding.cbEnabled.isChecked
        )
        AppCloudStorage.addContainer(newItem)
        if (AppCloudStorage.selectedContainer(S3ContainerScope.DEFAULT) == null) {
            AppCloudStorage.selectContainer(S3ContainerScope.DEFAULT, newItem.id)
        }
        reload()
    }

    private fun showActions(item: S3Container) {
        val actions = listOf(
            Action.EDIT,
            Action.TEST,
            Action.REFRESH,
            Action.SET_DEFAULT,
            if (item.enabled) Action.DISABLE else Action.ENABLE,
            Action.DELETE
        )
        selector(AppCloudStorage.containerDisplayLabel(item), actions.map { getString(it.titleRes) }) { _, index ->
            when (actions[index]) {
                Action.EDIT -> showEditDialog(item)
                Action.TEST -> testConnection(item)
                Action.REFRESH -> refreshCapacity(item)
                Action.SET_DEFAULT -> {
                    if (!item.enabled) {
                        toastOnUi(R.string.s3_container_disabled)
                        return@selector
                    }
                    AppCloudStorage.selectContainer(S3ContainerScope.DEFAULT, item.id)
                    toastOnUi(R.string.s3_container_set_default_success)
                    reload()
                }
                Action.ENABLE -> updateItem(item.copy(enabled = true, isFull = false))
                Action.DISABLE -> updateItem(item.copy(enabled = false))
                Action.DELETE -> confirmDelete(item)
            }
        }
    }

    private fun updateItem(item: S3Container) {
        AppCloudStorage.updateContainer(item)
        reload()
    }

    private fun confirmDelete(item: S3Container) {
        alert(R.string.s3_container_delete) {
            setMessage(getString(R.string.s3_container_delete_confirm, AppCloudStorage.containerDisplayLabel(item)))
            okButton {
                AppCloudStorage.deleteContainer(item.id)
                reload()
            }
            cancelButton()
        }
    }

    private fun testConnection(item: S3Container) {
        waitDialog.setText(R.string.loading)
        waitDialog.show()
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    S3CloudStorageBackend(S3ContainerScope.DEFAULT, item.id).check()
                }
            }.onSuccess {
                toastOnUi(R.string.s3_container_test_success)
            }.onFailure {
                toastOnUi(getString(R.string.s3_container_test_failed, it.localizedMessage.orEmpty()))
            }
            waitDialog.dismiss()
        }
    }

    private fun refreshCapacity(item: S3Container) {
        waitDialog.setText(R.string.loading)
        waitDialog.show()
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AppCloudStorage.refreshUsage(item.id) }
            }.onSuccess {
                toastOnUi(R.string.s3_container_capacity_refreshed)
                reload()
            }.onFailure {
                toastOnUi(it.localizedMessage.orEmpty())
            }
            waitDialog.dismiss()
        }
    }

    private inner class Adapter :
        RecyclerAdapter<S3Container, ItemS3ContainerBinding>(this@S3ContainerManageActivity) {

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
            item: S3Container,
            payloads: MutableList<Any>
        ) = binding.run {
            val isDefault = AppCloudStorage.selectedContainer(S3ContainerScope.DEFAULT)?.id == item.id
            tvName.text = if (isDefault) {
                getString(R.string.s3_container_default_name, AppCloudStorage.containerDisplayLabel(item))
            } else {
                AppCloudStorage.containerDisplayLabel(item)
            }
            tvPath.text = "${item.bucket}/${item.prefix.trim('/')}"
            val capacityMb = item.capacityMb.coerceAtLeast(0)
            val usedMb = bytesToMb(item.usedBytes)
            tvCapacity.text = if (capacityMb > 0) {
                getString(
                    R.string.s3_container_capacity_line,
                    capacityMb,
                    usedMb,
                    (capacityMb - usedMb).coerceAtLeast(0),
                    if (item.isFull) getString(R.string.yes) else getString(R.string.no)
                )
            } else {
                getString(R.string.s3_container_capacity_unlimited_line, usedMb)
            }
            tvState.text = getString(
                R.string.s3_container_state_line,
                if (item.enabled) getString(R.string.s3_container_enabled) else getString(R.string.s3_container_disabled)
            )
            tvName.applyUiSectionTitleStyle(this@S3ContainerManageActivity)
            tvPath.applyUiLabelStyle(this@S3ContainerManageActivity)
            tvCapacity.applyUiLabelStyle(this@S3ContainerManageActivity)
            tvState.applyUiLabelStyle(this@S3ContainerManageActivity)
            listOf(tvPath, tvCapacity, tvState).forEach { it.setTextColor(secondaryTextColor) }
            btnMore.setOnClickListener { showActions(item) }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemS3ContainerBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.bindingAdapterPosition - getHeaderCount())?.let { showEditDialog(it) }
            }
        }
    }

    private enum class Action(val titleRes: Int) {
        EDIT(R.string.s3_container_edit),
        TEST(R.string.s3_container_test),
        REFRESH(R.string.s3_container_refresh_capacity),
        SET_DEFAULT(R.string.s3_container_set_default),
        ENABLE(R.string.s3_container_enable),
        DISABLE(R.string.s3_container_disable),
        DELETE(R.string.s3_container_delete)
    }

    private companion object {
        fun mbToBytes(value: Long): Long = value.coerceAtLeast(0L) * 1024L * 1024L
        fun bytesToMb(value: Long): Long = value.coerceAtLeast(0L) / 1024L / 1024L
    }
}
