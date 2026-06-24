package io.legado.app.help.ai

import org.json.JSONArray
import org.json.JSONObject

object AiSanitizeTool {
    fun resolvedTools(): List<AiResolvedTool> = listOf(
        AiResolvedTool(
            name = "sanitize_text",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "sanitize_text")
                    put("description", "Sanitize text content using AI to fix OCR errors, broken words, and punctuation, then return the cleaned text.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("text", JSONObject().apply {
                                put("type", "string")
                                put("description", "The text to sanitize.")
                            })
                            put("intensity", JSONObject().apply {
                                put("type", "integer")
                                put("description", "Sanitize intensity from 1 to 10. Default is 3.")
                            })
                            put("bookKey", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional book key for cache scoping.")
                            })
                            put("chapterIndex", JSONObject().apply {
                                put("type", "integer")
                                put("description", "Optional chapter index for cache scoping.")
                            })
                            put("providerId", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional AI chat provider id. Use only when user explicitly selects a model; otherwise omit it.")
                            })
                        })
                        put("required", JSONArray().put("text"))
                    })
                })
            },
            execute = { args ->
                val text = args?.optString("text").orEmpty().trim()
                if (text.isBlank()) {
                    JSONObject().put("ok", false).put("success", false).put("error", "text is empty").toString()
                } else {
                    val intensity = (args?.optInt("intensity", 3) ?: 3).coerceIn(1, 10)
                    val bookKey = args?.optString("bookKey").orEmpty().trim()
                    val chapterIndex = args?.optInt("chapterIndex", -1) ?: -1
                    val providerId = args?.optString("providerId").orEmpty().trim()
                        .takeIf { it.isNotBlank() }
                    runCatching {
                        val result = AiSanitizeService.sanitize(
                            text,
                            intensity,
                            bookKey,
                            chapterIndex,
                            providerId
                        )
                        JSONObject()
                            .put("ok", true)
                            .put("success", true)
                            .put("type", "sanitized_text")
                            .put("bookKey", bookKey)
                            .put("chapterIndex", chapterIndex)
                            .put("originalLength", result.originalLength)
                            .put("sanitizedLength", result.sanitizedLength)
                            .put("cached", result.cached)
                            .put("text", result.sanitizedText)
                            .toString()
                    }.getOrElse {
                        JSONObject()
                            .put("ok", false)
                            .put("success", false)
                            .put("error", it.localizedMessage ?: it.javaClass.simpleName)
                            .toString()
                    }
                }
            }
        )
    )
}
