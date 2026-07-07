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
 * [ArkImageBackend] 编码形态 + Seedream 尺寸表 + 能力值单测（P3a）。
 *
 * 核心验证：
 * - **参考图用 data URI**——单张传字符串，多张传列表（与 Agnes image 始终数组形态不同）
 * - Seedream 尺寸表：2K 档（4.x/5.x）/ 1K 档（seedream-3）；imageSize 优先
 * - 未识别比例回退到分辨率 keyword（"1K"/"2K"）
 * - 响应解析顺序：b64_json 优先（与 Agnes 的 url 优先相反）——由 persistImage 私有方法实现，
 *   单测聚焦请求体编码 + 尺寸表
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ArkImageBackendTest {

    private fun newBackend(model: String = "doubao-seedream-5-0-lite-260128"): ArkImageBackend {
        val cfg = AiImageProviderConfig(
            name = "ark-test",
            type = AiImageProviderConfig.TYPE_ARK,
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            apiKey = "test-key",
            model = model
        )
        return ArkImageBackend(cfg)
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

    // ==================== T2I 编码形态 ====================

    @Test
    fun buildPayloadText2ImageHasNoImageField() {
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "一只橘猫",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16"
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject

        assertEquals("doubao-seedream-5-0-lite-260128", root.get("model").asString)
        assertEquals("prompt 应下传", "一只橘猫", root.get("prompt").asString)
        assertTrue("应有 size", root.has("size"))
        assertEquals("9:16 + 2K 档 → 1600x2848", "1600x2848", root.get("size").asString)
        assertFalse("T2I 不应有 image", root.has("image"))
    }

    // ==================== I2I 单张参考图：image 是字符串 ====================

    @Test
    fun buildPayloadSingleReferenceUsesDataUriString() {
        // Ark 单张参考图传字符串（区别于 Agnes 始终数组）
        val backend = newBackend()
        val ref = tmpJpeg("ark_single")
        val request = ImageGenerationRequest(
            prompt = "单参考",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(ReferenceImage(ref.path, "ref_0"))
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应有 image", root.has("image"))
        // 单张应是字符串，不是数组
        assertTrue("image 应是字符串（非数组）", root.get("image").isJsonPrimitive)
        val imageVal = root.get("image").asString
        assertTrue(
            "image 应是 data URI，实际：$imageVal",
            imageVal.startsWith("data:image/jpeg;base64,")
        )
    }

    // ==================== I2I 多张参考图：image 是数组 ====================

    @Test
    fun buildPayloadMultipleReferencesUsesDataUriArray() {
        val backend = newBackend()
        val ref1 = tmpJpeg("ark_r1")
        val ref2 = tmpJpeg("ark_r2")
        val request = ImageGenerationRequest(
            prompt = "多参考",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(
                ReferenceImage(ref1.path, "ref_0"),
                ReferenceImage(ref2.path, "ref_1")
            )
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应有 image", root.has("image"))
        assertTrue("多张 image 应是数组", root.get("image").isJsonArray)
        val arr = root.getAsJsonArray("image")
        assertEquals("image 应 2 项", 2, arr.size())
        assertTrue("arr[0] 应是 data URI", arr[0].asString.startsWith("data:image/jpeg;base64,"))
        assertTrue("arr[1] 应是 data URI", arr[1].asString.startsWith("data:image/jpeg;base64,"))
    }

    // ==================== seed 下传 ====================

    @Test
    fun buildPayloadCarriesSeedWhenProvided() {
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "带 seed",
            outputPath = File("/tmp/out.png"),
            seed = 42L
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject
        assertEquals("seed 应 42", 42L, root.get("seed").asLong)
    }

    @Test
    fun buildPayloadOmitsSeedWhenAbsent() {
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "无 seed",
            outputPath = File("/tmp/out.png")
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject
        assertFalse("无 seed 不应下传", root.has("seed"))
    }

    // ==================== resolveSize：Seedream 尺寸表 ====================

    @Test
    fun resolveSize2kMapForDefaultModel() {
        val backend = newBackend("doubao-seedream-5-0-lite-260128")
        assertEquals("9:16 → 1600x2848", "1600x2848", backend.resolveSize(backend.model, null, "9:16"))
        assertEquals("16:9 → 2848x1600", "2848x1600", backend.resolveSize(backend.model, null, "16:9"))
        assertEquals("1:1 → 2048x2048", "2048x2048", backend.resolveSize(backend.model, null, "1:1"))
        assertEquals("4:3 → 2304x1728", "2304x1728", backend.resolveSize(backend.model, null, "4:3"))
        assertEquals("21:9 → 3136x1344", "3136x1344", backend.resolveSize(backend.model, null, "21:9"))
    }

    @Test
    fun resolveSize1kMapForSeedream3() {
        val backend = newBackend("doubao-seedream-3-0-t2i-250415")
        assertEquals("seedream-3 9:16 → 720x1280", "720x1280", backend.resolveSize(backend.model, null, "9:16"))
        assertEquals("seedream-3 1:1 → 1024x1024", "1024x1024", backend.resolveSize(backend.model, null, "1:1"))
        assertEquals("seedream-3 16:9 → 1280x720", "1280x720", backend.resolveSize(backend.model, null, "16:9"))
    }

    @Test
    fun resolveSizeImageSizeTakesPrecedence() {
        // caller 显式传 imageSize 优先于尺寸表推导
        val backend = newBackend()
        assertEquals(
            "imageSize 优先",
            "4K",
            backend.resolveSize(backend.model, "4K", "9:16")
        )
        assertEquals(
            "具体尺寸也透传",
            "1024x1024",
            backend.resolveSize(backend.model, "1024x1024", "16:9")
        )
    }

    @Test
    fun resolveSizeUnrecognizedRatioFallsBackToKeyword() {
        val backend = newBackend("doubao-seedream-5-0-lite-260128")
        assertEquals(
            "未识别比例 + 2K 档 → '2K'",
            "2K",
            backend.resolveSize(backend.model, null, "5:4")
        )
    }

    @Test
    fun resolveSizeUnrecognizedRatioSeedream3FallsBackTo1k() {
        val backend = newBackend("doubao-seedream-3-0-t2i-250415")
        assertEquals(
            "未识别比例 + seedream-3 → '1K'",
            "1K",
            backend.resolveSize(backend.model, null, "5:4")
        )
    }

    // ==================== normalizeBaseUrl ====================

    @Test
    fun normalizeBaseUrlPrependsHttpsAndStripsTrailingSlash() {
        val backend = newBackend()
        assertEquals(
            "应补 https://",
            "https://ark.cn-beijing.volces.com/api/v3",
            backend.normalizeBaseUrl("ark.cn-beijing.volces.com/api/v3")
        )
        assertEquals(
            "应去尾斜杠",
            "https://ark.cn-beijing.volces.com/api/v3",
            backend.normalizeBaseUrl("https://ark.cn-beijing.volces.com/api/v3/")
        )
        assertEquals(
            "已是完整 https 应原样",
            "https://ark.cn-beijing.volces.com/api/v3",
            backend.normalizeBaseUrl("https://ark.cn-beijing.volces.com/api/v3")
        )
        assertEquals(
            "空串回落 Ark API v3 base",
            "https://ark.cn-beijing.volces.com/api/v3",
            backend.normalizeBaseUrl("")
        )
    }

    // ==================== typeId / model 默认值 ====================

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals("ark", backend.typeId)
        assertEquals(
            "空 model 应用默认值",
            "doubao-seedream-5-0-lite-260128",
            backend.model
        )
    }
}
