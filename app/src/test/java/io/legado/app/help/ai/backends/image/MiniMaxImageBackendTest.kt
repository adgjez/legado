package io.legado.app.help.ai.backends.image

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
 * [MiniMaxImageBackend] 单测——验证 T2I/I2I 编码形态、subject_reference 单脸、尺寸计算、
 * normalizeBaseUrl、base_resp 错误检查。
 *
 * 必须用 Robolectric：I2I 测试调 [buildPayload] → [ImageCodec.toDataUri] → android.util.Base64。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MiniMaxImageBackendTest {

    private fun newBackend(model: String = "image-01"): MiniMaxImageBackend {
        val cfg = AiImageProviderConfig(
            name = "minimax-test",
            type = AiImageProviderConfig.TYPE_MINIMAX,
            baseUrl = "https://api.minimaxi.com/v1",
            apiKey = "test-key",
            model = model
        )
        return MiniMaxImageBackend(cfg)
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
        assertEquals("minimax", backend.typeId)
        assertEquals("空 model 应用默认值", "image-01", backend.model)
    }

    // ==================== T2I 编码形态 ====================

    @Test
    fun buildPayloadText2ImageHasNoSubjectReference() {
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "一只橘猫",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16"
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject

        assertEquals("image-01", root.get("model").asString)
        assertEquals("prompt 应下传", "一只橘猫", root.get("prompt").asString)
        assertTrue("应有 width", root.has("width"))
        assertTrue("应有 height", root.has("height"))
        assertEquals("response_format 固定 url", "url", root.get("response_format").asString)
        assertEquals("n 应 1", 1, root.get("n").asInt)
        assertEquals("prompt_optimizer 应 false", false, root.get("prompt_optimizer").asBoolean)
        assertFalse("T2I 不应有 subject_reference", root.has("subject_reference"))

        val width = root.get("width").asInt
        val height = root.get("height").asInt
        assertEquals("width 应被 8 整除", 0, width % 8)
        assertEquals("height 应被 8 整除", 0, height % 8)
    }

    // ==================== I2I 单脸 subject_reference ====================

    @Test
    fun buildPayloadImage2ImageUsesSubjectReferenceWithImageFile() {
        val backend = newBackend()
        val ref = tmpJpeg("minimax_face")
        val request = ImageGenerationRequest(
            prompt = "参考人脸生成",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(ReferenceImage(ref.path, "face_0"))
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("I2I 应有 subject_reference", root.has("subject_reference"))
        val subjectRef = root.getAsJsonArray("subject_reference")
        assertEquals("subject_reference 应 1 项（单脸）", 1, subjectRef.size())
        val item = subjectRef[0].asJsonObject
        assertEquals("type 应 character", "character", item.get("type").asString)
        assertTrue(
            "image_file 应是 data URI 字符串",
            item.get("image_file").asString.startsWith("data:image/jpeg;base64,")
        )
    }

    @Test
    fun buildPayloadImage2ImageTruncatesMultipleRefsToFirst() {
        // 多张截断为首张（_SUBJECT_REF_LIMIT=1）
        val backend = newBackend()
        val ref1 = tmpJpeg("minimax_face1")
        val ref2 = tmpJpeg("minimax_face2")
        val ref3 = tmpJpeg("minimax_face3")
        val request = ImageGenerationRequest(
            prompt = "多参考截断",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(
                ReferenceImage(ref1.path, "face_0"),
                ReferenceImage(ref2.path, "face_1"),
                ReferenceImage(ref3.path, "face_2")
            )
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject

        val subjectRef = root.getAsJsonArray("subject_reference")
        assertEquals("多张应截断为首张（1 项）", 1, subjectRef.size())
        // 验证取的是首张 ref1 的 data URI
        val firstDataUri = subjectRef[0].asJsonObject.get("image_file").asString
        val expectedUri = io.legado.app.help.ai.backends.compress.ImageCodec.toDataUri(ref1)
        assertEquals("应取首张参考图", expectedUri, firstDataUri)
    }

    @Test(expected = IllegalStateException::class)
    fun buildPayloadImage2ImageFailsLoudWhenFirstRefMissing() {
        // fail-loud：首张参考图不存在抛错
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "失败测试",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(
                ReferenceImage("/tmp/minimax_not_exists_xxx.jpg", "face_0")
            )
        )
        backend.buildPayload(request)  // 期望抛 IllegalStateException
    }

    @Test
    fun buildPayloadImage2ImageFailLoudMessageMentionsPath() {
        // 验证错误消息包含路径与「不可读」
        val backend = newBackend()
        val missingPath = "/tmp/minimax_not_exists_yyy.jpg"
        val request = ImageGenerationRequest(
            prompt = "失败消息",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(ReferenceImage(missingPath, "face_0"))
        )
        var msg: String? = null
        try {
            backend.buildPayload(request)
            org.junit.Assert.fail("首张参考图不存在应抛错")
        } catch (e: IllegalStateException) {
            msg = e.message
        }
        assertTrue("错误消息应包含路径", msg!!.contains(missingPath))
        assertTrue("错误消息应提示不可读", msg!!.contains("不可读"))
    }

    // ==================== 尺寸计算（resolveDimensions）====================

    @Test
    fun resolveDimensions9to16DefaultShortIs1440() {
        // 9:16 竖屏 + 默认 2K 档（短边 1440）
        // 比例 9:16 → 短边 9，long_comp=16，short_unit=8*9=72
        // t = round(1440/72)=20；max_t_long=2048/(8*16)=16；t=min(20,16)=16
        // 宽=9*8*16=1152，高=16*8*16=2048
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "尺寸",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16"
        )
        val (w, h) = backend.resolveDimensions(request)
        assertEquals("9:16 竖屏宽应 1152", 1152, w)
        assertEquals("9:16 竖屏高应 2048（长边收口）", 2048, h)
    }

    @Test
    fun resolveDimensionsAlignsToMultipleOf8() {
        // 任一比例结果都应是 8 的倍数
        val backend = newBackend()
        listOf("9:16", "16:9", "1:1", "4:3", "3:4", "21:9").forEach { ratio ->
            val request = ImageGenerationRequest(
                prompt = "对齐",
                outputPath = File("/tmp/out.png"),
                aspectRatio = ratio
            )
            val (w, h) = backend.resolveDimensions(request)
            assertEquals("$ratio width 应对齐 8", 0, w % 8)
            assertEquals("$ratio height 应对齐 8", 0, h % 8)
        }
    }

    @Test
    fun resolveDimensionsLongEdgeCappedAt2048() {
        val backend = newBackend()
        listOf("9:16", "16:9", "21:9", "1:1").forEach { ratio ->
            val request = ImageGenerationRequest(
                prompt = "收口",
                outputPath = File("/tmp/out.png"),
                aspectRatio = ratio
            )
            val (w, h) = backend.resolveDimensions(request)
            assertTrue("$ratio 长边应 ≤ 2048：${maxOf(w, h)}", maxOf(w, h) <= 2048)
        }
    }

    @Test
    fun resolveDimensionsMinEdgeAtLeast512() {
        // min_edge=512 下限：任一比例 + 任一档位短边都应 ≥ 512
        val backend = newBackend()
        listOf("9:16", "16:9", "1:1", "4:3", "3:4").forEach { ratio ->
            listOf(null, "512px", "1k", "2k", "4k", "300").forEach { tier ->
                val request = ImageGenerationRequest(
                    prompt = "下限",
                    outputPath = File("/tmp/out.png"),
                    aspectRatio = ratio,
                    imageSize = tier
                )
                val (w, h) = backend.resolveDimensions(request)
                assertTrue(
                    "$ratio tier=$tier 短边应 ≥ 512：${minOf(w, h)}",
                    minOf(w, h) >= 512
                )
            }
        }
    }

    @Test
    fun resolveDimensionsSub512ShortClampsUpToMinEdge() {
        // 短边先夹 >= 512；imageSize="300" 被夹到 512
        // 9:16 short=512：short_unit=72，t=round(512/72)=7，max_t_long=16，t=7
        // 宽=9*8*7=504 → clampEdge → 512；高=16*8*7=896
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "下限夹取",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16",
            imageSize = "300"
        )
        val (w, h) = backend.resolveDimensions(request)
        assertEquals("短边 300 被夹到 512 后，宽应 512（min_edge 兜底）", 512, w)
        assertEquals("高应 896", 896, h)
        assertEquals("宽仍被 8 整除", 0, w % 8)
        assertEquals("高仍被 8 整除", 0, h % 8)
    }

    // ==================== resolutionToShortEdge 档位 ====================

    @Test
    fun resolutionToShortEdgeTierMap() {
        val backend = newBackend()
        assertEquals("512px → 512", 512, backend.resolutionToShortEdge("512px"))
        assertEquals("1K → 1024", 1024, backend.resolutionToShortEdge("1K"))
        assertEquals("2K → 1440", 1440, backend.resolutionToShortEdge("2K"))
        assertEquals("4K → 2160", 2160, backend.resolutionToShortEdge("4K"))
        assertEquals("大小写不敏感", 1024, backend.resolutionToShortEdge("1k"))
    }

    @Test
    fun resolutionToShortEdgeNullDefaultsTo1440() {
        val backend = newBackend()
        assertEquals("null → 1440（2K 默认）", 1440, backend.resolutionToShortEdge(null))
        assertEquals("空串 → 1440", 1440, backend.resolutionToShortEdge(""))
        assertEquals("无法解析 → 1440", 1440, backend.resolutionToShortEdge("abc"))
    }

    // ==================== normalizeBaseUrl ====================

    @Test
    fun normalizeBaseUrlEmptyFallsBackToDefault() {
        val backend = newBackend()
        assertEquals(
            "空串回落默认 base",
            "https://api.minimaxi.com/v1",
            backend.normalizeBaseUrl("")
        )
        assertEquals(
            "纯空白回落默认 base",
            "https://api.minimaxi.com/v1",
            backend.normalizeBaseUrl("   ")
        )
    }

    @Test
    fun normalizeBaseUrlStripsTrailingSlash() {
        val backend = newBackend()
        assertEquals(
            "尾斜杠应处理",
            "https://api.minimaxi.com/v1",
            backend.normalizeBaseUrl("https://api.minimaxi.com/v1/")
        )
    }

    @Test
    fun normalizeBaseUrlStripsV1AndReappends() {
        val backend = newBackend()
        assertEquals(
            "带 /v1 应剥后补回",
            "https://api.minimaxi.com/v1",
            backend.normalizeBaseUrl("https://api.minimaxi.com/v1")
        )
        assertEquals(
            "不带 /v1 应补",
            "https://api.minimaxi.com/v1",
            backend.normalizeBaseUrl("https://api.minimaxi.com")
        )
        assertEquals(
            "自定义 host 不带 /v1 应补",
            "https://gw.example.com/v1",
            backend.normalizeBaseUrl("https://gw.example.com")
        )
        assertEquals(
            "自定义 host 带 /v1 应等价",
            "https://gw.example.com/v1",
            backend.normalizeBaseUrl("https://gw.example.com/v1")
        )
    }

    // ==================== base_resp 错误检查 ====================

    @Test
    fun baseRespErrorZeroReturnsNull() {
        val backend = newBackend()
        val root = JsonParser.parseString("""{"base_resp":{"status_code":0,"status_msg":"success"}}""").asJsonObject
        assertNull("status_code=0 应无错误", backend.baseRespError(root))
    }

    @Test
    fun baseRespErrorMissingBaseRespReturnsNull() {
        val backend = newBackend()
        val root = JsonParser.parseString("""{"data":{"image_urls":["x"]}}""").asJsonObject
        assertNull("无 base_resp 应无错误", backend.baseRespError(root))
    }

    @Test(expected = IllegalStateException::class)
    fun baseRespErrorNonZeroThrows() {
        val backend = newBackend()
        val root = JsonParser.parseString(
            """{"base_resp":{"status_code":1001,"status_msg":"content filtered"}}"""
        ).asJsonObject
        val err = backend.baseRespError(root)
        // 非 0 应返回错误描述
        assertTrue("应返回错误描述", err != null)
        assertTrue("错误描述应含 status_code", err!!.contains("1001"))
        assertTrue("错误描述应含 status_msg", err.contains("content filtered"))
        error(err)  // 模拟 persistImage 的抛错行为
    }
}
