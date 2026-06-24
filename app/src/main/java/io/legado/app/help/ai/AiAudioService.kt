package io.legado.app.help.ai

import android.util.Base64
import com.script.ScriptBindings
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.AiGeneratedAudio
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.CacheManager
import io.legado.app.help.config.AppConfig
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.postJson
import io.legado.app.help.source.getShareScope
import io.legado.app.ui.main.ai.AiAudioProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 音频生成服务。
 *
 * 与 [AiVideoService] 对称，音频生成为异步流程：submit -> poll -> download。
 * 同时支持 OpenAI 兼容接口与 JS 脚本两种 provider。
 */
object AiAudioService {

    /** 下载音频的最大字节数（50MB）。 */
    private const val MAX_AUDIO_BYTES = 50L * 1024L * 1024L

    /** 默认轮询间隔（毫秒）。 */
    private const val DEFAULT_POLL_INTERVAL = 5000L

    data class AudioSubmitResult(val remoteTaskId: String, val status: String)

    data class AudioTaskStatus(
        val status: String,
        val progress: Int,
        val downloadUrl: String?
    )

    // region provider resolution

    fun currentProviderOrNull(): AiAudioProviderConfig? {
        return AppConfig.aiCurrentAudioProvider
    }

    fun providerByIdOrNull(providerId: String?): AiAudioProviderConfig? {
        return AppConfig.findEnabledAudioProvider(providerId)
    }

    private fun resolveProvider(provider: AiAudioProviderConfig?): AiAudioProviderConfig {
        return provider ?: currentProviderOrNull()
            ?: error("No available audio provider, please configure one first")
    }

    private fun effectiveModel(provider: AiAudioProviderConfig, params: JSONObject): String {
        return provider.model.trim().ifBlank {
            params.optString("model").trim().ifBlank { "audio" }
        }
    }

    // endregion

    // region async lifecycle

    suspend fun submit(
        prompt: String,
        params: JSONObject,
        provider: AiAudioProviderConfig
    ): AudioSubmitResult {
        return when (provider.type) {
            AiAudioProviderConfig.TYPE_JS -> submitByJs(prompt, params, provider)
            else -> submitByOpenAi(prompt, params, provider)
        }
    }

    suspend fun queryStatus(
        remoteTaskId: String,
        provider: AiAudioProviderConfig
    ): AudioTaskStatus {
        return when (provider.type) {
            AiAudioProviderConfig.TYPE_JS -> queryStatusByJs(remoteTaskId, provider)
            else -> queryStatusByOpenAi(remoteTaskId, provider)
        }
    }

    suspend fun download(remoteTaskId: String, provider: AiAudioProviderConfig): File {
        return when (provider.type) {
            AiAudioProviderConfig.TYPE_JS -> downloadByJs(remoteTaskId, provider)
            else -> downloadByOpenAi(remoteTaskId, provider)
        }
    }

    suspend fun cancel(remoteTaskId: String, provider: AiAudioProviderConfig) {
        when (provider.type) {
            AiAudioProviderConfig.TYPE_JS -> cancelByJs(remoteTaskId, provider)
            else -> cancelByOpenAi(remoteTaskId, provider)
        }
    }

    // endregion

    // region sync convenience wrapper

    /**
     * 完整的同步封装：submit -> poll -> download -> store。
     */
    suspend fun generateAndStore(
        prompt: String,
        provider: AiAudioProviderConfig? = null,
        metadata: AiAudioGalleryManager.AudioMetadata = AiAudioGalleryManager.AudioMetadata()
    ): AiGeneratedAudio {
        val target = resolveProvider(provider)
        val params = runCatching {
            JSONObject(target.defaultParamsJson.ifBlank { "{}" })
        }.getOrDefault(JSONObject())
        if (metadata.duration > 0.0 && !params.has("duration")) {
            params.put("duration", metadata.duration)
        }
        if (!params.has("format") && metadata.format.isNotBlank()) {
            params.put("format", metadata.format)
        }

        val submitResult = submit(prompt, params, target)
        val remoteTaskId = submitResult.remoteTaskId
        val pollInterval = target.pollIntervalMillisecond.takeIf { it > 0L } ?: DEFAULT_POLL_INTERVAL
        val deadline = System.currentTimeMillis() + target.validTimeout()
        var status = submitResult.status

        while (status == "processing" && System.currentTimeMillis() < deadline) {
            delay(pollInterval)
            status = queryStatus(remoteTaskId, target).status
        }

        return when (status) {
            "succeeded" -> {
                val file = download(remoteTaskId, target)
                val model = effectiveModel(target, params)
                val effectiveFormat = params.optString("format").takeIf { it.isNotBlank() }
                    ?: metadata.format.ifBlank { "mp3" }
                val effectiveMetadata = metadata.copy(format = effectiveFormat)
                AiAudioGalleryManager.saveGeneratedAudio(file.absolutePath, prompt, target, model, effectiveMetadata)
            }
            "failed" -> error("Audio generation failed for task $remoteTaskId")
            else -> error("Audio generation timed out for task $remoteTaskId")
        }
    }

    // endregion

    // region openai path

    private suspend fun submitByOpenAi(
        prompt: String,
        params: JSONObject,
        provider: AiAudioProviderConfig
    ): AudioSubmitResult {
        val baseUrl = normalizeBaseUrl(provider.baseUrl)
        require(baseUrl.isNotBlank()) { "Base URL is empty" }
        val model = effectiveModel(provider, params)
        val payload = JSONObject().apply {
            put("model", model)
            put("prompt", prompt)
            mergeJson(params, ignored = setOf("model", "prompt"))
        }
        val requestUrl = buildSubmitUrl(provider, baseUrl)
        val startedAt = System.currentTimeMillis()
        var status = ""
        try {
            provider.httpClient().newCallResponse {
                url(requestUrl)
                postJson(payload.toString())
                addHeader("Accept", "application/json")
                addHeader("Content-Type", "application/json")
                provider.apiKey.takeIf { it.isNotBlank() }?.let {
                    addHeader("Authorization", "Bearer $it")
                }
                addHeaders(AiChatService.parseCustomHeaders(provider.headers))
            }.use { response ->
                status = "${response.code} ${response.message}"
                val text = response.body.string()
                if (!response.isSuccessful) error(text.ifBlank { status })
                val result = submitResultFromJson(JSONObject(text))
                logRequest(provider, requestUrl, status, startedAt, true, model)
                return result
            }
        } catch (e: Throwable) {
            logRequest(provider, requestUrl, status.ifBlank { e.javaClass.simpleName }, startedAt, false, model, e)
            throw e
        }
    }

    private suspend fun queryStatusByOpenAi(
        remoteTaskId: String,
        provider: AiAudioProviderConfig
    ): AudioTaskStatus {
        val baseUrl = normalizeBaseUrl(provider.baseUrl)
        require(baseUrl.isNotBlank()) { "Base URL is empty" }
        val requestUrl = buildStatusUrl(provider, baseUrl, remoteTaskId)
        val startedAt = System.currentTimeMillis()
        var status = ""
        try {
            provider.httpClient().newCallResponse {
                url(requestUrl)
                addHeader("Accept", "application/json")
                provider.apiKey.takeIf { it.isNotBlank() }?.let {
                    addHeader("Authorization", "Bearer $it")
                }
                addHeaders(AiChatService.parseCustomHeaders(provider.headers))
            }.use { response ->
                status = "${response.code} ${response.message}"
                val text = response.body.string()
                if (!response.isSuccessful) error(text.ifBlank { status })
                val taskStatus = statusResultFromJson(JSONObject(text))
                logRequest(provider, requestUrl, status, startedAt, true)
                return taskStatus
            }
        } catch (e: Throwable) {
            logRequest(provider, requestUrl, status.ifBlank { e.javaClass.simpleName }, startedAt, false, throwable = e)
            throw e
        }
    }

    private suspend fun downloadByOpenAi(remoteTaskId: String, provider: AiAudioProviderConfig): File =
        withContext(Dispatchers.IO) {
            val status = queryStatusByOpenAi(remoteTaskId, provider)
            val url = status.downloadUrl
                ?: error("No download url for audio task $remoteTaskId")
            val tempFile = File.createTempFile("ai_audio_${remoteTaskId}_", ".mp3", appCtx.cacheDir)
            val startedAt = System.currentTimeMillis()
            var responseStatus = ""
            try {
                provider.httpClient().newCallResponse {
                    url(url)
                    addHeader("Accept", "application/octet-stream, */*")
                    provider.apiKey.takeIf { it.isNotBlank() }?.let {
                        addHeader("Authorization", "Bearer $it")
                    }
                    addHeaders(AiChatService.parseCustomHeaders(provider.headers))
                }.use { response ->
                    responseStatus = "${response.code} ${response.message}"
                    if (!response.isSuccessful) {
                        error(response.body.string().ifBlank { responseStatus })
                    }
                    response.body.contentLength().takeIf { it > MAX_AUDIO_BYTES }?.let {
                        error("Audio is too large: $it bytes")
                    }
                    copyToFileLimited(response.body.byteStream(), tempFile)
                }
                logRequest(provider, url, responseStatus, startedAt, true)
                tempFile
            } catch (e: Throwable) {
                runCatching { tempFile.delete() }
                logRequest(
                    provider, url,
                    responseStatus.ifBlank { e.javaClass.simpleName },
                    startedAt, false, throwable = e
                )
                throw e
            }
        }

    private suspend fun cancelByOpenAi(remoteTaskId: String, provider: AiAudioProviderConfig) {
        val baseUrl = normalizeBaseUrl(provider.baseUrl)
        if (baseUrl.isBlank()) return
        val requestUrl = buildCancelUrl(provider, baseUrl, remoteTaskId)
        val startedAt = System.currentTimeMillis()
        var status = ""
        try {
            provider.httpClient().newCallResponse {
                url(requestUrl)
                postJson("{}")
                addHeader("Accept", "application/json")
                addHeader("Content-Type", "application/json")
                provider.apiKey.takeIf { it.isNotBlank() }?.let {
                    addHeader("Authorization", "Bearer $it")
                }
                addHeaders(AiChatService.parseCustomHeaders(provider.headers))
            }.use { response ->
                status = "${response.code} ${response.message}"
            }
            logRequest(provider, requestUrl, status, startedAt, true)
        } catch (e: Throwable) {
            // best-effort, swallow errors
            logRequest(provider, requestUrl, status.ifBlank { e.javaClass.simpleName }, startedAt, false, throwable = e)
        }
    }

    // endregion

    // region js path

    private suspend fun submitByJs(
        prompt: String,
        params: JSONObject,
        provider: AiAudioProviderConfig
    ): AudioSubmitResult {
        AiSandboxBridge.setAllowedBaseUrl(provider.baseUrl)
        val paramsJson = params.toString()
        val result = evalAudioJs(
            provider,
            """
            ;(function(){
                if (typeof submit === 'function') return submit(prompt, provider, params);
                if (typeof run === 'function') return run(prompt, provider);
                if (typeof result !== 'undefined') return result;
                return null;
            })();
            """.trimIndent()
        ) {
            put("prompt", prompt)
            put("result", prompt)
            put("key", prompt)
            put("params", paramsJson)
        }
        return normalizeSubmitResult(result)
    }

    private suspend fun queryStatusByJs(
        remoteTaskId: String,
        provider: AiAudioProviderConfig
    ): AudioTaskStatus {
        AiSandboxBridge.setAllowedBaseUrl(provider.baseUrl)
        val result = evalAudioJs(
            provider,
            """
            ;(function(){
                if (typeof queryStatus === 'function') return queryStatus(remoteTaskId, provider);
                if (typeof query === 'function') return query(remoteTaskId, provider);
                return null;
            })();
            """.trimIndent()
        ) {
            put("remoteTaskId", remoteTaskId)
            put("result", remoteTaskId)
            put("key", remoteTaskId)
        }
        return normalizeStatusResult(result)
    }

    private suspend fun downloadByJs(remoteTaskId: String, provider: AiAudioProviderConfig): File {
        AiSandboxBridge.setAllowedBaseUrl(provider.baseUrl)
        val result = evalAudioJs(
            provider,
            """
            ;(function(){
                if (typeof download === 'function') return download(remoteTaskId, provider);
                return null;
            })();
            """.trimIndent()
        ) {
            put("remoteTaskId", remoteTaskId)
            put("result", remoteTaskId)
            put("key", remoteTaskId)
        }
        val source = normalizeAudioSourceString(result)
        val tempFile = File.createTempFile("ai_audio_${remoteTaskId}_", ".mp3", appCtx.cacheDir)
        val startedAt = System.currentTimeMillis()
        var responseStatus = ""
        try {
            when {
                source.startsWith("data:", true) -> {
                    val bytes = withContext(Dispatchers.IO) { decodeAudioDataUrl(source) }
                    if (bytes.size > MAX_AUDIO_BYTES) error("Audio is too large: ${bytes.size} bytes")
                    withContext(Dispatchers.IO) { tempFile.writeBytes(bytes) }
                    responseStatus = "ok ${bytes.size}"
                }
                source.startsWith("http", true) -> {
                    provider.httpClient().newCallResponse {
                        url(source)
                        addHeader("Accept", "application/octet-stream, */*")
                        provider.apiKey.takeIf { it.isNotBlank() }?.let {
                            addHeader("Authorization", "Bearer $it")
                        }
                        addHeaders(AiChatService.parseCustomHeaders(provider.headers))
                    }.use { response ->
                        responseStatus = "${response.code} ${response.message}"
                        if (!response.isSuccessful) {
                            error(response.body.string().ifBlank { responseStatus })
                        }
                        response.body.contentLength().takeIf { it > MAX_AUDIO_BYTES }?.let {
                            error("Audio is too large: $it bytes")
                        }
                        withContext(Dispatchers.IO) {
                            copyToFileLimited(response.body.byteStream(), tempFile)
                        }
                    }
                }
                else -> withContext(Dispatchers.IO) {
                    val file = File(source)
                    if (!file.isFile) error("Unsupported audio source: ${source.take(80)}")
                    if (file.length() > MAX_AUDIO_BYTES) error("Audio is too large: ${file.length()} bytes")
                    file.copyTo(tempFile, overwrite = true)
                    responseStatus = "ok ${file.length()}"
                }
            }
            logRequest(provider, "js:download", responseStatus, startedAt, true)
            tempFile
        } catch (e: Throwable) {
            runCatching { tempFile.delete() }
            logRequest(
                provider, "js:download",
                responseStatus.ifBlank { e.javaClass.simpleName },
                startedAt, false, throwable = e
            )
            throw e
        }
    }

    private suspend fun cancelByJs(remoteTaskId: String, provider: AiAudioProviderConfig) {
        AiSandboxBridge.setAllowedBaseUrl(provider.baseUrl)
        try {
            evalAudioJs(
                provider,
                """
                ;(function(){
                    if (typeof cancel === 'function') return cancel(remoteTaskId, provider);
                    return null;
                })();
                """.trimIndent()
            ) {
                put("remoteTaskId", remoteTaskId)
                put("result", remoteTaskId)
                put("key", remoteTaskId)
            }
        } catch (e: Throwable) {
            // best-effort, swallow errors
            AppLog.put("JS cancel audio task failed: $remoteTaskId", e)
        }
    }

    /**
     * 执行音频 JS 脚本，绑定 java/source/cache/cookie/baseUrl/provider 等变量。
     * 与 [AiVideoService] 的 evalVideoJs 模式保持一致。
     */
    private suspend fun evalAudioJs(
        provider: AiAudioProviderConfig,
        callJs: String,
        bindingsConfig: ScriptBindings.() -> Unit = {}
    ): Any? {
        AiSandboxBridge.setAllowedBaseUrl(provider.baseUrl)
        val script = provider.script.trim()
        if (script.isBlank()) error("JS script is empty")
        val source = AiAudioJsSource(provider)
        val coroutineContext = currentCoroutineContext()
        return withTimeout(provider.validTimeout()) {
            val bindings = buildScriptBindings { bindings ->
                bindings["java"] = source
                bindings["source"] = source
                bindings["cache"] = CacheManager
                bindings["cookie"] = CookieStore
                bindings["baseUrl"] = source.getKey()
                bindings["provider"] = provider
                bindings.apply(bindingsConfig)
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
                    append(callJs)
                },
                scope,
                coroutineContext
            )
        }
    }

    // endregion

    // region normalization

    private fun normalizeStatus(raw: String): String {
        val lower = raw.trim().lowercase()
        return when {
            lower.isEmpty() -> "processing"
            lower == "pending" || lower == "processing" || lower == "queued" ||
                lower == "running" || lower == "in_progress" || lower == "submitted" -> "processing"
            lower == "succeeded" || lower == "completed" || lower == "done" ||
                lower == "success" || lower == "finished" -> "succeeded"
            lower == "failed" || lower == "error" ||
                lower == "cancelled" || lower == "canceled" -> "failed"
            else -> "processing"
        }
    }

    private fun submitResultFromJson(json: JSONObject): AudioSubmitResult {
        val taskId = json.optString("id")
            .ifBlank { json.optString("task_id") }
            .ifBlank { json.optString("taskId") }
        if (taskId.isBlank()) error("No task id in audio submit response: ${jsonShape(json)}")
        val status = normalizeStatus(json.optString("status", "processing"))
        return AudioSubmitResult(taskId, status)
    }

    private fun normalizeSubmitResult(result: Any?): AudioSubmitResult {
        return when (result) {
            null -> error("Empty JS submit result")
            is String -> {
                val text = result.trim()
                if (text.isBlank() || text == "null" || text == "undefined") {
                    error("Empty JS submit result")
                }
                if (text.startsWith("{")) submitResultFromJson(JSONObject(text))
                else if (text.startsWith("[")) {
                    submitResultFromJson(JSONArray(text).optJSONObject(0) ?: error("Empty JS submit result"))
                } else AudioSubmitResult(text, "processing")
            }
            is JSONObject -> submitResultFromJson(result)
            is JSONArray -> submitResultFromJson(result.optJSONObject(0) ?: error("Empty JS submit result"))
            is NativeObject -> submitResultFromJson(result.toJSONObject())
            is NativeArray -> submitResultFromJson(result.toJSONArray().optJSONObject(0) ?: error("Empty JS submit result"))
            else -> {
                val text = result.toString().trim()
                if (text.startsWith("{")) submitResultFromJson(JSONObject(text))
                else AudioSubmitResult(text, "processing")
            }
        }
    }

    private fun statusResultFromJson(json: JSONObject): AudioTaskStatus {
        val status = normalizeStatus(json.optString("status", "processing"))
        val progress = extractProgress(json)
        val downloadUrl = audioFromOpenAiJson(json)
        return AudioTaskStatus(status, progress, downloadUrl)
    }

    private fun normalizeStatusResult(result: Any?): AudioTaskStatus {
        return when (result) {
            null -> AudioTaskStatus("processing", -1, null)
            is String -> {
                val text = result.trim()
                if (text.isBlank() || text == "null" || text == "undefined") {
                    return AudioTaskStatus("processing", -1, null)
                }
                if (text.startsWith("{")) statusResultFromJson(JSONObject(text))
                else if (text.startsWith("[")) {
                    statusResultFromJson(JSONArray(text).optJSONObject(0) ?: JSONObject())
                } else AudioTaskStatus(normalizeStatus(text), -1, null)
            }
            is JSONObject -> statusResultFromJson(result)
            is JSONArray -> statusResultFromJson(result.optJSONObject(0) ?: JSONObject())
            is NativeObject -> statusResultFromJson(result.toJSONObject())
            is NativeArray -> statusResultFromJson(result.toJSONArray().optJSONObject(0) ?: JSONObject())
            else -> {
                val text = result.toString().trim()
                if (text.startsWith("{")) statusResultFromJson(JSONObject(text))
                else AudioTaskStatus(normalizeStatus(text), -1, null)
            }
        }
    }

    private fun normalizeAudioSourceString(result: Any?): String {
        return when (result) {
            null -> error("Empty JS download result")
            is String -> {
                val text = result.trim()
                if (text.isBlank() || text == "null" || text == "undefined") error("Empty JS download result")
                if (text.startsWith("{")) audioFromOpenAiJson(JSONObject(text)) ?: text
                else text
            }
            is JSONObject -> audioFromOpenAiJson(result)
                ?: error("No audio url in JS download result: ${jsonShape(result)}")
            is JSONArray -> audioFromOpenAiArray(result) ?: error("No audio url in JS download result")
            is NativeObject -> audioFromOpenAiJson(result.toJSONObject())
                ?: error("No audio url in JS download result")
            is NativeArray -> audioFromOpenAiArray(result.toJSONArray())
                ?: error("No audio url in JS download result")
            else -> result.toString().trim().takeIf { it.isNotBlank() }
                ?: error("Empty JS download result")
        }
    }

    // endregion

    // region url finding

    private val AUDIO_URL_KEYS = listOf(
        "url", "audio_url", "download_url", "output_url", "result", "audio"
    )

    private val AUDIO_CONTAINER_KEYS = listOf("output", "audios", "data", "results", "items", "content")

    /**
     * 递归查找音频下载地址，兼容 url / audio_url / download_url / output_url /
     * result / output / audios / data[0].url 等字段。
     */
    private fun audioFromOpenAiJson(json: JSONObject): String? {
        for (key in AUDIO_URL_KEYS) {
            json.optString(key).takeIf { it.isNotBlank() && looksLikeAudioUrl(it) }?.let { return it }
        }
        for (key in AUDIO_CONTAINER_KEYS) {
            when (val value = json.opt(key)) {
                is String -> if (looksLikeAudioUrl(value)) return value
                is JSONObject -> audioFromOpenAiJson(value)?.let { return it }
                is JSONArray -> audioFromOpenAiArray(value)?.let { return it }
            }
        }
        return null
    }

    private fun audioFromOpenAiArray(array: JSONArray): String? {
        for (index in 0 until array.length()) {
            when (val item = array.opt(index)) {
                is JSONObject -> audioFromOpenAiJson(item)?.let { return it }
                is String -> if (looksLikeAudioUrl(item)) return item
            }
        }
        return null
    }

    private fun looksLikeAudioUrl(text: String): Boolean {
        val value = text.trim()
        if (value.isBlank()) return false
        return value.startsWith("http", true) || value.startsWith("data:audio", true)
    }

    private fun extractProgress(json: JSONObject): Int {
        val direct = json.opt("progress")
        val parsed = when (direct) {
            is Number -> direct.toDouble()
            is String -> direct.toDoubleOrNull()
            else -> null
        }
        if (parsed != null) {
            val value = if (parsed > 1.0) parsed.toInt() else (parsed * 100).toInt()
            if (value in 0..100) return value
        }
        for (key in AUDIO_CONTAINER_KEYS) {
            json.optJSONObject(key)?.let {
                val p = extractProgress(it)
                if (p in 0..100) return p
            }
        }
        return -1
    }

    // endregion

    // region file helpers

    private fun copyToFileLimited(input: InputStream, target: File): Long {
        var copied = 0L
        target.outputStream().use { output ->
            input.use { stream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > MAX_AUDIO_BYTES) error("Audio is too large: $copied bytes")
                    output.write(buffer, 0, read)
                }
            }
        }
        return copied
    }

    private fun decodeAudioDataUrl(source: String): ByteArray {
        val comma = source.indexOf(',')
        require(comma > 0 && source.substring(0, comma).contains(";base64", true)) {
            "Invalid audio data url"
        }
        val payload = source.substring(comma + 1).filterNot { it.isWhitespace() }
        return Base64.decode(payload, Base64.DEFAULT)
    }

    // endregion

    // region url helpers

    /**
     * 规范化 baseUrl，确保以 /v1 结尾。
     * 与 [AiVideoService] 一致，端点通过 submitEndpoint / statusEndpoint 单独解析。
     */
    private fun normalizeBaseUrl(raw: String): String {
        val normalized = raw.trim().trimEnd('/')
        return when {
            normalized.isBlank() -> ""
            normalized.endsWith("/v1") -> normalized
            else -> "$normalized/v1"
        }
    }

    private fun buildSubmitUrl(provider: AiAudioProviderConfig, baseUrl: String): String {
        val endpoint = provider.submitEndpoint.trim()
        return if (endpoint.isBlank()) {
            "${baseUrl.trimEnd('/')}/audio/generations"
        } else {
            resolveEndpoint(endpoint, baseUrl)
        }
    }

    private fun buildStatusUrl(
        provider: AiAudioProviderConfig,
        baseUrl: String,
        remoteTaskId: String
    ): String {
        val raw = provider.statusEndpoint.trim()
        val endpoint = if (raw.isBlank()) {
            "audio/generations/${remoteTaskId.trim()}"
        } else {
            raw.replace("{id}", remoteTaskId.trim()).replace("{taskId}", remoteTaskId.trim())
        }
        return resolveEndpoint(endpoint, baseUrl)
    }

    private fun buildCancelUrl(
        provider: AiAudioProviderConfig,
        baseUrl: String,
        remoteTaskId: String
    ): String {
        return "${buildStatusUrl(provider, baseUrl, remoteTaskId)}/cancel"
    }

    private fun resolveEndpoint(endpoint: String, baseUrl: String): String {
        val trimmed = endpoint.trim()
        return when {
            trimmed.startsWith("http", true) -> trimmed
            trimmed.startsWith("/") -> "${baseUrl.trimEnd('/')}$trimmed"
            else -> "${baseUrl.trimEnd('/')}/$trimmed"
        }
    }

    // endregion

    // region http & logging

    private fun AiAudioProviderConfig.httpClient(): OkHttpClient {
        val timeout = validTimeout()
        return okHttpClient.newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun logRequest(
        provider: AiAudioProviderConfig,
        url: String,
        status: String,
        startedAt: Long,
        success: Boolean,
        model: String = provider.model,
        throwable: Throwable? = null
    ) {
        val elapsed = System.currentTimeMillis() - startedAt
        val message = buildString {
            append("AI 生音频请求").append(if (success) "成功" else "失败")
            append("\nurl=").append(url)
            append("\nprovider=").append(provider.displayName())
            append("\nmodel=").append(model)
            append("\ntimeout=").append(provider.validTimeout())
            append("\nelapsed=").append(elapsed)
            append("\nstatus=").append(status)
        }
        AppLog.put(message, throwable)
    }

    // endregion

    // region json helpers

    private fun JSONObject.mergeJson(extra: JSONObject, ignored: Set<String> = emptySet()) {
        extra.keys().forEach { key ->
            if (key !in ignored) put(key, extra.opt(key))
        }
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

    // endregion

    // region js source

    private class AiAudioJsSource(
        private val provider: AiAudioProviderConfig
    ) : BaseSource {
        override var concurrentRate: String? = null
        override var loginUrl: String? = provider.loginUrl
        override var loginUi: String? = provider.loginUi
        override var header: String? = provider.headers
        override var enabledCookieJar: Boolean? = provider.enabledCookieJar
        override var jsLib: String? = provider.jsLib

        override fun getTag(): String {
            return "AiAudioRule:${provider.id}:${provider.displayName()}"
        }

        override fun getKey(): String {
            return "ai_audio_rule_${provider.id}"
        }
    }

    // endregion
}
