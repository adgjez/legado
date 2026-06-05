package io.legado.app.ui.main.ai

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
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
import io.legado.app.help.character.BookCharacterIdentityMigrator
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.config.AiWorldBookManageActivity
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.main.ai.compose.AiChatRoute
import io.legado.app.ui.main.ai.compose.AiChatScreenActions
import io.legado.app.ui.main.ai.compose.aiComposeStyle
import io.legado.app.ui.book.character.compose.CharacterAvatar
import io.legado.app.ui.widget.image.CoverImageView
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
    private var characterPickerGroups by mutableStateOf<List<CharacterPickGroup>>(emptyList())
    private var characterPickerVisible by mutableStateOf(false)

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
            if (characterPickerVisible) {
                AiCharacterCompanionPickerDialog(
                    groups = characterPickerGroups,
                    onDismiss = { characterPickerVisible = false },
                    onCharacterSelected = { group, character ->
                        characterPickerVisible = false
                        addCharacterCompanion(group, character)
                    }
                )
            }
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
                val books = appDb.bookDao.all
                books.forEach { BookCharacterIdentityMigrator.migrate(it) }
                val booksByKey = books.associateBy { it.characterBookKey() }
                val booksByLegacyUrl = books.associateBy { it.bookUrl }
                appDb.bookCharacterDao.allCharacters()
                    .filter { it.name.isNotBlank() }
                    .groupBy { it.bookUrl }
                    .map { (bookKey, characters) ->
                        val book = booksByKey[bookKey] ?: booksByLegacyUrl[bookKey]
                        CharacterPickGroup(
                            bookKey = bookKey,
                            bookName = book?.name.orEmpty(),
                            author = book?.author.orEmpty(),
                            coverUrl = book?.getDisplayCover().orEmpty(),
                            label = book?.let { "${it.name} · ${it.author.ifBlank { "未知作者" }}" }
                                ?: displayBookKey(bookKey),
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
            characterPickerGroups = groups
            characterPickerVisible = true
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

    private fun displayBookKey(bookKey: String): String {
        val value = bookKey.trim()
        if (!value.startsWith("work:")) return value.ifBlank { "未绑定书籍" }
        val body = value.removePrefix("work:")
        val parts = body.split('/', limit = 2)
        return when {
            parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank() -> "${parts[1]} · ${parts[0]}"
            body.isNotBlank() -> body
            else -> "未绑定书籍"
        }
    }

    @Composable
    private fun AiCharacterCompanionPickerDialog(
        groups: List<CharacterPickGroup>,
        onDismiss: () -> Unit,
        onCharacterSelected: (CharacterPickGroup, BookCharacter) -> Unit
    ) {
        val style = aiComposeStyle(this@AiChatActivity)
        var selectedGroup by remember(groups) { mutableStateOf(groups.firstOrNull()) }
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = RoundedCornerShape(style.metrics.cardRadius),
                color = style.colors.pageBackground,
                border = BorderStroke(style.metrics.strokeWidth, style.colors.stroke),
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "添加角色助手",
                                color = style.colors.primaryText,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = selectedGroup?.label?.takeIf { it.isNotBlank() } ?: "选择一本有角色卡的书",
                                color = style.colors.secondaryText,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Text(
                            text = "关闭",
                            color = style.colors.accent,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(style.metrics.chipRadius))
                                .clickable(onClick = onDismiss)
                                .padding(horizontal = 10.dp, vertical = 7.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(groups, key = { it.bookKey }) { group ->
                            CharacterBookPickCard(
                                group = group,
                                selected = group.bookKey == selectedGroup?.bookKey,
                                onClick = { selectedGroup = group }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    val current = selectedGroup
                    if (current == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("没有可添加的角色卡", color = style.colors.secondaryText, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 380.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(current.characters, key = { it.id }) { character ->
                                CharacterPickRow(
                                    character = character,
                                    onClick = { onCharacterSelected(current, character) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun CharacterBookPickCard(
        group: CharacterPickGroup,
        selected: Boolean,
        onClick: () -> Unit
    ) {
        val style = aiComposeStyle(this@AiChatActivity)
        Surface(
            shape = RoundedCornerShape(style.metrics.chipRadius),
            color = if (selected) style.colors.accent.copy(alpha = 0.12f) else style.colors.cardSurface,
            border = BorderStroke(
                style.metrics.strokeWidth,
                if (selected) style.colors.accent.copy(alpha = 0.42f) else style.colors.stroke
            ),
            modifier = Modifier
                .width(132.dp)
                .clickable(onClick = onClick)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(116.dp)
                        .clip(RoundedCornerShape(style.metrics.chipRadius)),
                    factory = {
                        CoverImageView(it).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                    },
                    update = {
                        it.load(
                            path = group.coverUrl,
                            name = group.bookName.ifBlank { group.label },
                            author = group.author,
                            loadOnlyWifi = false,
                            preferThumb = true
                        )
                    }
                )
                Text(
                    text = group.bookName.ifBlank { group.label },
                    color = style.colors.primaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 7.dp)
                )
                Text(
                    text = "${group.characters.size} 个角色",
                    color = if (selected) style.colors.accent else style.colors.secondaryText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @Composable
    private fun CharacterPickRow(
        character: BookCharacter,
        onClick: () -> Unit
    ) {
        val style = aiComposeStyle(this@AiChatActivity)
        Surface(
            shape = RoundedCornerShape(style.metrics.chipRadius),
            color = style.colors.cardSurface,
            border = BorderStroke(style.metrics.strokeWidth, style.colors.stroke),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CharacterAvatar(
                    path = character.avatar,
                    contentDescription = character.displayName(),
                    sizeDp = 42,
                    modifier = Modifier.clip(CircleShape)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 10.dp)
                ) {
                    Text(
                        text = character.displayName(),
                        color = style.colors.primaryText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = listOf(character.roleLabel(), character.genderLabel(), BookCharacterProfileMeta.ageOf(character))
                            .filter { it.isNotBlank() && it != "未知" }
                            .joinToString(" · ")
                            .ifBlank { "角色卡" },
                        color = style.colors.secondaryText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Text(
                    text = "添加",
                    color = style.colors.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }
    }

    private data class CharacterPickGroup(
        val bookKey: String,
        val bookName: String,
        val author: String,
        val coverUrl: String,
        val label: String,
        val characters: List<BookCharacter>
    )
}
