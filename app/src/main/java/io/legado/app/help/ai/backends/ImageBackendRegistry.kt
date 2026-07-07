package io.legado.app.help.ai.backends

import io.legado.app.ui.main.ai.AiImageProviderConfig

/**
 * 图像 backend registry（按配置构造，非单例——backend 持有 baseUrl/apiKey/model/paramsJson 状态，
 * 故每次按配置实例化）。
 *
 * P0：[factories] 空，[byConfig] 对任何 type 报错。
 * P3 各家 backend 实现后在此注册（agnes/ark/dashscope/gemini/grok/kling/minimax/openai/vidu）。
 */
object ImageBackendRegistry {
    private val factories: MutableMap<String, (AiImageProviderConfig) -> ImageBackend> = mutableMapOf()

    /** P3+ 各家 backend 在 companion init 里调此方法注册。 */
    fun register(typeId: String, factory: (AiImageProviderConfig) -> ImageBackend) {
        factories[typeId] = factory
    }

    fun byConfig(cfg: AiImageProviderConfig): ImageBackend =
        (factories[cfg.type] ?: error("未知图像 backend type: ${cfg.type}"))(cfg)
}
