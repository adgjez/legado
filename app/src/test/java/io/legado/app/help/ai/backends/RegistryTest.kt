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
 * P0：factories 空，所有 type 均报错。
 * P2a/P2b：agnes/ark/sora/veo 注册（companion init），byConfig 可解析；其余 type 仍未实现。
 * P2c+/P3：其余各家注册后逐步可解析。
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
    fun videoRegistryByConfigThrowsForUnimplementedTypes() {
        // P2a/P2b 后 ark/agnes/sora/veo 已注册（companion init）；其余 type 仍未实现，应报错。
        // 注：不测已注册的——它们的 companion init 在被任何测试类引用时即注册到全局
        // singleton，测试顺序非确定，故只测确定性「未实现」的 type。
        listOf(
            AiVideoProviderConfig.TYPE_OPENAI,
            AiVideoProviderConfig.TYPE_JS,
            AiVideoProviderConfig.TYPE_DOUBAO,
            AiVideoProviderConfig.TYPE_KLING,
            AiVideoProviderConfig.TYPE_NEWAPI,
            AiVideoProviderConfig.TYPE_V2,
            AiVideoProviderConfig.TYPE_DASHSCOPE,
            AiVideoProviderConfig.TYPE_MINIMAX,
            AiVideoProviderConfig.TYPE_VIDU,
            AiVideoProviderConfig.TYPE_GROK
        ).forEach { t ->
            val cfg = AiVideoProviderConfig(name = "x", type = t)
            assertThrows("$t 应报错", IllegalStateException::class.java) {
                VideoBackendRegistry.byConfig(cfg)
            }
        }
    }

    @Test
    fun videoRegistryResolvesAgnesArkSoraVeoAfterClassLoad() {
        // P2a/P2b：agnes/ark/sora/veo companion init 注册到 registry。强制类加载触发 init 后 byConfig 应可解析。
        Class.forName("io.legado.app.help.ai.backends.video.AgnesVideoBackend")
        Class.forName("io.legado.app.help.ai.backends.video.ArkVideoBackend")
        Class.forName("io.legado.app.help.ai.backends.video.SoraVideoBackend")
        Class.forName("io.legado.app.help.ai.backends.video.VeoVideoBackend")
        val agnesCfg = AiVideoProviderConfig(
            name = "agnes-test", type = AiVideoProviderConfig.TYPE_AGNES,
            baseUrl = "https://x", apiKey = "k", model = "agnes-video-v2.0"
        )
        val arkCfg = AiVideoProviderConfig(
            name = "ark-test", type = AiVideoProviderConfig.TYPE_ARK,
            baseUrl = "https://x", apiKey = "k", model = "doubao-seedance-2-0"
        )
        val soraCfg = AiVideoProviderConfig(
            name = "sora-test", type = AiVideoProviderConfig.TYPE_SORA,
            baseUrl = "https://x", apiKey = "k", model = "sora-2"
        )
        val veoCfg = AiVideoProviderConfig(
            name = "veo-test", type = AiVideoProviderConfig.TYPE_VEO,
            baseUrl = "https://x", apiKey = "k", model = "veo-3.1"
        )
        // 不抛即通过
        VideoBackendRegistry.byConfig(agnesCfg)
        VideoBackendRegistry.byConfig(arkCfg)
        VideoBackendRegistry.byConfig(soraCfg)
        VideoBackendRegistry.byConfig(veoCfg)
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
