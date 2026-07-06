package io.legado.app.help.ai.backends

import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VideoBackendRegistry] / [ImageBackendRegistry] 单测。
 *
 * P0 factories 空：所有 type（含老 type openai/js/doubao/agnes）均报错。
 * P2/P3 各家注册后，byConfig 才能正常分发。
 */
class RegistryTest {

    @Test
    fun videoRegistryByConfigUnknownTypeThrows() {
        val cfg = AiVideoProviderConfig(name = "x", type = "nonexistent")
        val ex = assertThrows(IllegalStateException::class.java) {
            VideoBackendRegistry.byConfig(cfg)
        }
        assertTrue(ex.message!!.contains("未知视频 backend type"))
        assertTrue(ex.message!!.contains("nonexistent"))
    }

    @Test
    fun videoRegistryByConfigThrowsForAllTypesWhileFactoriesEmpty() {
        // P0 factories 空，老 type（openai/js/doubao/agnes）也都报错
        listOf(
            AiVideoProviderConfig.TYPE_OPENAI,
            AiVideoProviderConfig.TYPE_JS,
            AiVideoProviderConfig.TYPE_DOUBAO,
            AiVideoProviderConfig.TYPE_AGNES,
            AiVideoProviderConfig.TYPE_ARK,
            AiVideoProviderConfig.TYPE_SORA
        ).forEach { t ->
            val cfg = AiVideoProviderConfig(name = "x", type = t)
            assertThrows("$t 应报错", IllegalStateException::class.java) {
                VideoBackendRegistry.byConfig(cfg)
            }
        }
    }

    @Test
    fun imageRegistryByConfigUnknownTypeThrows() {
        val cfg = AiImageProviderConfig(name = "x", type = "nonexistent")
        val ex = assertThrows(IllegalStateException::class.java) {
            ImageBackendRegistry.byConfig(cfg)
        }
        assertTrue(ex.message!!.contains("未知图像 backend type"))
    }

    @Test
    fun arcReelTypeConstantsExist() {
        // 验证 ArcReel type 常量已加（P2/P3 注册时用）
        assertEquals("ark", AiVideoProviderConfig.TYPE_ARK)
        assertEquals("agnes", AiVideoProviderConfig.TYPE_AGNES)
        assertEquals("sora", AiVideoProviderConfig.TYPE_SORA)
        assertEquals("veo", AiVideoProviderConfig.TYPE_VEO)
        assertEquals("kling", AiVideoProviderConfig.TYPE_KLING)
        assertEquals("newapi", AiVideoProviderConfig.TYPE_NEWAPI)
        assertEquals("v2", AiVideoProviderConfig.TYPE_V2)
        assertEquals("dashscope", AiVideoProviderConfig.TYPE_DASHSCOPE)
        assertEquals("minimax", AiVideoProviderConfig.TYPE_MINIMAX)
        assertEquals("vidu", AiVideoProviderConfig.TYPE_VIDU)
        assertEquals("grok", AiVideoProviderConfig.TYPE_GROK)

        assertEquals("agnes", AiImageProviderConfig.TYPE_AGNES)
        assertEquals("ark", AiImageProviderConfig.TYPE_ARK)
        assertEquals("dashscope", AiImageProviderConfig.TYPE_DASHSCOPE)
        assertEquals("gemini", AiImageProviderConfig.TYPE_GEMINI)
        assertEquals("grok", AiImageProviderConfig.TYPE_GROK)
        assertEquals("kling", AiImageProviderConfig.TYPE_KLING)
        assertEquals("minimax", AiImageProviderConfig.TYPE_MINIMAX)
        assertEquals("vidu", AiImageProviderConfig.TYPE_VIDU)
        // openai 图像 backend 仍保留常量（P3 注册 OpenAiImageBackend）
        assertEquals("openai", AiImageProviderConfig.TYPE_OPENAI)
    }
}
