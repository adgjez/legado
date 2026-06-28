package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGenTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.net.SocketException

internal object AiToolExecutor {

    private const val DEFAULT_TOOL_TIMEOUT_MILLIS = 120_000L
    private const val IMAGE_TOOL_TIMEOUT_MILLIS = 300_000L
    private const val VIDEO_TOOL_TIMEOUT_MILLIS = 600_000L
    private const val AUDIO_TOOL_TIMEOUT_MILLIS = 300_000L
    private const val SANITIZE_TOOL_TIMEOUT_MILLIS = 120_000L
    private const val NETWORK_ABORT_RETRY_COUNT = 1

    private val imageToolNames = setOf(
        "generate_image",
        "generate_book_character_avatar",
        "generate_images",
        "edit_image",
        "inpaint_image"
    )

    private val videoToolNames = setOf(
        "generate_video",
        "generate_video_from_image",
        "extract_video_frame",
        "continue_video_from_frame"
    )

    private val sanitizeToolNames = setOf(
        "sanitize_text"
    )

    private val audioToolNames = setOf(
        "generate_music",
        "generate_sound_effect"
    )

    private val storyToolNames = setOf(
        "generate_scene"
    )

    private val retryableToolNames = setOf(
        "query_bookshelf",
        "get_bookshelf_book_info",
        "list_book_chapters",
        "read_book_chapter_content",
        "query_read_records",
        "list_book_sources",
        "search_book_source",
        "get_book_source",
        "fetch_source_html",
        "debug_book_source",
        "reading_ajax",
        "reading_webview",
        "capture_web_requests",
        "search_web_tavily",
        "generate_image",
        "generate_book_character_avatar",
        "generate_images",
        "edit_image",
        "inpaint_image",
        "list_book_characters",
        "list_book_character_relations",
        "get_app_settings",
        "generate_video",
        "generate_video_from_image",
        "extract_video_frame",
        "continue_video_from_frame",
        "sanitize_text",
        "generate_music",
        "generate_sound_effect",
        "generate_scene"
    )

    suspend fun execute(
        toolCall: AiAgentToolCall,
        toolMap: Map<String, AiResolvedTool>,
        options: AiToolExecutionOptions
    ): String {
        val enabled = AiToolRegistry.effectiveEnabledToolNames()
        if (!options.useAllTools && toolCall.name !in enabled && toolCall.name !in options.extraToolNames) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "Tool is disabled: ${toolCall.name}")
            }.toString()
        }
        val resolvedTool = toolMap[toolCall.name]
        if (resolvedTool == null) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "Unknown tool: ${toolCall.name}")
            }.toString()
        }
        val arguments = runCatching {
            toolCall.arguments.trim().takeIf { it.isNotBlank() }?.let(::JSONObject)
        }.getOrElse { throwable ->
            return JSONObject().apply {
                put("ok", false)
                put("error", throwable.message ?: throwable.javaClass.simpleName)
            }.toString()
        }
        return runCatching {
            var lastError: Throwable? = null
            repeat(NETWORK_ABORT_RETRY_COUNT + 1) { attempt ->
                try {
                    return@runCatching withTimeout(toolTimeoutMillis(toolCall.name)) {
                        resolvedTool.execute(arguments)
                    }
                } catch (throwable: Throwable) {
                    lastError = throwable
                    if (attempt >= NETWORK_ABORT_RETRY_COUNT ||
                        toolCall.name !in retryableToolNames ||
                        !throwable.isAiRetryableNetworkAbort()
                    ) {
                        throw throwable
                    }
                }
            }
            throw lastError ?: IllegalStateException("Tool failed")
        }.getOrElse { throwable ->
            if (throwable is CancellationException && throwable !is TimeoutCancellationException) {
                throw throwable
            }
            JSONObject().apply {
                put("ok", false)
                put(
                    "error",
                    if (throwable is TimeoutCancellationException) {
                        "Tool timed out after ${toolTimeoutMillis(toolCall.name)} ms"
                    } else {
                        throwable.message ?: throwable.javaClass.simpleName
                    }
                )
            }.toString()
        }
    }

    private fun toolTimeoutMillis(name: String): Long {
        return when {
            name in videoToolNames -> VIDEO_TOOL_TIMEOUT_MILLIS
            name in imageToolNames -> IMAGE_TOOL_TIMEOUT_MILLIS
            name in audioToolNames -> AUDIO_TOOL_TIMEOUT_MILLIS
            name in sanitizeToolNames -> SANITIZE_TOOL_TIMEOUT_MILLIS
            name in storyToolNames -> 1_800_000L // 30 min for story pipeline
            else -> DEFAULT_TOOL_TIMEOUT_MILLIS
        }
    }

    // Tools that support fire-and-forget async submission (return a task id immediately).
    private val asyncVideoToolNames = setOf(
        "generate_video",
        "generate_video_from_image",
        "generate_video_keyframes",
        "generate_video_multi_image",
        "continue_video_from_frame"
    )

    private val asyncAudioToolNames = setOf(
        "generate_music",
        "generate_sound_effect"
    )

    private const val STORY_PIPELINE_TIMEOUT_MILLIS = 1_800_000L // 30 min

    /**
     * Async submit mode: for video/audio tools, submit the generation task to the
     * provider and return immediately with a task id instead of waiting for the
     * (potentially long) generation to complete. The [AiGenPoller] tracks progress.
     */
    suspend fun executeAsync(
        toolCall: AiAgentToolCall,
        toolMap: Map<String, AiResolvedTool>,
        options: AiToolExecutionOptions
    ): String {
        val enabled = AiToolRegistry.effectiveEnabledToolNames()
        if (!options.useAllTools && toolCall.name !in enabled && toolCall.name !in options.extraToolNames) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "Tool is disabled: ${toolCall.name}")
            }.toString()
        }
        val arguments = runCatching {
            toolCall.arguments.trim().takeIf { it.isNotBlank() }?.let(::JSONObject)
        }.getOrElse { throwable ->
            return JSONObject().apply {
                put("ok", false)
                put("error", throwable.message ?: throwable.javaClass.simpleName)
            }.toString()
        }
        val args = arguments ?: JSONObject()
        return runCatching {
            withTimeout(toolTimeoutMillis(toolCall.name)) {
                submitAsyncTask(toolCall.name, args)
            }
        }.getOrElse { throwable ->
            if (throwable is CancellationException && throwable !is TimeoutCancellationException) {
                throw throwable
            }
            JSONObject().apply {
                put("ok", false)
                put(
                    "error",
                    if (throwable is TimeoutCancellationException) {
                        "Tool timed out after ${toolTimeoutMillis(toolCall.name)} ms"
                    } else {
                        throwable.message ?: throwable.javaClass.simpleName
                    }
                )
            }.toString()
        }
    }

    private suspend fun submitAsyncTask(name: String, args: JSONObject): String {
        val prompt = args.optString("prompt").trim()
        if (prompt.isBlank()) {
            return JSONObject().put("ok", false).put("error", "prompt is empty").toString()
        }
        return when {
            name in asyncVideoToolNames -> submitVideoTaskAsync(args, prompt)
            name in asyncAudioToolNames -> submitAudioTaskAsync(name, args, prompt)
            else -> JSONObject()
                .put("ok", false)
                .put("error", "Tool $name does not support async submission")
                .toString()
        }
    }

    private suspend fun submitVideoTaskAsync(args: JSONObject, prompt: String): String {
        val providerId = args.optString("providerId").trim()
        val provider = if (providerId.isBlank()) null else AiVideoService.providerByIdOrNull(providerId)
        val targetProvider = provider ?: AiVideoService.currentProviderOrNull()
            ?: return JSONObject().put("ok", false).put("error", "No available video provider").toString()
        val inputImageId = args.optString("inputImageId").takeIf { it.isNotBlank() }
        val tailImageId = args.optString("tailImageId").takeIf { it.isNotBlank() }
        val referenceImageId = args.optString("referenceImageId").takeIf { it.isNotBlank() }
        val negativePrompt = args.optString("negativePrompt").trim()

        val taskId = AiGenTaskManager.createTask(
            modality = "video",
            prompt = prompt,
            providerId = targetProvider.id,
            providerName = targetProvider.displayName(),
            model = targetProvider.model,
            negativePrompt = negativePrompt,
            inputImageId = inputImageId,
            referenceImageId = referenceImageId,
            sourceType = AiVideoGalleryManager.SOURCE_TYPE_CHAT
        )
        // Generate emotional progress hint
        val hint = AiPromptRewriter.generateProgressHint("video", "")
        AiGenTaskManager.updateEmotionalHint(taskId, hint)
        return try {
            val submitResult = AiVideoService.submit(
                prompt = prompt,
                inputImageId = inputImageId,
                tailImageId = tailImageId,
                referenceImageId = referenceImageId,
                params = JSONObject(),
                provider = targetProvider
            )
            AiGenTaskManager.submitTask(taskId, submitResult.remoteTaskId)
            val task: AiGenTask? = appDb.aiGenTaskDao.get(taskId)
            JSONObject().apply {
                put("ok", true)
                put("taskId", taskId)
                put("status", task?.status ?: "submitted")
                put("remoteTaskId", submitResult.remoteTaskId)
                put("modality", "video")
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AiGenTaskManager.failTask(taskId, e.localizedMessage ?: e.javaClass.simpleName)
            JSONObject().apply {
                put("ok", false)
                put("error", e.localizedMessage ?: e.javaClass.simpleName)
                put("taskId", taskId)
            }.toString()
        }
    }

    private suspend fun submitAudioTaskAsync(name: String, args: JSONObject, prompt: String): String {
        val providerId = args.optString("providerId").trim()
        val provider = if (providerId.isBlank()) null else AiAudioService.providerByIdOrNull(providerId)
        val targetProvider = provider ?: AiAudioService.currentProviderOrNull()
            ?: return JSONObject().put("ok", false).put("error", "No available audio provider").toString()
        val audioType = if (name == "generate_sound_effect") "sfx" else "music"

        val taskId = AiGenTaskManager.createTask(
            modality = "audio",
            prompt = prompt,
            providerId = targetProvider.id,
            providerName = targetProvider.displayName(),
            model = targetProvider.model,
            sourceType = audioType
        )
        // Generate emotional progress hint
        val hint = AiPromptRewriter.generateProgressHint("audio", "")
        AiGenTaskManager.updateEmotionalHint(taskId, hint)
        return try {
            val submitResult = AiAudioService.submit(prompt, JSONObject(), targetProvider)
            AiGenTaskManager.submitTask(taskId, submitResult.remoteTaskId)
            val task: AiGenTask? = appDb.aiGenTaskDao.get(taskId)
            JSONObject().apply {
                put("ok", true)
                put("taskId", taskId)
                put("status", task?.status ?: "submitted")
                put("remoteTaskId", submitResult.remoteTaskId)
                put("modality", "audio")
            }.toString()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AiGenTaskManager.failTask(taskId, e.localizedMessage ?: e.javaClass.simpleName)
            JSONObject().apply {
                put("ok", false)
                put("error", e.localizedMessage ?: e.javaClass.simpleName)
                put("taskId", taskId)
            }.toString()
        }
    }

    /**
     * Story orchestration mode: wraps the [AiStoryPipeline] execution for the
     * `generate_scene` tool with a longer timeout and progress reporting.
     */
    suspend fun executeStory(
        toolCall: AiAgentToolCall,
        toolMap: Map<String, AiResolvedTool>,
        options: AiToolExecutionOptions,
        onProgress: ((Int, String) -> Unit)? = null
    ): String {
        val enabled = AiToolRegistry.effectiveEnabledToolNames()
        if (!options.useAllTools && toolCall.name !in enabled && toolCall.name !in options.extraToolNames) {
            return JSONObject().apply {
                put("ok", false)
                put("error", "Tool is disabled: ${toolCall.name}")
            }.toString()
        }
        val arguments = runCatching {
            toolCall.arguments.trim().takeIf { it.isNotBlank() }?.let(::JSONObject)
        }.getOrElse { throwable ->
            return JSONObject().apply {
                put("ok", false)
                put("error", throwable.message ?: throwable.javaClass.simpleName)
            }.toString()
        }
        val args = arguments ?: JSONObject()
        return runCatching {
            withTimeout(STORY_PIPELINE_TIMEOUT_MILLIS) {
                runStoryPipeline(args, onProgress)
            }
        }.getOrElse { throwable ->
            if (throwable is CancellationException && throwable !is TimeoutCancellationException) {
                throw throwable
            }
            JSONObject().apply {
                put("ok", false)
                put(
                    "error",
                    if (throwable is TimeoutCancellationException) {
                        "Story pipeline timed out after $STORY_PIPELINE_TIMEOUT_MILLIS ms"
                    } else {
                        throwable.message ?: throwable.javaClass.simpleName
                    }
                )
            }.toString()
        }
    }

    private suspend fun runStoryPipeline(
        args: JSONObject,
        onProgress: ((Int, String) -> Unit)?
    ): String {
        val chapterText = args.optString("chapterText").trim()
        if (chapterText.isBlank()) {
            return JSONObject().put("ok", false).put("error", "Missing chapterText").toString()
        }
        val bookKey = args.optString("bookKey")
        val bookName = args.optString("bookName")
        val bookAuthor = args.optString("bookAuthor")
        val chapterTitle = args.optString("chapterTitle")
        val characterDescriptions = args.optString("characterDescriptions")
        val playlist = AiStoryPipeline.execute(
            chapterText = chapterText,
            bookKey = bookKey,
            bookName = bookName,
            bookAuthor = bookAuthor,
            chapterTitle = chapterTitle,
            characterDescriptions = characterDescriptions
        ) { progress ->
            onProgress?.invoke(storyProgressPercent(progress), progress.message)
        }
        return JSONObject().apply {
            put("ok", true)
            put("success", true)
            put("type", "story_playlist")
            put("playlistId", playlist.id)
            put("sceneCount", playlist.sceneCount)
            put("totalDuration", playlist.totalDuration)
            put("status", playlist.status)
            put("message", "分镜视频生成完成，共 ${playlist.sceneCount} 个场景")
        }.toString()
    }

    private fun storyProgressPercent(progress: AiStoryPipeline.PipelineProgress): Int {
        val total = progress.total.coerceAtLeast(1)
        val base = when (progress.stage) {
            "planning" -> 0
            "generating_images" -> 10 + (progress.current * 40 / total)
            "generating_videos" -> 50 + (progress.current * 40 / total)
            "done" -> 100
            "failed" -> 0
            else -> 0
        }
        return base.coerceIn(0, 100)
    }
}

internal fun Throwable.isAiRetryableNetworkAbort(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        val message = current.message.orEmpty().lowercase()
        if (current is SocketException) return true
        if (current is IOException && (
                "software caused connection abort" in message ||
                        "connection reset" in message ||
                        "unexpected end of stream" in message ||
                        "stream was reset" in message ||
                        "closed" in message && "connection" in message
                )
        ) {
            return true
        }
        current = current.cause
    }
    return false
}
