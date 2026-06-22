package io.legado.app.ui.main.ai

import android.os.Bundle
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.databinding.ActivityAiVideoProviderEditBinding
import io.legado.app.help.ai.AiVideoProviderFactory
import io.legado.app.help.ai.VideoGenerationParams
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch
import java.util.UUID

class AiVideoProviderEditActivity : BaseActivity<ActivityAiVideoProviderEditBinding>() {

    override val binding by viewBinding(ActivityAiVideoProviderEditBinding::inflate)
    private var original: AiVideoProviderConfig? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.ai_video_provider_manage)
        val id = intent.getStringExtra(EXTRA_ID)
        original = id?.let { existing -> AppConfig.aiVideoProviderList.firstOrNull { it.id == existing } }
        original?.let { fillForm(it) }
        setupTypeChips()
        binding.btnSave.setOnClickListener { save() }
        binding.btnTest.setOnClickListener { test() }
    }

    private fun fillForm(config: AiVideoProviderConfig) {
        binding.etName.setText(config.name)
        binding.etBaseUrl.setText(config.baseUrl)
        binding.etApiKey.setText(config.apiKey)
        binding.etHeaders.setText(config.headers)
        binding.etModel.setText(config.model)
        binding.etDefaultParams.setText(config.defaultParamsJson)
        binding.etStylePrompt.setText(config.stylePrompt)
        binding.etPollInterval.setText(config.pollIntervalMs.toString())
        binding.etMaxWait.setText(config.maxWaitMs.toString())
        binding.etTimeout.setText(config.timeoutMillisecond.toString())
        binding.etScript.setText(config.script)
        binding.switchEnabled.isChecked = config.enabled
        val chipId = when (config.type) {
            AiVideoProviderConfig.TYPE_KLING -> R.id.chip_kling
            AiVideoProviderConfig.TYPE_JS -> R.id.chip_js
            else -> R.id.chip_openai
        }
        binding.chipGroupType.check(chipId)
    }

    private fun setupTypeChips() {
        binding.chipGroupType.setOnCheckedStateChangeListener { _, _ ->
            val type = currentType()
            binding.layoutScript.isVisible = type == AiVideoProviderConfig.TYPE_JS
        }
        binding.layoutScript.isVisible = currentType() == AiVideoProviderConfig.TYPE_JS
    }

    private fun currentType(): String {
        return when (binding.chipGroupType.checkedChipId) {
            R.id.chip_kling -> AiVideoProviderConfig.TYPE_KLING
            R.id.chip_js -> AiVideoProviderConfig.TYPE_JS
            else -> AiVideoProviderConfig.TYPE_OPENAI
        }
    }

    private fun buildConfig(): AiVideoProviderConfig {
        val base = original
        val name = binding.etName.text?.toString().orEmpty().trim()
        val type = currentType()
        val baseUrl = binding.etBaseUrl.text?.toString().orEmpty().trim()
        val apiKey = binding.etApiKey.text?.toString().orEmpty().trim()
        val headers = binding.etHeaders.text?.toString().orEmpty().trim()
        val model = binding.etModel.text?.toString().orEmpty().trim()
        val defaultParams = binding.etDefaultParams.text?.toString().orEmpty().trim()
        val stylePrompt = binding.etStylePrompt.text?.toString().orEmpty().trim()
        val pollInterval = binding.etPollInterval.text?.toString()?.toLongOrNull() ?: 5_000L
        val maxWait = binding.etMaxWait.text?.toString()?.toLongOrNull() ?: 1_800_000L
        val timeout = binding.etTimeout.text?.toString()?.toLongOrNull() ?: 120_000L
        val script = binding.etScript.text?.toString().orEmpty()
        val enabled = binding.switchEnabled.isChecked
        return if (base != null) {
            base.copy(
                name = name,
                type = type,
                baseUrl = baseUrl,
                apiKey = apiKey,
                headers = headers,
                model = model,
                defaultParamsJson = defaultParams,
                stylePrompt = stylePrompt,
                pollIntervalMs = pollInterval,
                maxWaitMs = maxWait,
                timeoutMillisecond = timeout,
                script = script,
                enabled = enabled
            )
        } else {
            AiVideoProviderConfig(
                id = UUID.randomUUID().toString(),
                name = name,
                type = type,
                baseUrl = baseUrl,
                apiKey = apiKey,
                headers = headers,
                model = model,
                defaultParamsJson = defaultParams,
                stylePrompt = stylePrompt,
                pollIntervalMs = pollInterval,
                maxWaitMs = maxWait,
                timeoutMillisecond = timeout,
                script = script,
                enabled = enabled
            )
        }
    }

    private fun save() {
        val config = buildConfig()
        if (config.name.isBlank()) {
            toastOnUi(R.string.ai_video_provider_name)
            return
        }
        AppConfig.upsertVideoProvider(config)
        if (AppConfig.aiCurrentVideoProviderId == null) {
            AppConfig.aiCurrentVideoProviderId = config.id
        }
        finish()
    }

    private fun test() {
        val config = buildConfig()
        if (config.name.isBlank()) {
            toastOnUi(R.string.ai_video_provider_name)
            return
        }
        if (config.type == AiVideoProviderConfig.TYPE_JS && config.script.isBlank()) {
            toastOnUi(R.string.ai_video_provider_script)
            return
        }
        lifecycleScope.launch {
            try {
                val provider = AiVideoProviderFactory.create(config)
                val taskId = provider.submit("test prompt", VideoGenerationParams(prompt = "test prompt"))
                alert(R.string.ai_video_provider_test) {
                    setMessage(getString(R.string.ai_video_provider_test_ok, taskId))
                    okButton()
                }
            } catch (e: Throwable) {
                alert(R.string.ai_video_provider_test) {
                    setMessage(getString(R.string.ai_video_provider_test_failed, e.message ?: e.javaClass.simpleName))
                    okButton()
                }
            }
        }
    }

    companion object {
        const val EXTRA_ID = "extra_id"
    }
}
