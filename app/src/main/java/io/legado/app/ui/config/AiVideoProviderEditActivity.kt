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
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.EventBus
import io.legado.app.databinding.ActivityAiImageProviderEditBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.code.CodeEditActivity
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding

class AiVideoProviderEditActivity : BaseActivity<ActivityAiImageProviderEditBinding>() {

    override val binding by viewBinding(ActivityAiImageProviderEditBinding::inflate)
    private var providerId: String? = null

    // Compose state
    private var nameText by mutableStateOf("")
    private var submitUrlText by mutableStateOf("")
    private var pollUrlTemplateText by mutableStateOf("")
    private var apiKeyText by mutableStateOf("")
    private var modelText by mutableStateOf("")
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
    private var enabledState by mutableStateOf(true)
    private var providerType by mutableStateOf(AiVideoProviderConfig.TYPE_OPENAI)
    private var paramsText by mutableStateOf("")
    private var scriptText by mutableStateOf("")
    private var jsLibText by mutableStateOf("")
    private var editingField: Field = Field.PARAMS

    private val codeEditLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val text = result.data?.getStringExtra("text") ?: return@registerForActivityResult
        when (editingField) {
            Field.PARAMS -> paramsText = text
            Field.SCRIPT -> scriptText = text
            Field.JS_LIB -> jsLibText = text
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        providerId = intent.getStringExtra(EXTRA_PROVIDER_ID)
        providerType = intent.getStringExtra(EXTRA_PROVIDER_TYPE) ?: AiVideoProviderConfig.TYPE_OPENAI
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
                val isOpenAi = providerType == AiVideoProviderConfig.TYPE_OPENAI ||
                    providerType == AiVideoProviderConfig.TYPE_AGNES ||
                    providerType == AiVideoProviderConfig.TYPE_DOUBAO
                AiVideoProviderEditScreen(
                    name = nameText,
                    onNameChange = { nameText = it },
                    submitUrl = submitUrlText,
                    onSubmitUrlChange = { submitUrlText = it },
                    pollUrlTemplate = pollUrlTemplateText,
                    onPollUrlTemplateChange = { pollUrlTemplateText = it },
                    apiKey = apiKeyText,
                    onApiKeyChange = { apiKeyText = it },
                    model = modelText,
                    onModelChange = { modelText = it },
                    headers = headersText,
                    onHeadersChange = { headersText = it },
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
                    submitTimeout = submitTimeoutText,
                    onSubmitTimeoutChange = { submitTimeoutText = it },
                    pollTimeout = pollTimeoutText,
                    onPollTimeoutChange = { pollTimeoutText = it },
                    pollInterval = pollIntervalText,
                    onPollIntervalChange = { pollIntervalText = it },
                    enabled = enabledState,
                    onEnabledChange = { enabledState = it },
                    providerType = providerType,
                    isOpenAi = isOpenAi,
                    onTypeClick = { selectType() },
                    paramsSummary = "${getString(R.string.ai_video_params)}: ${summary(paramsText.ifBlank { defaultParams() })}",
                    onParamsClick = {
                        openCodeEditor(
                            Field.PARAMS,
                            getString(R.string.ai_video_params),
                            paramsText.ifBlank { defaultParams() }
                        )
                    },
                    scriptSummary = "${getString(R.string.ai_video_script)}: ${summary(scriptText)}",
                    onScriptClick = {
                        openCodeEditor(Field.SCRIPT, getString(R.string.ai_video_script), scriptText)
                    },
                    jsLibSummary = "jsLib: ${summary(jsLibText)}",
                    onJsLibClick = {
                        openCodeEditor(Field.JS_LIB, "jsLib", jsLibText)
                    },
                    onSave = { save() },
                    onBack = { finish() }
                )
            }
        }
        container.addView(cv)
    }

    private fun bind(provider: AiVideoProviderConfig?) {
        if (provider == null && providerType == AiVideoProviderConfig.TYPE_AGNES) {
            // 新建 Agnes Provider 时预填配置
            applyAgnesPreset()
            return
        }
        if (provider == null && providerType == AiVideoProviderConfig.TYPE_DOUBAO) {
            // 新建豆包 Seedance 2.0 Provider 时预填配置
            applyDoubaoPreset()
            return
        }
        nameText = provider?.name.orEmpty()
        submitUrlText = provider?.submitUrl.orEmpty()
        pollUrlTemplateText = provider?.pollUrlTemplate.orEmpty()
        apiKeyText = provider?.apiKey.orEmpty()
        modelText = provider?.model.orEmpty()
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
        scriptText = provider?.script.orEmpty()
        jsLibText = provider?.jsLib.orEmpty()
    }

    /**
     * Agnes Video V2.0 预置配置：填好 submitUrl/pollUrlTemplate/JSONPath/状态值，
     * 用户只需填入 apiKey 即可使用。
     */
    private fun applyAgnesPreset() {
        nameText = "Agnes Video V2.0"
        modelText = "agnes-video-v2.0"
        submitUrlText = "https://apihub.agnes-ai.com/v1/videos"
        pollUrlTemplateText = "https://apihub.agnes-ai.com/agnesapi?video_id={taskId}"
        taskIdJsonPathText = "\$.video_id"
        videoUrlJsonPathText = "\$.remixed_from_video_id"
        statusJsonPathText = "\$.status"
        doneStatusValueText = "completed"
        failedStatusValueText = "failed"
        maxReferenceImagesText = "4"
        submitTimeoutText = "60000"
        pollTimeoutText = "600000"
        pollIntervalText = "3000"
        enabledState = true
        paramsText = ""
        scriptText = ""
        jsLibText = ""
        headersText = ""
        apiKeyText = ""
    }

    /**
     * 豆包 Seedance 2.0 预置配置：填好 submitUrl/pollUrlTemplate/JSONPath/状态值，
     * 用户只需填入 apiKey（即火山引擎 ARK API Key）即可使用。
     *
     * 端点：`POST https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks`
     * 轮询：`GET https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks/{taskId}`
     * taskIdJsonPath: `$.id`
     * videoUrlJsonPath: `$.content.video_url`
     * statusJsonPath: `$.status`，done=`succeeded`，failed=`failed`
     */
    private fun applyDoubaoPreset() {
        nameText = "豆包 Seedance 2.0"
        modelText = "doubao-seedance-2-0-260128"
        submitUrlText = "https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks"
        pollUrlTemplateText = "https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks/{taskId}"
        taskIdJsonPathText = "\$.id"
        videoUrlJsonPathText = "\$.content.video_url"
        statusJsonPathText = "\$.status"
        doneStatusValueText = "succeeded"
        failedStatusValueText = "failed"
        maxReferenceImagesText = "1"
        submitTimeoutText = "60000"
        pollTimeoutText = "600000"
        pollIntervalText = "3000"
        enabledState = true
        paramsText = ""
        scriptText = ""
        jsLibText = ""
        headersText = ""
        apiKeyText = ""
    }

    private fun selectType() {
        showComposeActionListDialog(
            title = getString(R.string.ai_video_provider_type),
            labels = listOf(
                getString(R.string.ai_video_provider_openai),
                getString(R.string.ai_video_provider_agnes),
                getString(R.string.ai_video_provider_doubao),
                getString(R.string.ai_video_provider_js)
            )
        ) { index ->
            providerType = when (index) {
                0 -> AiVideoProviderConfig.TYPE_OPENAI
                1 -> AiVideoProviderConfig.TYPE_AGNES
                2 -> AiVideoProviderConfig.TYPE_DOUBAO
                else -> AiVideoProviderConfig.TYPE_JS
            }
        }
    }

    private fun openCodeEditor(
        field: Field,
        title: String,
        text: String,
        languageName: String = "source.js"
    ) {
        editingField = field
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
        if ((providerType == AiVideoProviderConfig.TYPE_OPENAI ||
                providerType == AiVideoProviderConfig.TYPE_AGNES ||
                providerType == AiVideoProviderConfig.TYPE_DOUBAO) &&
            submitUrlText.isBlank()
        ) {
            toastOnUi(R.string.ai_provider_url_required)
            return
        }
        val old = currentProvider()
        val needDefaultProvider = old == null && AppConfig.aiCurrentVideoProvider == null
        val updated = (old ?: AiVideoProviderConfig(name = nameText, type = providerType)).copy(
            name = nameText,
            type = providerType,
            baseUrl = "",
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
            jsLib = jsLibText,
            script = scriptText,
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

    private fun defaultParams(): String {
        return when (providerType) {
            AiVideoProviderConfig.TYPE_OPENAI -> {
                "{\n  \"duration_seconds\": 8,\n  \"resolution\": \"1080p\",\n  \"aspect_ratio\": \"16:9\"\n}"
            }
            AiVideoProviderConfig.TYPE_AGNES -> {
                // Agnes 高级参数模板：mode/negative_prompt/seed/num_inference_steps
                "// 留空使用默认文生视频模式\n" +
                    "// 启用关键帧模式（需 ≥2 张参考图）：\n" +
                    "{\n" +
                    "  \"mode\": \"keyframes\",\n" +
                    "  \"negative_prompt\": \"blurry, low quality, distorted\",\n" +
                    "  \"seed\": 42,\n" +
                    "  \"num_inference_steps\": 30\n" +
                    "}"
            }
            AiVideoProviderConfig.TYPE_DOUBAO -> {
                // 豆包 Seedance 2.0 高级参数模板：
                // - duration: 视频时长秒数（4-15 或 -1 自动）；这里不设，由 NovelVideoParams.sceneDurationSeconds 注入
                // - camera_fixed: 镜头固定，避免运镜抖动（适合稳定叙事场景）
                // - return_last_frame: 返回尾帧，用于续拍衔接
                // - generate_audio: 是否生成音频
                // - watermark: 是否加水印
                // - seed: 随机种子
                "// 豆包 Seedance 2.0 高级参数（按模型自适应注入）\n" +
                    "// duration 由 NovelVideoParams.sceneDurationSeconds 自动覆盖\n" +
                    "{\n" +
                    "  \"camera_fixed\": false,\n" +
                    "  \"return_last_frame\": false,\n" +
                    "  \"generate_audio\": false,\n" +
                    "  \"watermark\": false,\n" +
                    "  \"seed\": 42\n" +
                    "}"
            }
            else -> ""
        }
    }

    private fun summary(value: String): String {
        return value.trim().lineSequence().firstOrNull()?.take(36)?.ifBlank { null } ?: "未设置"
    }

    private enum class Field {
        PARAMS,
        SCRIPT,
        JS_LIB
    }

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
