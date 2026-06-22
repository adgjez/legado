package io.legado.app.help.ai.video

/**
 * P4 AI 字幕设置快照。
 *
 * 从 PreferKey 读取，每次 [VideoAiEnhanceController.applySettings] 时刷新。
 */
data class VideoAiSettings(
    val subtitleEnabled: Boolean = false,
    val subtitleLanguage: String = "zh-CN"
)
