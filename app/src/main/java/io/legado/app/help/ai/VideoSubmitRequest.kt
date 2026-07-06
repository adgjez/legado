package io.legado.app.help.ai

/**
 * 跨 Provider 的视频生成请求抽象。
 *
 * 不同 Provider（OpenAI 风格 multipart、Agnes V2.0 JSON、豆包 Seedance 2.0 JSON）
 * 的请求体结构差异很大，但语义上都可表达为：提示词 + 时长 + 分辨率 + 参考图 + 高级参数。
 * 本数据类作为统一中间层，由 [AiVideoService] 按 Provider type 翻译成各自请求体。
 *
 * 字段语义：
 * @property prompt 视频提示词（已净化）
 * @property seconds 时长（秒）。Agnes 会转换为 num_frames（8n+1）；豆包直接 duration
 * @property size 分辨率。"1280x720"（Agnes 解析为 width/height）或 "720p"（豆包直接用）
 * @property referenceImages 参考图 URL 列表
 * @property mode 模式：agnes "ti2vid"/"keyframes"，豆包忽略
 * @property negativePrompt 反向提示词（Agnes 支持，豆包不支持）
 * @property seed 随机种子（Agnes/豆包均支持）
 * @property numInferenceSteps 推理步数（仅 Agnes）
 * @property cameraFixed 是否固定镜头（仅豆包 Seedance）
 * @property watermark 是否加水印
 * @property returnLastFrame 是否返回尾帧（仅豆包 Seedance，用于续拍）
 * @property generateAudio 是否生成音频（仅豆包 Seedance 2.0）
 * @property draft 是否生成草稿版本（仅豆包 Seedance 2.0）
 */
data class VideoSubmitRequest(
    val prompt: String,
    val seconds: Int,
    val size: String,
    val referenceImages: List<String> = emptyList(),
    val mode: String? = null,
    val negativePrompt: String? = null,
    val seed: Int? = null,
    val numInferenceSteps: Int? = null,
    val cameraFixed: Boolean? = null,
    val watermark: Boolean = false,
    val returnLastFrame: Boolean = false,
    val generateAudio: Boolean? = null,
    val draft: Boolean = false
)
