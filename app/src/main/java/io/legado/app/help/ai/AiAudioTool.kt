package io.legado.app.help.ai

import org.json.JSONArray
import org.json.JSONObject

object AiAudioTool {
    fun resolvedTools(): List<AiResolvedTool> = listOf(
        AiResolvedTool(
            name = "generate_music",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "generate_music")
                    put("description", "生成背景音乐，返回音频 id、本地路径、时长与格式。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("prompt", JSONObject().apply {
                                put("type", "string")
                                put("description", "音乐风格描述")
                            })
                            put("duration", JSONObject().apply {
                                put("type", "number")
                                put("description", "时长(秒)")
                            })
                            put("providerId", JSONObject().apply {
                                put("type", "string")
                                put("description", "可选的音频 provider id，仅当用户明确指定音频模型时使用，否则省略。")
                            })
                        })
                        put("required", JSONArray().put("prompt"))
                    })
                })
            },
            execute = { args ->
                val prompt = args?.optString("prompt").orEmpty().trim()
                if (prompt.isBlank()) {
                    JSONObject().put("ok", false).put("error", "prompt is empty").toString()
                } else {
                    val duration = args?.optDouble("duration", 0.0) ?: 0.0
                    val providerId = args?.optString("providerId").orEmpty().trim()
                    val provider = AiAudioService.providerByIdOrNull(providerId)
                        ?: AiAudioService.currentProviderOrNull()
                    if (provider == null) {
                        JSONObject().put("ok", false).put("error", "No audio provider configured").toString()
                    } else {
                        runCatching {
                            val metadata = AiAudioGalleryManager.AudioMetadata(
                                sourceType = AiAudioGalleryManager.SOURCE_TYPE_CHAT,
                                sourceText = prompt,
                                audioType = "music",
                                duration = duration
                            )
                            val audio = AiAudioService.generateAndStore(prompt, provider, metadata)
                            JSONObject()
                                .put("ok", true)
                                .put("type", "audio")
                                .put("audioId", audio.id)
                                .put("audioPath", audio.localPath)
                                .put("duration", audio.duration)
                                .put("format", audio.format)
                                .put("status", "succeeded")
                                .toString()
                        }.getOrElse {
                            JSONObject()
                                .put("ok", false)
                                .put("error", it.localizedMessage ?: it.javaClass.simpleName)
                                .apply {
                                    put("provider", provider.displayName())
                                    put("providerType", provider.type)
                                    put("baseUrl", provider.baseUrl)
                                    put("model", provider.model)
                                }
                                .toString()
                        }
                    }
                }
            }
        ),
        AiResolvedTool(
            name = "generate_sound_effect",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "generate_sound_effect")
                    put("description", "生成音效，返回音频 id、本地路径、时长与格式。")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("prompt", JSONObject().apply {
                                put("type", "string")
                                put("description", "音效描述")
                            })
                            put("duration", JSONObject().apply {
                                put("type", "number")
                                put("description", "时长(秒)")
                            })
                            put("providerId", JSONObject().apply {
                                put("type", "string")
                                put("description", "可选的音频 provider id，仅当用户明确指定音频模型时使用，否则省略。")
                            })
                        })
                        put("required", JSONArray().put("prompt"))
                    })
                })
            },
            execute = { args ->
                val prompt = args?.optString("prompt").orEmpty().trim()
                if (prompt.isBlank()) {
                    JSONObject().put("ok", false).put("error", "prompt is empty").toString()
                } else {
                    val duration = args?.optDouble("duration", 0.0) ?: 0.0
                    val providerId = args?.optString("providerId").orEmpty().trim()
                    val provider = AiAudioService.providerByIdOrNull(providerId)
                        ?: AiAudioService.currentProviderOrNull()
                    if (provider == null) {
                        JSONObject().put("ok", false).put("error", "No audio provider configured").toString()
                    } else {
                        runCatching {
                            val metadata = AiAudioGalleryManager.AudioMetadata(
                                sourceType = AiAudioGalleryManager.SOURCE_TYPE_CHAT,
                                sourceText = prompt,
                                audioType = "sound_effect",
                                duration = duration
                            )
                            val audio = AiAudioService.generateAndStore(prompt, provider, metadata)
                            JSONObject()
                                .put("ok", true)
                                .put("type", "audio")
                                .put("audioId", audio.id)
                                .put("audioPath", audio.localPath)
                                .put("duration", audio.duration)
                                .put("format", audio.format)
                                .put("status", "succeeded")
                                .toString()
                        }.getOrElse {
                            JSONObject()
                                .put("ok", false)
                                .put("error", it.localizedMessage ?: it.javaClass.simpleName)
                                .apply {
                                    put("provider", provider.displayName())
                                    put("providerType", provider.type)
                                    put("baseUrl", provider.baseUrl)
                                    put("model", provider.model)
                                }
                                .toString()
                        }
                    }
                }
            }
        )
    )
}
