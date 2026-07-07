package io.legado.app.help.ai.backends.image

import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.ImageCapability
import io.legado.app.help.ai.backends.ImageGenerationRequest
import io.legado.app.help.ai.backends.ReferenceImage
import io.legado.app.ui.main.ai.AiImageProviderConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [ViduImageBackend] 编码形态 + 能力值 + URL 归一化 + 能力校验单测（P3c）。
 *
 * 核心验证：
 * - 能力（模型驱动）：viduq2 → {T2I, I2I}；viduq1 → {I2I} only；未登记回落 {T2I, I2I}
 * - typeId/model 默认值（viduq2）
 * - buildPayload：`{model, prompt, images?:[], aspect_ratio?, resolution?, seed?}`
 * - **参考图用 data URI**——images 数组每项 `data:image/jpeg;base64,...`
 * - prompt 截断 2000；参考图上限 7 截断
 * - aspect_ratio / resolution 白名单校验（不在白名单丢弃）
 * - normalizeBaseUrl：补 https:// + 去尾斜杠 + 空回落默认 base
 * - 能力校验：viduq1 无参考图（T2I 请求）抛 mismatch_no_t2i 错
 *
 * Robolectric 必需：I2I 测试调 buildPayload → ImageCodec.toDataUri → android.util.Base64。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ViduImageBackendTest {

    private fun newBackend(model: String = "viduq2"): ViduImageBackend {
        val cfg = AiImageProviderConfig(
            name = "vidu-img-test",
            type = AiImageProviderConfig.TYPE_VIDU,
            baseUrl = "https://api.vidu.cn/ent/v2",
            apiKey = "test-key",
            model = model
        )
        return ViduImageBackend(cfg)
    }

    /** 最小 JPEG 魔数字节临时文件（ImageCodec.toDataUri 读盘 + Base64）。 */
    private fun tmpJpeg(name: String): File {
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            0, 1, 0, 0, 1, 0, 1, 0, 0,
            0xFF.toByte(), 0xD9.toByte()
        )
        return File.createTempFile(name, ".jpg").apply { writeBytes(bytes); deleteOnExit() }
    }

    // ==================== 能力值（模型驱动） ====================

    @Test
    fun capabilitiesViduq2SupportsTextAndImage() {
        val backend = newBackend("viduq2")
        assertTrue("viduq2 应有 T2I", ImageCapability.TEXT_TO_IMAGE in backend.capabilities)
        assertTrue("viduq2 应有 I2I", ImageCapability.IMAGE_TO_IMAGE in backend.capabilities)
        assertEquals(
            "viduq2 → {T2I, I2I}",
            setOf(ImageCapability.TEXT_TO_IMAGE, ImageCapability.IMAGE_TO_IMAGE),
            backend.capabilities
        )
    }

    @Test
    fun capabilitiesViduq1SupportsImageOnly() {
        val backend = newBackend("viduq1")
        assertFalse("viduq1 不应有 T2I", ImageCapability.TEXT_TO_IMAGE in backend.capabilities)
        assertTrue("viduq1 应有 I2I", ImageCapability.IMAGE_TO_IMAGE in backend.capabilities)
        assertEquals(
            "viduq1 → {I2I} only",
            setOf(ImageCapability.IMAGE_TO_IMAGE),
            backend.capabilities
        )
    }

    @Test
    fun resolveCapsUnknownModelFallsBackToFullSet() {
        val backend = newBackend()
        assertEquals(
            "未登记 model 回落 {T2I, I2I}",
            setOf(ImageCapability.TEXT_TO_IMAGE, ImageCapability.IMAGE_TO_IMAGE),
            backend.resolveCaps("some-future-vidu-v99")
        )
    }

    // ==================== typeId / model 默认值 ====================

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals(AiImageProviderConfig.TYPE_VIDU, backend.typeId)
        assertEquals("空 model 应用默认值", "viduq2", backend.model)
    }

    // ==================== T2I 编码形态 + prompt 截断 ====================

    @Test
    fun buildPayloadText2ImageHasNoImagesField() {
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "一只橘猫",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16"
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject

        assertEquals("viduq2", root.get("model").asString)
        assertEquals("一只橘猫", root.get("prompt").asString)
        assertFalse("T2I 不应有 images 字段", root.has("images"))
        // 9:16 在 viduq2 白名单内，应下传
        assertTrue("9:16 合法应下传 aspect_ratio", root.has("aspect_ratio"))
        assertEquals("9:16", root.get("aspect_ratio").asString)
    }

    @Test
    fun buildPayloadPromptTruncatedTo2000() {
        val backend = newBackend()
        val longPrompt = "a".repeat(3000)
        val request = ImageGenerationRequest(
            prompt = longPrompt,
            outputPath = File("/tmp/out.png"),
            aspectRatio = "1:1"
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject
        assertEquals(
            "prompt 应截断到 2000",
            2000,
            root.get("prompt").asString.length
        )
    }

    // ==================== I2I 编码形态（data URI 数组） ====================

    @Test
    fun buildPayloadImage2ImageUsesDataUriArray() {
        val backend = newBackend()
        val ref1 = tmpJpeg("vidu_r1")
        val ref2 = tmpJpeg("vidu_r2")
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

        assertTrue("I2I 应有 images 字段", root.has("images"))
        val images = root.getAsJsonArray("images")
        assertEquals("images 应 2 项", 2, images.size())
        images.forEach { el ->
            val s = el.asString
            assertTrue(
                "images 项应是 data URI: $s",
                s.startsWith("data:image/jpeg;base64,")
            )
        }
        assertEquals("I2I 仍应有 model", "viduq2", root.get("model").asString)
        assertEquals("I2I 仍应有 prompt", "参考图生成", root.get("prompt").asString)
    }

    @Test
    fun buildPayloadImage2ImageTruncatesToSevenRefs() {
        val backend = newBackend()
        val refs = (1..10).map { i -> ReferenceImage(tmpJpeg("vidu_ref_$i").path) }
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            referenceImages = refs
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject
        val images = root.getAsJsonArray("images")
        assertEquals(
            "参考图应截断到 ${ViduImageBackend.MAX_REFERENCE_IMAGES}",
            ViduImageBackend.MAX_REFERENCE_IMAGES,
            images.size()
        )
    }

    // ==================== aspect_ratio 白名单 ====================

    @Test
    fun buildPayloadAspectRatioWhitelistedPassedThrough() {
        val backend = newBackend("viduq2")
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "21:9"
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject
        assertTrue("21:9 在 viduq2 白名单应下传", root.has("aspect_ratio"))
        assertEquals("21:9", root.get("aspect_ratio").asString)
    }

    @Test
    fun buildPayloadAspectRatioNotWhitelistedDropped() {
        val backend = newBackend("viduq2")
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "5:4"
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject
        assertFalse("5:4 不在 viduq2 白名单应丢弃", root.has("aspect_ratio"))
    }

    @Test
    fun buildPayloadAspectRatioViduq1WhitelistStricter() {
        // viduq1 aspect_ratio 白名单更窄：21:9 在 viduq2 合法但 viduq1 非法
        val backend = newBackend("viduq1")
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "21:9",
            referenceImages = listOf(ReferenceImage(tmpJpeg("vidu_q1").path))
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject
        assertFalse("21:9 不在 viduq1 白名单应丢弃", root.has("aspect_ratio"))
    }

    // ==================== resolution 白名单 ====================

    @Test
    fun buildPayloadResolutionWhitelistedPassedThrough() {
        val backend = newBackend("viduq2")
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16",
            imageSize = "4K"
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject
        assertTrue("4K 在 viduq2 resolution 白名单应下传", root.has("resolution"))
        assertEquals("4K", root.get("resolution").asString)
    }

    @Test
    fun buildPayloadResolutionNotWhitelistedDropped() {
        val backend = newBackend("viduq2")
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16",
            imageSize = "8K"
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject
        assertFalse("8K 不在 viduq2 resolution 白名单应丢弃", root.has("resolution"))
    }

    @Test
    fun buildPayloadResolutionViduq1WhitelistStricter() {
        // viduq1 resolution 白名单仅 1080p：2K 在 viduq2 合法但 viduq1 非法
        val backend = newBackend("viduq1")
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16",
            imageSize = "2K",
            referenceImages = listOf(ReferenceImage(tmpJpeg("vidu_q1_res").path))
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject
        assertFalse("2K 不在 viduq1 resolution 白名单应丢弃", root.has("resolution"))
    }

    // ==================== seed 下传 ====================

    @Test
    fun buildPayloadSeedIncludedWhenProvided() {
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16",
            seed = 42L
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject
        assertEquals(42L, root.get("seed").asLong)
    }

    // ==================== normalizeBaseUrl ====================

    @Test
    fun normalizeBaseUrlHandlesEmptyTrailingSlashAndNoScheme() {
        val backend = newBackend()
        val expected = "https://api.vidu.cn/ent/v2"
        assertEquals("空串回落默认 base", expected, backend.normalizeBaseUrl(""))
        assertEquals("纯空白回落默认 base", expected, backend.normalizeBaseUrl("   "))
        assertEquals("无尾斜杠原样", expected, backend.normalizeBaseUrl("https://api.vidu.cn/ent/v2"))
        assertEquals("带尾斜杠应去掉", expected, backend.normalizeBaseUrl("https://api.vidu.cn/ent/v2/"))
        assertEquals(
            "无 http 前缀应补 https://",
            "https://api.vidu.cn/ent/v2",
            backend.normalizeBaseUrl("api.vidu.cn/ent/v2")
        )
        assertEquals(
            "无 scheme + 尾斜杠应补 + 去尾",
            "https://api.vidu.cn/ent/v2",
            backend.normalizeBaseUrl("api.vidu.cn/ent/v2/")
        )
    }

    // ==================== 能力校验（generate 开头） ====================

    @Test
    fun generateViduq1WithoutRefsThrowsMismatchNoT2i() {
        // viduq1 仅 I2I：无参考图（T2I 请求）应抛 mismatch_no_t2i 错
        val backend = newBackend("viduq1")
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16"
        )
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking { backend.generate(request) }
        }
        assertTrue(
            "错误信息应含 mismatch_no_t2i: ${ex.message}",
            ex.message!!.contains("mismatch_no_t2i")
        )
    }
}
