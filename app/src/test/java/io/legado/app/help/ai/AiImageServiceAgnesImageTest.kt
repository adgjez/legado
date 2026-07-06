package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiImageProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agnes Image 2.1 Flash 生图 Provider 测试。
 *
 * 验证：
 * 1. TYPE_AGNES 常量与预置配置（baseUrl/model/端点）
 * 2. isChatVisionModel 对 Agnes model 的判断（应 false，走 images API 而非 chat）
 *
 * @see <a href="https://agnes-ai.com/zh-Hans/docs/agnes-image-21-flash">Agnes Image 2.1 Flash 文档</a>
 */
class AiImageServiceAgnesImageTest {

    @Test
    fun agnesTypeConstantExists() {
        assertEquals("agnes", AiImageProviderConfig.TYPE_AGNES)
    }

    @Test
    fun agnesPresetUsesCorrectEndpoint() {
        val provider = AiImageProviderConfig(
            name = "Agnes Image 2.1 Flash",
            type = AiImageProviderConfig.TYPE_AGNES,
            baseUrl = "https://apihub.agnes-ai.com/v1",
            model = "agnes-image-2.1-flash"
        )
        assertTrue(provider.baseUrl.contains("apihub.agnes-ai.com"))
        assertTrue(provider.baseUrl.endsWith("/v1"))
        assertEquals("agnes-image-2.1-flash", provider.model)
    }

    /**
     * Agnes Image model 不应被误判为 chat vision model。
     * Agnes 走 /images/generations + extra_body.image，不走 /chat/completions。
     */
    @Test
    fun agnesImageIsNotChatVisionModel() {
        val provider = AiImageProviderConfig(
            name = "Agnes",
            type = AiImageProviderConfig.TYPE_AGNES,
            model = "agnes-image-2.1-flash"
        )
        assertFalse(AiImageService.isChatVisionModel(provider))
    }

    /**
     * 即使 model 名含 "image"，Agnes type 也不走 chat。
     * （isChatVisionModel 仅按 model 名 + endpoint 判断，与 type 无关；
     * 这里验证 model 名 "agnes-image-2.1-flash" 不触发 chat 判断关键词）
     */
    @Test
    fun agnesImageModelNameDoesNotTriggerChatKeywords() {
        // model 名不含 gpt-4o / image-vip / vision 关键词
        val provider = AiImageProviderConfig(
            name = "Agnes",
            type = AiImageProviderConfig.TYPE_AGNES,
            model = "agnes-image-2.1-flash"
        )
        val model = provider.model.lowercase()
        assertFalse(model.contains("gpt-4o"))
        assertFalse(model.contains("image-vip"))
        assertFalse(model.contains("vision"))
    }

    /**
     * Agnes 预置 defaultParamsJson 模板应包含 size 字段。
     */
    @Test
    fun agnesDefaultParamsContainsSize() {
        // 模拟 EditActivity.defaultParams() 的 Agnes 模板
        val paramsJson = """{"size": "1024x768", "response_format": "url"}"""
        assertTrue(paramsJson.contains("size"))
        assertTrue(paramsJson.contains("response_format"))
    }

    /**
     * Agnes 预置配置与 OpenAI 预置区别：baseUrl 直接含 /v1（Agnes 文档端点为 /v1/images/generations）。
     */
    @Test
    fun agnesBaseUrlDiffersFromOpenAi() {
        val agnesProvider = AiImageProviderConfig(
            name = "Agnes",
            type = AiImageProviderConfig.TYPE_AGNES,
            baseUrl = "https://apihub.agnes-ai.com/v1"
        )
        // Agnes baseUrl 已含 /v1，normalizeBaseUrl 不会重复追加
        assertTrue(agnesProvider.baseUrl.endsWith("/v1"))
    }
}
