package io.legado.app.help.ai.backends

import io.legado.app.ui.main.ai.AiImageProviderConfig

/**
 * 图像 backend registry（按配置构造）。
 *
 * P0：[factories] 空，[byConfig] 对任何 type 报错。
 * P3 各家注册（agnes/ark/dashscope/gemini/grok/kling/minimax/openai/vidu）。
 */
object ImageBackendRegistry {
    private val factories: Map<String, (AiImageProviderConfig) -> ImageBackend> = emptyMap()

    fun byConfig(cfg: AiImageProviderConfig): ImageBackend =
        (factories[cfg.type] ?: error("未知图像 backend type: ${cfg.type}"))(cfg)
}
