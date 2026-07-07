package io.legado.app.help.ai.backends.video

import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.compress.CompressedRef
import io.legado.app.help.ai.backends.compress.RefRole
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
 * [VeoVideoBackend] 编码形态 + 能力值单测（P2b）。
 *
 * 核心验证：
 * - REST 兜底（Gemini predictLongRunning），非 SDK Image 对象
 * - 参考图用 **bytesBase64Encoded base64**（SDK Image(image_bytes=) 的 REST 等价）
 *   用 [ImageCodec.toBareBase64]（NO_WRAP 无换行）避免 padding 问题
 * - 请求体结构：instances[0]{prompt, image?} + parameters{aspectRatio, durationSeconds,
 *   resolution?, lastFrame?, referenceImages?[], generateAudio?}
 * - 能力值：lastFrame=true, referenceImages=true, max=3, withStartFrame=false
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class VeoVideoBackendTest {

    private fun newBackend(model: String = "veo-3.1"): VeoVideoBackend {
        val cfg = AiVideoProviderConfig(
            name = "veo-test",
            type = AiVideoProviderConfig.TYPE_VEO,
            baseUrl = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = model,
            submitUrl = "",
            pollUrlTemplate = ""
        )
        return VeoVideoBackend(cfg)
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

    @Test
    fun videoCapabilitiesForModelLastFrameTrueMax3() {
        val caps = VeoVideoBackend.videoCapabilitiesForModel("veo-3.1")
        assertTrue("firstFrame 应 true", caps.firstFrame)
        assertTrue("lastFrame 应 true", caps.lastFrame)
        assertTrue("referenceImages 应 true", caps.referenceImages)
        assertEquals("maxReferenceImages 应 3", 3, caps.maxReferenceImages)
        assertFalse("withStartFrame 应 false", caps.referenceImagesWithStartFrame)
    }

    @Test
    fun buildSubmitBodyUsesBytesBase64EncodedForFirstFrame() {
        // 首帧 → instances[0].image.bytesBase64Encoded（裸 base64，无 data: 前缀）
        val backend = newBackend("veo-3.1")
        val firstFrame = tmpJpeg("veoff")
        val request = VideoGenerationRequest(
            prompt = "测试 prompt",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame
        )
        val compressed = listOf(CompressedRef(firstFrame, "first_frame", RefRole.FRAME))
        val body = backend.buildSubmitBody(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        val instance = root.getAsJsonArray("instances")[0].asJsonObject
        assertTrue("instance 应有 image", instance.has("image"))
        val imageObj = instance.getAsJsonObject("image")
        assertTrue("image 应有 bytesBase64Encoded", imageObj.has("bytesBase64Encoded"))
        val b64 = imageObj.get("bytesBase64Encoded").asString
        assertFalse("bytesBase64Encoded 应是裸 base64（无 data: 前缀）",
            b64.startsWith("data:"))
        assertFalse("base64 不应含换行（NO_WRAP）",
            b64.contains("\n") || b64.contains("\r"))
    }

    @Test
    fun buildSubmitBodyUsesBytesBase64EncodedForReferenceImages() {
        // 参考图 → parameters.referenceImages[]（referenceType=ASSET，bytesBase64Encoded 裸 base64）
        val backend = newBackend("veo-3.1")
        val ref1 = tmpJpeg("veor1")
        val ref2 = tmpJpeg("veor2")
        val request = VideoGenerationRequest(
            prompt = "测试",
            outputPath = File("/tmp/out.mp4"),
            referenceImages = listOf(ref1, ref2)
        )
        val compressed = listOf(
            CompressedRef(ref1, "ref_0", RefRole.ARRAY),
            CompressedRef(ref2, "ref_1", RefRole.ARRAY)
        )
        val body = backend.buildSubmitBody(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        val params = root.getAsJsonObject("parameters")
        assertTrue("parameters 应有 referenceImages", params.has("referenceImages"))
        val refArr = params.getAsJsonArray("referenceImages")
        assertEquals("应有 2 个参考图", 2, refArr.size())

        for (i in 0 until refArr.size()) {
            val refObj = refArr[i].asJsonObject
            assertEquals("referenceType 应 ASSET", "ASSET", refObj.get("referenceType").asString)
            val b64 = refObj.getAsJsonObject("image").get("bytesBase64Encoded").asString
            assertFalse("referenceImages[$i] 应是裸 base64（无 data: 前缀）",
                b64.startsWith("data:"))
            assertFalse("referenceImages[$i] base64 不应含换行",
                b64.contains("\n") || b64.contains("\r"))
        }
    }

    @Test
    fun buildSubmitBodyLastFrameInParameters() {
        // 尾帧 → parameters.lastFrame.bytesBase64Encoded
        val backend = newBackend("veo-3.1")
        val firstFrame = tmpJpeg("veoff")
        val lastFrame = tmpJpeg("veolf")
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

        val params = root.getAsJsonObject("parameters")
        assertTrue("parameters 应有 lastFrame", params.has("lastFrame"))
        val lastObj = params.getAsJsonObject("lastFrame")
        assertTrue("lastFrame 应有 bytesBase64Encoded", lastObj.has("bytesBase64Encoded"))
        assertFalse("lastFrame base64 不应含 data: 前缀",
            lastObj.get("bytesBase64Encoded").asString.startsWith("data:"))
    }

    @Test
    fun buildSubmitBodyIncludesCoreParameters() {
        val backend = newBackend("veo-3.1")
        val request = VideoGenerationRequest(
            prompt = "hello world",
            outputPath = File("/tmp/out.mp4"),
            aspectRatio = "16:9",
            durationSeconds = 8,
            generateAudio = true
        )
        val body = backend.buildSubmitBody(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject

        val instance = root.getAsJsonArray("instances")[0].asJsonObject
        assertEquals("hello world", instance.get("prompt").asString)

        val params = root.getAsJsonObject("parameters")
        assertEquals("16:9", params.get("aspectRatio").asString)
        assertEquals("8", params.get("durationSeconds").asString)
        assertTrue("generateAudio 应 true", params.get("generateAudio").asBoolean)
    }

    @Test
    fun buildSubmitBodyDurationSecondsAsString() {
        // ArcReel 原样：durationSeconds 是字符串
        val backend = newBackend("veo-3.1")
        val request = VideoGenerationRequest(
            prompt = "测试",
            outputPath = File("/tmp/out.mp4"),
            durationSeconds = 5
        )
        val body = backend.buildSubmitBody(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject
        val dur = root.getAsJsonObject("parameters").get("durationSeconds")
        assertTrue("durationSeconds 应是字符串", dur.isJsonPrimitive && dur.asJsonPrimitive.isString)
        assertEquals("5", dur.asString)
    }

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals("veo", backend.typeId)
        assertEquals("空 model 应用默认值", "veo-3.1", backend.model)
    }
}
