package io.legado.app.help.ai.backends

import io.legado.app.ui.config.ImageProviderTypeRegistry
import io.legado.app.ui.config.ProviderAuthScheme
import io.legado.app.ui.config.ProviderTypeMeta
import io.legado.app.ui.config.VideoProviderTypeRegistry
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 供应商连通性测试。用填入的 API Key + Base URL 发一个轻量 GET 请求，
 * 验证「地址可达 + Key 格式有效」，不实际生成媒体、不消耗额度。
 *
 * 判定规则：
 * - 2xx/3xx → 成功（地址可达）
 * - 401/403 → 失败「地址可达但 Key 无效」
 * - 404/其他 4xx → 成功（地址可达，根路径无内容属正常）
 * - 5xx → 失败「服务器异常」
 * - 网络异常 → 失败「无法连接，检查 Base URL/网络」
 *
 * 注意：该测试只能验证连通性与 Key 级别，不能保证生成参数正确。
 */
object ProviderConnectionTester {

    data class Result(val success: Boolean, val message: String)

    suspend fun testVideo(config: AiVideoProviderConfig): Result = withContext(Dispatchers.IO) {
        val meta = VideoProviderTypeRegistry.getOrNull(config.type)
            ?: return@withContext Result(false, "未知供应商类型")
        probe(meta, config.baseUrl, config.apiKey)
    }

    suspend fun testImage(config: AiImageProviderConfig): Result = withContext(Dispatchers.IO) {
        val meta = ImageProviderTypeRegistry.getOrNull(config.type)
            ?: return@withContext Result(false, "未知供应商类型")
        probe(meta, config.baseUrl, config.apiKey)
    }

    private fun probe(meta: ProviderTypeMeta, baseUrl: String, apiKey: String): Result {
        if (apiKey.isBlank()) return Result(false, "请先填写 API Key")
        val effective = baseUrl.trim().ifBlank { meta.defaultBaseUrl }
        if (effective.isBlank()) {
            return Result(false, "请先填写 Base URL（该类型无默认地址）")
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val builder = Request.Builder().url(effective)
        when (meta.authScheme) {
            ProviderAuthScheme.BEARER -> builder.addHeader("Authorization", "Bearer $apiKey")
            ProviderAuthScheme.TOKEN -> builder.addHeader("Authorization", "Token $apiKey")
            ProviderAuthScheme.X_GOOG_API_KEY -> builder.addHeader("x-goog-api-key", apiKey)
        }
        val request = builder.get().build()
        return try {
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    in 200..399 -> Result(true, "连接成功（HTTP ${resp.code}）")
                    401, 403 -> Result(false, "地址可达，但 API Key 无效（HTTP ${resp.code}）")
                    404 -> Result(true, "地址可达（HTTP 404，根路径无内容属正常）")
                    in 400..499 -> Result(true, "地址可达（HTTP ${resp.code}）")
                    else -> Result(false, "服务器返回 HTTP ${resp.code}，请检查地址")
                }
            }
        } catch (e: Exception) {
            Result(false, "无法连接：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
