package io.legado.app.help.ai

import android.util.Base64
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.help.source.getShareScope
import io.legado.app.ui.main.ai.AiImageProviderConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object AiImageService {

    private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
    private const val MAX_IMAGE_RESPONSE_BYTES = 48L * 1024L * 1024L

    private data class ImageGenerationResult(
        val source: String,
        val model: String
    )

    suspend fun generate(
        prompt: String,
        provider: AiImageProviderConfig? = null
    ): String {
        val target = resolveProvider(provider)
        return generateRaw(effectivePrompt(prompt, target), target).source
    }

    /**
     * 带参考图重载：用于小说→视频 Stage 5 的"场景图 + 角色三视图"参考生图。
     *
     * - [referenceImages] 为空时退化为 [generate]
     * - OpenAI 类型 Provider 走 `/chat/completions`，content 为 `[{text}, {image_url}...]` 数组，
     *   对齐 director_ai 的 gpt-4o-image-vip 用法
     * - JS 类型 Provider 暂不支持参考图，自动退化为单图生成（参考图忽略）
     */
    suspend fun generate(
        prompt: String,
        referenceImages: List<String>,
        provider: AiImageProviderConfig? = null
    ): String {
        if (referenceImages.isEmpty()) return generate(prompt, provider)
        val target = resolveProvider(provider)
        val effective = effectivePrompt(prompt, target)
        return when (target.type) {
            AiImageProviderConfig.TYPE_JS -> generateRaw(effective, target).source
            else -> generateByChatWithImages(effective, referenceImages, target).source
        }
    }

    suspend fun generateAndStore(
        prompt: String,
        provider: AiImageProviderConfig? = null,
        metadata: AiImageGalleryManager.ImageMetadata = AiImageGalleryManager.ImageMetadata()
    ): AiGeneratedImage {
        val target = resolveProvider(provider)
        val image = generateRaw(effectivePrompt(prompt, target), target)
        return AiImageGalleryManager.saveGeneratedImage(image.source, prompt, target, image.model, metadata)
    }

    /**
     * 带参考图的 [generateAndStore] 重载，用于 Stage 5 场景图入库。
     */
    suspend fun generateAndStore(
        prompt: String,
        referenceImages: List<String>,
        provider: AiImageProviderConfig? = null,
        metadata: AiImageGalleryManager.ImageMetadata = AiImageGalleryManager.ImageMetadata()
    ): AiGeneratedImage {
        if (referenceImages.isEmpty()) return generateAndStore(prompt, provider, metadata)
        val target = resolveProvider(provider)
        val effective = effectivePrompt(prompt, target)
        val image = when (target.type) {
            AiImageProviderConfig.TYPE_JS -> generateRaw(effective, target)
            else -> generateByChatWithImages(effective, referenceImages, target)
        }
        return AiImageGalleryManager.saveGeneratedImage(image.source, prompt, target, image.model, metadata)
    }

    fun currentProviderOrNull(): AiImageProviderConfig? {
        return AppConfig.aiCurrentImageProvider
    }

    fun providerByIdOrNull(providerId: String?): AiImageProviderConfig? {
        return AppConfig.findEnabledImageProvider(providerId)
    }

    private fun resolveProvider(provider: AiImageProviderConfig?): AiImageProviderConfig {
        return provider ?: currentProviderOrNull()
            ?: error("请选择可用生图模型")
    }

    private fun effectivePrompt(prompt: String, provider: AiImageProviderConfig): String {
        val style = provider.stylePrompt.trim()
        val content = prompt.trim()
        return when {
            style.isBlank() -> content
            content.isBlank() -> style
            else -> "$style\n\n$content"
        }
    }

    private suspend fun generateRaw(prompt: String, target: AiImageProviderConfig): ImageGenerationResult {
        return when (target.type) {
            AiImageProviderConfig.TYPE_JS -> generateByJs(prompt, target)
            else -> generateByOpenAi(prompt, target)
        }
    }

    private suspend fun generateByOpenAi(prompt: String, provider: AiImageProviderConfig): ImageGenerationResult {
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
    ): ImageGenerationResult {
        val effectiveModel = provider.model
            .ifBlank { params.optString("model").ifBlank { "gpt-image-1" } }
        val payload = JSONObject().apply {
            put("model", effectiveModel)
            put("prompt", prompt)
            put("n", 1)
            put("size", "1024x1024")
            mergeJson(params, ignored = setOf("endpoint", "model", "prompt"))
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
                val text = it.body.stringLimited(MAX_IMAGE_RESPONSE_BYTES)
                if (!it.isSuccessful) error(text.ifBlank { status })
                val root = JSONObject(text)
                imageFromOpenAiResponse(root)?.let { source ->
                    logRequest(provider, requestUrl, status, startedAt, true, effectiveModel)
                    return ImageGenerationResult(source, effectiveModel)
                }
                error("No image url or base64 field in response: ${jsonShape(root)}")
            }
        } catch (e: Throwable) {
            logRequest(provider, requestUrl, status.ifBlank { e.javaClass.simpleName }, startedAt, false, effectiveModel, e)
            throw e
        }
    }

    private suspend fun generateByResponses(
        prompt: String,
        provider: AiImageProviderConfig,
        baseUrl: String,
        params: JSONObject
    ): ImageGenerationResult {
        val effectiveModel = provider.model
            .ifBlank { params.optString("model").ifBlank { "gpt-5" } }
        val tool = JSONObject().apply {
            put("type", "image_generation")
            params.optJSONObject("tool")?.let { mergeJson(it) }
            mergeJson(
                params,
                ignored = setOf("endpoint", "model", "input", "tools", "stream", "response", "tool")
            )
        }
        val payload = JSONObject().apply {
            put("model", effectiveModel)
            put("input", prompt)
            put("tools", JSONArray().put(tool))
            params.optJSONObject("response")?.let { mergeJson(it) }
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
                val text = it.body.stringLimited(MAX_IMAGE_RESPONSE_BYTES)
                if (!it.isSuccessful) error(text.ifBlank { status })
                val output = JSONObject(text).optJSONArray("output") ?: error("Empty responses output")
                for (index in 0 until output.length()) {
                    val item = output.optJSONObject(index) ?: continue
                    if (item.optString("type") == "image_generation_call") {
                        item.optString("result").takeIf { b64 -> b64.isNotBlank() }?.let { b64 ->
                            ensureImageSourceWithinLimit(b64)
                            logRequest(provider, requestUrl, status, startedAt, true, effectiveModel)
                            return ImageGenerationResult(normalizeImageString(b64), effectiveModel)
                        }
                    }
                    imageFromOpenAiJson(item)?.let { source ->
                        logRequest(provider, requestUrl, status, startedAt, true, effectiveModel)
                        return ImageGenerationResult(source, effectiveModel)
                    }
                }
                error("No image_generation_call result in response")
            }
        } catch (e: Throwable) {
            logRequest(provider, requestUrl, status.ifBlank { e.javaClass.simpleName }, startedAt, false, effectiveModel, e)
            throw e
        }
    }

    /**
     * 带参考图的 OpenAI Chat Completions 生图（gpt-4o-image-vip 风格）。
     *
     * 请求：POST `${baseUrl}/chat/completions`
     * ```json
     * {
     *   "model": "gpt-4o-image-vip",
     *   "messages": [{
     *     "role": "user",
     *     "content": [
     *       {"type":"text","text":"<prompt>"},
     *       {"type":"image_url","image_url":{"url":"<ref1>"}},
     *       {"type":"image_url","image_url":{"url":"<ref2>"}}
     *     ]
     *   }]
     * }
     * ```
     *
     * 响应：从 `choices[0].message.content` 中提取图片 URL/base64。
     * content 可能是字符串（包含 markdown 图片或 URL），也可能是数组（含 image_url 项）。
     */
    private suspend fun generateByChatWithImages(
        prompt: String,
        referenceImages: List<String>,
        provider: AiImageProviderConfig
    ): ImageGenerationResult {
        val baseUrl = normalizeBaseUrl(provider.baseUrl)
        require(baseUrl.isNotBlank()) { "Base URL is empty" }
        val params = runCatching { JSONObject(provider.defaultParamsJson.ifBlank { "{}" }) }
            .getOrDefault(JSONObject())
        val effectiveModel = provider.model
            .ifBlank { params.optString("model").ifBlank { "gpt-4o-image-vip" } }

        val contentArray = JSONArray()
        contentArray.put(JSONObject().apply {
            put("type", "text")
            put("text", prompt)
        })
        // 最多 3 张参考图（与 veo3.1 maxReferenceImages 一致）；多余的按顺序截断
        referenceImages.filter { it.isNotBlank() }.take(3).forEach { url ->
            contentArray.put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply { put("url", url) })
            })
        }

        val messagesArray = JSONArray()
        messagesArray.put(JSONObject().apply {
            put("role", "user")
            put("content", contentArray)
        })

        val payload = JSONObject().apply {
            put("model", effectiveModel)
            put("messages", messagesArray)
            put("stream", false)
            mergeJson(params, ignored = setOf("endpoint", "model", "messages", "stream", "input", "tools", "response"))
        }

        val requestUrl = "${baseUrl.trimEnd('/')}/chat/completions"
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
                val text = it.body.stringLimited(MAX_IMAGE_RESPONSE_BYTES)
                if (!it.isSuccessful) error(text.ifBlank { status })
                val root = JSONObject(text)
                val source = extractImageFromChatResponse(root)
                    ?: error("Chat 响应未包含图片字段: ${jsonShape(root)}")
                logRequest(provider, requestUrl, status, startedAt, true, effectiveModel)
                return ImageGenerationResult(source, effectiveModel)
            }
        } catch (e: Throwable) {
            logRequest(provider, requestUrl, status.ifBlank { e.javaClass.simpleName }, startedAt, false, effectiveModel, e)
            throw e
        }
    }

    /**
     * 从 chat/completions 响应里提取图片。
     *
     * 兼容多种返回形态：
     * 1. `choices[0].message.content` 为数组，含 `{type:"image_url", image_url:{url}}` 项
     * 2. `choices[0].message.content` 为字符串，可能是 URL / data URL / markdown `![](...)`
     * 3. `choices[0].message.images` 数组（部分兼容实现）
     * 4. 顶层 `data.url` / `data[0].url` 等 OpenAI 风格回退
     */
    private fun extractImageFromChatResponse(root: JSONObject): String? {
        val choices = root.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val message = choices.optJSONObject(0)?.optJSONObject("message") ?: JSONObject()
            // 形态 1：content 是数组
            message.optJSONArray("content")?.let { contentArr ->
                for (i in 0 until contentArr.length()) {
                    val item = contentArr.optJSONObject(i) ?: continue
                    if (item.optString("type") == "image_url") {
                        val url = item.optJSONObject("image_url")?.optString("url").orEmpty()
                        if (url.isNotBlank()) {
                            runCatching { normalizeImageString(url) }.getOrNull()?.let { return it }
                        }
                    }
                    // 部分实现直接放 url 字段
                    item.optString("url").takeIf { it.isNotBlank() }?.let { url ->
                        runCatching { normalizeImageString(url) }.getOrNull()?.let { return it }
                    }
                }
            }
            // 形态 2：content 是字符串
            message.optString("content").takeIf { it.isNotBlank() }?.let { contentStr ->
                extractImageUrlFromString(contentStr)?.let { return it }
            }
            // 形态 3：message.images 数组
            message.optJSONArray("images")?.let { imagesArr ->
                for (i in 0 until imagesArr.length()) {
                    val item = imagesArr.opt(i)
                    when (item) {
                        is String -> runCatching { normalizeImageString(item) }.getOrNull()?.let { return it }
                        is JSONObject -> imageFromOpenAiJson(item)?.let { return it }
                    }
                }
            }
        }
        // 形态 4：顶层 OpenAI 风格回退
        return imageFromOpenAiJson(root)
    }

    /**
     * 从字符串里提取图片 URL / data URL。
     * 支持：纯 URL、纯 data URL、markdown 图片 `![alt](url)`、HTML `<img src="url">`。
     */
    private fun extractImageUrlFromString(content: String): String? {
        val trimmed = content.trim()
        // 纯 URL / data URL
        if (trimmed.startsWith("http", true) || trimmed.startsWith("data:", true)) {
            return runCatching { normalizeImageString(trimmed) }.getOrNull()
        }
        // markdown: ![alt](url)
        Regex("!\\[[^\\]]*]\\(([^)]+)\\)").find(trimmed)?.let { m ->
            runCatching { normalizeImageString(m.groupValues[1]) }.getOrNull()?.let { return it }
        }
        // html: <img src="url">
        Regex("<img[^>]+src=[\"']([^\"']+)[\"']").find(trimmed)?.let { m ->
            runCatching { normalizeImageString(m.groupValues[1]) }.getOrNull()?.let { return it }
        }
        // 兜底：在字符串里搜第一个 http(s) URL
        Regex("https?://\\S+\\.(?:png|jpe?g|webp|gif)\\S*", RegexOption.IGNORE_CASE).find(trimmed)?.let { m ->
            runCatching { normalizeImageString(m.value) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun ResponseBody.stringLimited(maxBytes: Long): String {
        val contentLength = contentLength()
        if (contentLength > maxBytes) {
            throw IllegalStateException("Image response is too large")
        }
        val source = source()
        source.request(maxBytes + 1L)
        if (source.buffer.size > maxBytes) {
            throw IllegalStateException("Image response is too large")
        }
        val charset = contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
        return source.buffer.readString(charset)
    }

    private suspend fun generateByJs(prompt: String, provider: AiImageProviderConfig): ImageGenerationResult {
        val script = provider.script.trim()
        if (script.isBlank()) error("JS script is empty")
        val source = AiImageJsSource(provider)
        val coroutineContext = currentCoroutineContext()
        val result = withTimeout(provider.validTimeout()) {
            val bindings = buildScriptBindings { bindings ->
                bindings["java"] = source
                bindings["source"] = source
                bindings["cache"] = CacheManager
                bindings["cookie"] = CookieStore
                bindings["baseUrl"] = source.getKey()
                bindings["prompt"] = prompt
                bindings["result"] = prompt
                bindings["key"] = prompt
                bindings["provider"] = provider
            }
            val sharedScope = source.getShareScope(coroutineContext)
            val scope = if (sharedScope == null) {
                RhinoScriptEngine.getRuntimeScope(bindings)
            } else {
                bindings.apply { prototype = sharedScope }
            }
            RhinoScriptEngine.eval(
                buildString {
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
                scope,
                coroutineContext
            )
        }
        return ImageGenerationResult(normalizeImageResult(result), provider.model.ifBlank { "JS" })
    }

    private class AiImageJsSource(
        private val provider: AiImageProviderConfig
    ) : BaseSource {
        override var concurrentRate: String? = null
        override var loginUrl: String? = provider.loginUrl
        override var loginUi: String? = provider.loginUi
        override var header: String? = provider.headers
        override var enabledCookieJar: Boolean? = provider.enabledCookieJar
        override var jsLib: String? = provider.jsLib

        override fun getTag(): String {
            return "AiImageRule:${provider.id}:${provider.displayName()}"
        }

        override fun getKey(): String {
            return "ai_image_rule_${provider.id}"
        }
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
        model: String = provider.model,
        throwable: Throwable? = null
    ) {
        val elapsed = System.currentTimeMillis() - startedAt
        val message = buildString {
            append("AI 生图请求")
            append(if (success) "成功" else "失败")
            append("\nurl=").append(url)
            append("\nprovider=").append(provider.displayName())
            append("\nmodel=").append(model)
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

    private fun normalizeImageResult(result: Any?): String {
        return when (result) {
            null -> error("Empty JS image result")
            is String -> normalizeImageString(result)
            is JSONObject -> imageFromJson(result)
            is JSONArray -> imageFromArray(result)
            is NativeObject -> imageFromJson(result.toJSONObject())
            is NativeArray -> imageFromArray(result.toJSONArray())
            else -> normalizeImageString(result.toString())
        }
    }

    private fun normalizeImageString(raw: String): String {
        val text = raw.trim()
        if (text.isBlank() || text == "null" || text == "undefined") error("Empty JS image result")
        if (text.startsWith("http", true) || text.startsWith("data:", true)) {
            ensureImageSourceWithinLimit(text)
            return text
        }
        if (text.startsWith("{")) return imageFromJson(JSONObject(text))
        if (text.startsWith("[")) return imageFromArray(JSONArray(text))
        if (looksLikeImageBase64(text)) return "data:image/png;base64,$text"
        error("Image provider needs a returned url, data url, base64, or JSON image result")
    }

    private fun imageFromJson(json: JSONObject): String {
        return imageFromOpenAiJson(json)
            ?: error("No image url or base64 field in image result: ${jsonShape(json)}")
    }

    private fun imageFromArray(array: JSONArray): String {
        return imageFromOpenAiArray(array)
            ?: error("No image item in image result")
    }

    private fun imageFromOpenAiResponse(json: JSONObject): String? {
        return imageFromOpenAiJson(json)
    }

    private fun imageFromOpenAiJson(json: JSONObject): String? {
        listOf("url", "imageUrl", "image_url", "image", "result", "src", "output").forEach { key ->
            when (val value = json.opt(key)) {
                is String -> runCatching { normalizeImageString(value) }.getOrNull()?.let { return it }
                is JSONObject -> imageFromOpenAiJson(value)?.let { return it }
                is JSONArray -> imageFromOpenAiArray(value)?.let { return it }
            }
        }
        listOf("b64_json", "base64", "b64").forEach { key ->
            json.optString(key).takeIf { it.isNotBlank() }?.let { value ->
                runCatching { normalizeImageString(value) }.getOrNull()?.let { return it }
            }
        }
        listOf("data", "images", "artifacts", "content").forEach { key ->
            json.optJSONArray(key)?.let { imageFromOpenAiArray(it)?.let { source -> return source } }
            json.optJSONObject(key)?.let { imageFromOpenAiJson(it)?.let { source -> return source } }
        }
        return null
    }

    private fun imageFromOpenAiArray(array: JSONArray): String? {
        for (index in 0 until array.length()) {
            runCatching {
                when (val item = array.opt(index)) {
                    is JSONObject -> imageFromOpenAiJson(item)
                    is String -> normalizeImageString(item)
                    else -> null
                }
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun NativeObject.toJSONObject(): JSONObject {
        return JSONObject().apply {
            ids.forEach { key ->
                val name = key.toString()
                val value = get(name, this@toJSONObject)
                put(name, nativeToJsonValue(value))
            }
        }
    }

    private fun NativeArray.toJSONArray(): JSONArray {
        return JSONArray().apply {
            ids.forEach { key ->
                val index = key.toString().toIntOrNull() ?: return@forEach
                put(index, nativeToJsonValue(get(index, this@toJSONArray)))
            }
        }
    }

    private fun nativeToJsonValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is NativeObject -> value.toJSONObject()
            is NativeArray -> value.toJSONArray()
            is Number, is Boolean, is String -> value
            else -> value.toString()
        }
    }

    private fun looksLikeImageBase64(text: String): Boolean {
        val payload = text.filterNot { it.isWhitespace() }
        if (payload.length < 100 || !payload.matches(Regex("[A-Za-z0-9+/=_-]+"))) return false
        if (estimateBase64Bytes(payload) > MAX_IMAGE_BYTES) return false
        return runCatching {
            val bytes = decodeBase64Sample(payload)
            bytes.size >= 8 && (
                bytes.copyOfRange(0, 4).toString(Charsets.ISO_8859_1) == "\u0089PNG" ||
                    (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) ||
                    bytes.copyOfRange(0, 3).toString(Charsets.ISO_8859_1) == "GIF" ||
                    bytes.copyOfRange(0, 4).toString(Charsets.ISO_8859_1) == "RIFF"
                )
        }.getOrDefault(false)
    }

    private fun ensureImageSourceWithinLimit(text: String) {
        val payload = when {
            text.startsWith("data:", true) -> text.substringAfter(',', "").filterNot { it.isWhitespace() }
            else -> text.filterNot { it.isWhitespace() }
        }
        if (payload.isBlank() || payload.startsWith("http", true)) return
        val estimatedBytes = estimateBase64Bytes(payload)
        if (estimatedBytes > MAX_IMAGE_BYTES) error("Image is too large: $estimatedBytes bytes")
    }

    private fun estimateBase64Bytes(payload: String): Long {
        return payload.length.toLong() * 3L / 4L
    }

    private fun decodeBase64Sample(payload: String): ByteArray {
        val sample = payload.take(64).padBase64()
        return runCatching {
            Base64.decode(sample, Base64.DEFAULT)
        }.getOrElse {
            Base64.decode(sample, Base64.URL_SAFE)
        }
    }

    private fun String.padBase64(): String {
        val remainder = length % 4
        return if (remainder == 0) this else this + "=".repeat(4 - remainder)
    }

    private fun jsonShape(json: JSONObject): String {
        return json.keys().asSequence().take(12).joinToString(prefix = "{", postfix = "}") { key ->
            val value = json.opt(key)
            val type = when (value) {
                is JSONObject -> "object"
                is JSONArray -> "array(${value.length()})"
                JSONObject.NULL, null -> "null"
                else -> value.javaClass.simpleName
            }
            "$key:$type"
        }
    }
}
