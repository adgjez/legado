package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiImageProviderConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AiImageService.isAgnesImageModel] 容错判断测试。
 *
 * 场景：用户旧 Provider type=openai，model=agnes-image-2.1-flash，
 * 应被识别为 Agnes 走 /v1/images/generations，而非 OpenAI chat/completions。
 */
class AiImageServiceAgnesFallbackTest {

    @Test
    fun typeAgnesIsAgnesImage() {
        val p = AiImageProviderConfig(
            name = "x", type = AiImageProviderConfig.TYPE_AGNES,
            model = "agnes-image-2.1-flash"
        )
        assertTrue(AiImageService.isAgnesImageModel(p))
    }

    @Test
    fun typeOpenAiButModelAgnesIsAgnesImage() {
        // 容错：type=openai 但 model 名含 agnes-image
        val p = AiImageProviderConfig(
            name = "x", type = AiImageProviderConfig.TYPE_OPENAI,
            model = "agnes-image-2.1-flash"
        )
        assertTrue(AiImageService.isAgnesImageModel(p))
    }

    @Test
    fun typeOpenAiAndModelGptImageIsNotAgnesImage() {
        val p = AiImageProviderConfig(
            name = "x", type = AiImageProviderConfig.TYPE_OPENAI,
            model = "gpt-image-1"
        )
        assertFalse(AiImageService.isAgnesImageModel(p))
    }

    @Test
    fun emptyModelIsNotAgnesImage() {
        val p = AiImageProviderConfig(
            name = "x", type = AiImageProviderConfig.TYPE_OPENAI,
            model = ""
        )
        assertFalse(AiImageService.isAgnesImageModel(p))
    }
}
