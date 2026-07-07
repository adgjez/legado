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
 * [DashScopeVideoBackend] 编码形态 + 能力查表 + normalizeBaseUrl 单测（P2d）。
 *
 * 核心验证：
 * - **6 档能力查表**（happyhorse/wan2.7 × t2v/i2v/r2v）+ 子串容忍 + 未知回落默认
 * - **参考图用 data URI**（`data:image/jpeg;base64,...`）——media[].url
 * - media 类型：`first_frame`（首帧）/ `reference_image`（r2v 参考）
 * - ratio 仅无首帧时下传（带首帧按首帧定宽高比，HappyHorse 会拒）
 * - wan2.7-r2v 首帧 + 参考叠加（reference_images_with_start_frame=true）
 * - fail-loud：r2v 未提供参考图即中止
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DashScopeVideoBackendTest {

    private fun newBackend(model: String = "happyhorse-1.0-i2v"): DashScopeVideoBackend {
        val cfg = AiVideoProviderConfig(
            name = "dashscope-test",
            type = AiVideoProviderConfig.TYPE_DASHSCOPE,
            baseUrl = "https://dashscope.aliyuncs.com",
            apiKey = "test-key",
            model = model,
            submitUrl = "",
            pollUrlTemplate = ""
        )
        return DashScopeVideoBackend(cfg)
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

    private fun mediaArray(body: String) = JsonParser.parseString(body).asJsonObject
        .getAsJsonObject("input").getAsJsonArray("media")

    // ==================== 能力查表（6 档） ====================

    @Test
    fun lookupProfileKnownModelsReturnsRegisteredProfiles() {
        // 6 档全验证 videoCaps
        val t2v = DashScopeVideoBackend.lookupProfile("happyhorse-1.0-t2v").videoCaps
        assertFalse("happyhorse t2v 无首帧", t2v.firstFrame)
        assertFalse("happyhorse t2v 无参考", t2v.referenceImages)

        val i2v = DashScopeVideoBackend.lookupProfile("happyhorse-1.0-i2v").videoCaps
        assertTrue("happyhorse i2v 有首帧", i2v.firstFrame)
        assertFalse("happyhorse i2v 无参考", i2v.referenceImages)

        val r2v = DashScopeVideoBackend.lookupProfile("happyhorse-1.0-r2v").videoCaps
        assertFalse("happyhorse r2v 无首帧", r2v.firstFrame)
        assertTrue("happyhorse r2v 有参考", r2v.referenceImages)
        assertEquals("happyhorse r2v max=9", 9, r2v.maxReferenceImages)

        val wanT2v = DashScopeVideoBackend.lookupProfile("wan2.7-t2v").videoCaps
        assertFalse("wan t2v 无首帧", wanT2v.firstFrame)

        val wanI2v = DashScopeVideoBackend.lookupProfile("wan2.7-i2v").videoCaps
        assertTrue("wan i2v 有首帧", wanI2v.firstFrame)

        val wanR2v = DashScopeVideoBackend.lookupProfile("wan2.7-r2v").videoCaps
        assertTrue("wan r2v 有首帧", wanR2v.firstFrame)
        assertTrue("wan r2v 有参考", wanR2v.referenceImages)
        assertEquals("wan r2v max=5", 5, wanR2v.maxReferenceImages)
        assertTrue("wan r2v withStartFrame=true", wanR2v.referenceImagesWithStartFrame)
    }

    @Test
    fun lookupProfileUnknownModelFallsBackToDefault() {
        val profile = DashScopeVideoBackend.lookupProfile("some-proxy-custom-name")
        assertTrue("未知 model 默认有首帧", profile.videoCaps.firstFrame)
        assertTrue("未知 model 默认有 t2v", VideoCapability.TEXT_TO_VIDEO in profile.capabilities)
    }

    @Test
    fun lookupProfileToleratesVendorPrefixSuffix() {
        // 子串容忍：代理中转的装饰名不应丢掉 r2v 的参考能力
        val decorated = DashScopeVideoBackend.lookupProfile("proxy/happyhorse-1.0-r2v").videoCaps
        assertTrue("装饰名应识别 r2v 参考", decorated.referenceImages)
        assertEquals(9, decorated.maxReferenceImages)

        val upperCased = DashScopeVideoBackend.lookupProfile("WAN2.7-R2V-0715").videoCaps
        assertTrue("大小写不敏感应识别 wan r2v", upperCased.referenceImages)
        assertTrue("wan r2v 应有首帧", upperCased.firstFrame)
    }

    @Test
    fun videoCapabilitiesForModelReflectsProfilesTable() {
        assertEquals(
            DashScopeVideoBackend.lookupProfile("happyhorse-1.0-r2v").videoCaps,
            DashScopeVideoBackend.videoCapabilitiesForModel("happyhorse-1.0-r2v")
        )
    }

    // ==================== data URI 编码形态 ====================

    @Test
    fun buildPayloadText2VideoHasNoMediaAndCarriesRatio() {
        val backend = newBackend("happyhorse-1.0-t2v")
        val request = VideoGenerationRequest(
            prompt = "文生视频",
            outputPath = File("/tmp/out.mp4"),
            aspectRatio = "16:9"
        )
        val body = backend.buildPayload(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject

        assertFalse("t2v 不应有 media", root.getAsJsonObject("input").has("media"))
        val parameters = root.getAsJsonObject("parameters")
        assertTrue("无首帧应有 ratio", parameters.has("ratio"))
        assertEquals("16:9", parameters.get("ratio").asString)
        assertEquals("watermark 应 false", false, parameters.get("watermark").asBoolean)
    }

    @Test
    fun buildPayloadImage2VideoUsesDataUriForFirstFrame() {
        val backend = newBackend("happyhorse-1.0-i2v")
        val firstFrame = tmpJpeg("dsff")
        val request = VideoGenerationRequest(
            prompt = "图生视频",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame,
            aspectRatio = "9:16"
        )
        val compressed = listOf(CompressedRef(firstFrame, "first_frame", RefRole.FRAME))
        val body = backend.buildPayload(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        val media = mediaArray(body)
        assertEquals("应有 1 个 media", 1, media.size())
        val entry = media[0].asJsonObject
        assertEquals("first_frame", entry.get("type").asString)
        val url = entry.get("url").asString
        assertTrue("url 应是 data URI，实际：$url", url.startsWith("data:image/jpeg;base64,"))

        // 带首帧不应下传 ratio（HappyHorse 会拒）
        assertFalse("带首帧不应有 ratio", root.getAsJsonObject("parameters").has("ratio"))
    }

    @Test
    fun buildPayloadReference2VideoUsesDataUriForEachReference() {
        val backend = newBackend("happyhorse-1.0-r2v")
        val ref1 = tmpJpeg("dsr1")
        val ref2 = tmpJpeg("dsr2")
        val request = VideoGenerationRequest(
            prompt = "参考生视频",
            outputPath = File("/tmp/out.mp4"),
            referenceImages = listOf(ref1, ref2)
        )
        val compressed = listOf(
            CompressedRef(ref1, "ref_0", RefRole.ARRAY),
            CompressedRef(ref2, "ref_1", RefRole.ARRAY)
        )
        val body = backend.buildPayload(request, compressed)
        val media = mediaArray(body)

        assertEquals("应有 2 个 reference_image", 2, media.size())
        media.forEach { entry ->
            assertEquals("reference_image", entry.asJsonObject.get("type").asString)
            assertTrue(
                "url 应是 data URI",
                entry.asJsonObject.get("url").asString.startsWith("data:image/jpeg;base64,")
            )
        }
    }

    @Test
    fun buildPayloadWanR2vCoexistsFirstFrameAndReferences() {
        // wan2.7-r2v：首帧 + 参考叠加（reference_images_with_start_frame=true）
        val backend = newBackend("wan2.7-r2v")
        val firstFrame = tmpJpeg("wanff")
        val ref = tmpJpeg("wanref")
        val request = VideoGenerationRequest(
            prompt = "首帧+参考",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame,
            referenceImages = listOf(ref)
        )
        val compressed = listOf(
            CompressedRef(firstFrame, "first_frame", RefRole.FRAME),
            CompressedRef(ref, "ref_0", RefRole.ARRAY)
        )
        val body = backend.buildPayload(request, compressed)
        val media = mediaArray(body)

        assertEquals("应有 first_frame + reference_image 共 2 个", 2, media.size())
        assertEquals("first_frame", media[0].asJsonObject.get("type").asString)
        assertEquals("reference_image", media[1].asJsonObject.get("type").asString)
        // 带首帧不应有 ratio
        assertFalse(
            "wan r2v 带首帧不应有 ratio",
            JsonParser.parseString(body).asJsonObject.getAsJsonObject("parameters").has("ratio")
        )
    }

    @Test
    fun buildPayloadReference2VideoWithoutRefsFails() {
        // r2v 必须有参考图——fail-loud，不静默退化为无参考生成
        val backend = newBackend("happyhorse-1.0-r2v")
        val request = VideoGenerationRequest(
            prompt = "缺参考",
            outputPath = File("/tmp/out.mp4")
        )
        assertThrows(IllegalStateException::class.java) {
            backend.buildPayload(request, emptyList())
        }
    }

    @Test
    fun buildPayloadCarriesResolutionDurationAndSeed() {
        val backend = newBackend("happyhorse-1.0-t2v")
        val request = VideoGenerationRequest(
            prompt = "参数校验",
            outputPath = File("/tmp/out.mp4"),
            resolution = "1080p",
            durationSeconds = 5,
            seed = 99L
        )
        val body = backend.buildPayload(request, emptyList())
        val parameters = JsonParser.parseString(body).asJsonObject.getAsJsonObject("parameters")
        assertEquals("resolution 应大写", "1080P", parameters.get("resolution").asString)
        assertEquals(5, parameters.get("duration").asInt)
        assertEquals(99L, parameters.get("seed").asLong)
    }

    // ==================== normalizeBaseUrl ====================

    @Test
    fun normalizeBaseUrlStripsKnownSuffixesAndAppendsApiV1() {
        val backend = newBackend()
        assertEquals(
            "https://dashscope.aliyuncs.com/api/v1",
            backend.normalizeBaseUrl("https://dashscope.aliyuncs.com")
        )
        assertEquals(
            "应剥 /api/v1 后缀再补回",
            "https://dashscope.aliyuncs.com/api/v1",
            backend.normalizeBaseUrl("https://dashscope.aliyuncs.com/api/v1")
        )
        assertEquals(
            "应剥 /compatible-mode/v1 后缀再补 /api/v1",
            "https://dashscope.aliyuncs.com/api/v1",
            backend.normalizeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
        )
        assertEquals(
            "空串回落北京 host",
            "https://dashscope.aliyuncs.com/api/v1",
            backend.normalizeBaseUrl("")
        )
    }

    // ==================== typeId / model 默认值 ====================

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals("dashscope", backend.typeId)
        assertEquals("空 model 应用默认值", "happyhorse-1.0-i2v", backend.model)
    }
}
