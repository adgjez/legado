package io.legado.app.ui.config

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityAiImageProviderEditBinding
import io.legado.app.help.ai.backends.ProviderConnectionTester
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

class AiImageProviderEditActivity : BaseActivity<ActivityAiImageProviderEditBinding>() {

    override val binding by viewBinding(ActivityAiImageProviderEditBinding::inflate)
    private var providerId: String? = null

    // 基础区
    private var nameText by mutableStateOf("")
    private var apiKeyText by mutableStateOf("")
    private var baseUrlText by mutableStateOf("")
    private var modelText by mutableStateOf("")
    private var providerType by mutableStateOf(AiImageProviderConfig.TYPE_ARK)

    // 高级区
    private var headersText by mutableStateOf("")
    private var timeoutText by mutableStateOf("300000")
    private var stylePromptText by mutableStateOf("")
    private var paramsText by mutableStateOf("")
    private var advancedExpanded by mutableStateOf(false)

    // 状态
    private var enabledState by mutableStateOf(true)
    private var testing by mutableStateOf(false)
    private var testResult by mutableStateOf<ProviderConnectionTester.Result?>(null)

    private var editingField: Field = Field.PARAMS

    private val codeEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val text = result.data?.getStringExtra("text") ?: return@registerForActivityResult
        when (editingField) {
            Field.PARAMS -> paramsText = text
            Field.STYLE_PROMPT -> stylePromptText = text
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)
        providerType = intent.getStringExtra(EXTRA_PROVIDER_TYPE) ?: AiImageProviderConfig.TYPE_ARK
        val provider = currentProvider()
        if (provider != null) providerType = provider.type
        bind(provider)
        initComposeContent()
    }

    private fun initComposeContent() {
        val container = binding.root as? ViewGroup ?: return
        val titleBar = binding.titleBar
        val index = container.indexOfChild(titleBar)
        while (container.childCount > index + 1) {
            container.removeViewAt(index + 1)
        }
        container.removeView(titleBar)
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                AiImageProviderEditScreen(
                    name = nameText,
                    onNameChange = { nameText = it },
                    apiKey = apiKeyText,
                    onApiKeyChange = { apiKeyText = it },
                    baseUrl = baseUrlText,
                    onBaseUrlChange = { baseUrlText = it },
                    model = modelText,
                    onModelChange = { modelText = it },
                    providerType = providerType,
                    onTypeChange = {
                        providerType = it
                        testResult = null
                    },
                    headers = headersText,
                    onHeadersChange = { headersText = it },
                    timeout = timeoutText,
                    onTimeoutChange = { timeoutText = it },
                    stylePromptSummary = "${getString(R.string.ai_image_style_prompt)}: ${summary(stylePromptText)}",
                    onStylePromptClick = {
                        editingField = Field.STYLE_PROMPT
                        openCodeEditor(
                            getString(R.string.ai_image_style_prompt), stylePromptText, "text.html.markdown"
                        )
                    },
                    paramsSummary = "${getString(R.string.ai_image_params)}: ${summary(paramsText.ifBlank { defaultParams() })}",
                    onParamsClick = {
                        editingField = Field.PARAMS
                        openCodeEditor(getString(R.string.ai_image_params), paramsText.ifBlank { defaultParams() })
                    },
                    enabled = enabledState,
                    onEnabledChange = { enabledState = it },
                    advancedExpanded = advancedExpanded,
                    onAdvancedExpandedChange = { advancedExpanded = it },
                    testing = testing,
                    testResult = testResult,
                    onTestClick = { runTest() },
                    onSave = { save() },
                    onBack = { finish() }
                )
            }
        }
        container.addView(cv)
    }

    private fun bind(provider: AiImageProviderConfig?) {
        nameText = provider?.name.orEmpty()
        apiKeyText = provider?.apiKey.orEmpty()
        baseUrlText = provider?.baseUrl.orEmpty()
        modelText = provider?.model.orEmpty()
        headersText = provider?.headers.orEmpty()
        timeoutText = (provider?.validTimeout() ?: 300_000L).toString()
        enabledState = provider?.enabled ?: true
        paramsText = provider?.defaultParamsJson.orEmpty()
        stylePromptText = provider?.stylePrompt.orEmpty()
    }

    /** 用当前编辑态构建临时 config 供连通性测试，不落库。 */
    private fun buildConfigForTest(): AiImageProviderConfig {
        val old = currentProvider()
        return (old ?: AiImageProviderConfig(name = nameText, type = providerType)).copy(
            name = nameText,
            type = providerType,
            baseUrl = baseUrlText,
            apiKey = apiKeyText,
            model = modelText
        )
    }

    private fun runTest() {
        if (testing) return
        testing = true
        testResult = null
        lifecycleScope.launch {
            try {
                testResult = ProviderConnectionTester.testImage(buildConfigForTest())
            } finally {
                testing = false
            }
        }
    }

    private fun openCodeEditor(title: String, text: String, languageName: String = "source.js") {
        codeEditLauncher.launch(Intent(this, CodeEditActivity::class.java).apply {
            putExtra("title", title)
            putExtra("text", text)
            putExtra("languageName", languageName)
        })
    }

    private fun save() {
        if (nameText.isBlank()) {
            toastOnUi(R.string.ai_provider_name_required)
            return
        }
        val old = currentProvider()
        val needDefaultProvider = old == null && AppConfig.aiCurrentImageProvider == null
        val updated = (old ?: AiImageProviderConfig(name = nameText, type = providerType)).copy(
            name = nameText,
            type = providerType,
            baseUrl = baseUrlText,
            apiKey = apiKeyText,
            headers = headersText,
            model = modelText,
            defaultParamsJson = paramsText.ifBlank { defaultParams() },
            stylePrompt = stylePromptText.trim(),
            timeoutMillisecond = timeoutText.toLongOrNull() ?: 300_000L,
            enabled = if (needDefaultProvider) true else enabledState
        )
        AppConfig.aiImageProviderList = AppConfig.aiImageProviderList
            .filterNot { it.id == updated.id } + updated
        if (needDefaultProvider) {
            AppConfig.ensureCurrentImageProvider(updated.id)
        }
        postEvent(EventBus.AI_CONFIG_CHANGED, true)
        finish()
    }

    private fun currentProvider(): AiImageProviderConfig? {
        val id = providerId ?: return null
        return AppConfig.aiImageProviderList.firstOrNull { it.id == id }
    }

    private fun defaultParams(): String = ""

    private fun summary(value: String): String =
        value.trim().lineSequence().firstOrNull()?.take(36)?.ifBlank { null } ?: "未设置"

    private enum class Field {
        PARAMS,
        STYLE_PROMPT
    }

    companion object {
        private const val EXTRA_PROVIDER_ID = "providerId"
        private const val EXTRA_PROVIDER_TYPE = "providerType"

        fun newIntent(context: Context, providerId: String?, type: String): Intent {
            return Intent(context, AiImageProviderEditActivity::class.java).apply {
                putExtra(EXTRA_PROVIDER_TYPE, type)
                providerId?.let { putExtra(EXTRA_PROVIDER_ID, it) }
            }
        }
    }
}
