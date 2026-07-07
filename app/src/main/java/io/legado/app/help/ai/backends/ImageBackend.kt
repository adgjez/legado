package io.legado.app.help.ai.backends

import java.io.File

/**
 * 图像 backend 抽象（移植 ArcReel lib/image_backends/base.py）。
 *
 * 非 sealed：各 backend 实现在 `image/` 子包，sealed 跨包不允许；
 * 动态分发经 [ImageBackendRegistry]，无需 exhaustive when。与 [VideoBackend] 同构。
 *
 * 生命周期模型 1a 忠实：[generate] 自管请求+解析+落盘全生命周期。
 */
interface ImageBackend {
    val typeId: String
    val model: String
    val capabilities: Set<ImageCapability>
    suspend fun generate(request: ImageGenerationRequest): ImageGenerationResult
}

enum class ImageCapability { TEXT_TO_IMAGE, IMAGE_TO_IMAGE }

/**
 * 图像参考图（path 可能是本地路径或压缩管线产出的临时文件路径）。
 * label 用于 Gemini 等按文件名推断参考图名的 backend。
 */
data class ReferenceImage(val path: String, val label: String = "")

data class ImageGenerationRequest(
    val prompt: String,
    val outputPath: File,
    val referenceImages: List<ReferenceImage> = emptyList(),
    val aspectRatio: String = "9:16",
    val imageSize: String? = null,
    val projectName: String? = null,
    val seed: Long? = null
)

data class ImageGenerationResult(
    val imagePath: File,
    val provider: String,
    val model: String,
    val usageTokens: Long? = null
)
