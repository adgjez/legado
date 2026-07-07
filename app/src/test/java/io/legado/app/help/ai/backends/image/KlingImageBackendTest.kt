package io.legado.app.help.ai.backends.image

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.ImageCapability
import io.legado.app.help.ai.backends.ImageGenerationRequest
import io.legado.app.help.ai.backends.ReferenceImage
import io.legado.app.ui.main.ai.AiImageProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [KlingImageBackend] 编码形态 + 能力值 + URL 归一化 + responseError 单测（P3c）。
 *
 * 核心验证：
 * - 能力恒 `{T2I, I2I}`；typeId/model 默认值（kling-image-o1）
 * - buildPayload：`{model_name, prompt, aspect_ratio, n:1, image?:[]}`（T2I 与 I2I 共用）
 * - **参考图用裸 base64**（无 `data:` 前缀）——image 数组每项是纯 base64 字符串
 * - 参考图上限 10 截断 + fail-loud（文件不可读抛错）
 * - normalizeBaseUrl：去尾斜杠、空回落默认 base
 * - responseError：code != 0 抛错（int 和 string 形式归一化 int 比较）
 *
 * Robolectric 必需：I2I 测试调 buildPayload → ImageCodec.toBareBase64 → android.util.Base64。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class KlingImageBackendTest {

    private fun newBackend(model: String = "kling-image-o1"): KlingImageBackend {
        val cfg = AiImageProviderConfig(
            name = "kling-img-test",
            type = AiImageProviderConfig.TYPE_KLING,
            baseUrl = "https://api.klingai.com",
            apiKey = "test-key",
            model = model
        )
        return KlingImageBackend(cfg)
    }

    /** 最小 JPEG 魔数字节临时文件（ImageCodec.toBareBase64 读盘 + Base64）。 */
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
    fun capabilitiesAlwaysTextAndImage() {
        val backend = newBackend()
        assertTrue("应有 T2I", ImageCapability.TEXT_TO_IMAGE in backend.capabilities)
        assertTrue("应有 I2I", ImageCapability.IMAGE_TO_IMAGE in backend.capabilities)
        assertEquals(
            "能力恒 {T2I, I2I}",
            setOf(ImageCapability.TEXT_TO_IMAGE, ImageCapability.IMAGE_TO_IMAGE),
            backend.capabilities
        )
    }

    // ==================== typeId / model 默认值 ====================

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals(AiImageProviderConfig.TYPE_KLING, backend.typeId)
        assertEquals("空 model 应用默认值", "kling-image-o1", backend.model)
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

        assertEquals("kling-image-o1", root.get("model_name").asString)
        assertEquals("一只橘猫", root.get("prompt").asString)
        assertEquals("9:16", root.get("aspect_ratio").asString)
        assertEquals("n 应 1", 1, root.get("n").asInt)
        assertFalse("T2I 不应有 image 字段", root.has("image"))
        assertFalse("不下传 resolution", root.has("resolution"))
    }

    // ==================== I2I 编码形态（裸 base64 数组） ====================

    @Test
    fun buildPayloadImage2ImageUsesBareBase64Array() {
        val backend = newBackend()
        val ref1 = tmpJpeg("kling_r1")
        val ref2 = tmpJpeg("kling_r2")
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

        assertTrue("I2I 应有 image 字段", root.has("image"))
        val image = root.getAsJsonArray("image")
        assertEquals("image 应 2 项", 2, image.size())
        // 裸 base64：不以 data: 开头
        image.forEach { el ->
            val s = el.asString
            assertFalse("image 项不应以 data: 开头（裸 base64）: $s", s.startsWith("data:"))
            assertTrue("image 项应非空 base64", s.isNotBlank())
        }
        assertEquals("I2I 仍应有 model_name", "kling-image-o1", root.get("model_name").asString)
        assertEquals("I2I 仍应有 prompt", "参考图生成", root.get("prompt").asString)
        assertEquals("I2I 仍应有 n", 1, root.get("n").asInt)
    }

    @Test
    fun buildPayloadImage2ImageTruncatesToTenRefs() {
        val backend = newBackend()
        val refs = (1..12).map { i -> ReferenceImage(tmpJpeg("kling_ref_$i").path) }
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            referenceImages = refs
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject
        val image = root.getAsJsonArray("image")
        assertEquals(
            "参考图应截断到 ${KlingImageBackend.MAX_REFERENCE_IMAGES}",
            KlingImageBackend.MAX_REFERENCE_IMAGES,
            image.size()
        )
    }

    @Test(expected = IllegalStateException::class)
    fun buildPayloadImage2ImageFailsLoudOnMissingRef() {
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(ReferenceImage("/nonexistent/kling_ref_missing.jpg"))
        )
        backend.buildPayload(request)
    }

    // ==================== normalizeBaseUrl ====================

    @Test
    fun normalizeBaseUrlStripsTrailingSlashAndDefaultsOnEmpty() {
        val backend = newBackend()
        val expected = "https://api.klingai.com"
        assertEquals("空串回落默认 base", expected, backend.normalizeBaseUrl(""))
        assertEquals("纯空白回落默认 base", expected, backend.normalizeBaseUrl("   "))
        assertEquals("无尾斜杠原样", expected, backend.normalizeBaseUrl("https://api.klingai.com"))
        assertEquals("带尾斜杠应去掉", expected, backend.normalizeBaseUrl("https://api.klingai.com/"))
        assertEquals(
            "多层尾斜杠应去掉",
            expected,
            backend.normalizeBaseUrl("https://api.klingai.com///")
        )
    }

    // ==================== responseError：code 归一化比较 ====================

    @Test
    fun responseErrorReturnsNullWhenCodeZeroOrMissing() {
        val backend = newBackend()
        // 缺 code 字段 → null
        val noCode = JsonObject().apply { addProperty("data", "x") }
        assertNull("缺 code 应 null", backend.responseError(noCode))
        // code = 0 → null
        val codeZero = JsonObject().apply { addProperty("code", 0) }
        assertNull("code=0 应 null", backend.responseError(codeZero))
        // code = "0" 字符串 → null
        val codeZeroStr = JsonObject().apply { addProperty("code", "0") }
        assertNull("code=\"0\" 应 null", backend.responseError(codeZeroStr))
    }

    @Test
    fun responseErrorReturnsMessageWhenCodeNonZeroInt() {
        val backend = newBackend()
        val root = JsonObject().apply {
            addProperty("code", 1001)
            addProperty("message", "鉴权失败")
        }
        val err = backend.responseError(root)
        assertTrue("应返回非空错误", err != null)
        assertTrue("错误应含 code=1001: $err", err!!.contains("1001"))
        assertTrue("错误应含 message: $err", err.contains("鉴权失败"))
    }

    @Test
    fun responseErrorReturnsMessageWhenCodeNonZeroString() {
        val backend = newBackend()
        // bearer/中转可能把 code 序列化成字符串，归一化 int 比较
        val root = JsonObject().apply {
            addProperty("code", "1001")
            addProperty("message", "参数非法")
        }
        val err = backend.responseError(root)
        assertTrue("字符串 code 应归一化比较: $err", err != null)
        assertTrue("错误应含 code=1001: $err", err!!.contains("1001"))
    }

    @Test
    fun responseErrorHandlesNonNumericCode() {
        val backend = newBackend()
        // 非数字 code（如 "InvalidApiKey"）应兜底报错
        val root = JsonObject().apply {
            addProperty("code", "InvalidApiKey")
            addProperty("message", "bad key")
        }
        val err = backend.responseError(root)
        assertTrue("非数字 code 应返回非空错误", err != null)
    }
}
