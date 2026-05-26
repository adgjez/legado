package io.legado.app.help.ai

import android.util.Base64
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.ui.main.ai.AiImageProviderConfig
import org.json.JSONObject

object AiImageService {

    suspend fun generate(
        prompt: String,
        provider: AiImageProviderConfig? = null
    ): String {
        val target = provider ?: AppConfig.aiEnabledImageProviders.firstOrNull()
            ?: error("未配置可用生图供应商")
        return when (target.type) {
            AiImageProviderConfig.TYPE_JS -> generateByJs(prompt, target)
            else -> generateByOpenAi(prompt, target)
        }
    }

    private suspend fun generateByOpenAi(prompt: String, provider: AiImageProviderConfig): String {
        val baseUrl = provider.baseUrl.trim().trimEnd('/')
        require(baseUrl.isNotBlank()) { "Base URL is empty" }
        val payload = JSONObject().apply {
            put("model", provider.model.ifBlank { "gpt-image-1" })
            put("prompt", prompt)
            put("n", 1)
            put("size", "1024x1024")
            mergeJson(provider.defaultParamsJson)
        }
        val response = okHttpClient.newCallResponse {
            url("$baseUrl/images/generations")
            postJson(payload.toString())
            addHeader("Accept", "application/json")
            provider.apiKey.takeIf { it.isNotBlank() }?.let {
                addHeader("Authorization", "Bearer $it")
            }
            addHeaders(AiChatService.parseCustomHeaders(provider.headers))
        }
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) error(text.ifBlank { "${it.code} ${it.message}" })
            val first = JSONObject(text).optJSONArray("data")?.optJSONObject(0)
                ?: error("Empty image response")
            first.optString("url").takeIf { url -> url.isNotBlank() }?.let { url -> return url }
            first.optString("b64_json").takeIf { b64 -> b64.isNotBlank() }?.let { b64 ->
                Base64.decode(b64, Base64.DEFAULT)
                return "data:image/png;base64,$b64"
            }
            error("No image url or b64_json in response")
        }
    }

    private fun generateByJs(prompt: String, provider: AiImageProviderConfig): String {
        val script = provider.script.trim()
        if (script.isBlank()) error("JS script is empty")
        return script
            .replace("\${prompt}", prompt)
            .takeIf { it.startsWith("http", true) || it.startsWith("data:image", true) }
            ?: error("JS image provider needs a returned url or data:image result")
    }

    private fun JSONObject.mergeJson(raw: String) {
        if (raw.isBlank()) return
        val extra = runCatching { JSONObject(raw) }.getOrNull() ?: return
        extra.keys().forEach { key -> put(key, extra.opt(key)) }
    }
}
