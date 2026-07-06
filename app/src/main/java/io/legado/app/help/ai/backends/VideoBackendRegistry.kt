package io.legado.app.help.ai.backends

import io.legado.app.ui.main.ai.AiVideoProviderConfig

/**
 * 视频 backend registry（按配置构造，非单例——backend 持有 baseUrl/apiKey/model/paramsJson 状态，
 * 故每次按配置实例化）。
 *
 * P0：[factories] 空，[byConfig] 对任何 type 报错。
 * P2 各家 backend 实现后在此注册（ark/agnes/sora/veo/kling/newapi/v2/dashscope/minimax/vidu/grok）。
 */
object VideoBackendRegistry {
    private val factories: Map<String, (AiVideoProviderConfig) -> VideoBackend> = emptyMap()

    fun byConfig(cfg: AiVideoProviderConfig): VideoBackend =
        (factories[cfg.type] ?: error("未知视频 backend type: ${cfg.type}"))(cfg)
}
