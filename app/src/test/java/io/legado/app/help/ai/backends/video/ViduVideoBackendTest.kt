package io.legado.app.help.ai.backends.video

import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.VideoCapability
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
 * [ViduVideoBackend] 编码形态 + 端点分派 + 能力值单测（P2d）。
 *
 * 核心验证：
 * - **参考图/首尾帧用 data URI**——`images` 数组每项 `data:image/jpeg;base64,...`
 * - 4 端点分派：refs→/reference2video / start+end→/start-end2video / start→/img2video / 都无→/text2video
 * - images 形态按端点：ref2video=refs 数组；start-end2video=[首帧,尾帧]；img2video=[首帧]；text2video 无 images
 * - `aspect_ratio` 仅 text2video / reference2video 接受
 * - `audio` 仅 q3 系列模型接受
 * - prompt 截断：reference2video 上限 2000，其他 5000
 * - 能力值：firstFrame/lastFrame/refs=true, max=7, withStartFrame=false（参考与首帧互斥）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ViduVideoBackendTest {

    private fun newBackend(model: String = "viduq3-turbo"): ViduVideoBackend {
        val cfg = AiVideoProviderConfig(
            name = "vidu-test",
            type = AiVideoProviderConfig.TYPE_VIDU,
            baseUrl = "https://api.vidu.cn/ent/v2",
            apiKey = "test-key",
            model = model,
            submitUrl = "",
            pollUrlTemplate = ""
        )
        return ViduVideoBackend(cfg)
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

    // ==================== selectEndpoint 4 分支 ====================

    @Test
    fun selectEndpointRefsWinsOverStartEnd() {
        val backend = newBackend()
        assertEquals(
            "见参考图即切 reference2video",
            "/reference2video",
            backend.selectEndpoint(hasRefs = true, hasStart = true, hasEnd = true)
        )
    }

    @Test
    fun selectEndpointStartEndWhenNoRefs() {
        val backend = newBackend()
        assertEquals(
            "首尾帧齐全走 start-end2video",
            "/start-end2video",
            backend.selectEndpoint(hasRefs = false, hasStart = true, hasEnd = true)
        )
    }

    @Test
    fun selectEndpointStartOnlyGoesImg2Video() {
        val backend = newBackend()
        assertEquals(
            "仅首帧走 img2video",
            "/img2video",
            backend.selectEndpoint(hasRefs = false, hasStart = true, hasEnd = false)
        )
    }

    @Test
    fun selectEndpointNothingGoesText2Video() {
        val backend = newBackend()
        assertEquals(
            "无参考无首帧走 text2video",
            "/text2video",
            backend.selectEndpoint(hasRefs = false, hasStart = false, hasEnd = false)
        )
    }

    // ==================== 能力值 ====================

    @Test
    fun videoCapabilitiesForModelAlwaysFullSet() {
        val caps = ViduVideoBackend.videoCapabilitiesForModel("viduq3-turbo")
        assertTrue("firstFrame 应 true", caps.firstFrame)
        assertTrue("lastFrame 应 true", caps.lastFrame)
        assertTrue("referenceImages 应 true", caps.referenceImages)
        assertEquals("maxReferenceImages 应 7", 7, caps.maxReferenceImages)
        assertFalse("withStartFrame 应 false（参考与首帧互斥）", caps.referenceImagesWithStartFrame)
    }

    @Test
    fun capabilitiesQ3ModelHasGenerateAudio() {
        val backend = newBackend("viduq3-pro")
        assertTrue("q3 模型应有 GENERATE_AUDIO", VideoCapability.GENERATE_AUDIO in backend.capabilities)
    }

    @Test
    fun capabilitiesNonQ3ModelOmitsGenerateAudio() {
        val backend = newBackend("vidu-1.5")
        assertFalse("非 q3 模型不应有 GENERATE_AUDIO", VideoCapability.GENERATE_AUDIO in backend.capabilities)
    }

    // ==================== text2video 编码形态 ====================

    @Test
    fun buildRequestText2VideoHasNoImagesAndCarriesAspectRatio() {
        val backend = newBackend("vidu-1.5")
        val request = VideoGenerationRequest(
            prompt = "文生视频",
            outputPath = File("/tmp/out.mp4"),
            aspectRatio = "16:9",
            durationSeconds = 4,
            resolution = "720p"
        )
        val (endpoint, body) = backend.buildRequest(request, emptyList())
        assertEquals("/text2video", endpoint)
        val root = JsonParser.parseString(body).asJsonObject

        assertEquals("vidu-1.5", root.get("model").asString)
        assertEquals("duration 应 4", 4, root.get("duration").asInt)
        assertEquals("720p", root.get("resolution").asString)
        assertEquals("aspect_ratio 应下传", "16:9", root.get("aspect_ratio").asString)
        assertFalse("text2video 不应有 images", root.has("images"))
        assertFalse("非 q3 模型不应有 audio", root.has("audio"))
    }

    // ==================== img2video 编码形态 ====================

    @Test
    fun buildRequestImg2VideoUsesDataUriForFirstFrameOnly() {
        val backend = newBackend("vidu-1.5")
        val firstFrame = tmpJpeg("vff")
        val request = VideoGenerationRequest(
            prompt = "图生视频",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame,
            aspectRatio = "16:9",
            durationSeconds = 4
        )
        val compressed = listOf(CompressedRef(firstFrame, "first_frame", RefRole.FRAME))
        val (endpoint, body) = backend.buildRequest(request, compressed)
        assertEquals("/img2video", endpoint)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应有 images", root.has("images"))
        val images = root.getAsJsonArray("images")
        assertEquals("img2video images 应 1 项", 1, images.size())
        assertTrue(
            "images[0] 应是 data URI",
            images[0].asString.startsWith("data:image/jpeg;base64,")
        )
        // img2video 不接受 aspect_ratio
        assertFalse("img2video 不应有 aspect_ratio", root.has("aspect_ratio"))
    }

    // ==================== start-end2video 编码形态 ====================

    @Test
    fun buildRequestStartEnd2VideoUsesDataUriForFirstAndLastFrame() {
        val backend = newBackend("vidu-1.5")
        val firstFrame = tmpJpeg("vse_ff")
        val lastFrame = tmpJpeg("vse_lf")
        val request = VideoGenerationRequest(
            prompt = "首尾帧",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame,
            endImage = lastFrame,
            durationSeconds = 4
        )
        val compressed = listOf(
            CompressedRef(firstFrame, "first_frame", RefRole.FRAME),
            CompressedRef(lastFrame, "last_frame", RefRole.FRAME)
        )
        val (endpoint, body) = backend.buildRequest(request, compressed)
        assertEquals("/start-end2video", endpoint)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应有 images", root.has("images"))
        val images = root.getAsJsonArray("images")
        assertEquals("start-end2video images 应 2 项（首帧 + 尾帧）", 2, images.size())
        assertTrue("images[0] 应是 data URI", images[0].asString.startsWith("data:image/jpeg;base64,"))
        assertTrue("images[1] 应是 data URI", images[1].asString.startsWith("data:image/jpeg;base64,"))
        assertFalse("start-end2video 不应有 aspect_ratio", root.has("aspect_ratio"))
    }

    // ==================== reference2video 编码形态 ====================

    @Test
    fun buildRequestReference2VideoUsesDataUriForEachReference() {
        val backend = newBackend("vidu-1.5")
        val ref1 = tmpJpeg("vr1")
        val ref2 = tmpJpeg("vr2")
        val request = VideoGenerationRequest(
            prompt = "多图参考",
            outputPath = File("/tmp/out.mp4"),
            referenceImages = listOf(ref1, ref2),
            aspectRatio = "16:9"
        )
        val compressed = listOf(
            CompressedRef(ref1, "ref_0", RefRole.ARRAY),
            CompressedRef(ref2, "ref_1", RefRole.ARRAY)
        )
        val (endpoint, body) = backend.buildRequest(request, compressed)
        assertEquals("/reference2video", endpoint)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应有 images", root.has("images"))
        val images = root.getAsJsonArray("images")
        assertEquals("reference2video images 应 2 项", 2, images.size())
        assertTrue("images[0] 应是 data URI", images[0].asString.startsWith("data:image/jpeg;base64,"))
        assertTrue("images[1] 应是 data URI", images[1].asString.startsWith("data:image/jpeg;base64,"))
        // reference2video 接受 aspect_ratio
        assertEquals("16:9", root.get("aspect_ratio").asString)
    }

    @Test
    fun buildRequestReference2VideoDropsStartFrameWhenRefsPresent() {
        // 见参考图即切 reference2video，首帧被丢弃（互斥）
        val backend = newBackend("vidu-1.5")
        val ref1 = tmpJpeg("vr_drop_ref")
        val firstFrame = tmpJpeg("vr_drop_ff")
        val request = VideoGenerationRequest(
            prompt = "参考+首帧",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame,
            referenceImages = listOf(ref1)
        )
        val compressed = listOf(
            CompressedRef(firstFrame, "first_frame", RefRole.FRAME),
            CompressedRef(ref1, "ref_0", RefRole.ARRAY)
        )
        val (endpoint, body) = backend.buildRequest(request, compressed)
        assertEquals("/reference2video", endpoint)
        val root = JsonParser.parseString(body).asJsonObject

        val images = root.getAsJsonArray("images")
        assertEquals("参考与首帧互斥：images 应仅 1 项（首帧被丢弃）", 1, images.size())
        assertTrue("images[0] 应是 data URI", images[0].asString.startsWith("data:image/jpeg;base64,"))
    }

    // ==================== audio 限 q3 ====================

    @Test
    fun buildRequestQ3ModelCarriesAudio() {
        val backend = newBackend("viduq3-pro")
        val request = VideoGenerationRequest(
            prompt = "q3 音频",
            outputPath = File("/tmp/out.mp4"),
            generateAudio = true
        )
        val (_, body) = backend.buildRequest(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject
        assertTrue("q3 模型应有 audio 字段", root.has("audio"))
        assertTrue("audio 应为 true", root.get("audio").asBoolean)
    }

    @Test
    fun buildRequestNonQ3ModelOmitsAudio() {
        val backend = newBackend("vidu-1.5")
        val request = VideoGenerationRequest(
            prompt = "非 q3",
            outputPath = File("/tmp/out.mp4"),
            generateAudio = true
        )
        val (_, body) = backend.buildRequest(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject
        assertFalse("非 q3 模型不应有 audio 字段", root.has("audio"))
    }

    // ==================== prompt 截断 ====================

    @Test
    fun buildRequestReference2VideoTruncatesPromptTo2000() {
        val backend = newBackend("vidu-1.5")
        val ref1 = tmpJpeg("vptrunc")
        val longPrompt = "a".repeat(3000)
        val request = VideoGenerationRequest(
            prompt = longPrompt,
            outputPath = File("/tmp/out.mp4"),
            referenceImages = listOf(ref1)
        )
        val compressed = listOf(CompressedRef(ref1, "ref_0", RefRole.ARRAY))
        val (endpoint, body) = backend.buildRequest(request, compressed)
        assertEquals("/reference2video", endpoint)
        val root = JsonParser.parseString(body).asJsonObject
        assertEquals("reference2video prompt 应截断到 2000", 2000, root.get("prompt").asString.length)
    }

    @Test
    fun buildRequestText2VideoTruncatesPromptTo5000() {
        val backend = newBackend("vidu-1.5")
        val longPrompt = "a".repeat(6000)
        val request = VideoGenerationRequest(
            prompt = longPrompt,
            outputPath = File("/tmp/out.mp4")
        )
        val (endpoint, body) = backend.buildRequest(request, emptyList())
        assertEquals("/text2video", endpoint)
        val root = JsonParser.parseString(body).asJsonObject
        assertEquals("text2video prompt 应截断到 5000", 5000, root.get("prompt").asString.length)
    }

    // ==================== seed / resolution 默认 ====================

    @Test
    fun buildRequestCarriesSeedAndDefaultResolution() {
        val backend = newBackend("vidu-1.5")
        val request = VideoGenerationRequest(
            prompt = "seed",
            outputPath = File("/tmp/out.mp4"),
            seed = 12345L,
            resolution = null
        )
        val (_, body) = backend.buildRequest(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject
        assertEquals("seed 应 12345", 12345L, root.get("seed").asLong)
        assertEquals("空 resolution 应回落默认 720p", "720p", root.get("resolution").asString)
    }

    // ==================== normalizeBaseUrl ====================

    @Test
    fun normalizeBaseUrlPrependsHttpsAndStripsTrailingSlash() {
        val backend = newBackend()
        assertEquals(
            "应补 https://",
            "https://api.vidu.cn/ent/v2",
            backend.normalizeBaseUrl("api.vidu.cn/ent/v2")
        )
        assertEquals(
            "应去尾斜杠",
            "https://api.vidu.cn/ent/v2",
            backend.normalizeBaseUrl("https://api.vidu.cn/ent/v2/")
        )
        assertEquals(
            "已是完整 https 应原样",
            "https://api.vidu.cn/ent/v2",
            backend.normalizeBaseUrl("https://api.vidu.cn/ent/v2")
        )
        assertEquals(
            "空串回落 Vidu 开放平台 base",
            "https://api.vidu.cn/ent/v2",
            backend.normalizeBaseUrl("")
        )
    }

    // ==================== typeId / model 默认值 ====================

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals("vidu", backend.typeId)
        assertEquals("空 model 应用默认值", "viduq3-turbo", backend.model)
    }
}
