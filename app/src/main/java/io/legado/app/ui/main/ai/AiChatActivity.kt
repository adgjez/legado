package io.legado.app.ui.main.ai

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAiChatBinding
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.config.AiWorldBookManageActivity
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.main.ai.compose.AiChatRoute
import io.legado.app.ui.main.ai.compose.AiChatScreenActions
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AiChatActivity : BaseActivity<ActivityAiChatBinding>(
    fullScreen = false,
    imageBg = false
) {

    override val binding by viewBinding(ActivityAiChatBinding::inflate)

    private val viewModel by viewModels<AiChatViewModel>()
    private val historyTimeFormat by lazy { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    private val refreshToken = mutableIntStateOf(0)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.composeRoot.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeRoot.setContent {
            AiChatRoute(
                viewModel = viewModel,
                lifecycleOwner = this,
                compactHeader = false,
                refreshToken = refreshToken.intValue,
                actions = AiChatScreenActions(
                    onSend = ::dispatchSend,
                    onStop = ::cancelCurrentRequest,
                    onOpenSettings = ::openAiSettings,
                    onNewChat = ::startNewChatFromMenu,
                    onOpenHistory = ::openHistoryFromMenu,
                    onSelectModel = ::showModelSelectorDialog,
                    onOpenImageGallery = ::openImageGallery,
                    onOpenWindowAbilities = ::showWindowAbilityDialog
                )
            )
        }
        lifecycleScope.launch(Dispatchers.IO) {
            AiImageGalleryManager.cleanupExpiredTemporary()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshToken.intValue += 1
    }

    private fun dispatchSend(content: String): Boolean {
        if (content.isBlank() || viewModel.isRequesting) return false
        val provider = AppConfig.aiCurrentProvider
        if (provider?.baseUrl.isNullOrBlank() || AppConfig.aiCurrentModelConfig == null) {
            toastOnUi(R.string.ai_missing_config)
            return false
        }
        viewModel.startRequest(
            userContent = content.trim(),
            thinkingText = getString(R.string.ai_chat_thinking),
            cancelledText = getString(R.string.ai_chat_cancelled),
            failureMessage = { getString(R.string.ai_request_failed, it) }
        )
        return true
    }

    private fun cancelCurrentRequest() {
        viewModel.stopRequest(getString(R.string.ai_chat_cancelled))
    }

    private fun openHistoryFromMenu() {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        showHistoryDialog()
    }

    private fun startNewChatFromMenu() {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        viewModel.startNewSession()
        refreshToken.intValue += 1
    }

    private fun openAiSettings() {
        android.content.Intent(this, ConfigActivity::class.java).apply {
            putExtra("configTag", ConfigTag.AI_CONFIG)
        }.also(::startActivity)
    }

    private fun openImageGallery() {
        startActivity(android.content.Intent(this, AiImageGalleryActivity::class.java))
    }

    private fun showWindowAbilityDialog() {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        selector(
            "当前窗口能力",
            listOf(
                "Skill：${viewModel.activeWindowSkillIds().size} 个",
                "MCP：${viewModel.activeWindowMcpServerIds().size} 个",
                "世界书：${activeSessionWorldBookCount()} 个",
                "清空 Skill/MCP"
            )
        ) { _, _, index ->
            when (index) {
                0 -> showWindowSkillDialog()
                1 -> showWindowMcpDialog()
                2 -> openSessionWorldBookManage()
                3 -> {
                    viewModel.setActiveWindowSkillIds(emptySet())
                    viewModel.setActiveWindowMcpServerIds(emptySet())
                    refreshToken.intValue += 1
                }
            }
        }
    }

    private fun openSessionWorldBookManage() {
        startActivity(
            Intent(this, AiWorldBookManageActivity::class.java)
                .putExtra(AiWorldBookManageActivity.EXTRA_TARGET_TYPE, AiWorldBookBinding.TARGET_SESSION)
                .putExtra(AiWorldBookManageActivity.EXTRA_TARGET_KEY, viewModel.activeSessionId())
        )
    }

    private fun activeSessionWorldBookCount(): Int {
        val sessionId = viewModel.activeSessionId()
        return AppConfig.aiWorldBookList.count { worldBook ->
            worldBook.enabled && worldBook.bindings.any { binding ->
                binding.enabled &&
                        binding.targetType == AiWorldBookBinding.TARGET_SESSION &&
                        binding.targetKey == sessionId
            }
        }
    }

    private fun showWindowSkillDialog() {
        val skills = AppConfig.aiSkillList
        if (skills.isEmpty()) {
            toastOnUi("没有可用 Skill")
            return
        }
        val selected = viewModel.activeWindowSkillIds().toMutableSet()
        alert(title = "当前窗口 Skill") {
            multiChoiceItems(
                items = skills.map { skill -> skill.name.ifBlank { "Skill" } }.toTypedArray(),
                checkedItems = BooleanArray(skills.size) { index -> skills[index].id in selected }
            ) { _, which, isChecked ->
                if (isChecked) selected += skills[which].id else selected -= skills[which].id
            }
            okButton {
                viewModel.setActiveWindowSkillIds(selected)
                refreshToken.intValue += 1
            }
            neutralButton("清空") {
                viewModel.setActiveWindowSkillIds(emptySet())
                refreshToken.intValue += 1
            }
            cancelButton()
        }
    }

    private fun showWindowMcpDialog() {
        val servers = AppConfig.aiMcpServerList.filter { it.enabled }
        if (servers.isEmpty()) {
            toastOnUi("没有已启用 MCP")
            return
        }
        val selected = viewModel.activeWindowMcpServerIds().toMutableSet()
        alert(title = "当前窗口 MCP") {
            multiChoiceItems(
                items = servers.map { server -> server.name.ifBlank { "MCP" } }.toTypedArray(),
                checkedItems = BooleanArray(servers.size) { index -> servers[index].id in selected }
            ) { _, which, isChecked ->
                if (isChecked) selected += servers[which].id else selected -= servers[which].id
            }
            okButton {
                viewModel.setActiveWindowMcpServerIds(selected)
                refreshToken.intValue += 1
            }
            neutralButton("清空") {
                viewModel.setActiveWindowMcpServerIds(emptySet())
                refreshToken.intValue += 1
            }
            cancelButton()
        }
    }

    private fun showHistoryDialog() {
        val sessions = viewModel.historySessions()
        if (sessions.isEmpty()) {
            toastOnUi(R.string.ai_history_empty)
            return
        }
        val items = mutableListOf(getString(R.string.ai_history_clear_all))
        items += sessions.map { session ->
            "${session.title}\n${historyTimeFormat.format(Date(session.updatedAt))}"
        }
        selector(getString(R.string.ai_chat_history), items) { _, _, index ->
            if (index == 0) {
                confirmClearAllHistory()
            } else {
                showHistorySessionActions(sessions[index - 1])
            }
        }
    }

    private fun showHistorySessionActions(session: AiChatSession) {
        selector(
            session.title,
            listOf(
                getString(R.string.ai_history_open),
                getString(R.string.ai_history_delete)
            )
        ) { _, _, index ->
            when (index) {
                0 -> {
                    viewModel.loadSession(session.id)
                    refreshToken.intValue += 1
                }
                1 -> confirmDeleteHistorySession(session)
            }
        }
    }

    private fun confirmDeleteHistorySession(session: AiChatSession) {
        alert(
            title = getString(R.string.ai_history_delete),
            message = getString(R.string.ai_history_delete_confirm, session.title)
        ) {
            okButton {
                viewModel.deleteSession(session.id)
                refreshToken.intValue += 1
            }
            cancelButton()
        }
    }

    private fun confirmClearAllHistory() {
        alert(
            title = getString(R.string.ai_history_clear_all),
            message = getString(R.string.ai_history_clear_all_confirm)
        ) {
            okButton {
                viewModel.clearAllSessions()
                refreshToken.intValue += 1
            }
            cancelButton()
        }
    }

    private fun showModelSelectorDialog() {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        val models = AppConfig.aiModelConfigList
        if (models.isEmpty()) {
            toastOnUi(R.string.ai_no_models)
            return
        }
        val providerNameMap = AppConfig.aiProviderList.associateBy({ it.id }, { it.name })
        selector(
            getString(R.string.ai_current_model),
            models.map { model ->
                providerNameMap[model.providerId]?.takeIf { it.isNotBlank() }
                    ?.let { "${model.modelId} · $it" }
                    ?: model.modelId
            }
        ) { _, _, index ->
            AppConfig.aiCurrentModelId = models[index].id
            refreshToken.intValue += 1
        }
    }
}
