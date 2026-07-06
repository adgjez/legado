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
        val labels = listOf(
            getString(R.string.ai_video_provider_openai),
            getString(R.string.ai_video_provider_agnes),
            getString(R.string.ai_video_provider_js)
        )
        showComposeActionListDialog(
            title = getString(R.string.add),
            labels = labels
        ) { index ->
            val type = when (index) {
                0 -> AiVideoProviderConfig.TYPE_OPENAI
                1 -> AiVideoProviderConfig.TYPE_AGNES
                else -> AiVideoProviderConfig.TYPE_JS
            }
            openEdit(AiVideoProviderEditActivity.newIntent(this, null, type))
        }
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
