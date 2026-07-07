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
 * [NewApiVideoBackend] 编码形态 + 能力值 + resolveSize 单测（P2c）。
 *
 * 核心验证：
 * - **参考图用 data URI**（`data:image/jpeg;base64,...`）——NewAPI `/video/generations` 端点约定
 * - 仅支持首帧（`image` 字段）；多张参考图不支持（[videoCapabilities.referenceImages]=false）
 * - resolveSize：短边来自 resolution（720/1080/4k），比例来自 aspectRatio，对齐 8 的倍数
 * - 能力值：firstFrame=true, lastFrame=false, referenceImages=false, max=0, withStartFrame=false
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NewApiVideoBackendTest {

    private fun newBackend(model: String = "kling-v1"): NewApiVideoBackend {
        val cfg = AiVideoProviderConfig(
            name = "newapi-test",
            type = AiVideoProviderConfig.TYPE_NEWAPI,
            baseUrl = "https://api.newapi.com",
            apiKey = "test-key",
            model = model,
            submitUrl = "",
            pollUrlTemplate = ""
        )
        return NewApiVideoBackend(cfg)
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
    fun videoCapabilitiesForModelOnlyFirstFrame() {
        val caps = NewApiVideoBackend.videoCapabilitiesForModel("any-model")
        assertTrue("firstFrame 应 true", caps.firstFrame)
        assertFalse("lastFrame 应 false", caps.lastFrame)
        assertFalse("referenceImages 应 false", caps.referenceImages)
        assertEquals("maxReferenceImages 应 0", 0, caps.maxReferenceImages)
        assertFalse("withStartFrame 应 false", caps.referenceImagesWithStartFrame)
    }

    // ==================== data URI 编码形态 ====================

    @Test
    fun buildSubmitBodyUsesDataUriForFirstFrame() {
        val backend = newBackend("kling-v1")
        val firstFrame = tmpJpeg("newapiff")
        val request = VideoGenerationRequest(
            prompt = "首帧",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame
        )
        val compressed = listOf(CompressedRef(firstFrame, "first_frame", RefRole.FRAME))
        val body = backend.buildSubmitBody(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应有 image 字段", root.has("image"))
        val imageVal = root.get("image").asString
        assertTrue("image 应是 data URI（data: 前缀），实际：$imageVal",
            imageVal.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun buildSubmitBodyIgnoresReferenceImages() {
        // NewAPI 不支持参考图——referenceImages 即使传入也不进 body（与 ArcReel 一致）
        val backend = newBackend("kling-v1")
        val ref1 = tmpJpeg("newapir1")
        val ref2 = tmpJpeg("newapir2")
        val request = VideoGenerationRequest(
            prompt = "参考图",
            outputPath = File("/tmp/out.mp4"),
            referenceImages = listOf(ref1, ref2)
        )
        // 注意：buildReferenceSpecs 只取 startImage；compressed 列表本不应有 ref_*
        // 但即使误传，buildSubmitBody 也只取 label=="first_frame" 的，参考图被忽略
        val compressed = listOf(
            CompressedRef(ref1, "ref_0", RefRole.ARRAY),
            CompressedRef(ref2, "ref_1", RefRole.ARRAY)
        )
        val body = backend.buildSubmitBody(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        assertFalse("不应有 image 字段（无首帧）", root.has("image"))
        // 没有任何参考图字段
        assertFalse("不应有 reference_images 字段", root.has("reference_images"))
        assertFalse("不应有 image_urls 字段", root.has("image_urls"))
    }

    @Test
    fun buildSubmitBodyIncludesRequiredFields() {
        val backend = newBackend("kling-v1")
        val request = VideoGenerationRequest(
            prompt = "hello",
            outputPath = File("/tmp/out.mp4"),
            aspectRatio = "16:9",
            durationSeconds = 5,
            seed = 42L
        )
        val body = backend.buildSubmitBody(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject
        assertEquals("kling-v1", root.get("model").asString)
        assertEquals("hello", root.get("prompt").asString)
        assertTrue("应有 width", root.has("width"))
        assertTrue("应有 height", root.has("height"))
        assertEquals("duration 应 5", 5, root.get("duration").asInt)
        assertEquals("n 应 1", 1, root.get("n").asInt)
        assertEquals("seed 应 42", 42L, root.get("seed").asLong)
    }

    // ==================== resolveSize（短边+比例+对齐8） ====================

    @Test
    fun resolveSize720pWith16to9Returns720x1280() {
        val backend = newBackend("kling-v1")
        // 720 短边 + 16:9（横屏）→ 高=720，宽=720*16/9=1280 → 都对齐 8
        val (w, h) = backend.resolveSize("720", "16:9")
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun resolveSize1080pWith9to16Returns1080x1920() {
        val backend = newBackend("kling-v1")
        // 1080 短边 + 9:16（竖屏）→ 宽=1080，高=1080*16/9=1920
        val (w, h) = backend.resolveSize("1080", "9:16")
        assertEquals(1080, w)
        assertEquals(1920, h)
    }

    @Test
    fun resolveSize4kReturns2160ShortEdge() {
        val backend = newBackend("kling-v1")
        // 4k → 短边 2160；16:9 横屏 → 2160x3840
        val (w, h) = backend.resolveSize("4k", "16:9")
        assertEquals(3840, w)
        assertEquals(2160, h)
    }

    @Test
    fun resolveSizeNullResolutionDefaultsTo720() {
        val backend = newBackend("kling-v1")
        val (w, h) = backend.resolveSize(null, "9:16")
        // 缺省 720 短边 + 9:16 竖屏 → 720x1280
        assertEquals(720, w)
        assertEquals(1280, h)
    }

    @Test
    fun resolveSizeAlignsToMultipleOf8() {
        // 任一 resolution+aspectRatio 组合，结果都应是 8 的倍数（修复 1080 不被 16 整除等问题）
        val backend = newBackend("kling-v1")
        listOf(
            "720" to "9:16",
            "1080" to "16:9",
            "1080" to "1:1",
            "1080" to "4:3",
            "4k" to "21:9"
        ).forEach { (res, ratio) ->
            val (w, h) = backend.resolveSize(res, ratio)
            assertEquals("$res+$ratio width 应对齐 8", 0, w % 8)
            assertEquals("$res+$ratio height 应对齐 8", 0, h % 8)
        }
    }

    @Test
    fun resolveSize1080pWith1to1Returns1080x1080() {
        val backend = newBackend("kling-v1")
        // 1080 短边 + 1:1 → 1080x1080（rw==rh，进入 else 分支但 w=short=1080, h=1080）
        val (w, h) = backend.resolveSize("1080", "1:1")
        assertEquals(1080, w)
        assertEquals(1080, h)
    }

    // ==================== typeId / model 默认值 ====================

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals("newapi", backend.typeId)
        assertEquals("空 model 应用默认值", "kling-v1", backend.model)
    }
}
