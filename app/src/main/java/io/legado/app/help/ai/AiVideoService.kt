package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.help.ai.backends.MediaGenerator
import io.legado.app.help.ai.backends.VideoBackend
import io.legado.app.help.ai.backends.VideoBackendRegistry
import io.legado.app.help.ai.backends.VideoGenerationRequest
import io.legado.app.help.ai.backends.VideoGenerationResult
import io.legado.app.help.ai.backends.VideoProgress
import io.legado.app.help.ai.backends.compress.CompressedRef
import io.legado.app.help.ai.backends.compress.PayloadLimits
import io.legado.app.help.ai.backends.compress.RefRole
import io.legado.app.help.ai.backends.compress.ReferenceSpec
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 文生视频服务薄壳（P5 重构后）。
 *
 * 老 submit/poll/downloadToLocal 三段式 API 与 [AiVideoTaskPoller] 已删除，
 * 统一走 [generate] → [VideoBackendRegistry] 分发 → [MediaGenerator] 咽喉层压缩 → backend.generate。
 *
 * Provider 解析顺序：显式传入 > [AppConfig.aiCurrentVideoProvider]。
 */
object AiVideoService {

    fun providerByIdOrNull(providerId: String?): AiVideoProviderConfig? =
        AppConfig.findEnabledVideoProvider(providerId)

    fun currentProviderOrNull(): AiVideoProviderConfig? = AppConfig.aiCurrentVideoProvider

    /**
     * 薄壳生成入口：resolveProvider → [VideoBackendRegistry.byConfig] →
     * [resolvePayloadLimits] → [buildSpecs] → [MediaGenerator.runWithReferenceCompression] { backend.generate }。
     *
     * @param request 视频生成请求（参考图为本地 [File]，由 [MediaGenerator] 咽喉层压缩）
     * @param provider Provider；null 用 [currentProviderOrNull]
     * @param onProgress 进度回调（submitted/polling/done/failed）
     */
    suspend fun generate(
        request: VideoGenerationRequest,
        provider: AiVideoProviderConfig? = null,
        onProgress: (VideoProgress) -> Unit = {}
    ): VideoGenerationResult = withContext(Dispatchers.IO) {
        val target = resolveProvider(provider)
        val backend = VideoBackendRegistry.byConfig(target)
        val limits = resolvePayloadLimits(target)
        val specs = buildSpecs(request, backend)
        MediaGenerator.runWithReferenceCompression(specs, limits) { compressed ->
            val merged = request.withCompressedRefs(compressed)
            backend.generate(merged, onProgress)
        }
    }

    /**
     * 按 [VideoBackend.videoCapabilities] 分配 [ReferenceSpec] 角色 + 标签。
     *
     * - 首帧 → `first_frame`，[RefRole.FRAME]（永不缩尺寸）
     * - 尾帧：
     *   - 后端支持 lastFrame → `last_frame`，[RefRole.FRAME]
     *   - 后端不支持 lastFrame 但支持 referenceImages → 降级为 `last_frame_downgraded`，
     *     [RefRole.ARRAY]（与 ArcReel 尾帧三级 fallback 对齐）
     *   - 都不支持 → 忽略并 [AppLog.put] warning
     * - 参考图数组 → `ref_$i`，[RefRole.ARRAY]
     */
    internal fun buildSpecs(
        request: VideoGenerationRequest,
        backend: VideoBackend
    ): List<ReferenceSpec> {
        val specs = mutableListOf<ReferenceSpec>()
        val caps = backend.videoCapabilities
        request.startImage?.let { specs.add(ReferenceSpec(it, "first_frame", RefRole.FRAME)) }
        request.endImage?.let { endImage ->
            when {
                caps.lastFrame -> specs.add(ReferenceSpec(endImage, "last_frame", RefRole.FRAME))
                caps.referenceImages -> {
                    specs.add(ReferenceSpec(endImage, "last_frame_downgraded", RefRole.ARRAY))
                    logBackendWarning("video backend ${backend.typeId} 不支持 last_frame，尾帧降级为 reference_images")
                }
                else -> logBackendWarning("video backend ${backend.typeId} 不支持 last_frame 与 reference_images，尾帧被忽略：${endImage}")
            }
        }
        request.referenceImages?.forEachIndexed { i, f ->
            specs.add(ReferenceSpec(f, "ref_$i", RefRole.ARRAY))
        }
        return specs
    }

    /**
     * 把压缩产物按 label 还原回 [VideoGenerationRequest]。
     *
     * - `first_frame` → [VideoGenerationRequest.startImage]
     * - `last_frame` → [VideoGenerationRequest.endImage]
     * - 其他（含降级尾帧 `last_frame_downgraded` 与 `ref_*`）→ [VideoGenerationRequest.referenceImages]
     */
    internal fun VideoGenerationRequest.withCompressedRefs(
        compressed: List<CompressedRef>
    ): VideoGenerationRequest {
        val firstFrame = compressed.firstOrNull { it.label == "first_frame" }?.path
        val lastFrame = compressed.firstOrNull { it.label == "last_frame" }?.path
        val refs = compressed
            .filter { it.label != "first_frame" && it.label != "last_frame" }
            .map { it.path }
        return copy(startImage = firstFrame, endImage = lastFrame, referenceImages = refs)
    }

    /**
     * 从 [AiVideoProviderConfig.defaultParamsJson] 读 `reference_total_max_bytes` /
     * `reference_single_max_bytes` 覆盖默认 [PayloadLimits]。
     *
     * 解析失败/字段缺失 → 用 [PayloadLimits] 默认值（total 8MB / single 4MB）。
     */
    internal fun resolvePayloadLimits(provider: AiVideoProviderConfig): PayloadLimits {
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

    // ============================================================
    // 内部工具
    // ============================================================

    /**
     * 记录 backend 能力 warning。用 runCatching 包裹 [AppLog.put]，避免
     * 在 Robolectric 测试环境下 [AppConfig] 类初始化失败（appCtx 未初始化）
     * 导致 buildSpecs 抛 ExceptionInInitializerError。日志失败不应影响业务。
     */
    private fun logBackendWarning(message: String) {
        runCatching { AppLog.put(message) }
    }

    private fun resolveProvider(provider: AiVideoProviderConfig?): AiVideoProviderConfig {
        return provider ?: currentProviderOrNull()
            ?: error("未配置文生视频 Provider，请先在设置中添加")
    }
}
