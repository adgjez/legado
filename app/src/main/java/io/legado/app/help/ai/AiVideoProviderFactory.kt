package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiVideoProviderConfig

/**
 * AI 视频 Provider 工厂
 */
object AiVideoProviderFactory {
    fun create(config: AiVideoProviderConfig): AiVideoProvider {
        return when (config.type) {
            AiVideoProviderConfig.TYPE_KLING -> AiVideoKlingProvider(config)
            AiVideoProviderConfig.TYPE_JS -> AiVideoJsProvider(config)
            else -> AiVideoOpenAiProvider(config)
        }
    }
}
