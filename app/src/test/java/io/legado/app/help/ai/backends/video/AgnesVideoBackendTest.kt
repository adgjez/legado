package io.legado.app.help.ai.backends.video

import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.compress.CompressedRef
import io.legado.app.help.ai.backends.compress.RefRole
import io.legado.app.help.ai.backends.VideoCapabilities
import io.legado.app.help.ai.backends.VideoGenerationRequest
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
 * [AgnesVideoBackend] 编码形态 + 能力值单测（P2a 头条：Incorrect padding 修复的证明）。
 *
 * 核心验证：
 * - **参考图用裸 base64**（无 `data:` 前缀，无换行）← 修复 Incorrect padding 的关键
 *   ArcReel 注释原话「带 `data:` 前缀会在生成期触发 padding 错误」
 * - 首帧 → `image` 字段（裸 base64 字符串）
 * - 参考图 → `extra_body.image` 数组（裸 base64 字符串列表）
 * - 能力值：all true, max=4, withStartFrame=false
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class AgnesVideoBackendTest {

    private fun newBackend(model: String = "agnes-video-v2.0"): AgnesVideoBackend {
        val cfg = AiVideoProviderConfig(
            name = "agnes-test",
            type = AiVideoProviderConfig.TYPE_AGNES,
            baseUrl = "https://api.agnes.ai",
            apiKey = "test-key",
            model = model,
            submitUrl = "",
            pollUrlTemplate = ""
        )
        return AgnesVideoBackend(cfg)
    }

    private fun tmpJpeg(name: String): File {
        // 最小有效 JPEG（FFD8 FFE0 ... FFD9）——isLikelyImage 看魔数 FFD8 即可
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            0, 1, 0, 0, 1, 0, 1, 0, 0,
            0xFF.toByte(), 0xD9.toByte()
        )
        val f = File.createTempFile(name, ".jpg").apply { writeBytes(bytes); deleteOnExit() }
        return f
    }

    @Test
    fun videoCapabilitiesForModelAllTrueMax4() {
        val caps = AgnesVideoBackend.videoCapabilitiesForModel("agnes-video-v2.0")
        assertTrue("firstFrame 应 true", caps.firstFrame)
        assertTrue("lastFrame 应 true", caps.lastFrame)
        assertTrue("referenceImages 应 true", caps.referenceImages)
        assertEquals("maxReferenceImages 应 4", 4, caps.maxReferenceImages)
        assertFalse("withStartFrame 应 false", caps.referenceImagesWithStartFrame)
    }

    @Test
    fun videoCapabilitiesForModelAnyModelReturnsAllTrue() {
        // agnes 能力不随 model 变（all true max=4 是 endpoint 级常量）
        val caps = AgnesVideoBackend.videoCapabilitiesForModel("any-other-model")
        assertTrue(caps.firstFrame)
        assertTrue(caps.lastFrame)
        assertTrue(caps.referenceImages)
        assertEquals(4, caps.maxReferenceImages)
    }

    @Test
    fun buildSubmitBodyUsesBareBase64ForFirstFrame() {
        val backend = newBackend("agnes-video-v2.0")
        val firstFrame = tmpJpeg("agnesff")
        val request = VideoGenerationRequest(
            prompt = "测试 prompt",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame
        )
        val compressed = listOf(CompressedRef(firstFrame, "first_frame", RefRole.FRAME))
        val body = backend.buildSubmitBody(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        // image 字段应是裸 base64（不含 data: 前缀）← Incorrect padding 修复的核心证明
        assertTrue("应有 image 字段", root.has("image"))
        val imageB64 = root.get("image").asString
        assertFalse("image 字段应是裸 base64（无 data: 前缀），实际：$imageB64",
            imageB64.startsWith("data:"))
        // NO_WRAP：不应含换行
        assertFalse("image base64 不应含换行（NO_WRAP）",
            imageB64.contains("\n") || imageB64.contains("\r"))
    }

    @Test
    fun buildSubmitBodyUsesBareBase64ForReferenceImages() {
        val backend = newBackend("agnes-video-v2.0")
        val ref1 = tmpJpeg("agnesr1")
        val ref2 = tmpJpeg("agnesr2")
        val request = VideoGenerationRequest(
            prompt = "测试 prompt",
            outputPath = File("/tmp/out.mp4"),
            referenceImages = listOf(ref1, ref2)
        )
        val compressed = listOf(
            CompressedRef(ref1, "ref_0", RefRole.ARRAY),
            CompressedRef(ref2, "ref_1", RefRole.ARRAY)
        )
        val body = backend.buildSubmitBody(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        // extra_body.image 数组——每个元素应是裸 base64
        assertTrue("应有 extra_body", root.has("extra_body"))
        val extraBody = root.getAsJsonObject("extra_body")
        assertTrue("extra_body 应有 image 数组", extraBody.has("image"))
        val imageArr = extraBody.getAsJsonArray("image")
        assertEquals("应有 2 个参考图", 2, imageArr.size())

        for (i in 0 until imageArr.size()) {
            val b64 = imageArr[i].asString
            assertFalse("extra_body.image[$i] 应是裸 base64（无 data: 前缀），实际：$b64",
                b64.startsWith("data:"))
            assertFalse("extra_body.image[$i] base64 不应含换行（NO_WRAP）",
                b64.contains("\n") || b64.contains("\r"))
        }
    }

    @Test
    fun buildSubmitBodyFirstAndLastFrameUsesKeyframesMode() {
        // 首帧 + 尾帧 → keyframes 模式：extra_body.image=[start_b64, end_b64] + mode=keyframes
        // （参考图与首/尾帧互斥，agnes 不支持混合，故此用例只测首+尾帧组合）
        val backend = newBackend("agnes-video-v2.0")
        val firstFrame = tmpJpeg("agnesff")
        val lastFrame = tmpJpeg("agneslf")
        val request = VideoGenerationRequest(
            prompt = "测试",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame,
            endImage = lastFrame
        )
        val compressed = listOf(
            CompressedRef(firstFrame, "first_frame", RefRole.FRAME),
            CompressedRef(lastFrame, "last_frame", RefRole.FRAME)
        )
        val body = backend.buildSubmitBody(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        // keyframes 模式：不写顶层 image，仅 extra_body.image=[首帧, 尾帧] + mode=keyframes
        assertFalse("keyframes 模式不应写顶层 image", root.has("image"))
        assertTrue("应有 extra_body", root.has("extra_body"))
        val extraBody = root.getAsJsonObject("extra_body")
        assertTrue("extra_body 应有 image 数组", extraBody.has("image"))
        val imageArr = extraBody.getAsJsonArray("image")
        assertEquals("extra_body.image 应有首+尾帧共 2 个元素", 2, imageArr.size())
        assertTrue("extra_body 应有 mode", extraBody.has("mode"))
        assertEquals("mode 应为 keyframes", "keyframes", extraBody.get("mode").asString)
    }

    @Test
    fun buildSubmitBodyIncludesModelPromptAndDimensions() {
        val backend = newBackend("agnes-video-v2.0")
        val request = VideoGenerationRequest(
            prompt = "hello world",
            outputPath = File("/tmp/out.mp4"),
            aspectRatio = "9:16",
            durationSeconds = 5
        )
        val body = backend.buildSubmitBody(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject
        assertEquals("agnes-video-v2.0", root.get("model").asString)
        assertEquals("hello world", root.get("prompt").asString)
        // 9:16 → 短边 720（VIDEO_TIER 默认）+ round_to=8 + max_long_edge=1920 → 720x1280
        assertEquals("9:16 width 应 720", 720, root.get("width").asInt)
        assertEquals("9:16 height 应 1280", 1280, root.get("height").asInt)
        assertEquals("frame_rate 应 24", 24, root.get("frame_rate").asInt)
        // num_frames 对齐 8n+1：5s*24=120 → (120-1)/8=14.875 → round=15 → 8*15+1=121
        assertEquals("num_frames 应 121（8n+1 对齐）", 121, root.get("num_frames").asInt)
    }

    @Test
    fun buildSubmitBodyNeverAddsWithAudioEvenWhenRequested() {
        // Agnes 视频无音频能力：即使 request.generateAudio=true 也不下发 with_audio / generate_audio
        // （ArcReel agnes.py 明确纪律：agnes 视频恒无声，generate_audio=False）
        val backend = newBackend("agnes-video-v2.0")
        val request = VideoGenerationRequest(
            prompt = "测试",
            outputPath = File("/tmp/out.mp4"),
            generateAudio = true
        )
        val body = backend.buildSubmitBody(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject
        // 反向断言：提交体不应含 with_audio 字段
        val hasWithAudio = root.has("extra_body") &&
            root.getAsJsonObject("extra_body").has("with_audio")
        assertFalse("Agnes 视频无音频能力，不应下发 with_audio", hasWithAudio)
    }

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals("agnes", backend.typeId)
        assertEquals("空 model 应用默认值", "agnes-video-v2.0", backend.model)
    }
}
