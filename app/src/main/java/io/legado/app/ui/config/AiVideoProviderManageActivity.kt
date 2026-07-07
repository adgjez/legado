package io.legado.app.ui.config

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityAiProviderManageBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiVideoProviderManageActivity : BaseActivity<ActivityAiProviderManageBinding>() {

    override val binding by viewBinding(ActivityAiProviderManageBinding::inflate)
    private val providersState = mutableStateOf<List<AiVideoProviderConfig>>(emptyList())
    private val currentProviderIdState = mutableStateOf("")

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            AiVideoProviderManageScreen(
                providers = providersState.value,
                currentProviderId = currentProviderIdState.value,
                onBack = { finish() },
                onAdd = { showAddSelector() },
                onOpenProvider = { provider ->
                    openEdit(AiVideoProviderEditActivity.newIntent(this, provider.id, provider.type))
                },
                providerActions = ::providerActions
            )
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        AppConfig.ensureCurrentVideoProvider()
        val providers = AppConfig.aiVideoProviderList.sortedBy { it.order }
        providersState.value = providers
        currentProviderIdState.value = AppConfig.aiCurrentVideoProvider?.id.orEmpty()
    }

    private fun showAddSelector() {
        val types = listOf(
            AiVideoProviderConfig.TYPE_ARK,
            AiVideoProviderConfig.TYPE_AGNES,
            AiVideoProviderConfig.TYPE_SORA,
            AiVideoProviderConfig.TYPE_VEO,
            AiVideoProviderConfig.TYPE_KLING,
            AiVideoProviderConfig.TYPE_NEWAPI,
            AiVideoProviderConfig.TYPE_V2,
            AiVideoProviderConfig.TYPE_DASHSCOPE,
            AiVideoProviderConfig.TYPE_MINIMAX,
            AiVideoProviderConfig.TYPE_VIDU,
            AiVideoProviderConfig.TYPE_GROK
        )
        showComposeActionListDialog(
            title = getString(R.string.add),
            labels = types.map { videoProviderTypeLabel(it) }
        ) { index ->
            types.getOrNull(index)?.let { type ->
                openEdit(AiVideoProviderEditActivity.newIntent(this, null, type))
            }
        }
    }

    private fun videoProviderTypeLabel(type: String): String = when (type) {
        AiVideoProviderConfig.TYPE_ARK -> getString(R.string.ai_video_provider_ark)
        AiVideoProviderConfig.TYPE_AGNES -> getString(R.string.ai_video_provider_agnes)
        AiVideoProviderConfig.TYPE_SORA -> getString(R.string.ai_video_provider_sora)
        AiVideoProviderConfig.TYPE_VEO -> getString(R.string.ai_video_provider_veo)
        AiVideoProviderConfig.TYPE_KLING -> getString(R.string.ai_video_provider_kling)
        AiVideoProviderConfig.TYPE_NEWAPI -> getString(R.string.ai_video_provider_newapi)
        AiVideoProviderConfig.TYPE_V2 -> getString(R.string.ai_video_provider_v2)
        AiVideoProviderConfig.TYPE_DASHSCOPE -> getString(R.string.ai_video_provider_dashscope)
        AiVideoProviderConfig.TYPE_MINIMAX -> getString(R.string.ai_video_provider_minimax)
        AiVideoProviderConfig.TYPE_VIDU -> getString(R.string.ai_video_provider_vidu)
        AiVideoProviderConfig.TYPE_GROK -> getString(R.string.ai_video_provider_grok)
        else -> type
    }

    private fun openEdit(intent: Intent) {
        startActivity(intent)
    }

    private fun providerActions(provider: AiVideoProviderConfig): List<AppManagementMenuAction> {
        val isCurrent = provider.id == AppConfig.aiCurrentVideoProviderId
        return buildList {
            if (!isCurrent) {
                add(
                    AppManagementMenuAction("设为当前生视频模型") {
                        if (!provider.enabled) {
                            toastOnUi("请先启用该生视频模型")
                        } else {
                            AppConfig.aiCurrentVideoProviderId = provider.id
                            notifyAiConfigChanged()
                            reload()
                            toastOnUi("已设为当前生视频模型")
                        }
                    }
                )
            }
            add(
                AppManagementMenuAction(getString(if (provider.enabled) R.string.disable else R.string.enable)) {
                    AppConfig.aiVideoProviderList = AppConfig.aiVideoProviderList.map {
                        if (it.id == provider.id) it.copy(enabled = !it.enabled) else it
                    }
                    notifyAiConfigChanged()
                    reload()
                }
            )
            add(
                AppManagementMenuAction(getString(R.string.edit)) {
                    openEdit(
                        AiVideoProviderEditActivity.newIntent(
                            this@AiVideoProviderManageActivity,
                            provider.id,
                            provider.type
                        )
                    )
                }
            )
            add(
                AppManagementMenuAction(
                    text = getString(R.string.delete),
                    danger = true,
                    onClick = { confirmDelete(provider) }
                )
            )
        }
    }

    private fun confirmDelete(provider: AiVideoProviderConfig) {
        showComposeConfirmDialog(
            title = provider.displayName(),
            message = getString(R.string.delete),
            dangerPositive = true,
            onPositive = {
                AppConfig.aiVideoProviderList = AppConfig.aiVideoProviderList.filterNot { it.id == provider.id }
                notifyAiConfigChanged()
                reload()
                toastOnUi(R.string.delete)
            }
        )
    }

    private fun notifyAiConfigChanged() {
        postEvent(EventBus.AI_CONFIG_CHANGED, true)
    }
}
