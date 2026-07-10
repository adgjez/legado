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
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch

class AiVideoProviderEditActivity : BaseActivity<ActivityAiImageProviderEditBinding>() {

    override val binding by viewBinding(ActivityAiImageProviderEditBinding::inflate)
    private var providerId: String? = null

    // 基础区
    private var nameText by mutableStateOf("")
    private var apiKeyText by mutableStateOf("")
    private var baseUrlText by mutableStateOf("")
    private var modelText by mutableStateOf("")
    private var providerType by mutableStateOf(AiVideoProviderConfig.TYPE_ARK)

    // 高级区
    private var submitUrlText by mutableStateOf("")
    private var pollUrlTemplateText by mutableStateOf("")
    private var headersText by mutableStateOf("")
    private var taskIdJsonPathText by mutableStateOf("\$.data.id")
    private var videoUrlJsonPathText by mutableStateOf("\$.data.video_url")
    private var statusJsonPathText by mutableStateOf("\$.data.status")
    private var doneStatusValueText by mutableStateOf("succeeded")
    private var failedStatusValueText by mutableStateOf("failed")
    private var maxReferenceImagesText by mutableStateOf("3")
    private var submitTimeoutText by mutableStateOf("60000")
    private var pollTimeoutText by mutableStateOf("600000")
    private var pollIntervalText by mutableStateOf("2000")
    private var paramsText by mutableStateOf("")
    private var advancedExpanded by mutableStateOf(false)

    // 状态
    private var enabledState by mutableStateOf(true)
    private var testing by mutableStateOf(false)
    private var testResult by mutableStateOf<ProviderConnectionTester.Result?>(null)

    private var editingParams = false

    private val codeEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val text = result.data?.getStringExtra("text") ?: return@registerForActivityResult
        if (editingParams) paramsText = text
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)
        providerType = intent.getStringExtra(EXTRA_PROVIDER_TYPE) ?: AiVideoProviderConfig.TYPE_ARK
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
                AiVideoProviderEditScreen(
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
                        // 切换类型后清空旧测试结果
                        testResult = null
                    },
                    submitUrl = submitUrlText,
                    onSubmitUrlChange = { submitUrlText = it },
                    pollUrlTemplate = pollUrlTemplateText,
                    onPollUrlTemplateChange = { pollUrlTemplateText = it },
                    taskIdJsonPath = taskIdJsonPathText,
                    onTaskIdJsonPathChange = { taskIdJsonPathText = it },
                    videoUrlJsonPath = videoUrlJsonPathText,
                    onVideoUrlJsonPathChange = { videoUrlJsonPathText = it },
                    statusJsonPath = statusJsonPathText,
                    onStatusJsonPathChange = { statusJsonPathText = it },
                    doneStatusValue = doneStatusValueText,
                    onDoneStatusValueChange = { doneStatusValueText = it },
                    failedStatusValue = failedStatusValueText,
                    onFailedStatusValueChange = { failedStatusValueText = it },
                    maxReferenceImages = maxReferenceImagesText,
                    onMaxReferenceImagesChange = { maxReferenceImagesText = it },
                    headers = headersText,
                    onHeadersChange = { headersText = it },
                    submitTimeout = submitTimeoutText,
                    onSubmitTimeoutChange = { submitTimeoutText = it },
                    pollTimeout = pollTimeoutText,
                    onPollTimeoutChange = { pollTimeoutText = it },
                    pollInterval = pollIntervalText,
                    onPollIntervalChange = { pollIntervalText = it },
                    paramsSummary = "${getString(R.string.ai_video_params)}: ${summary(paramsText.ifBlank { defaultParams() })}",
                    onParamsClick = {
                        editingParams = true
                        openCodeEditor(getString(R.string.ai_video_params), paramsText.ifBlank { defaultParams() })
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

    private fun bind(provider: AiVideoProviderConfig?) {
        nameText = provider?.name.orEmpty()
        apiKeyText = provider?.apiKey.orEmpty()
        baseUrlText = provider?.baseUrl.orEmpty()
        modelText = provider?.model.orEmpty()
        submitUrlText = provider?.submitUrl.orEmpty()
        pollUrlTemplateText = provider?.pollUrlTemplate.orEmpty()
        headersText = provider?.headers.orEmpty()
        taskIdJsonPathText = provider?.taskIdJsonPath?.ifBlank { "\$.data.id" } ?: "\$.data.id"
        videoUrlJsonPathText = provider?.videoUrlJsonPath?.ifBlank { "\$.data.video_url" } ?: "\$.data.video_url"
        statusJsonPathText = provider?.statusJsonPath?.ifBlank { "\$.data.status" } ?: "\$.data.status"
        doneStatusValueText = provider?.doneStatusValue?.ifBlank { "succeeded" } ?: "succeeded"
        failedStatusValueText = provider?.failedStatusValue?.ifBlank { "failed" } ?: "failed"
        maxReferenceImagesText = (provider?.maxReferenceImages ?: 3).toString()
        submitTimeoutText = (provider?.validSubmitTimeout() ?: 60_000L).toString()
        pollTimeoutText = (provider?.validPollTimeout() ?: 600_000L).toString()
        pollIntervalText = (provider?.validPollInterval() ?: 2_000L).toString()
        enabledState = provider?.enabled ?: true
        paramsText = provider?.defaultParamsJson.orEmpty()
    }

    /** 用当前编辑态构建临时 config 供连通性测试，不落库。 */
    private fun buildConfigForTest(): AiVideoProviderConfig {
        val old = currentProvider()
        return (old ?: AiVideoProviderConfig(name = nameText, type = providerType)).copy(
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
                testResult = ProviderConnectionTester.testVideo(buildConfigForTest())
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
        val needDefaultProvider = old == null && AppConfig.aiCurrentVideoProvider == null
        val updated = (old ?: AiVideoProviderConfig(name = nameText, type = providerType)).copy(
            name = nameText,
            type = providerType,
            baseUrl = baseUrlText,
            apiKey = apiKeyText,
            headers = headersText,
            model = modelText,
            submitUrl = submitUrlText,
            pollUrlTemplate = pollUrlTemplateText,
            taskIdJsonPath = taskIdJsonPathText.ifBlank { "\$.data.id" },
            videoUrlJsonPath = videoUrlJsonPathText.ifBlank { "\$.data.video_url" },
            statusJsonPath = statusJsonPathText.ifBlank { "\$.data.status" },
            doneStatusValue = doneStatusValueText.ifBlank { "succeeded" },
            failedStatusValue = failedStatusValueText.ifBlank { "failed" },
            defaultParamsJson = paramsText.ifBlank { defaultParams() },
            maxReferenceImages = maxReferenceImagesText.toIntOrNull()?.coerceIn(0, 16) ?: 3,
            submitTimeoutMillisecond = submitTimeoutText.toLongOrNull() ?: 60_000L,
            pollTimeoutMillisecond = pollTimeoutText.toLongOrNull() ?: 600_000L,
            pollIntervalMillisecond = pollIntervalText.toLongOrNull() ?: 2_000L,
            enabled = if (needDefaultProvider) true else enabledState
        )
        AppConfig.aiVideoProviderList = AppConfig.aiVideoProviderList
            .filterNot { it.id == updated.id } + updated
        if (needDefaultProvider) {
            AppConfig.ensureCurrentVideoProvider(updated.id)
        }
        postEvent(EventBus.AI_CONFIG_CHANGED, true)
        finish()
    }

    private fun currentProvider(): AiVideoProviderConfig? {
        val id = providerId ?: return null
        return AppConfig.aiVideoProviderList.firstOrNull { it.id == id }
    }

    private fun defaultParams(): String = ""

    private fun summary(value: String): String =
        value.trim().lineSequence().firstOrNull()?.take(36)?.ifBlank { null } ?: "未设置"

    companion object {
        private const val EXTRA_PROVIDER_ID = "providerId"
        private const val EXTRA_PROVIDER_TYPE = "providerType"

        fun newIntent(context: Context, providerId: String?, type: String): Intent {
            return Intent(context, AiVideoProviderEditActivity::class.java).apply {
                putExtra(EXTRA_PROVIDER_TYPE, type)
                providerId?.let { putExtra(EXTRA_PROVIDER_ID, it) }
            }
        }
    }
}
