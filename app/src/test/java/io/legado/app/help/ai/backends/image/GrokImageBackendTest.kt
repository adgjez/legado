package io.legado.app.help.ai.backends.image

import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.ImageCapability
import io.legado.app.help.ai.backends.ImageGenerationRequest
import io.legado.app.help.ai.backends.ReferenceImage
import io.legado.app.ui.main.ai.AiImageProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [GrokImageBackend] 单测——验证 T2I/I2I 编码形态、data URI 列表、文件跳过、normalizeBaseUrl。
 *
 * 必须用 Robolectric：I2I 测试调 [buildPayload] → [ImageCodec.toDataUri] → android.util.Base64。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GrokImageBackendTest {

    private fun newBackend(model: String = "grok-imagine-image"): GrokImageBackend {
        val cfg = AiImageProviderConfig(
            name = "grok-test",
            type = AiImageProviderConfig.TYPE_GROK,
            baseUrl = "https://api.x.ai",
            apiKey = "test-key",
            model = model
        )
        return GrokImageBackend(cfg)
    }

    private fun tmpJpeg(name: String): File {
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            0, 1, 0, 0, 1, 0, 1, 0, 0,
            0xFF.toByte(), 0xD9.toByte()
        )
        return File.createTempFile(name, ".jpg").apply { writeBytes(bytes); deleteOnExit() }
    }

    // ==================== 能力值 ====================

    @Test
    fun capabilitiesSupportsTextAndImage() {
        val backend = newBackend()
        assertTrue("应有 T2I", ImageCapability.TEXT_TO_IMAGE in backend.capabilities)
        assertTrue("应有 I2I", ImageCapability.IMAGE_TO_IMAGE in backend.capabilities)
    }

    // ==================== typeId / model 默认值 ====================

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals("grok", backend.typeId)
        assertEquals("空 model 应用默认值", "grok-imagine-image", backend.model)
    }

    // ==================== T2I 编码形态 ====================

    @Test
    fun buildPayloadText2ImageHasNoImageUrlsField() {
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "一只橘猫",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16"
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject

        assertEquals("grok-imagine-image", root.get("model").asString)
        assertEquals("prompt 应下传", "一只橘猫", root.get("prompt").asString)
        assertEquals("aspect_ratio 应透传", "9:16", root.get("aspect_ratio").asString)
        assertFalse("T2I 不应有 image_urls", root.has("image_urls"))
        assertFalse("imageSize 为空时不应有 resolution", root.has("resolution"))
    }

    @Test
    fun buildPayloadText2ImageResolutionOptional() {
        // imageSize 非空时下传 resolution；为空时不下传
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "测试分辨率",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "16:9",
            imageSize = "1k"
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("imageSize 非空时应有 resolution", root.has("resolution"))
        assertEquals("resolution 应透传 imageSize", "1k", root.get("resolution").asString)
        assertFalse("T2I 不应有 image_urls", root.has("image_urls"))
    }

    // ==================== I2I data URI 列表编码形态 ====================

    @Test
    fun buildPayloadImage2ImageUsesDataUriArray() {
        val backend = newBackend()
        val ref1 = tmpJpeg("grok_r1")
        val ref2 = tmpJpeg("grok_r2")
        val request = ImageGenerationRequest(
            prompt = "参考图生成",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(
                ReferenceImage(ref1.path, "ref_0"),
                ReferenceImage(ref2.path, "ref_1")
            )
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("I2I 应有 image_urls", root.has("image_urls"))
        val imageUrls = root.getAsJsonArray("image_urls")
        assertEquals("image_urls 应 2 项", 2, imageUrls.size())
        assertTrue(
            "image_urls[0] 应是 data URI",
            imageUrls[0].asString.startsWith("data:image/jpeg;base64,")
        )
        assertTrue(
            "image_urls[1] 应是 data URI",
            imageUrls[1].asString.startsWith("data:image/jpeg;base64,")
        )
    }

    @Test
    fun buildPayloadImage2ImageSkipsMissingFiles() {
        // 文件不存在跳过（不 fail-loud，与 OpenAiImageBackend.submitEdit 同款）
        val backend = newBackend()
        val ref1 = tmpJpeg("grok_exists")
        val request = ImageGenerationRequest(
            prompt = "混合参考",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(
                ReferenceImage(ref1.path, "ref_0"),
                ReferenceImage("/tmp/grok_not_exists_xxx.jpg", "ref_missing")
            )
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应仍有 image_urls", root.has("image_urls"))
        val imageUrls = root.getAsJsonArray("image_urls")
        assertEquals("缺失文件应被跳过，仅剩 1 项", 1, imageUrls.size())
        assertTrue(
            "剩余项应是 data URI",
            imageUrls[0].asString.startsWith("data:image/jpeg;base64,")
        )
    }

    // ==================== normalizeBaseUrl ====================

    @Test
    fun normalizeBaseUrlEmptyFallsBackToXai() {
        val backend = newBackend()
        assertEquals(
            "空串回落 xAI host",
            "https://api.x.ai",
            backend.normalizeBaseUrl("")
        )
        assertEquals(
            "纯空白回落 xAI host",
            "https://api.x.ai",
            backend.normalizeBaseUrl("   ")
        )
    }

    @Test
    fun normalizeBaseUrlStripsTrailingSlash() {
        val backend = newBackend()
        assertEquals(
            "尾斜杠应去除",
            "https://api.x.ai",
            backend.normalizeBaseUrl("https://api.x.ai/")
        )
    }

    @Test
    fun normalizeBaseUrlPrependsHttpsIfMissing() {
        val backend = newBackend()
        assertEquals(
            "无 http 前缀应补 https://",
            "https://api.x.ai",
            backend.normalizeBaseUrl("api.x.ai")
        )
        assertEquals(
            "无 http 前缀+尾斜杠应补 https:// 且去尾斜杠",
            "https://api.x.ai",
            backend.normalizeBaseUrl("api.x.ai/")
        )
    }
}
