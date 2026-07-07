package io.legado.app.help.ai.backends.video

import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.VideoGenerationRequest
import io.legado.app.help.ai.backends.compress.CompressedRef
import io.legado.app.help.ai.backends.compress.RefRole
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [GrokVideoBackend] 编码形态 + 能力值单测（P2d）。
 *
 * 核心验证：
 * - **首帧/参考图用 data URI**——`image_url`（首帧，data URI 字符串）/
 *   `reference_image_urls`（参考图，data URI 数组）
 * - **首帧与参考图可叠加**（`withStartFrame=true`）：两者同时存在时两个字段都下传
 *   ——区别于 Vidu（互斥）/ MiniMax Hailuo（无参考）/ NewAPI（无参考）
 * - 尾帧不建模（`lastFrame=false`）
 * - 能力值：firstFrame=true, lastFrame=false, referenceImages=true, max=7, withStartFrame=true
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GrokVideoBackendTest {

    private fun newBackend(model: String = "grok-imagine-video"): GrokVideoBackend {
        val cfg = AiVideoProviderConfig(
            name = "grok-test",
            type = AiVideoProviderConfig.TYPE_GROK,
            baseUrl = "https://api.x.ai",
            apiKey = "test-key",
            model = model,
            submitUrl = "",
            pollUrlTemplate = ""
        )
        return GrokVideoBackend(cfg)
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
    fun videoCapabilitiesForModelSupportsFirstFrameAndRefs() {
        val caps = GrokVideoBackend.videoCapabilitiesForModel("grok-imagine-video")
        assertTrue("firstFrame 应 true", caps.firstFrame)
        assertFalse("lastFrame 应 false", caps.lastFrame)
        assertTrue("referenceImages 应 true", caps.referenceImages)
        assertEquals("maxReferenceImages 应 7", 7, caps.maxReferenceImages)
        assertTrue(
            "withStartFrame 应 true（首帧与参考图可叠加）",
            caps.referenceImagesWithStartFrame
        )
    }

    // ==================== text2video 编码形态 ====================

    @Test
    fun buildPayloadText2VideoHasNoImageFields() {
        val backend = newBackend()
        val request = VideoGenerationRequest(
            prompt = "文生视频",
            outputPath = File("/tmp/out.mp4"),
            aspectRatio = "16:9",
            durationSeconds = 5
        )
        val body = backend.buildPayload(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject

        assertEquals("grok-imagine-video", root.get("model").asString)
        assertEquals("duration 应 5", 5, root.get("duration").asInt)
        assertEquals("16:9", root.get("aspect_ratio").asString)
        assertFalse("文生视频不应有 image_url", root.has("image_url"))
        assertFalse("文生视频不应有 reference_image_urls", root.has("reference_image_urls"))
    }

    // ==================== 首帧 image_url 编码形态 ====================

    @Test
    fun buildPayloadUsesDataUriForFirstFrame() {
        val backend = newBackend()
        val firstFrame = tmpJpeg("gff")
        val request = VideoGenerationRequest(
            prompt = "图生视频",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame
        )
        val compressed = listOf(CompressedRef(firstFrame, "first_frame", RefRole.FRAME))
        val body = backend.buildPayload(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应有 image_url", root.has("image_url"))
        val imageUrl = root.get("image_url").asString
        assertTrue(
            "image_url 应是 data URI，实际：$imageUrl",
            imageUrl.startsWith("data:image/jpeg;base64,")
        )
        assertFalse("仅首帧不应有 reference_image_urls", root.has("reference_image_urls"))
    }

    // ==================== 参考图 reference_image_urls 编码形态 ====================

    @Test
    fun buildPayloadUsesDataUriArrayForReferenceImages() {
        val backend = newBackend()
        val ref1 = tmpJpeg("gr1")
        val ref2 = tmpJpeg("gr2")
        val request = VideoGenerationRequest(
            prompt = "多图参考",
            outputPath = File("/tmp/out.mp4"),
            referenceImages = listOf(ref1, ref2)
        )
        val compressed = listOf(
            CompressedRef(ref1, "ref_0", RefRole.ARRAY),
            CompressedRef(ref2, "ref_1", RefRole.ARRAY)
        )
        val body = backend.buildPayload(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        assertFalse("无首帧不应有 image_url", root.has("image_url"))
        assertTrue("应有 reference_image_urls", root.has("reference_image_urls"))
        val arr = root.getAsJsonArray("reference_image_urls")
        assertEquals("reference_image_urls 应 2 项", 2, arr.size())
        assertTrue("arr[0] 应是 data URI", arr[0].asString.startsWith("data:image/jpeg;base64,"))
        assertTrue("arr[1] 应是 data URI", arr[1].asString.startsWith("data:image/jpeg;base64,"))
    }

    // ==================== 首帧 + 参考图叠加（关键差异点） ====================

    @Test
    fun buildPayloadCoexistsFirstFrameAndReferenceImages() {
        // Grok 关键差异点：首帧（image_url）与参考图（reference_image_urls）可叠加
        // 区别于 Vidu（互斥）/ MiniMax Hailuo（无参考）/ NewAPI（无参考）
        val backend = newBackend()
        val firstFrame = tmpJpeg("gboth_ff")
        val ref1 = tmpJpeg("gboth_r1")
        val ref2 = tmpJpeg("gboth_r2")
        val request = VideoGenerationRequest(
            prompt = "首帧+参考叠加",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame,
            referenceImages = listOf(ref1, ref2)
        )
        val compressed = listOf(
            CompressedRef(firstFrame, "first_frame", RefRole.FRAME),
            CompressedRef(ref1, "ref_0", RefRole.ARRAY),
            CompressedRef(ref2, "ref_1", RefRole.ARRAY)
        )
        val body = backend.buildPayload(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        // 两个字段共存——这是 Grok 与其他 backend 的核心差异
        assertTrue("叠加模式：应有 image_url", root.has("image_url"))
        assertTrue("叠加模式：应有 reference_image_urls", root.has("reference_image_urls"))
        assertTrue(
            "image_url 应是 data URI",
            root.get("image_url").asString.startsWith("data:image/jpeg;base64,")
        )
        val arr = root.getAsJsonArray("reference_image_urls")
        assertEquals("reference_image_urls 应 2 项", 2, arr.size())
        assertTrue("arr[0] 应是 data URI", arr[0].asString.startsWith("data:image/jpeg;base64,"))
        assertTrue("arr[1] 应是 data URI", arr[1].asString.startsWith("data:image/jpeg;base64,"))
    }

    // ==================== resolution 下传 ====================

    @Test
    fun buildPayloadCarriesResolutionWhenProvided() {
        val backend = newBackend()
        val request = VideoGenerationRequest(
            prompt = "带分辨率",
            outputPath = File("/tmp/out.mp4"),
            aspectRatio = "16:9",
            resolution = "1080p"
        )
        val body = backend.buildPayload(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject
        assertEquals("1080p", root.get("resolution").asString)
    }

    @Test
    fun buildPayloadOmitsResolutionWhenAbsent() {
        val backend = newBackend()
        val request = VideoGenerationRequest(
            prompt = "无分辨率",
            outputPath = File("/tmp/out.mp4")
        )
        val body = backend.buildPayload(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject
        assertFalse("无 resolution 不应下传", root.has("resolution"))
    }

    // ==================== normalizeBaseUrl ====================

    @Test
    fun normalizeBaseUrlPrependsHttpsAndStripsTrailingSlash() {
        val backend = newBackend()
        assertEquals(
            "应补 https://",
            "https://api.x.ai",
            backend.normalizeBaseUrl("api.x.ai")
        )
        assertEquals(
            "应去尾斜杠",
            "https://api.x.ai",
            backend.normalizeBaseUrl("https://api.x.ai/")
        )
        assertEquals(
            "已是完整 https 应原样",
            "https://api.x.ai",
            backend.normalizeBaseUrl("https://api.x.ai")
        )
        assertEquals(
            "空串回落 xAI host",
            "https://api.x.ai",
            backend.normalizeBaseUrl("")
        )
    }

    // ==================== typeId / model 默认值 ====================

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals("grok", backend.typeId)
        assertEquals("空 model 应用默认值", "grok-imagine-video", backend.model)
    }
}
