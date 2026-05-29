package io.legado.app.ui.config

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ActivityAiProviderManageBinding
import io.legado.app.databinding.ItemS3ContainerBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiImageProviderManageActivity : BaseActivity<ActivityAiProviderManageBinding>() {

    override val binding by viewBinding(ActivityAiProviderManageBinding::inflate)
    private val adapter by lazy { Adapter() }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.ai_image_provider_manage)
        binding.tvSummary.text = getString(R.string.ai_image_provider_manage_summary)
        binding.tvSummary.applyUiLabelStyle(this)
        binding.tvSummary.setTextColor(secondaryTextColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        (binding.recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.btnAdd.text = getString(R.string.add)
        binding.btnAdd.background = actionBackground()
        binding.btnAdd.setOnClickListener { showAddSelector() }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        adapter.setItems(AppConfig.aiImageProviderList.sortedBy { it.order })
    }

    private fun showAddSelector() {
        selector(
            getString(R.string.add),
            listOf(getString(R.string.ai_image_provider_openai), getString(R.string.ai_image_provider_js))
        ) { _, index ->
            val type = if (index == 0) AiImageProviderConfig.TYPE_OPENAI else AiImageProviderConfig.TYPE_JS
            openEdit(AiImageProviderEditActivity.newIntent(this, null, type))
        }
    }

    private fun openEdit(intent: Intent) {
        startActivity(intent)
    }

    private fun showActions(provider: AiImageProviderConfig) {
        selector(
            provider.displayName(),
            listOf(
                getString(if (provider.enabled) R.string.disable else R.string.enable),
                getString(R.string.edit),
                getString(R.string.delete)
            )
        ) { _, index ->
            when (index) {
                0 -> {
                    AppConfig.aiImageProviderList = AppConfig.aiImageProviderList.map {
                        if (it.id == provider.id) it.copy(enabled = !it.enabled) else it
                    }
                    reload()
                }
                1 -> openEdit(AiImageProviderEditActivity.newIntent(this, provider.id, provider.type))
                2 -> confirmDelete(provider)
            }
        }
    }

    private fun confirmDelete(provider: AiImageProviderConfig) {
        alert(provider.displayName()) {
            setMessage(R.string.delete)
            okButton {
                AppConfig.aiImageProviderList = AppConfig.aiImageProviderList.filterNot { it.id == provider.id }
                reload()
                toastOnUi(R.string.delete)
            }
            cancelButton()
        }
    }

    private fun actionBackground() = UiCorner.actionSelector(
        ContextCompat.getColor(this, R.color.background_card),
        ContextCompat.getColor(this, R.color.background_menu),
        UiCorner.actionRadius(this)
    )

    private inner class Adapter :
        RecyclerAdapter<AiImageProviderConfig, ItemS3ContainerBinding>(this@AiImageProviderManageActivity) {

        override fun getViewBinding(parent: ViewGroup): ItemS3ContainerBinding {
            return ItemS3ContainerBinding.inflate(inflater, parent, false).apply {
                root.background = UiCorner.panelRounded(
                    root.context,
                    ContextCompat.getColor(root.context, R.color.background_card),
                    UiCorner.panelRadius(root.context)
                )
                btnMore.background = actionBackground()
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemS3ContainerBinding,
            item: AiImageProviderConfig,
            payloads: MutableList<Any>
        ) = binding.run {
            tvName.text = item.displayName()
            tvPath.text = if (item.type == AiImageProviderConfig.TYPE_OPENAI) {
                item.baseUrl.ifBlank { "OpenAI" }
            } else {
                getString(R.string.ai_image_provider_js)
            }
            tvCapacity.text = item.model.ifBlank { if (item.type == AiImageProviderConfig.TYPE_OPENAI) "gpt-image-1" else "JS" }
            tvState.text = getString(if (item.enabled) R.string.enabled else R.string.disabled)
            tvSelected.visibility = if (item.enabled) View.VISIBLE else View.GONE
            tvSelected.setTextColor(ContextCompat.getColor(this@AiImageProviderManageActivity, R.color.accent))
            tvName.applyUiSectionTitleStyle(this@AiImageProviderManageActivity)
            tvPath.applyUiLabelStyle(this@AiImageProviderManageActivity)
            tvCapacity.applyUiLabelStyle(this@AiImageProviderManageActivity)
            tvState.applyUiLabelStyle(this@AiImageProviderManageActivity)
            listOf(tvPath, tvCapacity, tvState).forEach { it.setTextColor(secondaryTextColor) }
            btnMore.setOnClickListener { showActions(item) }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemS3ContainerBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.bindingAdapterPosition - getHeaderCount())?.let {
                    openEdit(AiImageProviderEditActivity.newIntent(this@AiImageProviderManageActivity, it.id, it.type))
                }
            }
        }
    }
}
