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
 * [AgnesImageBackend] 编码形态 + 尺寸计算 + 能力值单测（P3a）。
 *
 * 核心验证：
 * - **参考图用 data URI 列表**——`image` 字段为 data URI 数组（每项 `data:image/jpeg;base64,...`）
 * - 尺寸：aspect_size（比例优先、清晰度其次，8 整除，长边 2048 收口，默认短边 1440 即 2K 档）
 * - T2I 无 image 字段；I2I 有 image 数组
 * - 不发 response_format（上游 litellm 网关报 UnsupportedParamsError）
 * - normalizeBaseUrl：剥 /v1 后补 /v1
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgnesImageBackendTest {

    private fun newBackend(model: String = "agnes-image-2.1-flash"): AgnesImageBackend {
        val cfg = AiImageProviderConfig(
            name = "agnes-test",
            type = AiImageProviderConfig.TYPE_AGNES,
            baseUrl = "https://apihub.agnes-ai.com/v1",
            apiKey = "test-key",
            model = model
        )
        return AgnesImageBackend(cfg)
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

        assertEquals("agnes-image-2.1-flash", root.get("model").asString)
        assertEquals("prompt 应下传", "一只橘猫", root.get("prompt").asString)
        assertEquals("n 应 1", 1, root.get("n").asInt)
        assertTrue("应有 size", root.has("size"))
        assertFalse("T2I 不应有 image", root.has("image"))
        assertFalse("不应有 response_format", root.has("response_format"))
    }

    // ==================== I2I data URI 列表编码形态 ====================

    @Test
    fun buildPayloadImage2ImageUsesDataUriArray() {
        val backend = newBackend()
        val ref1 = tmpJpeg("agnes_r1")
        val ref2 = tmpJpeg("agnes_r2")
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

        assertTrue("I2I 应有 image", root.has("image"))
        val image = root.getAsJsonArray("image")
        assertEquals("image 应 2 项", 2, image.size())
        assertTrue(
            "image[0] 应是 data URI",
            image[0].asString.startsWith("data:image/jpeg;base64,")
        )
        assertTrue(
            "image[1] 应是 data URI",
            image[1].asString.startsWith("data:image/jpeg;base64,")
        )
    }

    @Test
    fun buildPayloadSingleReferenceStillUsesArray() {
        // Agnes image 始终用数组形态（区别于 Ark 单张传字符串）
        val backend = newBackend()
        val ref = tmpJpeg("agnes_single")
        val request = ImageGenerationRequest(
            prompt = "单参考",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(ReferenceImage(ref.path, "ref_0"))
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应有 image", root.has("image"))
        val image = root.getAsJsonArray("image")
        assertEquals("单参考图仍应是数组（1 项）", 1, image.size())
    }

    // ==================== 尺寸计算（aspect_size） ====================

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
    fun resolveDimensions16to9DefaultShortIs1440() {
        // 16:9 横屏 + 默认 2K 档
        // 比例 16:9 → 短边 9，long_comp=16，short_unit=8*9=72
        // t=round(1440/72)=20；max_t_long=2048/(8*16)=16；t=min(20,16)=16
        // 宽=16*8*16=2048，高=9*8*16=1152
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "横屏",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "16:9"
        )
        val (w, h) = backend.resolveDimensions(request)
        assertEquals("16:9 横屏宽应 2048（长边收口）", 2048, w)
        assertEquals("16:9 横屏高应 1152", 1152, h)
    }

    @Test
    fun resolveDimensions1to1DefaultShortIs1440() {
        // 1:1 + 默认 2K 档 → 短边=长边，t 受 max_long_edge=2048 夹取
        // 比例 1:1 → short_comp=1, long_comp=1, short_unit=8
        // t=round(1440/8)=180；max_t_long=2048/(8*1)=256；t=min(180,256)=180
        // 宽=1*8*180=1440，高=1440
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "正方形",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "1:1"
        )
        val (w, h) = backend.resolveDimensions(request)
        assertEquals("1:1 宽高应相等", w, h)
        assertTrue("1:1 应 ≤ 2048（长边收口）", w <= 2048)
        assertEquals("1:1 应 1440", 1440, w)
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
    fun resolutionToShortEdgeCustomSizeStripsRatio() {
        // 自定义 "宽x高" → min(宽,高)，剥离自带比例
        val backend = newBackend()
        assertEquals("1920x1080 → min=1080", 1080, backend.resolutionToShortEdge("1920x1080"))
        assertEquals("1080x1920 → min=1080", 1080, backend.resolutionToShortEdge("1080x1920"))
        assertEquals("全角叉号", 720, backend.resolutionToShortEdge("1280×720"))
        assertEquals("星号", 720, backend.resolutionToShortEdge("720*1280"))
    }

    @Test
    fun resolutionToShortEdgePureNumber() {
        val backend = newBackend()
        assertEquals("纯数字 800 → 800", 800, backend.resolutionToShortEdge("800"))
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
    fun normalizeBaseUrlStripsV1AndReappends() {
        val backend = newBackend()
        assertEquals(
            "应剥 /v1 后补回",
            "https://apihub.agnes-ai.com/v1",
            backend.normalizeBaseUrl("https://apihub.agnes-ai.com/v1")
        )
        assertEquals(
            "无 /v1 应补",
            "https://apihub.agnes-ai.com/v1",
            backend.normalizeBaseUrl("https://apihub.agnes-ai.com")
        )
        assertEquals(
            "带尾斜杠应处理",
            "https://apihub.agnes-ai.com/v1",
            backend.normalizeBaseUrl("https://apihub.agnes-ai.com/v1/")
        )
        assertEquals(
            "空串回落默认 base",
            "https://apihub.agnes-ai.com/v1",
            backend.normalizeBaseUrl("")
        )
    }

    // ==================== typeId / model 默认值 ====================

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals("agnes", backend.typeId)
        assertEquals("空 model 应用默认值", "agnes-image-2.1-flash", backend.model)
    }
}
