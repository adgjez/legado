package io.legado.app.help.ai.asr

import java.io.File

/**
 * ASR 引擎抽象（P3）。
 *
 * 不同实现（Whisper 云端、用户 JS、本地模型）都通过本接口接入，由 [AsrEngineFactory] 按
 * [AsrConfig] 选择。
 */
interface AsrEngine {
    /**
     * 对一个本地音频文件做语音识别，返回带时间戳的字幕段列表。
     *
     * @param audio 待识别的本地音频文件（mp3/m4a/wav），由调用方保证已落盘。
     * @param language BCP-47 语言标签（如 "zh"、"en"），可空让服务端自动检测。
     * @return 字幕段，按 [AsrSegment.startMs] 升序排列。
     */
    suspend fun transcribe(audio: File, language: String): List<AsrSegment>
}

/**
 * 一段字幕的最小单位。
 *
 * @param startMs 起始时间（毫秒，含）。
 * @param endMs 结束时间（毫秒，含）。
 * @param text 字幕文本（不含换行）。
 */
data class AsrSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)

    /**
     * SRT 格式：`{idx}\n{hh:mm:ss,mmm} --> {hh:mm:ss,mmm}\n{text}\n`
     */
    fun toSrt(index: Int): String {
        val start = formatSrtTimestamp(startMs)
        val end = formatSrtTimestamp(endMs)
        return "$index\n$start --> $end\n$text\n"
    }

    private fun formatSrtTimestamp(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val h = safe / 3_600_000L
        val m = (safe % 3_600_000L) / 60_000L
        val s = (safe % 60_000L) / 1_000L
        val mil = safe % 1_000L
        return "%02d:%02d:%02d,%03d".format(h, m, s, mil)
    }
}

/**
 * ASR 引擎配置。
 *
 * @param type 引擎类型：whisper / js / local
 * @param baseUrl API 基础地址（Whisper 类型需要）
 * @param apiKey API key
 * @param model 模型名（如 "whisper-1"）
 * @param script JS 脚本（JsAsrEngine 需要）
 * @param timeoutMillis HTTP 超时
 */
data class AsrConfig(
    val type: String = TYPE_WHISPER,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "whisper-1",
    val script: String = "",
    val timeoutMillis: Long = 300_000L
) {
    companion object {
        const val TYPE_WHISPER = "whisper"
        const val TYPE_JS = "js"
        const val TYPE_LOCAL = "local"
    }
}

/**
 * 多段合并为 SRT 文本。
 */
fun List<AsrSegment>.toSrtText(): String =
    mapIndexed { i, seg -> seg.toSrt(i + 1) }.joinToString("\n")
