package io.legado.app.help.ai

import io.legado.app.data.entities.AiGeneratedImage
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

object AiImageToolEnhanced {

    fun resolvedTools(): List<AiResolvedTool> = listOf(
        // generate_images (batch)
        AiResolvedTool(
            name = "generate_images",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "generate_images")
                    put("description", "批量生成多张图片")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("prompt", JSONObject(mapOf("type" to "string", "description" to "图片描述")))
                            put("n", JSONObject(mapOf("type" to "integer", "description" to "生成数量，默认2")))
                            put("negativePrompt", JSONObject(mapOf("type" to "string", "description" to "负向提示词")))
                            put("providerId", JSONObject(mapOf("type" to "string")))
                        })
                        put("required", JSONArray().put("prompt"))
                    })
                })
            },
            execute = { params ->
                val prompt = params?.optString("prompt")?.trim().orEmpty()
                if (prompt.isBlank()) {
                    return@AiResolvedTool errorJson("prompt is required")
                }
                val provider = AiImageService.providerByIdOrNull(params?.optString("providerId"))
                    ?: AiImageService.currentProviderOrNull()
                    ?: return@AiResolvedTool errorJson("No available image provider")
                val n = (params?.optInt("n", 2) ?: 2).coerceIn(1, 10)
                val negativePrompt = params?.optString("negativePrompt")?.trim().orEmpty()
                val metadata = AiImageGalleryManager.ImageMetadata(
                    sourceType = AiImageGalleryManager.SOURCE_TYPE_CHAT,
                    sourceText = if (negativePrompt.isBlank()) prompt
                    else "$prompt\n[negative: $negativePrompt]"
                )
                try {
                    val images: List<AiGeneratedImage> =
                        AiImageService.generateBatch(prompt, n, provider, metadata)
                    if (images.isEmpty()) {
                        return@AiResolvedTool errorJson("Batch generation produced no images")
                    }
                    val imagesArray = JSONArray()
                    images.forEach { image ->
                        imagesArray.put(JSONObject().apply {
                            put("id", image.id)
                            put("path", image.localPath)
                        })
                    }
                    JSONObject().apply {
                        put("ok", true)
                        put("type", "images_batch")
                        put("count", images.size)
                        put("images", imagesArray)
                    }.toString()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorJson(e.localizedMessage ?: e.javaClass.simpleName)
                }
            }
        ),
        // edit_image
        AiResolvedTool(
            name = "edit_image",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "edit_image")
                    put("description", "编辑/修改已有图片")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("prompt", JSONObject(mapOf("type" to "string")))
                            put("inputImageId", JSONObject(mapOf("type" to "string", "description" to "要编辑的图片ID")))
                            put("providerId", JSONObject(mapOf("type" to "string")))
                        })
                        put("required", JSONArray().put("prompt").put("inputImageId"))
                    })
                })
            },
            execute = { params ->
                val prompt = params?.optString("prompt")?.trim().orEmpty()
                if (prompt.isBlank()) {
                    return@AiResolvedTool errorJson("prompt is required")
                }
                val inputImageId = params?.optString("inputImageId")?.trim().orEmpty()
                if (inputImageId.isBlank()) {
                    return@AiResolvedTool errorJson("inputImageId is required")
                }
                val provider = AiImageService.providerByIdOrNull(params?.optString("providerId"))
                    ?: AiImageService.currentProviderOrNull()
                    ?: return@AiResolvedTool errorJson("No available image provider")
                val metadata = AiImageGalleryManager.ImageMetadata(
                    sourceType = AiImageGalleryManager.SOURCE_TYPE_CHAT,
                    sourceText = prompt
                )
                try {
                    val image = AiImageService.generateFromImage(
                        prompt, inputImageId, null, provider, metadata
                    )
                    JSONObject().apply {
                        put("ok", true)
                        put("type", "image_edit")
                        put("imageId", image.id)
                        put("imagePath", image.localPath)
                    }.toString()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorJson(e.localizedMessage ?: e.javaClass.simpleName)
                }
            }
        ),
        // inpaint_image
        AiResolvedTool(
            name = "inpaint_image",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "inpaint_image")
                    put("description", "局部重绘图片（需要mask区域）")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("prompt", JSONObject(mapOf("type" to "string")))
                            put("inputImageId", JSONObject(mapOf("type" to "string")))
                            put("maskDescription", JSONObject(mapOf("type" to "string", "description" to "要重绘的区域描述")))
                        })
                        put("required", JSONArray().put("prompt").put("inputImageId"))
                    })
                })
            },
            execute = { params ->
                val prompt = params?.optString("prompt")?.trim().orEmpty()
                if (prompt.isBlank()) {
                    return@AiResolvedTool errorJson("prompt is required")
                }
                val inputImageId = params?.optString("inputImageId")?.trim().orEmpty()
                if (inputImageId.isBlank()) {
                    return@AiResolvedTool errorJson("inputImageId is required")
                }
                val maskDescription = params?.optString("maskDescription")?.trim().orEmpty()
                val provider = AiImageService.providerByIdOrNull(params?.optString("providerId"))
                    ?: AiImageService.currentProviderOrNull()
                    ?: return@AiResolvedTool errorJson("No available image provider")
                // For now, maskDescription is passed as part of prompt; no real mask base64 yet.
                val effectivePrompt = if (maskDescription.isBlank()) prompt
                else "$prompt\n\n重绘区域: $maskDescription"
                val metadata = AiImageGalleryManager.ImageMetadata(
                    sourceType = AiImageGalleryManager.SOURCE_TYPE_CHAT,
                    sourceText = effectivePrompt
                )
                try {
                    val image = AiImageService.generateFromImage(
                        effectivePrompt, inputImageId, null, provider, metadata
                    )
                    JSONObject().apply {
                        put("ok", true)
                        put("type", "inpaint")
                        put("imageId", image.id)
                        put("imagePath", image.localPath)
                    }.toString()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    errorJson(e.localizedMessage ?: e.javaClass.simpleName)
                }
            }
        )
    )

    private fun errorJson(message: String): String {
        return JSONObject().apply {
            put("ok", false)
            put("error", message)
        }.toString()
    }
}
