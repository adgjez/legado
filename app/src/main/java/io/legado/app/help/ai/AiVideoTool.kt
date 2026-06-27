package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiVideoProviderConfig
import org.json.JSONArray
import org.json.JSONObject

object AiVideoTool {
    fun resolvedTools(): List<AiResolvedTool> = listOf(
        // ── 文生视频 ──
        AiResolvedTool(
            name = "generate_video",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "generate_video")
                    put("description", "Generate a video from a text prompt and return the video id and local path.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("prompt", JSONObject().apply {
                                put("type", "string")
                                put("description", "Video prompt")
                            })
                            put("negativePrompt", JSONObject().apply {
                                put("type", "string")
                                put("description", "Negative prompt describing content to avoid in the video.")
                            })
                            put("providerId", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional video provider id. Use only when user explicitly selects a video model; otherwise omit it.")
                            })
                            put("duration", JSONObject().apply {
                                put("type", "number")
                                put("description", "Video duration in seconds.")
                            })
                            put("aspectRatio", JSONObject().apply {
                                put("type", "string")
                                put("description", "Video aspect ratio, e.g. 16:9, 9:16, 1:1.")
                            })
                            put("cameraControl", JSONObject().apply {
                                put("type", "string")
                                put("description", "Camera control parameters, e.g. pan, zoom, orbit.")
                            })
                            put("referenceImageId", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional reference image id from the AI image gallery to guide the video.")
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
                    val providerId = args?.optString("providerId").orEmpty().trim()
                    val provider = if (providerId.isBlank()) null else AiVideoService.providerByIdOrNull(providerId)
                    val targetProvider = provider ?: AiVideoService.currentProviderOrNull()
                    val referenceImageId = args?.optString("referenceImageId")?.takeIf { it.isNotBlank() }
                    if (providerId.isNotBlank() && provider == null) {
                        JSONObject()
                            .put("ok", false)
                            .put("success", false)
                            .put("error", "video provider is unavailable: $providerId")
                            .toString()
                    } else {
                        runCatching {
                            val video = AiVideoService.generateAndStore(
                                prompt,
                                referenceImageId,
                                null,
                                null,
                                provider,
                                metadata = AiVideoGalleryManager.VideoMetadata(
                                    sourceType = AiVideoGalleryManager.SOURCE_TYPE_CHAT,
                                    sourceText = prompt
                                )
                            )
                            JSONObject()
                                .put("ok", true)
                                .put("success", true)
                                .put("type", "video")
                                .put("videoId", video.id)
                                .put("videoPath", video.localPath)
                                .put("thumbnailPath", video.thumbnailPath)
                                .put("duration", video.duration)
                                .put("provider", video.providerName)
                                .put("model", video.model)
                                .toString()
                        }.getOrElse {
                            JSONObject()
                                .put("ok", false)
                                .put("success", false)
                                .put("error", it.localizedMessage ?: it.javaClass.simpleName)
                                .apply {
                                    targetProvider?.let { current ->
                                        put("provider", current.displayName())
                                        put("providerType", current.type)
                                        put("baseUrl", current.baseUrl)
                                        put("model", current.model)
                                    }
                                }
                                .toString()
                        }
                    }
                }
            }
        ),
        // ── 图生视频 ──
        AiResolvedTool(
            name = "generate_video_from_image",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "generate_video_from_image")
                    put("description", "Generate a video from an input image and an optional prompt, returning the video id and local path.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("prompt", JSONObject().apply {
                                put("type", "string")
                                put("description", "Video prompt describing the desired motion or scene.")
                            })
                            put("inputImageId", JSONObject().apply {
                                put("type", "string")
                                put("description", "AI image gallery image id used as the first frame of the video.")
                            })
                            put("tailImageId", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional AI image gallery image id used as the last frame of the video.")
                            })
                            put("negativePrompt", JSONObject().apply {
                                put("type", "string")
                                put("description", "Negative prompt describing content to avoid in the video.")
                            })
                            put("providerId", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional video provider id. Use only when user explicitly selects a video model; otherwise omit it.")
                            })
                            put("duration", JSONObject().apply {
                                put("type", "number")
                                put("description", "Video duration in seconds.")
                            })
                            put("cameraControl", JSONObject().apply {
                                put("type", "string")
                                put("description", "Camera control parameters, e.g. pan, zoom, orbit.")
                            })
                        })
                        put("required", JSONArray().put("prompt").put("inputImageId"))
                    })
                })
            },
            execute = { args ->
                val prompt = args?.optString("prompt").orEmpty().trim()
                val inputImageId = args?.optString("inputImageId").orEmpty().trim()
                if (prompt.isBlank()) {
                    JSONObject().put("ok", false).put("success", false).put("error", "prompt is empty").toString()
                } else if (inputImageId.isBlank()) {
                    JSONObject().put("ok", false).put("success", false).put("error", "inputImageId is empty").toString()
                } else {
                    val tailImageId = args?.optString("tailImageId")?.takeIf { it.isNotBlank() }
                    val providerId = args?.optString("providerId").orEmpty().trim()
                    val provider = if (providerId.isBlank()) null else AiVideoService.providerByIdOrNull(providerId)
                    val targetProvider = provider ?: AiVideoService.currentProviderOrNull()
                    if (providerId.isNotBlank() && provider == null) {
                        JSONObject()
                            .put("ok", false)
                            .put("success", false)
                            .put("error", "video provider is unavailable: $providerId")
                            .toString()
                    } else {
                        runCatching {
                            val video = AiVideoService.generateAndStore(
                                prompt,
                                inputImageId,
                                tailImageId,
                                null,
                                provider,
                                metadata = AiVideoGalleryManager.VideoMetadata(
                                    sourceType = AiVideoGalleryManager.SOURCE_TYPE_CHAT,
                                    sourceText = prompt
                                )
                            )
                            JSONObject()
                                .put("ok", true)
                                .put("success", true)
                                .put("type", "video")
                                .put("videoId", video.id)
                                .put("videoPath", video.localPath)
                                .put("thumbnailPath", video.thumbnailPath)
                                .put("duration", video.duration)
                                .put("provider", video.providerName)
                                .put("model", video.model)
                                .toString()
                        }.getOrElse {
                            JSONObject()
                                .put("ok", false)
                                .put("success", false)
                                .put("error", it.localizedMessage ?: it.javaClass.simpleName)
                                .apply {
                                    targetProvider?.let { current ->
                                        put("provider", current.displayName())
                                        put("providerType", current.type)
                                        put("baseUrl", current.baseUrl)
                                        put("model", current.model)
                                    }
                                }
                                .toString()
                        }
                    }
                }
            }
        ),
        // ── 关键帧动画（尾帧驱动） ──
        AiResolvedTool(
            name = "generate_video_keyframes",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "generate_video_keyframes")
                    put("description", "Create a smooth keyframe animation video from a starting image and an ending image. The AI will interpolate the motion between them. Use this when the user wants to animate a transition or transformation from one image to another.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("prompt", JSONObject().apply {
                                put("type", "string")
                                put("description", "Video prompt describing the desired motion/transition between the two keyframes.")
                            })
                            put("inputImageId", JSONObject().apply {
                                put("type", "string")
                                put("description", "AI image gallery image id used as the starting keyframe.")
                            })
                            put("tailImageId", JSONObject().apply {
                                put("type", "string")
                                put("description", "AI image gallery image id used as the ending keyframe.")
                            })
                            put("providerId", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional video provider id.")
                            })
                        })
                        put("required", JSONArray().put("inputImageId").put("tailImageId"))
                    })
                })
            },
            execute = { args ->
                val inputImageId = args?.optString("inputImageId").orEmpty().trim()
                val tailImageId = args?.optString("tailImageId").orEmpty().trim()
                val prompt = args?.optString("prompt").orEmpty().trim()
                if (inputImageId.isBlank()) {
                    JSONObject().put("ok", false).put("success", false).put("error", "inputImageId is empty").toString()
                } else if (tailImageId.isBlank()) {
                    JSONObject().put("ok", false).put("success", false).put("error", "tailImageId is empty").toString()
                } else {
                    val providerId = args?.optString("providerId").orEmpty().trim()
                    val provider = if (providerId.isBlank()) null else AiVideoService.providerByIdOrNull(providerId)
                    val targetProvider = provider ?: AiVideoService.currentProviderOrNull()
                    if (providerId.isNotBlank() && provider == null) {
                        JSONObject()
                            .put("ok", false).put("success", false)
                            .put("error", "video provider is unavailable: $providerId")
                            .toString()
                    } else {
                        runCatching {
                            val video = AiVideoService.generateAndStore(
                                prompt.ifBlank { "Create a smooth transition from the first keyframe to the second keyframe" },
                                inputImageId,
                                tailImageId,
                                null,
                                provider,
                                metadata = AiVideoGalleryManager.VideoMetadata(
                                    sourceType = AiVideoGalleryManager.SOURCE_TYPE_CHAT,
                                    sourceText = prompt
                                )
                            )
                            JSONObject()
                                .put("ok", true).put("success", true)
                                .put("type", "video")
                                .put("videoId", video.id)
                                .put("videoPath", video.localPath)
                                .put("thumbnailPath", video.thumbnailPath)
                                .put("provider", video.providerName)
                                .put("model", video.model)
                                .toString()
                        }.getOrElse {
                            JSONObject()
                                .put("ok", false).put("success", false)
                                .put("error", it.localizedMessage ?: it.javaClass.simpleName)
                                .apply {
                                    targetProvider?.let { current ->
                                        put("provider", current.displayName())
                                        put("model", current.model)
                                    }
                                }
                                .toString()
                        }
                    }
                }
            }
        ),
        // ── 多图视频（多参考图融合） ──
        AiResolvedTool(
            name = "generate_video_multi_image",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "generate_video_multi_image")
                    put("description", "Generate a video using multiple reference images from the AI image gallery. The AI will use them as visual references to guide the video content. Use this when the user wants to create a video that incorporates multiple visual references or styles.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("prompt", JSONObject().apply {
                                put("type", "string")
                                put("description", "Video prompt describing the desired scene or motion.")
                            })
                            put("inputImageId", JSONObject().apply {
                                put("type", "string")
                                put("description", "Primary AI image gallery image id used as the first frame.")
                            })
                            put("referenceImageId", JSONObject().apply {
                                put("type", "string")
                                put("description", "Additional AI image gallery image id used as a visual reference for the video content.")
                            })
                            put("providerId", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional video provider id.")
                            })
                        })
                        put("required", JSONArray().put("prompt").put("inputImageId"))
                    })
                })
            },
            execute = { args ->
                val prompt = args?.optString("prompt").orEmpty().trim()
                val inputImageId = args?.optString("inputImageId").orEmpty().trim()
                val referenceImageId = args?.optString("referenceImageId")?.takeIf { it.isNotBlank() }
                if (prompt.isBlank()) {
                    JSONObject().put("ok", false).put("success", false).put("error", "prompt is empty").toString()
                } else if (inputImageId.isBlank()) {
                    JSONObject().put("ok", false).put("success", false).put("error", "inputImageId is empty").toString()
                } else {
                    val providerId = args?.optString("providerId").orEmpty().trim()
                    val provider = if (providerId.isBlank()) null else AiVideoService.providerByIdOrNull(providerId)
                    val targetProvider = provider ?: AiVideoService.currentProviderOrNull()
                    if (providerId.isNotBlank() && provider == null) {
                        JSONObject()
                            .put("ok", false).put("success", false)
                            .put("error", "video provider is unavailable: $providerId")
                            .toString()
                    } else {
                        runCatching {
                            val video = AiVideoService.generateAndStore(
                                prompt,
                                inputImageId,
                                null,
                                referenceImageId,
                                provider,
                                metadata = AiVideoGalleryManager.VideoMetadata(
                                    sourceType = AiVideoGalleryManager.SOURCE_TYPE_CHAT,
                                    sourceText = prompt
                                )
                            )
                            JSONObject()
                                .put("ok", true).put("success", true)
                                .put("type", "video")
                                .put("videoId", video.id)
                                .put("videoPath", video.localPath)
                                .put("thumbnailPath", video.thumbnailPath)
                                .put("provider", video.providerName)
                                .put("model", video.model)
                                .toString()
                        }.getOrElse {
                            JSONObject()
                                .put("ok", false).put("success", false)
                                .put("error", it.localizedMessage ?: it.javaClass.simpleName)
                                .apply {
                                    targetProvider?.let { current ->
                                        put("provider", current.displayName())
                                        put("model", current.model)
                                    }
                                }
                                .toString()
                        }
                    }
                }
            }
        ),
        // ── 提取帧 ──
        AiResolvedTool(
            name = "extract_video_frame",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "extract_video_frame")
                    put("description", "Extract a single frame from a generated video and save it as an AI gallery image.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("videoId", JSONObject().apply {
                                put("type", "string")
                                put("description", "AI generated video id.")
                            })
                            put("timestampMs", JSONObject().apply {
                                put("type", "number")
                                put("description", "timestamp in milliseconds, -1 for last frame")
                            })
                        })
                        put("required", JSONArray().put("videoId"))
                    })
                })
            },
            execute = { args ->
                val videoId = args?.optString("videoId").orEmpty().trim()
                if (videoId.isBlank()) {
                    JSONObject().put("ok", false).put("success", false).put("error", "videoId is empty").toString()
                } else {
                    val timestampMs = args?.optLong("timestampMs", -1L) ?: -1L
                    runCatching {
                        val image = if (timestampMs <= -1L) {
                            AiVideoService.extractLastFrame(videoId)
                        } else {
                            AiVideoService.extractFrame(videoId, timestampMs)
                        }
                        JSONObject()
                            .put("ok", true).put("success", true)
                            .put("type", "image")
                            .put("imageId", image.id)
                            .put("imagePath", image.localPath)
                            .put("sourceVideoId", videoId)
                            .toString()
                    }.getOrElse {
                        JSONObject()
                            .put("ok", false).put("success", false)
                            .put("error", it.localizedMessage ?: it.javaClass.simpleName)
                            .toString()
                    }
                }
            }
        ),
        // ── 续生视频 ──
        AiResolvedTool(
            name = "continue_video_from_frame",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "continue_video_from_frame")
                    put("description", "Continue or extend an existing video by extracting its last frame and generating a new video from it.")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("videoId", JSONObject().apply {
                                put("type", "string")
                                put("description", "AI generated video id to continue from.")
                            })
                            put("prompt", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional prompt describing the desired continuation.")
                            })
                            put("providerId", JSONObject().apply {
                                put("type", "string")
                                put("description", "Optional video provider id.")
                            })
                            put("duration", JSONObject().apply {
                                put("type", "number")
                                put("description", "Video duration in seconds.")
                            })
                        })
                        put("required", JSONArray().put("videoId"))
                    })
                })
            },
            execute = { args ->
                val videoId = args?.optString("videoId").orEmpty().trim()
                if (videoId.isBlank()) {
                    JSONObject().put("ok", false).put("success", false).put("error", "videoId is empty").toString()
                } else {
                    val prompt = args?.optString("prompt").orEmpty().trim()
                    val providerId = args?.optString("providerId").orEmpty().trim()
                    val provider = if (providerId.isBlank()) null else AiVideoService.providerByIdOrNull(providerId)
                    val targetProvider = provider ?: AiVideoService.currentProviderOrNull()
                    if (providerId.isNotBlank() && provider == null) {
                        JSONObject()
                            .put("ok", false).put("success", false)
                            .put("error", "video provider is unavailable: $providerId")
                            .toString()
                    } else {
                        runCatching {
                            val frame = AiVideoService.extractLastFrame(videoId)
                            val video = AiVideoService.generateAndStore(
                                prompt,
                                frame.id,
                                null,
                                null,
                                provider,
                                metadata = AiVideoGalleryManager.VideoMetadata(
                                    sourceType = AiVideoGalleryManager.SOURCE_TYPE_CHAT,
                                    sourceText = prompt
                                )
                            )
                            JSONObject()
                                .put("ok", true).put("success", true)
                                .put("type", "video")
                                .put("videoId", video.id)
                                .put("videoPath", video.localPath)
                                .put("parentVideoId", videoId)
                                .toString()
                        }.getOrElse {
                            JSONObject()
                                .put("ok", false).put("success", false)
                                .put("error", it.localizedMessage ?: it.javaClass.simpleName)
                                .apply {
                                    targetProvider?.let { current ->
                                        put("provider", current.displayName())
                                        put("providerType", current.type)
                                        put("baseUrl", current.baseUrl)
                                        put("model", current.model)
                                    }
                                }
                                .toString()
                        }
                    }
                }
            }
        )
    )
}