package io.legado.app.help.ai.asr

/**
 * ASR 引擎工厂。
 *
 * 按 [AsrConfig.type] 选择具体实现：
 *   - whisper → [WhisperAsrEngine]
 *   - js → [JsAsrEngine]
 *   - local → [LocalAsrEngine]
 */
object AsrEngineFactory {
    fun create(config: AsrConfig): AsrEngine = when (config.type) {
        AsrConfig.TYPE_WHISPER -> WhisperAsrEngine(config)
        AsrConfig.TYPE_JS -> JsAsrEngine(config)
        AsrConfig.TYPE_LOCAL -> LocalAsrEngine()
        else -> WhisperAsrEngine(config)
    }
}
