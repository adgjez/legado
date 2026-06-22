package io.legado.app.ui.main.ai

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.ActivityAiVideoProviderManageBinding
import io.legado.app.databinding.ItemAiVideoProviderBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiVideoProviderManageActivity : BaseActivity<ActivityAiVideoProviderManageBinding>() {

    override val binding by viewBinding(ActivityAiVideoProviderManageBinding::inflate)
    private val adapter by lazy { Adapter(this) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.ai_video_provider_manage)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.fabAdd.setOnClickListener { openEditor(null) }
        refreshSummary()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshSummary()
        refreshList()
    }

    private fun refreshSummary() {
        val providers = AppConfig.aiVideoProviderList
        val enabled = AppConfig.aiEnabledVideoProviders
        binding.tvSummary.text = getString(
            R.string.ai_video_provider_summary,
            enabled.size,
            providers.size
        )
    }

    private fun refreshList() {
        adapter.setItems(AppConfig.aiVideoProviderList)
    }

    private fun openEditor(config: AiVideoProviderConfig?) {
        val intent = Intent(this, AiVideoProviderEditActivity::class.java)
        if (config != null) {
            intent.putExtra(AiVideoProviderEditActivity.EXTRA_ID, config.id)
        }
        startActivity(intent)
    }

    private inner class Adapter(context: android.content.Context) :
        RecyclerAdapter<AiVideoProviderConfig, ItemAiVideoProviderBinding>(context) {
        override fun getViewBinding(parent: ViewGroup): ItemAiVideoProviderBinding {
            return ItemAiVideoProviderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemAiVideoProviderBinding) {
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    getItem(pos)?.let { openEditor(it) }
                }
            }
            holder.itemView.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    getItem(pos)?.let { config -> confirmDelete(config) }
                }
                true
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemAiVideoProviderBinding,
            item: AiVideoProviderConfig,
            payloads: MutableList<Any>
        ) {
            binding.tvName.text = item.displayName()
            binding.tvType.text = item.type
            binding.tvModel.text = item.model.ifBlank { "—" }
            binding.tvEnabled.text = if (item.enabled) getString(R.string.enable) else getString(R.string.no)
        }
    }

    private fun confirmDelete(config: AiVideoProviderConfig) {
        alert(R.string.delete) {
            setMessage(config.displayName())
            okButton { AppConfig.deleteVideoProvider(config.id) }
            cancelButton()
        }
    }
}
