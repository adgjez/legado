package io.legado.app.help.ai.backends.video

import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.VideoCapability
import io.legado.app.help.ai.backends.VideoGenerationRequest
import io.legado.app.help.ai.backends.compress.CompressedRef
import io.legado.app.help.ai.backends.compress.RefRole
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [MiniMaxVideoBackend] 编码形态 + 能力查表 + 校验单测（P2d）。
 *
 * 核心验证：
 * - **参考图用 data URI**——Hailuo `first_frame_image` / S2V `subject_reference[].image[]`
 * - 2 档能力：Hailuo 首帧；S2V-01 单脸参考（firstFrame=false, refs=true, max=1）
 * - Fast 仅 i2v，无首帧的文生视频请求被能力拒绝
 * - resolution × duration 白名单校验（768p:{6,10}, 1080p:{6}）
 * - S2V 无参考图 fail-loud
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MiniMaxVideoBackendTest {

    private fun newBackend(model: String = "MiniMax-Hailuo-2.3"): MiniMaxVideoBackend {
        val cfg = AiVideoProviderConfig(
            name = "minimax-test",
            type = AiVideoProviderConfig.TYPE_MINIMAX,
            baseUrl = "https://api.minimaxi.com/v1",
            apiKey = "test-key",
            model = model,
            submitUrl = "",
            pollUrlTemplate = ""
        )
        return MiniMaxVideoBackend(cfg)
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

    // ==================== 能力查表（2 档） ====================

    @Test
    fun capabilitiesForModelHailuoSupportsTextAndImage() {
        val caps = MiniMaxVideoBackend.capabilitiesForModel("MiniMax-Hailuo-2.3")
        assertTrue("Hailuo 应有 t2v", VideoCapability.TEXT_TO_VIDEO in caps)
        assertTrue("Hailuo 应有 i2v", VideoCapability.IMAGE_TO_VIDEO in caps)
    }

    @Test
    fun capabilitiesForModelFastOnlyImage() {
        val caps = MiniMaxVideoBackend.capabilitiesForModel("MiniMax-Hailuo-2.3-Fast")
        assertFalse("Fast 不应有 t2v", VideoCapability.TEXT_TO_VIDEO in caps)
        assertTrue("Fast 应有 i2v", VideoCapability.IMAGE_TO_VIDEO in caps)
    }

    @Test
    fun capabilitiesForModelS2vIsEmptySet() {
        // S2V 既非 t2v 也非 i2v（subject_reference 驱动）
        val caps = MiniMaxVideoBackend.capabilitiesForModel("S2V-01")
        assertTrue("S2V 端点级能力应为空", caps.isEmpty())
    }

    @Test
    fun videoCapabilitiesForModelHailuoFirstFrameOnly() {
        val caps = MiniMaxVideoBackend.videoCapabilitiesForModel("MiniMax-Hailuo-2.3")
        assertTrue("Hailuo 有首帧", caps.firstFrame)
        assertFalse("Hailuo 无参考", caps.referenceImages)
    }

    @Test
    fun videoCapabilitiesForModelS2vUsesSingleFaceReference() {
        val caps = MiniMaxVideoBackend.videoCapabilitiesForModel("S2V-01")
        assertFalse("S2V 无首帧", caps.firstFrame)
        assertTrue("S2V 有参考", caps.referenceImages)
        assertEquals("S2V max=1", 1, caps.maxReferenceImages)
        assertFalse("S2V withStartFrame=false", caps.referenceImagesWithStartFrame)
    }

    // ==================== Hailuo 编码形态 ====================

    @Test
    fun buildPayloadHailuoUsesDataUriForFirstFrame() {
        val backend = newBackend("MiniMax-Hailuo-2.3")
        val firstFrame = tmpJpeg("mmff")
        val request = VideoGenerationRequest(
            prompt = "图生视频",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame,
            resolution = "768p",
            durationSeconds = 6
        )
        val compressed = listOf(CompressedRef(firstFrame, "first_frame", RefRole.FRAME))
        val body = backend.buildPayload(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        assertEquals("MiniMax-Hailuo-2.3", root.get("model").asString)
        assertEquals("duration 应 6", 6, root.get("duration").asInt)
        assertEquals("resolution 应大写", "768P", root.get("resolution").asString)
        assertTrue("应有 first_frame_image", root.has("first_frame_image"))
        val imageVal = root.get("first_frame_image").asString
        assertTrue("first_frame_image 应是 data URI，实际：$imageVal",
            imageVal.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun buildPayloadHailuoText2VideoOmitsFirstFrameImage() {
        val backend = newBackend("MiniMax-Hailuo-2.3")
        val request = VideoGenerationRequest(
            prompt = "文生视频",
            outputPath = File("/tmp/out.mp4"),
            resolution = "1080p",
            durationSeconds = 6
        )
        val body = backend.buildPayload(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject

        assertFalse("文生视频不应有 first_frame_image", root.has("first_frame_image"))
        assertEquals("1080P", root.get("resolution").asString)
    }

    // ==================== Fast 能力拒绝 ====================

    @Test
    fun buildPayloadFastRejectsText2Video() {
        // Fast 仅 i2v，无首帧的文生视频请求被能力拒绝
        val backend = newBackend("MiniMax-Hailuo-2.3-Fast")
        val request = VideoGenerationRequest(
            prompt = "文生视频",
            outputPath = File("/tmp/out.mp4"),
            resolution = "768p",
            durationSeconds = 6
        )
        assertThrows("Fast 不支持 t2v 应报错", IllegalStateException::class.java) {
            backend.buildPayload(request, emptyList())
        }
    }

    // ==================== resolution × duration 校验 ====================

    @Test
    fun buildPayloadRejectsUnsupportedResolutionDuration() {
        // 1080P 仅 6s，10s 应被拒
        val backend = newBackend("MiniMax-Hailuo-2.3")
        val firstFrame = tmpJpeg("mmff2")
        val request = VideoGenerationRequest(
            prompt = "越界",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame,
            resolution = "1080p",
            durationSeconds = 10
        )
        val compressed = listOf(CompressedRef(firstFrame, "first_frame", RefRole.FRAME))
        assertThrows("1080P 10s 不支持应报错", IllegalStateException::class.java) {
            backend.buildPayload(request, compressed)
        }
    }

    @Test
    fun buildPayloadAccepts768pWith10s() {
        val backend = newBackend("MiniMax-Hailuo-2.3")
        val firstFrame = tmpJpeg("mmff3")
        val request = VideoGenerationRequest(
            prompt = "768p 10s",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame,
            resolution = "768p",
            durationSeconds = 10
        )
        val compressed = listOf(CompressedRef(firstFrame, "first_frame", RefRole.FRAME))
        val body = backend.buildPayload(request, compressed)
        // 不抛即通过；额外校验 duration 透传
        assertEquals(10, JsonParser.parseString(body).asJsonObject.get("duration").asInt)
    }

    // ==================== S2V-01 subject_reference 编码形态 ====================

    @Test
    fun buildPayloadS2vUsesSubjectReferenceDataUri() {
        val backend = newBackend("S2V-01")
        val face = tmpJpeg("mms2v")
        val request = VideoGenerationRequest(
            prompt = "单脸参考",
            outputPath = File("/tmp/out.mp4"),
            referenceImages = listOf(face)
        )
        val compressed = listOf(CompressedRef(face, "ref_0", RefRole.ARRAY))
        val body = backend.buildPayload(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        assertEquals("S2V-01", root.get("model").asString)
        assertFalse("S2V 不应有 first_frame_image", root.has("first_frame_image"))
        assertFalse("S2V 不应有 resolution", root.has("resolution"))
        assertFalse("S2V 不应有 duration", root.has("duration"))
        assertTrue("应有 subject_reference", root.has("subject_reference"))

        val subjectRef = root.getAsJsonArray("subject_reference")
        assertEquals(1, subjectRef.size())
        val character = subjectRef[0].asJsonObject
        assertEquals("character", character.get("type").asString)
        val imageArr = character.getAsJsonArray("image")
        assertEquals("image 数组应 1 项", 1, imageArr.size())
        assertTrue(
            "image[0] 应是 data URI",
            imageArr[0].asString.startsWith("data:image/jpeg;base64,")
        )
    }

    @Test
    fun buildPayloadS2vWithoutReferenceFails() {
        // S2V-01 无参考图 fail-loud
        val backend = newBackend("S2V-01")
        val request = VideoGenerationRequest(
            prompt = "缺参考",
            outputPath = File("/tmp/out.mp4")
        )
        assertThrows(IllegalStateException::class.java) {
            backend.buildPayload(request, emptyList())
        }
    }

    // ==================== normalizeBaseUrl ====================

    @Test
    fun normalizeBaseUrlStripsV1SuffixAndReappends() {
        val backend = newBackend()
        assertEquals(
            "https://api.minimaxi.com/v1",
            backend.normalizeBaseUrl("https://api.minimaxi.com")
        )
        assertEquals(
            "应剥 /v1 后缀再补回",
            "https://api.minimaxi.com/v1",
            backend.normalizeBaseUrl("https://api.minimaxi.com/v1")
        )
        assertEquals(
            "空串回落国内站",
            "https://api.minimaxi.com/v1",
            backend.normalizeBaseUrl("")
        )
        assertEquals(
            "国际站 host 也应归一化",
            "https://api.minimax.io/v1",
            backend.normalizeBaseUrl("https://api.minimax.io/v1")
        )
    }

    // ==================== typeId / model 默认值 ====================

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals("minimax", backend.typeId)
        assertEquals("空 model 应用默认值", "MiniMax-Hailuo-2.3", backend.model)
    }
}
