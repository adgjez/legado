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
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiImageProviderManageActivity : BaseActivity<ActivityAiProviderManageBinding>() {

    override val binding by viewBinding(ActivityAiProviderManageBinding::inflate)
    private val providersState = mutableStateOf<List<AiImageProviderConfig>>(emptyList())
    private val currentProviderIdState = mutableStateOf("")

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            AiImageProviderManageScreen(
                providers = providersState.value,
                currentProviderId = currentProviderIdState.value,
                onBack = { finish() },
                onAdd = { showAddSelector() },
                onOpenProvider = { provider ->
                    openEdit(AiImageProviderEditActivity.newIntent(this, provider.id, provider.type))
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
        AppConfig.ensureCurrentImageProvider()
        val providers = AppConfig.aiImageProviderList.sortedBy { it.order }
        providersState.value = providers
        currentProviderIdState.value = AppConfig.aiCurrentImageProvider?.id.orEmpty()
    }

    private fun showAddSelector() {
        val types = listOf(
            AiImageProviderConfig.TYPE_ARK,
            AiImageProviderConfig.TYPE_DASHSCOPE,
            AiImageProviderConfig.TYPE_GEMINI,
            AiImageProviderConfig.TYPE_GROK,
            AiImageProviderConfig.TYPE_KLING,
            AiImageProviderConfig.TYPE_MINIMAX,
            AiImageProviderConfig.TYPE_VIDU
        )
        showComposeActionListDialog(
            title = getString(R.string.add),
            labels = types.map { imageProviderTypeLabel(it) }
        ) { index ->
            types.getOrNull(index)?.let { type ->
                openEdit(AiImageProviderEditActivity.newIntent(this, null, type))
            }
        }
    }

    private fun imageProviderTypeLabel(type: String): String = when (type) {
        AiImageProviderConfig.TYPE_ARK -> getString(R.string.ai_image_provider_ark)
        AiImageProviderConfig.TYPE_DASHSCOPE -> getString(R.string.ai_image_provider_dashscope)
        AiImageProviderConfig.TYPE_GEMINI -> getString(R.string.ai_image_provider_gemini)
        AiImageProviderConfig.TYPE_GROK -> getString(R.string.ai_image_provider_grok)
        AiImageProviderConfig.TYPE_KLING -> getString(R.string.ai_image_provider_kling)
        AiImageProviderConfig.TYPE_MINIMAX -> getString(R.string.ai_image_provider_minimax)
        AiImageProviderConfig.TYPE_VIDU -> getString(R.string.ai_image_provider_vidu)
        else -> type
    }

    private fun openEdit(intent: Intent) {
        startActivity(intent)
    }

    private fun providerActions(provider: AiImageProviderConfig): List<AppManagementMenuAction> {
        val isCurrent = provider.id == AppConfig.aiCurrentImageProviderId
        return buildList {
            if (!isCurrent) {
                add(
                    AppManagementMenuAction("设为当前生图模型") {
                        if (!provider.enabled) {
                            toastOnUi("请先启用该生图模型")
                        } else {
                            AppConfig.aiCurrentImageProviderId = provider.id
                            notifyAiConfigChanged()
                            reload()
                            toastOnUi("已设为当前生图模型")
                        }
                    }
                )
            }
            add(
                AppManagementMenuAction(getString(if (provider.enabled) R.string.disable else R.string.enable)) {
                    AppConfig.aiImageProviderList = AppConfig.aiImageProviderList.map {
                        if (it.id == provider.id) it.copy(enabled = !it.enabled) else it
                    }
                    notifyAiConfigChanged()
                    reload()
                }
            )
            add(
                AppManagementMenuAction(getString(R.string.edit)) {
                    openEdit(
                        AiImageProviderEditActivity.newIntent(
                            this@AiImageProviderManageActivity,
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

    private fun confirmDelete(provider: AiImageProviderConfig) {
        showComposeConfirmDialog(
            title = provider.displayName(),
            message = getString(R.string.delete),
            dangerPositive = true,
            onPositive = {
                AppConfig.aiImageProviderList = AppConfig.aiImageProviderList.filterNot { it.id == provider.id }
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
