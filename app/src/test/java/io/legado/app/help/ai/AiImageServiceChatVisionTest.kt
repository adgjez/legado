package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiImageProviderConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AiImageService.isChatVisionModel] 判断逻辑测试。
 *
 * Stage 5 场景图生图据此决定路由：
 * - chat vision model → /chat/completions + image_url 参考图（人物一致）
 * - images API 类 model → /images/generations 纯文生图（参考图忽略）
 */
class AiImageServiceChatVisionTest {

    @Test
    fun gpt4oImageVipIsChatVision() {
        val p = AiImageProviderConfig(name = "x", model = "gpt-4o-image-vip")
        assertTrue(AiImageService.isChatVisionModel(p))
    }

    @Test
    fun gpt4oIsChatVision() {
        val p = AiImageProviderConfig(name = "x", model = "gpt-4o-2024-11-20")
        assertTrue(AiImageService.isChatVisionModel(p))
    }

    @Test
    fun visionKeywordIsChatVision() {
        val p = AiImageProviderConfig(name = "x", model = "some-vision-model")
        assertTrue(AiImageService.isChatVisionModel(p))
    }

    @Test
    fun gptImage1IsNotChatVision() {
        val p = AiImageProviderConfig(name = "x", model = "gpt-image-1")
        assertFalse(AiImageService.isChatVisionModel(p))
    }

    @Test
    fun dallE3IsNotChatVision() {
        val p = AiImageProviderConfig(name = "x", model = "dall-e-3")
        assertFalse(AiImageService.isChatVisionModel(p))
    }

    @Test
    fun endpointChatOverridesModelName() {
        // 显式 endpoint=chat，即使 model 名不含 vision 关键词也走 chat
        val p = AiImageProviderConfig(
            name = "x", model = "custom-model",
            defaultParamsJson = """{"endpoint": "chat"}"""
        )
        assertTrue(AiImageService.isChatVisionModel(p))
    }

    @Test
    fun endpointImagesOverridesModelName() {
        // 显式 endpoint=images，即使 model 名含 gpt-4o 也走 images API
        val p = AiImageProviderConfig(
            name = "x", model = "gpt-4o-image-vip",
            defaultParamsJson = """{"endpoint": "images"}"""
        )
        assertFalse(AiImageService.isChatVisionModel(p))
    }

    @Test
    fun endpointResponsesOverridesModelName() {
        val p = AiImageProviderConfig(
            name = "x", model = "gpt-4o",
            defaultParamsJson = """{"endpoint": "responses"}"""
        )
        assertFalse(AiImageService.isChatVisionModel(p))
    }

    @Test
    fun emptyModelAndEmptyParamsIsNotChatVision() {
        val p = AiImageProviderConfig(name = "x", model = "")
        assertFalse(AiImageService.isChatVisionModel(p))
    }

    @Test
    fun invalidParamsJsonFallsBackToModelName() {
        // paramsJson 解析失败时按 model 名判断
        val p = AiImageProviderConfig(
            name = "x", model = "gpt-4o-image-vip",
            defaultParamsJson = "{ invalid"
        )
        assertTrue(AiImageService.isChatVisionModel(p))
    }
}
