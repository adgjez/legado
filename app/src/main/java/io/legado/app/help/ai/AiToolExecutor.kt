package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.net.SocketException

internal object AiToolExecutor {

    private const val DEFAULT_TOOL_TIMEOUT_MILLIS = 120_000L
    private const val IMAGE_TOOL_TIMEOUT_MILLIS = 300_000L
    private const val NETWORK_ABORT_RETRY_COUNT = 1

    private val imageToolNames = setOf(
        "generate_image",
        "generate_book_character_avatar"
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
        "workspace_import_book_source",
        "workspace_debug_book_source",
        "reading_ajax",
        "reading_webview",
        "capture_web_requests",
        "search_web_tavily",
        "generate_image",
        "generate_book_character_avatar",
        "list_book_characters",
        "list_book_character_relations",
        "get_app_settings"
    )

    suspend fun execute(
        toolCall: AiAgentToolCall,
        toolMap: Map<String, AiResolvedTool>,
        options: AiToolExecutionOptions
    ): String {
        val enabled = AppConfig.aiEnabledToolNames.ifEmpty { AiToolRegistry.defaultEnabledTools }
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
                    // 协程取消必须向上传播，不能降级为错误 JSON 或触发重试
                    if (throwable is CancellationException) throw throwable
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
            // runCatching 会吞掉 CancellationException，这里显式重抛以恢复取消语义
            if (throwable is CancellationException) throw throwable
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
        return if (name in imageToolNames) IMAGE_TOOL_TIMEOUT_MILLIS else DEFAULT_TOOL_TIMEOUT_MILLIS
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
