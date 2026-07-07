package io.legado.app.help.ai

import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.help.ai.backends.ImageBackendRegistry
import io.legado.app.help.ai.backends.ImageGenerationRequest as BackendImageRequest
import io.legado.app.help.ai.backends.ImageGenerationResult as BackendImageResult
import io.legado.app.help.ai.backends.MediaGenerator
import io.legado.app.help.ai.backends.ReferenceImage
import io.legado.app.help.ai.backends.compress.PayloadLimits
import io.legado.app.help.ai.backends.compress.RefRole
import io.legado.app.help.ai.backends.compress.ReferenceSpec
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiImageProviderConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 生图服务薄壳（P5 重构后）。
 *
 * 老 generate(String)/generateAndStore(String)/generateByOpenAi/generateByAgnesImagesApi/
 * generateByJs 等实现已删除，统一走 [generate] → [ImageBackendRegistry] 分发 →
 * [MediaGenerator] 咽喉层压缩 → backend.generate。
 *
 * Provider 解析顺序：显式传入 > [AppConfig.aiCurrentImageProvider]。
 */
object AiImageService {

    fun currentProviderOrNull(): AiImageProviderConfig? {
        return AppConfig.aiCurrentImageProvider
    }

    fun providerByIdOrNull(providerId: String?): AiImageProviderConfig? {
        return AppConfig.findEnabledImageProvider(providerId)
    }

    // ============================================================
    // 薄壳生成入口
    // ============================================================

    /**
     * 薄壳生成入口：resolveProvider → [ImageBackendRegistry.byConfig] →
     * [resolvePayloadLimits] → [MediaGenerator.runWithReferenceCompression] { backend.generate }。
     *
     * @param request 图像生成请求（参考图为本地路径，由 [MediaGenerator] 咽喉层压缩）
     * @param provider Provider；null 用 [currentProviderOrNull]
     */
    suspend fun generate(
        request: BackendImageRequest,
        provider: AiImageProviderConfig? = null
    ): BackendImageResult = withContext(Dispatchers.IO) {
        val target = resolveProvider(provider)
        val backend = ImageBackendRegistry.byConfig(target)
        val limits = resolvePayloadLimits(target)
        val specs = request.referenceImages.map { ReferenceSpec(File(it.path), it.label, RefRole.ARRAY) }
        MediaGenerator.runWithReferenceCompression(specs, limits) { compressed ->
            val mergedRefs = compressed.map { ReferenceImage(it.path.absolutePath, it.label) }
            backend.generate(request.copy(referenceImages = mergedRefs))
        }
    }

    /**
     * 薄壳 [generateAndStore]：调 [generate] 后存 [AiImageGalleryManager]。
     *
     * [BackendImageResult.imagePath] 是本地文件路径，[AiImageGalleryManager.saveGeneratedImage]
     * 的 writeImageToTempFile 已支持本地文件源（[File.isFile] 分支），无需适配。
     *
     * @param request 图像生成请求
     * @param provider Provider；null 用 [currentProviderOrNull]
     * @param metadata 入库元数据
     */
    suspend fun generateAndStore(
        request: BackendImageRequest,
        provider: AiImageProviderConfig? = null,
        metadata: AiImageGalleryManager.ImageMetadata = AiImageGalleryManager.ImageMetadata()
    ): AiGeneratedImage {
        val target = resolveProvider(provider)
        val result = generate(request, target)
        return AiImageGalleryManager.saveGeneratedImage(
            imageSource = result.imagePath.absolutePath,
            prompt = request.prompt,
            provider = target,
            model = result.model,
            metadata = metadata
        )
    }

    /**
     * 从 [AiImageProviderConfig.defaultParamsJson] 读 `reference_total_max_bytes` /
     * `reference_single_max_bytes` 覆盖默认 [PayloadLimits]。
     *
     * 解析失败/字段缺失 → 用 [PayloadLimits] 默认值（total 8MB / single 4MB）。
     */
    internal fun resolvePayloadLimits(provider: AiImageProviderConfig): PayloadLimits {
        val defaults = PayloadLimits()
        val params = provider.defaultParamsJson.takeIf { it.isNotBlank() } ?: return defaults
        val root = runCatching { JsonParser.parseString(params).asJsonObject }.getOrNull() ?: return defaults
        val totalMax = root.get("reference_total_max_bytes")?.takeIf { !it.isJsonNull }?.asLong
        val singleMax = root.get("reference_single_max_bytes")?.takeIf { !it.isJsonNull }?.asLong
        return PayloadLimits(
            totalMaxBytes = totalMax ?: defaults.totalMaxBytes,
            singleMaxBytes = singleMax ?: defaults.singleMaxBytes
        )
    }

    private fun resolveProvider(provider: AiImageProviderConfig?): AiImageProviderConfig {
        return provider ?: currentProviderOrNull()
            ?: error("请选择可用生图模型")
    }
}
