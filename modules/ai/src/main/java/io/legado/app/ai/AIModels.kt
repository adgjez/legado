package io.legado.app.ai

import com.google.gson.annotations.SerializedName

/**
 * AI 图片生成请求
 */
data class AIImageRequest(
    val prompt: String,
    val size: String = "1024x1024",
    val n: Int = 1,
    @SerializedName("response_format")
    val responseFormat: String = "b64_json"
)

/**
 * OpenAI 图片生成响应
 */
data class OpenAIImageResponse(
    val created: Long,
    val data: List<ImageData>
) {
    data class ImageData(
        @SerializedName("b64_json")
        val b64Json: String?,
        val url: String?
    )
}

/**
 * Gemini 图片生成请求
 */
data class GeminiRequest(
    val instances: List<GeminiInstance>,
    val parameters: GeminiParameters
)

data class GeminiInstance(
    val prompt: String
)

data class GeminiParameters(
    val sampleCount: Int = 1
)

/**
 * Gemini 响应
 */
data class GeminiResponse(
    val predictions: List<GeminiPrediction>
) {
    data class GeminiPrediction(
        @SerializedName("bytesBase64Encoded")
        val bytesBase64Encoded: String?
    )
}

/**
 * AI 提供商
 */
enum class AIProvider(val displayName: String) {
    OPENAI("OpenAI (DALL-E)"),
    GEMINI("Google Gemini"),
    CUSTOM("自定义 (OpenAI 兼容)")
}

/**
 * AI 生成配置
 */
data class AIConfig(
    val provider: AIProvider = AIProvider.OPENAI,
    val apiKey: String = "",
    val baseUrl: String = "https://api.openai.com/v1",
    val model: String = "dall-e-3",
    val style: String = "vivid"
)