package io.legado.app.help.ai

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Base64
import com.script.ScriptBindings
import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.data.entities.AiGeneratedVideo
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
import io.legado.app.ui.main.ai.AiVideoProviderConfig
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * 视频生成服务。
 *
 * 与 [AiImageService] 对称，但视频生成为异步流程：submit -> poll -> download。
 * 同时支持 OpenAI 兼容接口与 JS 脚本两种 provider。
 */
object AiVideoService {

    /** 下载视频的最大字节数（200MB）。 */
    private const val MAX_VIDEO_BYTES = 200L * 1024L * 1024L

    /** 默认轮询间隔（毫秒）。 */
    private const val DEFAULT_POLL_INTERVAL = 5000L

    /** 抽帧缩放的最大边长，用于 OOM 保护。 */
    private const val MAX_FRAME_DIMENSION = 1024

    /** 抽帧保存的图片来源类型。 */
    private const val SOURCE_TYPE_VIDEO_FRAME = "video_frame_extract"

    data class VideoSubmitResult(val remoteTaskId: String, val status: String)

    data class VideoTaskStatus(
        val status: String,
        val progress: Int,
        val downloadUrl: String?,
        val previewUrl: String?
    )

    // region provider resolution

    fun currentProviderOrNull(): AiVideoProviderConfig? {
        return AppConfig.aiCurrentVideoProvider
    }

    fun providerByIdOrNull(providerId: String?): AiVideoProviderConfig? {
        return AppConfig.findEnabledVideoProvider(providerId)
    }

    private fun resolveProvider(provider: AiVideoProviderConfig?): AiVideoProviderConfig {
        return provider ?: currentProviderOrNull()
            ?: error("No available video provider, please configure one first")
    }

    private fun effectivePrompt(prompt: String, provider: AiVideoProviderConfig): String {
        val style = provider.stylePrompt.trim()
        return if (style.isBlank()) prompt else buildString {
            append(style)
            if (prompt.isNotBlank()) {
                append('\n')
                append(prompt)
            }
        }
    }

    private fun effectiveModel(provider: AiVideoProviderConfig, params: JSONObject): String {
        return provider.model.trim().ifBlank {
            params.optString("model").trim().ifBlank { "video" }
        }
    }

    // endregion

    // region async lifecycle

    suspend fun submit(
        prompt: String,
        inputImageId: String? = null,
        tailImageId: String? = null,
        referenceImageId: String? = null,
        params: JSONObject = JSONObject(),
        provider: AiVideoProviderConfig
    ): VideoSubmitResult {
        return when (provider.type) {
            AiVideoProviderConfig.TYPE_JS ->
                submitByJs(prompt, inputImageId, tailImageId, referenceImageId, params, provider)
            else ->
                submitByOpenAi(prompt, inputImageId, tailImageId, referenceImageId, params, provider)
        }
    }

    suspend fun queryStatus(
        remoteTaskId: String,
        provider: AiVideoProviderConfig
    ): VideoTaskStatus {
        return when (provider.type) {
            AiVideoProviderConfig.TYPE_JS -> queryStatusByJs(remoteTaskId, provider)
            else -> queryStatusByOpenAi(remoteTaskId, provider)
        }
    }

    suspend fun download(remoteTaskId: String, provider: AiVideoProviderConfig): File {
        return when (provider.type) {
            AiVideoProviderConfig.TYPE_JS -> downloadByJs(remoteTaskId, provider)
            else -> downloadByOpenAi(remoteTaskId, provider, null)
        }
    }

    /** 带预取下载链接的下载，避免重复查询 status */
    private suspend fun download(remoteTaskId: String, provider: AiVideoProviderConfig, downloadUrl: String?): File {
        return when (provider.type) {
            AiVideoProviderConfig.TYPE_JS -> downloadByJs(remoteTaskId, provider)
            else -> downloadByOpenAi(remoteTaskId, provider, downloadUrl)
        }
    }

    suspend fun cancel(remoteTaskId: String, provider: AiVideoProviderConfig) {
        when (provider.type) {
            AiVideoProviderConfig.TYPE_JS -> cancelByJs(remoteTaskId, provider)
            else -> cancelByOpenAi(remoteTaskId, provider)
        }
    }

    // endregion

    // region sync convenience wrapper

    /**
     * 便捷重载：仅 prompt + provider + metadata。
     * 供 [AiVideoTool] 中 `generate_video` 等无图片输入的场景使用。
     */
    suspend fun generateAndStore(
        prompt: String,
        provider: AiVideoProviderConfig? = null,
        metadata: AiVideoGalleryManager.VideoMetadata = AiVideoGalleryManager.VideoMetadata()
    ): AiGeneratedVideo = generateAndStore(prompt, null, null, null, provider, metadata)

    /**
     * 完整的同步封装：submit -> poll -> download -> store。
     */
    suspend fun generateAndStore(
        prompt: String,
        inputImageId: String?,
        tailImageId: String?,
        referenceImageId: String?,
        provider: AiVideoProviderConfig? = null,
        metadata: AiVideoGalleryManager.VideoMetadata = AiVideoGalleryManager.VideoMetadata()
    ): AiGeneratedVideo {
        val target = resolveProvider(provider)
        val effective = effectivePrompt(prompt, target)
        val params = runCatching {
            JSONObject(target.defaultParamsJson.ifBlank { "{}" })
        }.getOrDefault(JSONObject())

        val submitResult = submit(effective, inputImageId, tailImageId, referenceImageId, params, target)
        val remoteTaskId = submitResult.remoteTaskId
        val pollInterval = target.pollIntervalMillisecond.takeIf { it > 0L } ?: DEFAULT_POLL_INTERVAL
        val deadline = System.currentTimeMillis() + target.validTimeout()
        var status = submitResult.status
        var lastDownloadUrl: String? = null

        while (status == "processing" && System.currentTimeMillis() < deadline) {
            delay(pollInterval)
            val result = queryStatus(remoteTaskId, target)
            status = result.status
            lastDownloadUrl = result.downloadUrl ?: lastDownloadUrl
        }

        return when (status) {
            "succeeded" -> {
                val file = download(remoteTaskId, target, lastDownloadUrl)
                val model = effectiveModel(target, params)
                AiVideoGalleryManager.saveGeneratedVideo(file.absolutePath, effective, target, model, metadata)
            }
            "failed" -> error("Video generation failed for task $remoteTaskId")
            else -> error("Video generation timed out for task $remoteTaskId")
        }
    }

    // endregion

    // region frame extraction

    suspend fun extractLastFrame(videoId: String): AiGeneratedImage {
        return extractFrame(videoId, -1L)
    }

    suspend fun extractFrame(videoId: String, timestampMs: Long): AiGeneratedImage =
        withContext(Dispatchers.IO) {
            val video = AiVideoGalleryManager.getVideo(videoId)
                ?: error("Video not found: $videoId")
            val videoFile = File(video.localPath).takeIf { it.isFile }
                ?: error("Video file not found: $videoId")
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoFile.absolutePath)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val safeDuration = (durationMs - 1L).coerceAtLeast(0L)
                val targetMs = if (timestampMs < 0L) safeDuration else timestampMs.coerceIn(0L, safeDuration)
                val targetUs = targetMs * 1000L

                // OOM 保护：先尝试 OPTION_CLOSEST，失败回退 OPTION_CLOSEST_SYNC
                val frame = retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(targetUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
                    ?: error("Failed to extract frame at ${timestampMs}ms from video $videoId")

                val scaled = scaleBitmap(frame)
                if (scaled !== frame) frame.recycle()
                try {
                    saveFrameAsImage(scaled, videoId, timestampMs)
                } finally {
                    scaled.recycle()
                }
            } finally {
                runCatching { retriever.release() }
            }
        }

    private fun scaleBitmap(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= MAX_FRAME_DIMENSION && height <= MAX_FRAME_DIMENSION) return bitmap
        val scale = minOf(
            MAX_FRAME_DIMENSION.toFloat() / width,
            MAX_FRAME_DIMENSION.toFloat() / height
        )
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    private suspend fun saveFrameAsImage(
        bitmap: Bitmap,
        videoId: String,
        timestampMs: Long
    ): AiGeneratedImage {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        val imageSource = "data:image/png;base64,$base64"
        val prompt = buildString {
            append("Video frame")
            if (timestampMs < 0L) append(" (last frame)") else {
                append(" @ ").append(timestampMs).append("ms")
            }
        }
        val imageProvider = AiImageProviderConfig(
            name = "Video Frame",
            type = AiImageProviderConfig.TYPE_OPENAI,
            model = "frame-extract"
        )
        val metadata = AiImageGalleryManager.ImageMetadata(
            sourceType = SOURCE_TYPE_VIDEO_FRAME,
            sourceText = "Extracted from video $videoId"
        )
        return AiImageGalleryManager.saveGeneratedImage(imageSource, prompt, imageProvider, "frame-extract", metadata)
    }

    // endregion

    // region openai path

    private suspend fun submitByOpenAi(
        prompt: String,
        inputImageId: String?,
        tailImageId: String?,
        referenceImageId: String?,
        params: JSONObject,
        provider: AiVideoProviderConfig
    ): VideoSubmitResult {
        val baseUrl = normalizeBaseUrl(provider.baseUrl)
        require(baseUrl.isNotBlank()) { "Base URL is empty" }
        val model = effectiveModel(provider, params)
        val template = resolveTemplate(provider)
        val payload = template.buildSubmitPayload(
            prompt = prompt,
            model = model,
            inputImage = resolveImageAsDataUrl(inputImageId),
            tailImage = resolveImageAsDataUrl(tailImageId),
            referenceImage = resolveImageAsDataUrl(referenceImageId),
            extraParams = params,
            provider = provider
        )
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
                val result = submitResultFromJson(JSONObject(text), template)
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
        provider: AiVideoProviderConfig
    ): VideoTaskStatus {
        val baseUrl = normalizeBaseUrl(provider.baseUrl)
        require(baseUrl.isNotBlank()) { "Base URL is empty" }
        val requestUrl = buildStatusUrl(provider, baseUrl, remoteTaskId)
        val template = resolveTemplate(provider)
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
                val taskStatus = statusResultFromJson(JSONObject(text), template)
                logRequest(provider, requestUrl, status, startedAt, true)
                return taskStatus
            }
        } catch (e: Throwable) {
            logRequest(provider, requestUrl, status.ifBlank { e.javaClass.simpleName }, startedAt, false, throwable = e)
            throw e
        }
    }

    private suspend fun downloadByOpenAi(
        remoteTaskId: String,
        provider: AiVideoProviderConfig,
        downloadUrl: String? = null
    ): File = withContext(Dispatchers.IO) {
            val url = downloadUrl
                ?: queryStatusByOpenAi(remoteTaskId, provider).downloadUrl
                ?: error("No download url for video task $remoteTaskId")
            val tempFile = File.createTempFile("ai_video_${remoteTaskId}_", ".mp4", appCtx.cacheDir)
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
                    response.body.contentLength().takeIf { it > MAX_VIDEO_BYTES }?.let {
                        error("Video is too large: $it bytes")
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

    private suspend fun cancelByOpenAi(remoteTaskId: String, provider: AiVideoProviderConfig) {
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
        inputImageId: String?,
        tailImageId: String?,
        referenceImageId: String?,
        params: JSONObject,
        provider: AiVideoProviderConfig
    ): VideoSubmitResult {
        AiSandboxBridge.setAllowedBaseUrl(provider.baseUrl)
        val paramsJson = params.toString()
        val result = evalVideoJs(
            provider,
            """
            ;(function(){
                if (typeof submit === 'function') return submit(prompt, provider, inputImage, tailImage, referenceImage, params);
                if (typeof run === 'function') return run(prompt, provider);
                if (typeof result !== 'undefined') return result;
                return null;
            })();
            """.trimIndent()
        ) {
            put("prompt", prompt)
            put("result", prompt)
            put("key", prompt)
            put("inputImage", inputImageId)
            put("tailImage", tailImageId)
            put("referenceImage", referenceImageId)
            put("params", paramsJson)
        }
        return normalizeSubmitResult(result)
    }

    private suspend fun queryStatusByJs(
        remoteTaskId: String,
        provider: AiVideoProviderConfig
    ): VideoTaskStatus {
        AiSandboxBridge.setAllowedBaseUrl(provider.baseUrl)
        val result = evalVideoJs(
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

    private suspend fun downloadByJs(remoteTaskId: String, provider: AiVideoProviderConfig): File {
        AiSandboxBridge.setAllowedBaseUrl(provider.baseUrl)
        val result = evalVideoJs(
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
        val source = normalizeVideoSourceString(result)
        val tempFile = File.createTempFile("ai_video_${remoteTaskId}_", ".mp4", appCtx.cacheDir)
        val startedAt = System.currentTimeMillis()
        var responseStatus = ""
        try {
            when {
                source.startsWith("data:", true) -> {
                    val bytes = withContext(Dispatchers.IO) { decodeVideoDataUrl(source) }
                    if (bytes.size > MAX_VIDEO_BYTES) error("Video is too large: ${bytes.size} bytes")
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
                        response.body.contentLength().takeIf { it > MAX_VIDEO_BYTES }?.let {
                            error("Video is too large: $it bytes")
                        }
                        withContext(Dispatchers.IO) {
                            copyToFileLimited(response.body.byteStream(), tempFile)
                        }
                    }
                }
                else -> withContext(Dispatchers.IO) {
                    val file = File(source)
                    if (!file.isFile) error("Unsupported video source: ${source.take(80)}")
                    if (file.length() > MAX_VIDEO_BYTES) error("Video is too large: ${file.length()} bytes")
                    file.copyTo(tempFile, overwrite = true)
                    responseStatus = "ok ${file.length()}"
                }
            }
            logRequest(provider, "js:download", responseStatus, startedAt, true)
            return tempFile
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

    private suspend fun cancelByJs(remoteTaskId: String, provider: AiVideoProviderConfig) {
        AiSandboxBridge.setAllowedBaseUrl(provider.baseUrl)
        try {
            evalVideoJs(
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
            AppLog.put("JS cancel video task failed: $remoteTaskId", e)
        }
    }

    /**
     * 执行视频 JS 脚本，绑定 java/source/cache/cookie/baseUrl/provider 等变量。
     * 与 [AiImageService] 的 generateByJs 模式保持一致。
     */
    private suspend fun evalVideoJs(
        provider: AiVideoProviderConfig,
        callJs: String,
        bindingsConfig: ScriptBindings.() -> Unit = {}
    ): Any? {
        AiSandboxBridge.setAllowedBaseUrl(provider.baseUrl)
        val script = provider.script.trim()
        if (script.isBlank()) error("JS script is empty")
        val source = AiVideoJsSource(provider)
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

    private fun submitResultFromJson(json: JSONObject, template: AiVideoApiTemplate = DefaultVideoTemplate): VideoSubmitResult {
        val taskId = template.parseSubmitResult(json)
        if (taskId.isBlank()) error("No task id in video submit response: ${jsonShape(json)}")
        val status = normalizeStatus(template.parseStatus(json))
        return VideoSubmitResult(taskId, status)
    }

    private fun normalizeSubmitResult(result: Any?): VideoSubmitResult {
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
                } else VideoSubmitResult(text, "processing")
            }
            is JSONObject -> submitResultFromJson(result)
            is JSONArray -> submitResultFromJson(result.optJSONObject(0) ?: error("Empty JS submit result"))
            is NativeObject -> submitResultFromJson(result.toJSONObject())
            is NativeArray -> submitResultFromJson(result.toJSONArray().optJSONObject(0) ?: error("Empty JS submit result"))
            else -> {
                val text = result.toString().trim()
                if (text.startsWith("{")) submitResultFromJson(JSONObject(text))
                else VideoSubmitResult(text, "processing")
            }
        }
    }

    private fun statusResultFromJson(json: JSONObject, template: AiVideoApiTemplate = DefaultVideoTemplate): VideoTaskStatus {
        val status = normalizeStatus(template.parseStatus(json))
        val progress = template.parseProgress(json)
        val downloadUrl = template.parseDownloadUrl(json)
        val previewUrl = template.parsePreviewUrl(json)
        return VideoTaskStatus(status, progress, downloadUrl, previewUrl)
    }

    private fun normalizeStatusResult(result: Any?): VideoTaskStatus {
        return when (result) {
            null -> VideoTaskStatus("processing", -1, null, null)
            is String -> {
                val text = result.trim()
                if (text.isBlank() || text == "null" || text == "undefined") {
                    return VideoTaskStatus("processing", -1, null, null)
                }
                if (text.startsWith("{")) statusResultFromJson(JSONObject(text))
                else if (text.startsWith("[")) {
                    statusResultFromJson(JSONArray(text).optJSONObject(0) ?: JSONObject())
                } else VideoTaskStatus(normalizeStatus(text), -1, null, null)
            }
            is JSONObject -> statusResultFromJson(result)
            is JSONArray -> statusResultFromJson(result.optJSONObject(0) ?: JSONObject())
            is NativeObject -> statusResultFromJson(result.toJSONObject())
            is NativeArray -> statusResultFromJson(result.toJSONArray().optJSONObject(0) ?: JSONObject())
            else -> {
                val text = result.toString().trim()
                if (text.startsWith("{")) statusResultFromJson(JSONObject(text))
                else VideoTaskStatus(normalizeStatus(text), -1, null, null)
            }
        }
    }

    private fun normalizeVideoSourceString(result: Any?): String {
        return when (result) {
            null -> error("Empty JS download result")
            is String -> {
                val text = result.trim()
                if (text.isBlank() || text == "null" || text == "undefined") error("Empty JS download result")
                if (text.startsWith("{")) videoFromOpenAiJson(JSONObject(text)) ?: text
                else text
            }
            is JSONObject -> videoFromOpenAiJson(result)
                ?: error("No video url in JS download result: ${jsonShape(result)}")
            is JSONArray -> videoFromOpenAiArray(result) ?: error("No video url in JS download result")
            is NativeObject -> videoFromOpenAiJson(result.toJSONObject())
                ?: error("No video url in JS download result")
            is NativeArray -> videoFromOpenAiArray(result.toJSONArray())
                ?: error("No video url in JS download result")
            else -> result.toString().trim().takeIf { it.isNotBlank() }
                ?: error("Empty JS download result")
        }
    }

    // endregion

    // region url finding

    private val VIDEO_URL_KEYS = listOf(
        "url", "video_url", "download_url", "output_url", "result", "video",
        "mp4", "webm", "mov", "gif"
    )

    private val VIDEO_CONTAINER_KEYS = listOf("output", "videos", "data", "results", "items", "content")

    /**
     * 递归查找视频下载地址，兼容 url / video_url / download_url / output /
     * result / videos / data[0].url / output.mp4 / output_url 等字段。
     */
    private fun videoFromOpenAiJson(json: JSONObject): String? {
        for (key in VIDEO_URL_KEYS) {
            json.optString(key).takeIf { it.isNotBlank() && looksLikeVideoUrl(it) }?.let { return it }
        }
        for (key in VIDEO_CONTAINER_KEYS) {
            when (val value = json.opt(key)) {
                is String -> if (looksLikeVideoUrl(value)) return value
                is JSONObject -> videoFromOpenAiJson(value)?.let { return it }
                is JSONArray -> videoFromOpenAiArray(value)?.let { return it }
            }
        }
        return null
    }

    private fun videoFromOpenAiArray(array: JSONArray): String? {
        for (index in 0 until array.length()) {
            when (val item = array.opt(index)) {
                is JSONObject -> videoFromOpenAiJson(item)?.let { return it }
                is String -> if (looksLikeVideoUrl(item)) return item
            }
        }
        return null
    }

    private fun looksLikeVideoUrl(text: String): Boolean {
        val value = text.trim()
        if (value.isBlank()) return false
        return value.startsWith("http", true) || value.startsWith("data:video", true)
    }

    private fun findPreviewUrl(json: JSONObject): String? {
        for (key in listOf(
            "preview_url", "preview_image", "preview",
            "thumbnail_url", "thumbnail", "cover_url", "cover"
        )) {
            json.optString(key).takeIf { it.isNotBlank() && isUrlOrData(it) }?.let { return it }
        }
        for (key in VIDEO_CONTAINER_KEYS) {
            json.optJSONObject(key)?.let { findPreviewUrl(it)?.let { url -> return url } }
        }
        return null
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
        for (key in VIDEO_CONTAINER_KEYS) {
            json.optJSONObject(key)?.let {
                val p = extractProgress(it)
                if (p in 0..100) return p
            }
        }
        return -1
    }

    private fun isUrlOrData(text: String): Boolean {
        return text.startsWith("http", true) || text.startsWith("data:", true)
    }

    // endregion

    // region image resolution

    private fun resolveImageAsDataUrl(imageId: String?): String? {
        if (imageId.isNullOrBlank()) return null
        val image = AiImageGalleryManager.getImage(imageId) ?: return null
        val file = File(image.localPath).takeIf { it.isFile } ?: return null
        return runCatching {
            val bytes = file.readBytes()
            val mime = detectImageMime(bytes)
            "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        }.getOrNull()
    }

    private fun detectImageMime(bytes: ByteArray): String {
        return when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
            bytes.size >= 12 &&
                String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
                String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
            bytes.size >= 3 && String(bytes, 0, 3, Charsets.ISO_8859_1) == "GIF" -> "image/gif"
            else -> "image/png"
        }
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
                    if (copied > MAX_VIDEO_BYTES) error("Video is too large: $copied bytes")
                    output.write(buffer, 0, read)
                }
            }
        }
        return copied
    }

    private fun decodeVideoDataUrl(source: String): ByteArray {
        val comma = source.indexOf(',')
        require(comma > 0 && source.substring(0, comma).contains(";base64", true)) {
            "Invalid video data url"
        }
        val payload = source.substring(comma + 1).filterNot { it.isWhitespace() }
        return Base64.decode(payload, Base64.DEFAULT)
    }

    // endregion

    // region url helpers

    /**
     * 规范化 baseUrl，确保以 /v1 结尾。
     * 与 [AiImageService] 不同，此处不剥离 /videos/generations，
     * 因为视频端点通过 submitEndpoint / statusEndpoint 单独解析。
     */
    private fun normalizeBaseUrl(raw: String): String {
        val normalized = raw.trim().trimEnd('/')
        return when {
            normalized.isBlank() -> ""
            normalized.endsWith("/v1") -> normalized
            normalized.endsWith("/videos") -> normalized.removeSuffix("/videos")
            normalized.endsWith("/videos/generations") -> normalized.removeSuffix("/videos/generations")
            normalized.endsWith("/responses") -> normalized.removeSuffix("/responses")
            else -> "$normalized/v1"
        }
    }

    /** 委托给模板的 URL 构建，避免重复逻辑 */
    private fun buildSubmitUrl(provider: AiVideoProviderConfig, baseUrl: String): String =
        resolveTemplate(provider).buildSubmitUrl(baseUrl, provider)

    private fun buildStatusUrl(
        provider: AiVideoProviderConfig,
        baseUrl: String,
        remoteTaskId: String
    ): String = resolveTemplate(provider).buildStatusUrl(baseUrl, remoteTaskId, provider)

    private fun buildCancelUrl(
        provider: AiVideoProviderConfig,
        baseUrl: String,
        remoteTaskId: String
    ): String = resolveTemplate(provider).buildCancelUrl(baseUrl, remoteTaskId, provider)

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

    private fun AiVideoProviderConfig.httpClient(): OkHttpClient {
        val timeout = validTimeout()
        return okHttpClient.newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun logRequest(
        provider: AiVideoProviderConfig,
        url: String,
        status: String,
        startedAt: Long,
        success: Boolean,
        model: String = provider.model,
        throwable: Throwable? = null
    ) {
        val elapsed = System.currentTimeMillis() - startedAt
        val message = buildString {
            append("AI 生视频请求").append(if (success) "成功" else "失败")
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

    private fun resolveTemplate(provider: AiVideoProviderConfig): AiVideoApiTemplate {
        return AiVideoApiTemplate.find(provider.template) ?: DefaultVideoTemplate
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

    private class AiVideoJsSource(
        private val provider: AiVideoProviderConfig
    ) : BaseSource {
        override var concurrentRate: String? = null
        override var loginUrl: String? = provider.loginUrl
        override var loginUi: String? = provider.loginUi
        override var header: String? = provider.headers
        override var enabledCookieJar: Boolean? = provider.enabledCookieJar
        override var jsLib: String? = provider.jsLib

        override fun getTag(): String {
            return "AiVideoRule:${provider.id}:${provider.displayName()}"
        }

        override fun getKey(): String {
            return "ai_video_rule_${provider.id}"
        }
    }

    // endregion
}
