package io.legado.app.help.ai.backends.video

import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.compress.CompressedRef
import io.legado.app.help.ai.backends.compress.RefRole
import io.legado.app.help.ai.backends.VideoCapabilities
import io.legado.app.help.ai.backends.VideoGenerationRequest
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun buildSubmitBodyFirstFramePlusRefsSeparatesCorrectly() {
        // 首帧 + 参考图：首帧进 image 字段，参考图进 extra_body.image 数组
        val backend = newBackend("agnes-video-v2.0")
        val firstFrame = tmpJpeg("agnesff")
        val ref1 = tmpJpeg("agnesr1")
        val request = VideoGenerationRequest(
            prompt = "测试",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame,
            referenceImages = listOf(ref1)
        )
        val compressed = listOf(
            CompressedRef(firstFrame, "first_frame", RefRole.FRAME),
            CompressedRef(ref1, "ref_0", RefRole.ARRAY)
        )
        val body = backend.buildSubmitBody(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        // 首帧进 image 字段
        assertTrue("首帧应进 image 字段", root.has("image"))
        assertNotNull(root.get("image").asString)

        // 参考图进 extra_body.image 数组
        val imageArr = root.getAsJsonObject("extra_body").getAsJsonArray("image")
        assertEquals("extra_body.image 应只有 1 个参考图（首帧不在内）", 1, imageArr.size())
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
        // 9:16 → 长边 1080，归一化为 607x1080
        assertTrue("应有 width", root.has("width"))
        assertTrue("应有 height", root.has("height"))
        assertEquals("frame_rate 应 24", 24, root.get("frame_rate").asInt)
        // 5 秒 * 24 = 120 帧
        assertEquals("num_frames 应 120（5s * 24fps）", 120, root.get("num_frames").asInt)
    }

    @Test
    fun buildSubmitBodyGenerateAudioAddsWithAudioFlag() {
        val backend = newBackend("agnes-video-v2.0")
        val request = VideoGenerationRequest(
            prompt = "测试",
            outputPath = File("/tmp/out.mp4"),
            generateAudio = true
        )
        val body = backend.buildSubmitBody(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject
        assertTrue("generateAudio=true 应在 extra_body 加 with_audio=true",
            root.has("extra_body") && root.getAsJsonObject("extra_body").has("with_audio"))
        assertEquals(true, root.getAsJsonObject("extra_body").get("with_audio").asBoolean)
    }

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals("agnes", backend.typeId)
        assertEquals("空 model 应用默认值", "agnes-video-v2.0", backend.model)
    }
}
