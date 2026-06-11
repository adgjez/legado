package io.legado.app.ui.config

import android.content.Intent
import android.text.InputType
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogAiMcpServerEditBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.ai.AiToolRegistry
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiMcpServerConfig
import io.legado.app.ui.main.ai.AiImageGalleryActivity
import io.legado.app.ui.main.ai.AiSkillConfig
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiConfigFragment : ComposeSettingFragment() {

    private val defaultSkillUrls = listOf(
        "https://raw.githubusercontent.com/DandanLLab/legadoSkill/main/.trae/skills/legado-book-source-tamer/SKILL.md",
        "https://raw.githubusercontent.com/DandanLLab/legadoSkill/main/skills/SKILLV0.7.md",
        "https://raw.githubusercontent.com/DandanLLab/legadoSkill/main/SKILL.md"
    )

    private companion object {
        const val KEY_IMPORT_DEFAULT_SKILL = "aiImportDefaultSkill"
        const val KEY_MANAGE_NATIVE_TOOLS = "aiManageNativeTools"
        const val KEY_CONTEXT_COMPRESSION = "aiContextCompression"
        const val KEY_WORLD_BOOK_MANAGE = "aiWorldBookManage"
        const val KEY_DEFAULT_MODEL_SETTINGS = "aiDefaultModelSettings"
        const val KEY_IMAGE_GALLERY = "aiImageGallery"
        const val KEY_IMAGE_PROVIDER_MANAGE = "aiImageProviderManage"
        const val KEY_MANAGE_PROVIDERS = "aiManageProviders"
        const val KEY_ADD_MCP_SERVER = "aiAddMcpServer"
        const val KEY_MANAGE_MCP_SERVERS = "aiManageMcpServers"
    }

    override val titleRes: Int = R.string.ai_setting

    override fun onViewCreated(view: android.view.View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        observeEvent<Boolean>(EventBus.AI_CONFIG_CHANGED) {
            refreshUi()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun buildPageSpec(): SettingPageSpec {
        val canEnable = AppConfig.aiCurrentModelConfig != null
        val currentProvider = AppConfig.aiCurrentProvider
        val mcpServers = AppConfig.aiMcpServerList
        val enabledMcpCount = mcpServers.count { it.enabled }
        val imageProviders = AppConfig.aiImageProviderList
        val skills = AppConfig.aiSkillList
        val enabledSkillCount = skills.count { it.enabled }
        val worldBooks = AppConfig.aiWorldBookList
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    title = getString(R.string.ai_assistant),
                    items = listOf(
                        SettingSwitchSpec(
                            key = PreferKey.aiAssistantEnabled,
                            title = getString(R.string.ai_enable),
                            summary = getString(
                                if (canEnable) {
                                    R.string.ai_enable_summary
                                } else {
                                    R.string.ai_enable_summary_disabled
                                }
                            ),
                            checked = AppConfig.aiAssistantEnabled,
                            enabled = canEnable,
                            onCheckedChange = { AppConfig.aiAssistantEnabled = it }
                        ),
                        SettingActionSpec(
                            key = KEY_IMPORT_DEFAULT_SKILL,
                            title = getString(R.string.ai_import_default_skill),
                            summary = getString(R.string.ai_import_default_skill_summary),
                            onClick = ::importDefaultSkill
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiSkillPrompt,
                            title = getString(R.string.ai_skill_prompt),
                            summary = if (skills.isEmpty()) {
                                getString(R.string.ai_skill_prompt_summary_empty)
                            } else {
                                getString(
                                    R.string.ai_skill_prompt_summary,
                                    enabledSkillCount,
                                    skills.size
                                )
                            },
                            onClick = ::showManageSkillsDialog
                        ),
                        SettingActionSpec(
                            key = KEY_MANAGE_NATIVE_TOOLS,
                            title = getString(R.string.ai_manage_native_tools),
                            summary = "${getString(R.string.ai_manage_native_tools_summary)} · ${AiToolRegistry.effectiveEnabledToolNames().size}",
                            onClick = ::showManageNativeToolsDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiReadToolMode,
                            title = "正文问 AI 工具范围",
                            summary = readToolModeLabel(),
                            onClick = ::showReadToolModeDialog
                        ),
                        switch(
                            key = PreferKey.aiEnterToSend,
                            title = getString(R.string.ai_enter_to_send),
                            summary = getString(R.string.ai_enter_to_send_summary),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.aiThinkingToolbarEnabled,
                            title = "显示思考工具栏",
                            summary = "关闭后聊天页不显示思考和工具调用过程卡片，不影响后台执行",
                            defaultValue = true
                        ),
                        SettingActionSpec(
                            key = KEY_CONTEXT_COMPRESSION,
                            title = getString(R.string.ai_context_compression),
                            summary = if (AppConfig.aiContextCompressionEnabled) {
                                "${AppConfig.aiContextWindowTokens} / ${AppConfig.aiThinkingContextTokens}"
                            } else {
                                getString(R.string.ai_context_compression_summary_default)
                            },
                            onClick = ::showContextCompressionDialog
                        ),
                        SettingActionSpec(
                            key = KEY_WORLD_BOOK_MANAGE,
                            title = "世界书管理",
                            summary = if (worldBooks.isEmpty()) {
                                "未配置世界书"
                            } else {
                                "${worldBooks.count { it.enabled }}/${worldBooks.size} 启用 · ${worldBooks.sumOf { it.entries.size }} 条目"
                            },
                            onClick = {
                                startActivity(Intent(requireContext(), AiWorldBookManageActivity::class.java))
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_DEFAULT_MODEL_SETTINGS,
                            title = "默认模型",
                            summary = "问AI ${modelLabel(AppConfig.aiAskModelConfig)} / 总结 ${modelLabel(AppConfig.aiSummaryModelConfig)} / 多角色 ${modelLabel(AppConfig.aiReadAloudRoleModelConfig)} / 生图 ${imageProviderLabel()}",
                            onClick = ::showDefaultModelSettingsDialog
                        ),
                        SettingActionSpec(
                            key = KEY_IMAGE_GALLERY,
                            title = getString(R.string.ai_image_gallery),
                            summary = getString(R.string.ai_image_gallery_summary),
                            onClick = {
                                startActivity(Intent(requireContext(), AiImageGalleryActivity::class.java))
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_IMAGE_PROVIDER_MANAGE,
                            title = getString(R.string.ai_image_provider_manage),
                            summary = if (imageProviders.isEmpty()) {
                                getString(R.string.ai_image_provider_summary_empty)
                            } else {
                                getString(
                                    R.string.ai_image_provider_summary,
                                    imageProviders.count { it.enabled },
                                    imageProviders.size
                                )
                            },
                            onClick = {
                                startActivity(Intent(requireContext(), AiImageProviderManageActivity::class.java))
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.ai_provider),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_MANAGE_PROVIDERS,
                            title = getString(R.string.ai_manage_providers),
                            summary = if (AppConfig.aiProviderList.isEmpty()) {
                                getString(R.string.ai_no_providers)
                            } else {
                                buildString {
                                    append(currentProvider?.name ?: getString(R.string.ai_current_provider_summary_empty))
                                    append(" · ")
                                    append(getString(R.string.ai_manage_providers_summary, AppConfig.aiProviderList.size))
                                }
                            },
                            onClick = {
                                startActivity(Intent(requireContext(), AiProviderManageActivity::class.java))
                            }
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.ai_mcp),
                    items = listOf(
                        SettingActionSpec(
                            key = KEY_ADD_MCP_SERVER,
                            title = getString(R.string.ai_add_mcp_server),
                            summary = getString(R.string.ai_add_mcp_server_summary),
                            onClick = { showEditMcpServerDialog() }
                        ),
                        SettingActionSpec(
                            key = KEY_MANAGE_MCP_SERVERS,
                            title = getString(R.string.ai_manage_mcp_servers),
                            summary = if (mcpServers.isEmpty()) {
                                getString(R.string.ai_no_mcp_servers)
                            } else {
                                getString(
                                    R.string.ai_manage_mcp_servers_summary,
                                    enabledMcpCount,
                                    mcpServers.size
                                )
                            },
                            onClick = ::showManageMcpServersDialog
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.ai_web_tools),
                    items = listOf(
                        SettingSwitchSpec(
                            key = PreferKey.aiTavilyEnabled,
                            title = getString(R.string.ai_tavily_enable),
                            summary = getString(
                                if (AppConfig.aiTavilyApiKey.isBlank()) {
                                    R.string.ai_tavily_enable_summary_missing
                                } else {
                                    R.string.ai_tavily_enable_summary
                                }
                            ),
                            checked = booleanSetting(PreferKey.aiTavilyEnabled, false),
                            onCheckedChange = { updateBooleanSetting(PreferKey.aiTavilyEnabled, it) }
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiTavilyApiKey,
                            title = getString(R.string.ai_tavily_api_key),
                            summary = if (AppConfig.aiTavilyApiKey.isBlank()) {
                                getString(R.string.ai_tavily_api_key_summary)
                            } else {
                                getString(R.string.ai_tavily_api_key_summary_ready)
                            },
                            onClick = ::showTavilyApiKeyDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiTavilyBaseUrl,
                            title = getString(R.string.ai_tavily_base_url),
                            summary = AppConfig.aiTavilyBaseUrl,
                            onClick = ::showTavilyBaseUrlDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiTavilyTopic,
                            title = getString(R.string.ai_tavily_topic),
                            summary = tavilyTopicLabel(),
                            onClick = ::showTavilyTopicDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiTavilySearchDepth,
                            title = getString(R.string.ai_tavily_search_depth),
                            summary = tavilySearchDepthLabel(),
                            onClick = ::showTavilySearchDepthDialog
                        ),
                        SettingActionSpec(
                            key = PreferKey.aiTavilyMaxResults,
                            title = getString(R.string.ai_tavily_max_results),
                            summary = AppConfig.aiTavilyMaxResults.toString(),
                            onClick = ::showTavilyMaxResultsDialog
                        )
                    )
                )
            )
        )
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.aiAssistantEnabled -> refreshUi(notifyMain = true)
            PreferKey.aiAskModelId,
            PreferKey.aiSummaryModelId,
            PreferKey.aiReadAloudRoleModelId,
            PreferKey.aiCurrentImageProviderId -> refreshUi()
            PreferKey.aiReadToolMode -> refreshUi()
        }
    }

    private fun switch(
        key: String,
        title: String,
        summary: String,
        defaultValue: Boolean
    ): SettingSwitchSpec {
        return SettingSwitchSpec(
            key = key,
            title = title,
            summary = summary,
            checked = booleanSetting(key, defaultValue),
            onCheckedChange = { updateBooleanSetting(key, it) }
        )
    }

    private fun readToolModeLabel(): String {
        return when (AppConfig.aiReadToolMode) {
            AppConfig.AI_READ_TOOL_MODE_ALL -> "全量工具"
            AppConfig.AI_READ_TOOL_MODE_SAFE -> "阅读安全工具"
            else -> "使用已启用工具"
        }
    }

    private fun tavilyTopicLabel(): String {
        return getString(
            when (AppConfig.aiTavilyTopic) {
                "news" -> R.string.ai_tavily_topic_news
                "finance" -> R.string.ai_tavily_topic_finance
                else -> R.string.ai_tavily_topic_general
            }
        )
    }

    private fun tavilySearchDepthLabel(): String {
        return getString(
            when (AppConfig.aiTavilySearchDepth) {
                "advanced" -> R.string.ai_tavily_search_depth_advanced
                "ultra-fast" -> R.string.ai_tavily_search_depth_ultra_fast
                else -> R.string.ai_tavily_search_depth_basic
            }
        )
    }

    private fun showReadToolModeDialog() {
        val values = listOf(
            AppConfig.AI_READ_TOOL_MODE_ENABLED,
            AppConfig.AI_READ_TOOL_MODE_SAFE,
            AppConfig.AI_READ_TOOL_MODE_ALL
        )
        val labels = listOf(
            "使用已启用工具",
            "阅读安全工具",
            "全量工具"
        )
        showComposeActionListDialog(
            title = "正文问 AI 工具范围",
            labels = labels.mapIndexed { index, label ->
                if (values[index] == AppConfig.aiReadToolMode) "$label ✓" else label
            }
        ) { index ->
            AppConfig.aiReadToolMode = values[index]
            refreshUi()
        }
    }

    private fun showDefaultModelSettingsDialog() {
        val items = listOf(
            "正文问 AI：${modelLabel(AppConfig.aiAskModelConfig)}",
            "文章总结：${modelLabel(AppConfig.aiSummaryModelConfig)}",
            "多角色：${modelLabel(AppConfig.aiReadAloudRoleModelConfig)}",
            "图像生成供应商：${imageProviderLabel()}"
        )
        requireContext().selector("默认模型", items) { _, _, index ->
            when (index) {
                0 -> selectDefaultModel("正文问 AI 模型", AppConfig.aiAskModelId) {
                    AppConfig.aiAskModelId = it
                }
                1 -> selectDefaultModel("文章总结模型", AppConfig.aiSummaryModelId) {
                    AppConfig.aiSummaryModelId = it
                }
                2 -> selectDefaultModel("多角色模型", AppConfig.aiReadAloudRoleModelId) {
                    AppConfig.aiReadAloudRoleModelId = it
                }
                3 -> selectDefaultImageProvider()
            }
        }
    }

    private fun selectDefaultModel(
        title: String,
        currentId: String?,
        onSelect: (String) -> Unit
    ) {
        val models = AppConfig.aiModelConfigList
        if (models.isEmpty()) {
            toastOnUi(R.string.ai_no_models)
            return
        }
        val providerNameMap = AppConfig.aiProviderList.associateBy({ it.id }, { it.name })
        requireContext().selector(
            title,
            models.map { model ->
                val label = providerNameMap[model.providerId]?.takeIf { it.isNotBlank() }
                    ?.let { "${model.modelId} - $it" }
                    ?: model.modelId
                if (model.id == currentId) "$label ✓" else label
            }
        ) { _, _, index ->
            onSelect(models[index].id)
            refreshUi()
        }
    }

    private fun selectDefaultImageProvider() {
        val providers = AppConfig.aiEnabledImageProviders
        if (providers.isEmpty()) {
            toastOnUi(R.string.ai_image_provider_summary_empty)
            return
        }
        val currentId = AppConfig.aiCurrentImageProvider?.id
        requireContext().selector(
            "图像生成供应商",
            providers.map { provider ->
                val label = provider.displayName()
                if (provider.id == currentId) "$label ✓" else label
            }
        ) { _, _, index ->
            AppConfig.aiCurrentImageProviderId = providers[index].id
            refreshUi()
        }
    }

    private fun modelLabel(model: AiModelConfig?): String {
        model ?: return "未配置"
        val providerName = AppConfig.aiProviderList.firstOrNull { it.id == model.providerId }
            ?.name
            ?.takeIf { it.isNotBlank() }
        return providerName?.let { "${model.modelId} - $it" } ?: model.modelId
    }

    private fun imageProviderLabel(): String {
        return AppConfig.aiCurrentImageProvider?.displayName() ?: "未配置"
    }

    private fun showEditMcpServerDialog(server: AiMcpServerConfig? = null) {
        val binding = DialogAiMcpServerEditBinding.inflate(layoutInflater).apply {
            editMcpServerName.setText(server?.name.orEmpty())
            editMcpServerEndpoint.setText(server?.endpoint.orEmpty())
            editMcpServerApiKey.setText(server?.apiKey.orEmpty())
            checkMcpServerEnabled.isChecked = server?.enabled ?: true
        }
        alert(
            title = getString(
                if (server == null) R.string.ai_add_mcp_server else R.string.ai_edit_mcp_server
            )
        ) {
            customView { binding.root }
            okButton {
                val name = binding.editMcpServerName.text?.toString()?.trim().orEmpty()
                val endpoint = binding.editMcpServerEndpoint.text?.toString()?.trim().orEmpty()
                val apiKey = binding.editMcpServerApiKey.text?.toString()?.trim().orEmpty()
                when {
                    name.isEmpty() -> {
                        toastOnUi(R.string.ai_mcp_server_name_required)
                        return@okButton
                    }

                    endpoint.isEmpty() -> {
                        toastOnUi(R.string.ai_mcp_server_endpoint_required)
                        return@okButton
                    }
                }
                val servers = AppConfig.aiMcpServerList.toMutableList()
                val updated = server?.copy(
                    name = name,
                    endpoint = endpoint,
                    apiKey = apiKey,
                    enabled = binding.checkMcpServerEnabled.isChecked
                ) ?: AiMcpServerConfig(
                    name = name,
                    endpoint = endpoint,
                    apiKey = apiKey,
                    enabled = binding.checkMcpServerEnabled.isChecked
                )
                val targetIndex = servers.indexOfFirst { it.id == updated.id }
                if (targetIndex >= 0) {
                    servers[targetIndex] = updated
                } else {
                    servers.add(updated)
                }
                AppConfig.aiMcpServerList = servers
                refreshUi()
                toastOnUi(R.string.ai_mcp_server_saved)
            }
            cancelButton()
        }
    }

    private fun showManageMcpServersDialog() {
        val servers = AppConfig.aiMcpServerList
        if (servers.isEmpty()) {
            toastOnUi(R.string.ai_no_mcp_servers)
            return
        }
        context?.selector(
            getString(R.string.ai_manage_mcp_servers),
            servers.map { server ->
                buildString {
                    append(server.name)
                    if (!server.enabled) append(" (off)")
                }
            }
        ) { _, _, index ->
            val server = servers[index]
            context?.selector(
                server.name,
                arrayListOf(
                    getString(
                        if (server.enabled) {
                            R.string.ai_disable_mcp_server
                        } else {
                            R.string.ai_enable_mcp_server
                        }
                    ),
                    getString(R.string.ai_edit_mcp_server),
                    getString(R.string.ai_remove_mcp_server)
                )
            ) { _, action ->
                when (action) {
                    0 -> {
                        AppConfig.aiMcpServerList = AppConfig.aiMcpServerList.map {
                            if (it.id == server.id) it.copy(enabled = !it.enabled) else it
                        }
                        refreshUi()
                    }

                    1 -> showEditMcpServerDialog(server)
                    2 -> confirmRemoveMcpServer(server)
                }
            }
        }
    }

    private fun confirmRemoveMcpServer(server: AiMcpServerConfig) {
        alert(
            title = server.name,
            message = getString(R.string.ai_remove_mcp_server_confirm)
        ) {
            okButton {
                AppConfig.aiMcpServerList = AppConfig.aiMcpServerList.filterNot { it.id == server.id }
                refreshUi()
                toastOnUi(R.string.ai_mcp_server_removed)
            }
            cancelButton()
        }
    }

    private fun showSystemPromptDialog() {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.ai_system_prompt_hint)
            editView.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            editView.minLines = 8
            editView.setText(AppConfig.aiSystemPrompt)
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(titleResource = R.string.ai_system_prompt) {
            customView { binding.root }
            okButton {
                AppConfig.aiSystemPrompt = binding.editView.text?.toString().orEmpty()
                refreshUi()
            }
            neutralButton(R.string.restore_default) {
                AppConfig.aiSystemPrompt = AppConfig.DEFAULT_AI_SYSTEM_PROMPT
                refreshUi()
            }
            cancelButton()
        }
    }

    private fun showContextCompressionDialog() {
        val enabledText = if (AppConfig.aiContextCompressionEnabled) "关闭上下文压缩" else "启用上下文压缩"
        context?.selector(
            getString(R.string.ai_context_compression),
            arrayListOf(
                enabledText,
                "上下文长度: ${AppConfig.aiContextWindowTokens}",
                "思考上下文: ${AppConfig.aiThinkingContextTokens}"
            )
        ) { _, index ->
            when (index) {
                0 -> {
                    AppConfig.aiContextCompressionEnabled = !AppConfig.aiContextCompressionEnabled
                    refreshUi()
                }
                1 -> showTokenSelector(true)
                2 -> showTokenSelector(false)
            }
        }
    }

    private fun showTokenSelector(contextWindow: Boolean) {
        val values = if (contextWindow) {
            listOf(32_000, 64_000, 128_000, 258_000, 512_000, 1_000_000)
        } else {
            listOf(0, 32_000, 64_000, 128_000, 258_000)
        }
        context?.selector(
            if (contextWindow) getString(R.string.ai_context_tokens) else getString(R.string.ai_thinking_context_tokens),
            values.map { it.toString() }
        ) { _, index ->
            if (contextWindow) AppConfig.aiContextWindowTokens = values[index]
            else AppConfig.aiThinkingContextTokens = values[index]
            refreshUi()
        }
    }

    private fun showManageNativeToolsDialog() {
        lifecycleScope.launch {
            val tools = runCatching { AiToolRegistry.resolveAllToolNamesForManage() }
                .getOrDefault(emptyList())
            if (tools.isEmpty()) {
                toastOnUi(R.string.not_available)
                return@launch
            }
            val enabled = AiToolRegistry.effectiveEnabledToolNames().toMutableSet()
            val groupedTools = tools.map { AiToolRegistry.metaOfTool(it) }
                .groupBy { it.group }
                .toSortedMap(compareBy { groupOrder(it) })
            val displayItems = mutableListOf<ToolDisplayItem>()
            groupedTools.forEach { (group, groupTools) ->
                displayItems.add(ToolDisplayItem.Header(group))
                groupTools.sortedBy { it.label }.forEach { tool ->
                    val isEnabled = tool.name in enabled
                    displayItems.add(ToolDisplayItem.Tool(tool.name, tool.label, isEnabled))
                }
            }
            alert(getString(R.string.ai_manage_native_tools)) {
                customView { createToolListView(displayItems, enabled) }
                okButton {
                    AppConfig.aiEnabledToolNames = enabled
                    refreshUi()
                }
                negativeButton(R.string.select_all) {
                    AppConfig.aiEnabledToolNames = tools.toSet()
                    refreshUi()
                }
                neutralButton(R.string.restore_default) {
                    AppConfig.aiEnabledToolNames = emptySet()
                    refreshUi()
                }
                cancelButton()
            }
        }
    }

    private fun groupOrder(group: String): Int {
        return when (group) {
            "书架" -> 0
            "书源" -> 1
            "阅读" -> 2
            "阅读网络" -> 3
            "联网搜索" -> 4
            "AI 生图" -> 5
            "角色资料" -> 6
            "设置" -> 7
            "MCP 工具" -> 8
            else -> 8
        }
    }

    private fun showTavilyApiKeyDialog() {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.ai_tavily_api_key_hint)
            editView.inputType = InputType.TYPE_CLASS_TEXT
            editView.setText(AppConfig.aiTavilyApiKey)
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(titleResource = R.string.ai_tavily_api_key) {
            customView { binding.root }
            okButton {
                AppConfig.aiTavilyApiKey = binding.editView.text?.toString().orEmpty()
                refreshUi()
            }
            cancelButton()
        }
    }

    private fun showTavilyBaseUrlDialog() {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = "https://api.tavily.com/search"
            editView.inputType = InputType.TYPE_CLASS_TEXT
            editView.setText(AppConfig.aiTavilyBaseUrl)
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(titleResource = R.string.ai_tavily_base_url) {
            customView { binding.root }
            okButton {
                AppConfig.aiTavilyBaseUrl = binding.editView.text?.toString().orEmpty()
                refreshUi()
            }
            neutralButton(R.string.restore_default) {
                AppConfig.aiTavilyBaseUrl = "https://api.tavily.com/search"
                refreshUi()
            }
            cancelButton()
        }
    }

    private fun showTavilyTopicDialog() {
        val values = listOf("general", "news", "finance")
        val labels = listOf(
            getString(R.string.ai_tavily_topic_general),
            getString(R.string.ai_tavily_topic_news),
            getString(R.string.ai_tavily_topic_finance)
        )
        showComposeActionListDialog(
            title = getString(R.string.ai_tavily_topic),
            labels = labels
        ) { index ->
            AppConfig.aiTavilyTopic = values[index]
            refreshUi()
        }
    }

    private fun showTavilySearchDepthDialog() {
        val values = listOf("basic", "advanced", "ultra-fast")
        val labels = listOf(
            getString(R.string.ai_tavily_search_depth_basic),
            getString(R.string.ai_tavily_search_depth_advanced),
            getString(R.string.ai_tavily_search_depth_ultra_fast)
        )
        showComposeActionListDialog(
            title = getString(R.string.ai_tavily_search_depth),
            labels = labels
        ) { index ->
            AppConfig.aiTavilySearchDepth = values[index]
            refreshUi()
        }
    }

    private fun showTavilyMaxResultsDialog() {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = "1-10"
            editView.inputType = InputType.TYPE_CLASS_NUMBER
            editView.setText(AppConfig.aiTavilyMaxResults.toString())
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(titleResource = R.string.ai_tavily_max_results) {
            customView { binding.root }
            okButton {
                val value = binding.editView.text?.toString()?.trim()?.toIntOrNull()
                if (value == null) {
                    toastOnUi(R.string.ai_tavily_max_results_invalid)
                    return@okButton
                }
                AppConfig.aiTavilyMaxResults = value
                refreshUi()
            }
            cancelButton()
        }
    }

    private fun importDefaultSkill() {
        toastOnUi(R.string.ai_skill_importing)
        lifecycleScope.launch {
            val result = withContext(IO) {
                runCatching {
                    var lastError = ""
                    defaultSkillUrls.forEach { skillUrl ->
                        okHttpClient.newCallResponse {
                            url(skillUrl)
                        }.use { response ->
                            if (response.isSuccessful) {
                                return@runCatching skillUrl to response.body.string()
                            }
                            lastError = "${response.code} ${response.message}"
                        }
                    }
                    error(lastError.ifBlank { "No available SKILL.md" })
                }
            }
            result.onSuccess { (skillUrl, skill) ->
                if (skill.isBlank()) {
                    toastOnUi(R.string.ai_skill_import_empty)
                    return@onSuccess
                }
                val skillConfig = parseSkillConfig(skill, skillUrl)
                AppConfig.aiSkillList = AppConfig.aiSkillList
                    .filterNot { it.sourceUrl == skillConfig.sourceUrl || it.name == skillConfig.name }
                    .plus(skillConfig)
                refreshUi()
                toastOnUi(R.string.ai_skill_imported)
            }.onFailure {
                toastOnUi(getString(R.string.ai_skill_import_failed, it.localizedMessage ?: "Error"))
            }
        }
    }

    private fun showManageSkillsDialog() {
        val skills = AppConfig.aiSkillList
        val actions = mutableListOf(getString(R.string.ai_add_skill_manual))
        actions += skills.map { skill ->
            buildString {
                append(skill.name)
                append(" · ")
                append(
                    getString(
                        if (skill.enabled) R.string.enabled else R.string.disabled
                    )
                )
            }
        }
        context?.selector(getString(R.string.ai_manage_skills), actions) { _, _, index ->
            if (index == 0) {
                showSkillEditDialog()
            } else {
                showSkillActionDialog(skills[index - 1])
            }
        }
    }

    private fun showSkillActionDialog(skill: AiSkillConfig) {
        context?.selector(
            skill.name,
            arrayListOf(
                getString(if (skill.enabled) R.string.disable else R.string.enable),
                getString(R.string.edit),
                getString(R.string.delete)
            )
        ) { _, action ->
            when (action) {
                0 -> {
                    AppConfig.aiSkillList = AppConfig.aiSkillList.map {
                        if (it.id == skill.id) it.copy(enabled = !it.enabled) else it
                    }
                    refreshUi()
                }

                1 -> showSkillEditDialog(skill)
                2 -> confirmRemoveSkill(skill)
            }
        }
    }

    private fun showSkillEditDialog(skill: AiSkillConfig? = null) {
        val binding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.ai_skill_prompt_hint)
            editView.inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            editView.minLines = 8
            editView.setText(skill?.content.orEmpty())
            editView.setSelection(editView.text?.length ?: 0)
        }
        alert(titleResource = R.string.ai_skill_prompt) {
            customView { binding.root }
            okButton {
                val content = binding.editView.text?.toString().orEmpty()
                if (content.isBlank()) {
                    toastOnUi(R.string.ai_skill_import_empty)
                    return@okButton
                }
                val updated = parseSkillConfig(content, skill?.sourceUrl.orEmpty(), skill)
                val skills = AppConfig.aiSkillList.toMutableList()
                val index = skills.indexOfFirst { it.id == updated.id }
                if (index >= 0) {
                    skills[index] = updated
                } else {
                    skills.add(updated)
                }
                AppConfig.aiSkillList = skills
                refreshUi()
            }
            cancelButton()
        }
    }

    private fun confirmRemoveSkill(skill: AiSkillConfig) {
        alert(
            title = skill.name,
            message = getString(R.string.ai_remove_skill_confirm)
        ) {
            okButton {
                AppConfig.aiSkillList = AppConfig.aiSkillList.filterNot { it.id == skill.id }
                refreshUi()
            }
            cancelButton()
        }
    }

    private fun parseSkillConfig(
        content: String,
        sourceUrl: String = "",
        oldSkill: AiSkillConfig? = null
    ): AiSkillConfig {
        val name = Regex("""(?m)^\s*name:\s*["']?([^"'\n]+)["']?\s*$""")
            .find(content)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
        val description = Regex("""(?m)^\s*description:\s*["']?([^"'\n]+)["']?\s*$""")
            .find(content)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
        return (oldSkill ?: AiSkillConfig(
            name = name.ifBlank { getString(R.string.ai_skill_default_name) },
            content = content
        )).copy(
            name = name.ifBlank { oldSkill?.name ?: getString(R.string.ai_skill_default_name) },
            description = description.ifBlank { oldSkill?.description.orEmpty() },
            content = content.trim(),
            sourceUrl = sourceUrl.ifBlank { oldSkill?.sourceUrl.orEmpty() },
            enabled = oldSkill?.enabled ?: true
        )
    }

    private fun refreshUi(notifyMain: Boolean = false) {
        val canEnable = AppConfig.aiCurrentModelConfig != null
        val storedEnabled = booleanSetting(PreferKey.aiAssistantEnabled, false)
        if (!canEnable && storedEnabled) {
            AppConfig.aiAssistantEnabled = false
        }
        refreshSettings()
        if (notifyMain || (!canEnable && storedEnabled)) {
            postEvent(EventBus.NOTIFY_MAIN, false)
        }
    }

    private sealed class ToolDisplayItem {
        data class Header(val title: String) : ToolDisplayItem()
        data class Tool(val name: String, val displayName: String, var isEnabled: Boolean) : ToolDisplayItem()
    }

    private fun createToolListView(
        items: List<ToolDisplayItem>,
        enabled: MutableSet<String>
    ): android.widget.ScrollView {
        val scrollView = android.widget.ScrollView(requireContext())
        val linearLayout = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val dp8 = (8 * resources.displayMetrics.density).toInt()
            val dp16 = (16 * resources.displayMetrics.density).toInt()
            setPadding(dp16, dp8, dp16, dp8)
        }
        items.forEach { item ->
            when (item) {
                is ToolDisplayItem.Header -> {
                    val headerView = android.widget.TextView(requireContext()).apply {
                        text = item.title
                        textSize = 16f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        val dp16 = (16 * resources.displayMetrics.density).toInt()
                        val dp8 = (8 * resources.displayMetrics.density).toInt()
                        setPadding(0, dp16, 0, dp8)
                    }
                    linearLayout.addView(headerView)
                }
                is ToolDisplayItem.Tool -> {
                    val checkBox = android.widget.CheckBox(requireContext()).apply {
                        text = item.displayName
                        isChecked = item.isEnabled
                        setOnCheckedChangeListener { _, isChecked ->
                            item.isEnabled = isChecked
                            if (isChecked) enabled.add(item.name) else enabled.remove(item.name)
                        }
                    }
                    linearLayout.addView(checkBox)
                }
            }
        }
        scrollView.addView(linearLayout)
        return scrollView
    }
}
