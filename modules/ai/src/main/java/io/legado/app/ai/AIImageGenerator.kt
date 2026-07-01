package io.legado.app.ai

import android.content.Context
import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * AI 图片生成器
 */
class AIImageGenerator(private val context: Context) {

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun generate(prompt: String): Result<String> = withContext(Dispatchers.IO) {
        val config = AIKeyManager.loadConfig(context)
        try {
            check(config.apiKey.isNotBlank()) { "请先配置 API Key" }

            when (config.provider) {
                AIProvider.OPENAI, AIProvider.CUSTOM -> generateOpenAI(config, prompt)
                AIProvider.GEMINI -> generateGemini(config, prompt)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun generateOpenAI(config: AIConfig, prompt: String): Result<String> {
        val requestBody = mapOf(
            "model" to config.model,
            "prompt" to prompt,
            "n" to 1,
            "size" to "1024x1024",
            "response_format" to "b64_json"
        )

        val request = Request.Builder()
            .url("${config.baseUrl}/images/generations")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(gson.toJson(requestBody).toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw Exception("Empty response")
            if (!response.isSuccessful) {
                throw Exception("API 错误 (${response.code}): $body")
            }
            val imageResponse = gson.fromJson(body, OpenAIImageResponse::class.java)
            val b64 = imageResponse.data.firstOrNull()?.b64Json
                ?: throw Exception("未返回图片数据")
            return Result.success(b64)
        }
    }

    private fun generateGemini(config: AIConfig, prompt: String): Result<String> {
        val url = "https://generativelanguage.googleapis.com/v1/models/${config.model}:predict?key=${config.apiKey}"
        val requestBody = GeminiRequest(
            instances = listOf(GeminiInstance(prompt)),
            parameters = GeminiParameters(sampleCount = 1)
        )

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(gson.toJson(requestBody).toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw Exception("Empty response")
            if (!response.isSuccessful) {
                throw Exception("Gemini 错误 (${response.code}): $body")
            }
            val geminiResponse = gson.fromJson(body, GeminiResponse::class.java)
            val b64 = geminiResponse.predictions.firstOrNull()?.bytesBase64Encoded
                ?: throw Exception("Gemini 未返回图片数据")
            return Result.success(b64)
        }
    }

    fun base64ToBytes(base64: String): ByteArray =
        Base64.decode(base64, Base64.DEFAULT)
}