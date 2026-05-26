package io.legado.app.help.ai

import android.util.Base64
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.ui.main.ai.AiImageProviderConfig
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AiImageService {

    suspend fun generate(
        prompt: String,
        provider: AiImageProviderConfig? = null
    ): String {
        val target = resolveProvider(provider)
        return generateRaw(prompt, target)
    }

    suspend fun generateAndStore(
        prompt: String,
        provider: AiImageProviderConfig? = null
    ): AiGeneratedImage {
        val target = resolveProvider(provider)
        val image = generateRaw(prompt, target)
        return AiImageGalleryManager.saveGeneratedImage(image, prompt, target)
    }

    private fun resolveProvider(provider: AiImageProviderConfig?): AiImageProviderConfig {
        return provider ?: AppConfig.aiEnabledImageProviders.firstOrNull()
            ?: error("未配置可用生图供应商")
    }

    private suspend fun generateRaw(prompt: String, target: AiImageProviderConfig): String {
        return when (target.type) {
            AiImageProviderConfig.TYPE_JS -> generateByJs(prompt, target)
            else -> generateByOpenAi(prompt, target)
        }
    }

    private suspend fun generateByOpenAi(prompt: String, provider: AiImageProviderConfig): String {
        val baseUrl = normalizeBaseUrl(provider.baseUrl)
        require(baseUrl.isNotBlank()) { "Base URL is empty" }
        val params = runCatching { JSONObject(provider.defaultParamsJson.ifBlank { "{}" }) }
            .getOrDefault(JSONObject())
        return if (params.optString("endpoint").equals("responses", true)) {
            generateByResponses(prompt, provider, baseUrl, params)
        } else {
            generateByImagesApi(prompt, provider, baseUrl, params)
        }
    }

    private suspend fun generateByImagesApi(
        prompt: String,
        provider: AiImageProviderConfig,
        baseUrl: String,
        params: JSONObject
    ): String {
        val payload = JSONObject().apply {
            put("model", provider.model.ifBlank { "gpt-image-1" })
            put("prompt", prompt)
            put("n", 1)
            put("size", "1024x1024")
            mergeJson(params, ignored = setOf("endpoint"))
        }
        val requestUrl = "${baseUrl.trimEnd('/')}/images/generations"
        val startedAt = System.currentTimeMillis()
        var status = ""
        try {
            val response = provider.httpClient().newCallResponse {
                url(requestUrl)
                postJson(payload.toString())
                addHeader("Accept", "application/json")
                addHeader("Content-Type", "application/json")
                provider.apiKey.takeIf { it.isNotBlank() }?.let {
                    addHeader("Authorization", "Bearer $it")
                }
                addHeaders(AiChatService.parseCustomHeaders(provider.headers))
            }
            response.use {
                status = "${it.code} ${it.message}"
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) error(text.ifBlank { status })
                val first = JSONObject(text).optJSONArray("data")?.optJSONObject(0)
                    ?: error("Empty image response")
                first.optString("url").takeIf { url -> url.isNotBlank() }?.let { url ->
                    logRequest(provider, requestUrl, status, startedAt, true)
                    return url
                }
                first.optString("b64_json").takeIf { b64 -> b64.isNotBlank() }?.let { b64 ->
                    Base64.decode(b64, Base64.DEFAULT)
                    logRequest(provider, requestUrl, status, startedAt, true)
                    return "data:image/png;base64,$b64"
                }
                error("No image url or b64_json in response")
            }
        } catch (e: Throwable) {
            logRequest(provider, requestUrl, status.ifBlank { e.javaClass.simpleName }, startedAt, false, e)
            throw e
        }
    }

    private suspend fun generateByResponses(
        prompt: String,
        provider: AiImageProviderConfig,
        baseUrl: String,
        params: JSONObject
    ): String {
        val tool = JSONObject().apply {
            put("type", "image_generation")
            mergeJson(params, ignored = setOf("endpoint", "model", "input", "tools", "stream"))
        }
        val payload = JSONObject().apply {
            put("model", provider.model.ifBlank { "gpt-5" })
            put("input", prompt)
            put("tools", JSONArray().put(tool))
        }
        val requestUrl = "${baseUrl.trimEnd('/')}/responses"
        val startedAt = System.currentTimeMillis()
        var status = ""
        try {
            val response = provider.httpClient().newCallResponse {
                url(requestUrl)
                postJson(payload.toString())
                addHeader("Accept", "application/json")
                addHeader("Content-Type", "application/json")
                provider.apiKey.takeIf { it.isNotBlank() }?.let {
                    addHeader("Authorization", "Bearer $it")
                }
                addHeaders(AiChatService.parseCustomHeaders(provider.headers))
            }
            response.use {
                status = "${it.code} ${it.message}"
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) error(text.ifBlank { status })
                val output = JSONObject(text).optJSONArray("output") ?: error("Empty responses output")
                for (index in 0 until output.length()) {
                    val item = output.optJSONObject(index) ?: continue
                    if (item.optString("type") == "image_generation_call") {
                        item.optString("result").takeIf { b64 -> b64.isNotBlank() }?.let { b64 ->
                            Base64.decode(b64, Base64.DEFAULT)
                            logRequest(provider, requestUrl, status, startedAt, true)
                            return "data:image/png;base64,$b64"
                        }
                    }
                }
                error("No image_generation_call result in response")
            }
        } catch (e: Throwable) {
            logRequest(provider, requestUrl, status.ifBlank { e.javaClass.simpleName }, startedAt, false, e)
            throw e
        }
    }

    private suspend fun generateByJs(prompt: String, provider: AiImageProviderConfig): String {
        val script = provider.script.trim()
        if (script.isBlank()) error("JS script is empty")
        val result = RhinoScriptEngine.eval(
            buildString {
                if (provider.jsLib.isNotBlank()) append(provider.jsLib).append('\n')
                append(script).append('\n')
                append(
                    """
                    ;(function(){
                        if (typeof generate === 'function') return generate(prompt, provider);
                        if (typeof run === 'function') return run(prompt, provider);
                        if (typeof result !== 'undefined') return result;
                        return null;
                    })();
                    """.trimIndent()
                )
            },
            RhinoScriptEngine.getRuntimeScope(
                buildScriptBindings { bindings ->
                    bindings["prompt"] = prompt
                    bindings["provider"] = provider
                }
            ),
            currentCoroutineContext()
        )?.toString().orEmpty().trim()
        return result
            .takeIf { it.startsWith("http", true) || it.startsWith("data:image", true) }
            ?: error("JS image provider needs a returned url or data:image result")
    }

    private fun normalizeBaseUrl(raw: String): String {
        val normalized = raw.trim().trimEnd('/')
        return when {
            normalized.isBlank() -> ""
            normalized.endsWith("/v1") -> normalized
            normalized.endsWith("/images/generations") -> normalized.removeSuffix("/images/generations")
            normalized.endsWith("/responses") -> normalized.removeSuffix("/responses")
            else -> "$normalized/v1"
        }
    }

    private fun AiImageProviderConfig.httpClient(): OkHttpClient {
        val timeout = validTimeout()
        return okHttpClient.newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun logRequest(
        provider: AiImageProviderConfig,
        url: String,
        status: String,
        startedAt: Long,
        success: Boolean,
        throwable: Throwable? = null
    ) {
        val elapsed = System.currentTimeMillis() - startedAt
        val message = buildString {
            append("AI 生图请求")
            append(if (success) "成功" else "失败")
            append("\nurl=").append(url)
            append("\nprovider=").append(provider.displayName())
            append("\nmodel=").append(provider.model)
            append("\ntimeout=").append(provider.validTimeout())
            append("\nelapsed=").append(elapsed)
            append("\nstatus=").append(status)
        }
        AppLog.put(message, throwable)
    }

    private fun JSONObject.mergeJson(extra: JSONObject, ignored: Set<String> = emptySet()) {
        extra.keys().forEach { key ->
            if (key !in ignored) put(key, extra.opt(key))
        }
    }
}
