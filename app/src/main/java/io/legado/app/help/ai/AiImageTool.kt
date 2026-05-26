package io.legado.app.help.ai

import org.json.JSONArray
import org.json.JSONObject

object AiImageTool {
    fun resolvedTools(): List<AiResolvedTool> = listOf(
        AiResolvedTool(
            name = "generate_image",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "generate_image")
                    put("description", "Generate an image and return an image url or data url.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("prompt", JSONObject().apply {
                                put("type", "string")
                                put("description", "Image prompt")
                            })
                        })
                        put("required", JSONArray().put("prompt"))
                    })
                })
            },
            execute = { args ->
                val prompt = args?.optString("prompt").orEmpty().trim()
                if (prompt.isBlank()) {
                    JSONObject().put("ok", false).put("success", false).put("error", "prompt is empty").toString()
                } else {
                    runCatching {
                        JSONObject()
                            .put("ok", true)
                            .put("success", true)
                            .put("type", "image")
                            .put("prompt", prompt)
                            .put("image", AiImageService.generate(prompt))
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
