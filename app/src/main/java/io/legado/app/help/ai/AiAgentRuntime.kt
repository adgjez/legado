package io.legado.app.help.ai

import io.legado.app.R
import io.legado.app.data.entities.AiAgentTrace
import io.legado.app.ui.main.ai.AI_API_MODE_RESPONSES
import io.legado.app.ui.main.ai.AiChatException
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx

internal object AiAgentRuntime {

    private const val MAX_TOOL_ROUNDS = 12
    private const val MAX_SEARCH_RESULT_CARDS = 8

    suspend fun runToolLoop(
        apiMode: String,
        conversation: MutableList<JSONObject>,
        tools: List<AiResolvedTool>,
        requestLog: StringBuilder,
        onStatus: (JSONObject) -> Unit,
        includeStructuredBlocks: Boolean,
        useAllTools: Boolean,
        extraToolNames: Set<String>,
        agentRun: AiAgentStateStore.Run?,
        requestAssistantTurn: suspend (
            round: Int,
            messages: List<JSONObject>,
            tools: List<AiResolvedTool>
        ) -> AiAgentAssistantTurn
    ): String {
        val toolMap = tools.associateBy { it.name }
        val searchResultCards = JSONArray()
        val toolEvents = JSONArray()
        val toolOptions = AiToolExecutionOptions(
            useAllTools = useAllTools,
            extraToolNames = extraToolNames
        )
        repeat(MAX_TOOL_ROUNDS) { round ->
            val roundNo = round + 1
            AiAgentStateStore.trace(
                run = agentRun,
                eventType = AiAgentTrace.EVENT_STATUS,
                payload = JSONObject()
                    .put("stage", "round_start")
                    .put("round", roundNo),
                round = roundNo
            )
            val thinkingKey = "thinking_$roundNo"
            onStatus(
                JSONObject().apply {
                    put("key", thinkingKey)
                    put("kind", "thinking")
                    put("stage", "start")
                    put("round", roundNo)
                    put("label", appCtx.getString(R.string.ai_chat_thinking))
                    put("success", true)
                }
            )
            val assistantTurn = requestAssistantTurn(roundNo, conversation, tools)
            conversation += assistantTurn.rawMessage
            AiAgentStateStore.trace(
                run = agentRun,
                eventType = AiAgentTrace.EVENT_MODEL_RESPONSE,
                payload = JSONObject()
                    .put("round", roundNo)
                    .put("contentChars", assistantTurn.content.length)
                    .put("toolCalls", assistantTurn.toolCalls.size)
                    .put("reasoningChars", assistantTurn.reasoningContent.length),
                round = roundNo,
                success = true
            )
            if (assistantTurn.toolCalls.isEmpty()) {
                onStatus(
                    JSONObject().apply {
                        put("key", thinkingKey)
                        put("kind", "thinking")
                        put("stage", "finish")
                        put("round", roundNo)
                        put("label", appCtx.getString(R.string.ai_chat_thinking_done))
                        put("content", assistantTurn.reasoningContent)
                        put("removeIfBlank", assistantTurn.reasoningContent.isBlank())
                        put("success", true)
                    }
                )
                val content = assistantTurn.content
                if (content.isBlank()) {
                    throw AiChatException(
                        message = "Empty response",
                        debugLog = requestLog.toSafeDebugLog()
                    )
                }
                AiAgentStateStore.trace(
                    run = agentRun,
                    eventType = AiAgentTrace.EVENT_STATUS,
                    payload = JSONObject()
                        .put("stage", "round_finish")
                        .put("round", roundNo)
                        .put("outputChars", content.length),
                    round = roundNo
                )
                return if (includeStructuredBlocks) {
                    appendStructuredBlocks(content, searchResultCards, toolEvents)
                } else {
                    content
                }
            }
            onStatus(
                JSONObject().apply {
                    put("key", thinkingKey)
                    put("kind", "thinking")
                    put("stage", "finish")
                    put("round", roundNo)
                    put("label", appCtx.getString(R.string.ai_chat_thinking_done))
                    put("content", assistantTurn.reasoningContent)
                    put("fallback", appCtx.getString(R.string.ai_tool_status_calling))
                    put("success", true)
                }
            )
            assistantTurn.toolCalls.forEach { toolCall ->
                onStatus(
                    JSONObject().apply {
                        put("key", toolCall.id.ifBlank { toolCall.name })
                        put("kind", "tool")
                        put("name", toolCall.name)
                        put("stage", "call")
                        put("label", appCtx.getString(R.string.ai_tool_status_calling))
                        put("content", toolCall.arguments)
                        put("success", true)
                    }
                )
                toolEvents.put(
                    JSONObject().apply {
                        put("name", toolCall.name)
                        put("stage", "call")
                        put("content", toolCall.arguments)
                        put("success", true)
                    }
                )
                AiAgentStateStore.trace(
                    run = agentRun,
                    eventType = AiAgentTrace.EVENT_TOOL_CALL,
                    payload = JSONObject()
                        .put("name", toolCall.name)
                        .put("arguments", toolCall.arguments.take(8_000)),
                    round = roundNo,
                    success = true
                )
                val result = AiToolExecutor.execute(toolCall, toolMap, toolOptions)
                collectSearchResultCards(toolCall, result, searchResultCards)
                val resultSuccess = parseToolResultSuccess(result)
                AiAgentStateStore.trace(
                    run = agentRun,
                    eventType = AiAgentTrace.EVENT_TOOL_RESULT,
                    payload = JSONObject()
                        .put("name", toolCall.name)
                        .put("result", result.take(8_000)),
                    round = roundNo,
                    success = resultSuccess
                )
                toolEvents.put(
                    JSONObject().apply {
                        put("name", toolCall.name)
                        put("stage", "result")
                        put("content", result)
                        put("success", resultSuccess)
                    }
                )
                onStatus(
                    JSONObject().apply {
                        put("key", toolCall.id.ifBlank { toolCall.name })
                        put("kind", "tool")
                        put("name", toolCall.name)
                        put("stage", "result")
                        put(
                            "label",
                            appCtx.getString(
                                if (resultSuccess) R.string.ai_tool_status_done else R.string.ai_tool_status_failed
                            )
                        )
                        put("content", result)
                        put("success", resultSuccess)
                    }
                )
                conversation += JSONObject().apply {
                    if (apiMode == AI_API_MODE_RESPONSES) {
                        put("type", "function_call_output")
                        put("call_id", toolCall.id)
                        put("output", result)
                    } else {
                        put("role", "tool")
                        put("tool_call_id", toolCall.id)
                        put("content", result)
                    }
                }
            }
        }
        AiAgentStateStore.trace(
            run = agentRun,
            eventType = AiAgentTrace.EVENT_STATUS,
            payload = JSONObject().put("stage", "round_limit"),
            round = MAX_TOOL_ROUNDS
        )
        conversation += JSONObject().apply {
            put("role", "system")
            put(
                "content",
                appCtx.getString(R.string.ai_tool_round_limit_system_prompt)
            )
        }
        val finalTurn = requestAssistantTurn(MAX_TOOL_ROUNDS + 1, conversation, emptyList())
        if (finalTurn.content.isBlank()) {
            throw AiChatException(
                message = appCtx.getString(R.string.ai_tool_round_limit_summary),
                debugLog = requestLog.toSafeDebugLog()
            )
        }
        return if (includeStructuredBlocks) {
            appendStructuredBlocks(finalTurn.content, searchResultCards, toolEvents)
        } else {
            finalTurn.content
        }
    }

    private fun collectSearchResultCards(
        toolCall: AiAgentToolCall,
        result: String,
        cards: JSONArray
    ) {
        if (toolCall.name != "search_book_source") return
        runCatching {
            val results = JSONObject(result).optJSONArray("results") ?: return
            for (index in 0 until results.length()) {
                if (cards.length() >= MAX_SEARCH_RESULT_CARDS) break
                val item = results.optJSONObject(index) ?: continue
                if (item.optString("bookUrl").isBlank() || item.optString("origin").isBlank()) continue
                cards.put(JSONObject().apply {
                    put("name", item.optString("name").take(80))
                    put("author", item.optString("author").take(60))
                    put("originName", item.optString("originName").take(60))
                    put("kind", item.optString("kind").take(80))
                    put("intro", item.optString("intro").replace(Regex("\\s+"), " ").trim().take(160))
                    put("latestChapterTitle", item.optString("latestChapterTitle").take(80))
                    put("coverUrl", item.optString("coverUrl"))
                    put("bookUrl", item.optString("bookUrl"))
                    put("origin", item.optString("origin"))
                    put("target", item.optString("target"))
                })
            }
        }
    }

    private fun appendStructuredBlocks(content: String, cards: JSONArray, toolEvents: JSONArray): String {
        if (cards.length() == 0) return content
        val payload = JSONObject().apply {
            put("type", "search_book_results")
            put("results", cards)
        }
        return buildString {
            append(content.trimEnd())
            if (cards.length() > 0) {
                append("\n\n```legado-search-results\n")
                append(payload)
                append("\n```")
            }
        }
    }

    private fun parseToolResultSuccess(result: String): Boolean {
        return runCatching {
            JSONObject(result).optBoolean("ok", true)
        }.getOrDefault(true)
    }

    private fun StringBuilder.toSafeDebugLog(): String {
        return AiChatService.safeDebugLog(toString())
    }
}
