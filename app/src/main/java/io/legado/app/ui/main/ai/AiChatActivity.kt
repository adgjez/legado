package io.legado.app.ui.main.ai

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import androidx.activity.viewModels
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacter
import io.legado.app.databinding.ActivityAiChatBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.character.BookCharacterProfileMeta
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
import kotlinx.coroutines.withContext
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
                    onOpenWindowAbilities = ::showWindowAbilityDialog,
                    onOpenWorldBooks = ::showCompanionWorldBookDialog,
                    onToggleAutoSpeak = ::toggleAutoSpeak,
                    onSpeakMessage = { text, companion ->
                        AiChatSpeechPlayer.speak(text, companion.ttsRouteJson)
                    },
                    onAddCompanion = ::showAddCompanionDialog,
                    onSelectCompanion = ::selectCompanion,
                    onEditCompanion = ::showEditCompanionDialog,
                    onDeleteCompanion = ::confirmDeleteCompanion
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

    override fun onDestroy() {
        AiChatSpeechPlayer.stop()
        super.onDestroy()
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

    private fun selectCompanion(companionId: String) {
        if (!viewModel.switchCompanion(companionId)) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        refreshToken.intValue += 1
    }

    private fun toggleAutoSpeak() {
        AppConfig.aiChatAutoSpeakEnabled = !AppConfig.aiChatAutoSpeakEnabled
        refreshToken.intValue += 1
    }

    private fun showAddCompanionDialog() {
        if (viewModel.isRequesting) {
            toastOnUi(R.string.ai_chat_wait_current)
            return
        }
        lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) {
                val booksByKey = appDb.bookDao.all.associateBy { it.characterBookKey() }
                appDb.bookCharacterDao.allCharacters()
                    .filter { it.name.isNotBlank() }
                    .groupBy { it.bookUrl }
                    .map { (bookKey, characters) ->
                        val book = booksByKey[bookKey]
                        CharacterPickGroup(
                            bookKey = bookKey,
                            label = book?.let { "${it.name} · ${it.author.ifBlank { "未知作者" }}" }
                                ?: bookKey.ifBlank { "未绑定书籍" },
                            characters = characters.sortedWith(
                                compareByDescending<BookCharacter> { it.roleLevel }
                                    .thenBy { it.sortOrder }
                                    .thenBy { it.id }
                            )
                        )
                    }
                    .filter { it.characters.isNotEmpty() }
                    .sortedBy { it.label }
            }
            if (groups.isEmpty()) {
                toastOnUi("没有可添加的角色卡")
                return@launch
            }
            selector("选择书籍", groups.map { "${it.label}\n${it.characters.size} 个角色" }) { _, _, index ->
                showCharacterSelector(groups[index])
            }
        }
    }

    private fun showCharacterSelector(group: CharacterPickGroup) {
        selector(
            group.label,
            group.characters.map { character ->
                "${character.displayName()} · ${character.roleLabel()} · ${character.genderLabel()}"
            }
        ) { _, _, index ->
            addCharacterCompanion(group, group.characters[index])
        }
    }

    private fun addCharacterCompanion(group: CharacterPickGroup, character: BookCharacter) {
        val companionId = characterCompanionId(character)
        val existing = AppConfig.aiChatCompanionList.firstOrNull { it.id == companionId }
        if (existing != null) {
            viewModel.switchCompanion(existing.id)
            refreshToken.intValue += 1
            return
        }
        val companion = AiChatCompanionConfig(
            id = companionId,
            type = AiChatCompanionConfig.TYPE_CHARACTER,
            name = character.displayName(),
            avatar = character.avatar,
            bookKey = group.bookKey,
            characterId = character.id.toString(),
            prompt = buildCharacterCompanionPrompt(group, character),
            ttsRouteJson = character.speechRouteJson,
            order = AppConfig.aiChatCompanionList.size
        )
        AppConfig.upsertAiChatCompanion(companion)
        viewModel.switchCompanion(companion.id)
        refreshToken.intValue += 1
    }

    private fun showEditCompanionDialog(companion: AiChatCompanionConfig) {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.setSingleLine(false)
            editView.minLines = 8
            editView.inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            editView.setText(companion.prompt)
        }
        alert(title = "${companion.name} · 人格提示词") {
            customView { binding.root }
            okButton {
                val prompt = binding.editView.text?.toString().orEmpty().trim()
                if (prompt.isBlank()) {
                    toastOnUi("提示词不能为空")
                } else {
                    AppConfig.upsertAiChatCompanion(companion.copy(prompt = prompt))
                    refreshToken.intValue += 1
                }
            }
            cancelButton()
        }
    }

    private fun confirmDeleteCompanion(companion: AiChatCompanionConfig) {
        if (companion.id == AiChatCompanionConfig.DEFAULT_COMPANION_ID) {
            toastOnUi("默认助手不能删除")
            return
        }
        alert(
            title = "删除角色助手",
            message = "确定删除「${companion.name}」？它的聊天历史也会删除。"
        ) {
            okButton {
                AppConfig.removeAiChatCompanion(companion.id)
                viewModel.switchCompanion(AiChatCompanionConfig.DEFAULT_COMPANION_ID)
                refreshToken.intValue += 1
            }
            cancelButton()
        }
    }

    private fun showCompanionWorldBookDialog() {
        val companion = viewModel.currentCompanion()
        val worldBooks = AppConfig.aiWorldBookList
        if (worldBooks.isEmpty()) {
            selector("世界书", listOf("打开世界书管理")) { _, _, _ ->
                openWorldBookManage()
            }
            return
        }
        val selected = companion.worldBookIds.toMutableSet()
        alert(title = "${companion.name} · 世界书") {
            multiChoiceItems(
                items = worldBooks.map { book ->
                    "${book.name}${if (book.enabled) "" else "（停用）"}"
                }.toTypedArray(),
                checkedItems = BooleanArray(worldBooks.size) { index -> worldBooks[index].id in selected }
            ) { _, which, isChecked ->
                if (isChecked) selected += worldBooks[which].id else selected -= worldBooks[which].id
            }
            okButton {
                AppConfig.upsertAiChatCompanion(
                    companion.copy(worldBookIds = selected.filter { id -> worldBooks.any { it.id == id } })
                )
                refreshToken.intValue += 1
            }
            neutralButton("管理") { openWorldBookManage() }
            cancelButton()
        }
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
                "世界书：${activeCompanionWorldBookCount()} 个",
                "清空 Skill/MCP"
            )
        ) { _, _, index ->
            when (index) {
                0 -> showWindowSkillDialog()
                1 -> showWindowMcpDialog()
                2 -> showCompanionWorldBookDialog()
                3 -> {
                    viewModel.setActiveWindowSkillIds(emptySet())
                    viewModel.setActiveWindowMcpServerIds(emptySet())
                    refreshToken.intValue += 1
                }
            }
        }
    }

    private fun openWorldBookManage() {
        startActivity(Intent(this, AiWorldBookManageActivity::class.java))
    }

    private fun activeCompanionWorldBookCount(): Int {
        val companion = viewModel.currentCompanion()
        return companion.worldBookIds.count { worldBookId ->
            AppConfig.aiWorldBookList.any { it.id == worldBookId && it.enabled }
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

    private fun characterCompanionId(character: BookCharacter): String {
        return "character_${character.id}"
    }

    private fun buildCharacterCompanionPrompt(
        group: CharacterPickGroup,
        character: BookCharacter
    ): String {
        val age = BookCharacterProfileMeta.ageOf(character)
        val profileLines = listOfNotNull(
            "来源作品：${group.label}".takeIf { group.label.isNotBlank() },
            "角色名：${character.displayName()}",
            "角色定位：${character.roleLabel()}",
            "性别：${character.genderLabel()}".takeIf { character.genderLabel() != "未知" },
            "年纪：$age".takeIf { age.isNotBlank() },
            "身份：${character.identity}".takeIf { character.identity.isNotBlank() },
            "外观：${character.appearance}".takeIf { character.appearance.isNotBlank() },
            "性格：${character.personality}".takeIf { character.personality.isNotBlank() },
            "能力/技能：${character.skills}".takeIf { character.skills.isNotBlank() },
            "背景：${character.biography}".takeIf { character.biography.isNotBlank() },
            "属性：${BookCharacterProfileMeta.attributesWithoutAge(character.attributes)}"
                .takeIf { BookCharacterProfileMeta.attributesWithoutAge(character.attributes).isNotBlank() }
        )
        return buildString {
            append("你正在扮演小说角色「")
            append(character.displayName())
            append("」与用户对话。")
            append("始终保持角色身份、语气、经历和认知边界，不要自称 AI，不要跳出角色解释系统规则。")
            append("没有把握的剧情细节可以自然回避或反问，不要编造与角色设定冲突的内容。")
            append("\n\n角色资料：\n")
            append(profileLines.joinToString("\n"))
        }
    }

    private data class CharacterPickGroup(
        val bookKey: String,
        val label: String,
        val characters: List<BookCharacter>
    )
}
