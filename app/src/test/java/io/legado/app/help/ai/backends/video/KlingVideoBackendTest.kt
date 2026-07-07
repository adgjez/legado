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
 * [KlingVideoBackend] 编码形态 + 能力值 + 子路径派生 + jobId 编解码单测（P2c）。
 *
 * 核心验证：
 * - **参考图用裸 base64**（无 `data:` 前缀，无换行）——可灵 `image` / `image_tail` /
 *   `image_list[].image` 接受 URL 或纯 base64，无 data URI 前缀
 * - 5 档能力查表（[_KLING_VIDEO_CAPS]）：v2-5-turbo / v3 / v3-omni / v2-6 / video-o1
 * - 子路径派生：reference_images → multi-image2video；start_image → image2video；都无 → text2video
 * - enable_audio 门控：仅 text2video/image2video 且 caps.audioParam 时携带；v2-6 pro 档才有声
 * - jobId 编解码：`subpath:task_id:audio`（3 段）resume 时复原查询端点 + 有声决策
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class KlingVideoBackendTest {

    private fun newBackend(model: String = "kling-v2-5-turbo"): KlingVideoBackend {
        val cfg = AiVideoProviderConfig(
            name = "kling-test",
            type = AiVideoProviderConfig.TYPE_KLING,
            baseUrl = "https://api.klingai.com",
            apiKey = "test-key",
            model = model,
            submitUrl = "",
            pollUrlTemplate = ""
        )
        return KlingVideoBackend(cfg)
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

    // ==================== 能力查表（5 档 + 默认） ====================

    @Test
    fun lookupCapsKnownModelReturnsRegisteredCaps() {
        // v2-5-turbo：默认 model，文/图生视频 + 首尾帧，无音频/参考
        val turbo = KlingVideoBackend.lookupCaps("kling-v2-5-turbo")
        assertTrue("turbo t2v", turbo.textToVideo)
        assertTrue("turbo i2v", turbo.imageToVideo)
        assertTrue("turbo lastFrame", turbo.lastFrame)
        assertFalse("turbo 不应支持参考图", turbo.referenceImages)
        assertFalse("turbo 不应有声", turbo.generateAudio)
        assertFalse("turbo 不应有 audioParam", turbo.audioParam)

        // v3：旗舰，首尾帧 + 4K，无参考/无 generateAudio，但接受 audioParam
        val v3 = KlingVideoBackend.lookupCaps("kling-v3")
        assertTrue("v3 t2v", v3.textToVideo)
        assertTrue("v3 i2v", v3.imageToVideo)
        assertFalse("v3 不应支持参考图", v3.referenceImages)
        assertFalse("v3 不应 generateAudio", v3.generateAudio)
        assertTrue("v3 应有 audioParam（接受 enable_audio 字段）", v3.audioParam)

        // v3-omni：多图主体 R2V
        val omni = KlingVideoBackend.lookupCaps("kling-v3-omni")
        assertTrue("v3-omni 参考图应 true", omni.referenceImages)
        assertEquals("v3-omni 参考图上限 4", 4, omni.maxReferenceImages)
        assertTrue("v3-omni 应有 audioParam", omni.audioParam)

        // v2-6：pro 档支持视频内人声
        val v26 = KlingVideoBackend.lookupCaps("kling-v2-6")
        assertTrue("v2-6 应 generateAudio（pro 档有声）", v26.generateAudio)
        assertTrue("v2-6 应有 audioParam", v26.audioParam)

        // video-o1：图生 + 多图主体 R2V，不支持 t2v
        val o1 = KlingVideoBackend.lookupCaps("kling-video-o1")
        assertFalse("video-o1 不应支持 t2v", o1.textToVideo)
        assertTrue("video-o1 应支持 i2v", o1.imageToVideo)
        assertTrue("video-o1 应支持参考图", o1.referenceImages)
    }

    @Test
    fun lookupCapsUnknownModelFallsBackToDefault() {
        // 未登记 model 回落保守默认：t2v/i2v/lastFrame true，refs/audio false
        val unknown = KlingVideoBackend.lookupCaps("some-future-kling-v99")
        assertTrue("unknown t2v（保守默认）", unknown.textToVideo)
        assertTrue("unknown i2v（保守默认）", unknown.imageToVideo)
        assertFalse("unknown 不应支持参考图（保守默认）", unknown.referenceImages)
        assertFalse("unknown 不应有声（保守默认）", unknown.generateAudio)
    }

    @Test
    fun lookupCapsStripsVendorPrefix() {
        // 厂商前缀剥离：vendor/kling-v3-omni 或 provider:kling-v3-omni 应命中 v3-omni
        val fromSlash = KlingVideoBackend.lookupCaps("aliyun/kling-v3-omni")
        assertTrue("斜杠前缀应剥离后命中 v3-omni 参考图能力", fromSlash.referenceImages)
        assertEquals(4, fromSlash.maxReferenceImages)

        val fromColon = KlingVideoBackend.lookupCaps("provider:kling-v3-omni")
        assertTrue("冒号前缀应剥离后命中 v3-omni 参考图能力", fromColon.referenceImages)

        // 大小写归一化
        val upperCase = KlingVideoBackend.lookupCaps("Kling-V3-Omni")
        assertTrue("大小写归一化后应命中 v3-omni", upperCase.referenceImages)
    }

    @Test
    fun videoCapabilitiesForModelReflectsCapsTable() {
        // v3-omni：firstFrame 恒真；lastFrame=true；refs=true max=4；withStartFrame=false
        val omniCaps = KlingVideoBackend.videoCapabilitiesForModel("kling-v3-omni")
        assertTrue("firstFrame 恒真", omniCaps.firstFrame)
        assertTrue("v3-omni lastFrame", omniCaps.lastFrame)
        assertTrue("v3-omni referenceImages", omniCaps.referenceImages)
        assertEquals(4, omniCaps.maxReferenceImages)
        assertFalse("v3-omni withStartFrame 应 false（image_list 与 image 不共存）",
            omniCaps.referenceImagesWithStartFrame)

        // turbo（默认）：无参考图能力
        val turboCaps = KlingVideoBackend.videoCapabilitiesForModel("kling-v2-5-turbo")
        assertTrue("turbo firstFrame 恒真", turboCaps.firstFrame)
        assertFalse("turbo 不应支持参考图", turboCaps.referenceImages)
        assertEquals(0, turboCaps.maxReferenceImages)
    }

    // ==================== 裸 base64 编码形态 ====================

    @Test
    fun buildPayloadText2VideoUsesBareBase64AbsenceAndNoImage() {
        // 纯文生视频：无图字段，subpath=text2video
        val backend = newBackend("kling-v2-5-turbo")
        val request = VideoGenerationRequest(
            prompt = "测试",
            outputPath = File("/tmp/out.mp4"),
            aspectRatio = "16:9",
            durationSeconds = 5
        )
        val (subpath, body) = backend.buildPayload(request, emptyList())
        assertEquals("text2video", subpath)
        val root = JsonParser.parseString(body).asJsonObject
        assertEquals("kling-v2-5-turbo", root.get("model_name").asString)
        assertEquals("测试", root.get("prompt").asString)
        assertEquals("5", root.get("duration").asString)
        assertEquals("16:9", root.get("aspect_ratio").asString)
        // turbo 无 audioParam → 不携带 enable_audio
        assertFalse("turbo 不应有 enable_audio 字段", root.has("enable_audio"))
    }

    @Test
    fun buildPayloadImage2VideoUsesBareBase64ForStartImage() {
        // 首帧 → image 字段（裸 base64），subpath=image2video
        val backend = newBackend("kling-v2-5-turbo")
        val firstFrame = tmpJpeg("klingff")
        val request = VideoGenerationRequest(
            prompt = "首帧测试",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame
        )
        val compressed = listOf(CompressedRef(firstFrame, "first_frame", RefRole.FRAME))
        val (subpath, body) = backend.buildPayload(request, compressed)
        assertEquals("image2video", subpath)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应有 image 字段", root.has("image"))
        val imageB64 = root.get("image").asString
        assertFalse("image 应是裸 base64（无 data: 前缀），实际：$imageB64",
            imageB64.startsWith("data:"))
        assertFalse("image base64 不应含换行（NO_WRAP）",
            imageB64.contains("\n") || imageB64.contains("\r"))
    }

    @Test
    fun buildPayloadImage2VideoIncludesBareBase64ForLastFrame() {
        // 首帧 + 尾帧 → image + image_tail 都是裸 base64
        val backend = newBackend("kling-v2-5-turbo")
        val firstFrame = tmpJpeg("klingff")
        val lastFrame = tmpJpeg("klinglf")
        val request = VideoGenerationRequest(
            prompt = "首尾帧",
            outputPath = File("/tmp/out.mp4"),
            startImage = firstFrame,
            endImage = lastFrame
        )
        val compressed = listOf(
            CompressedRef(firstFrame, "first_frame", RefRole.FRAME),
            CompressedRef(lastFrame, "last_frame", RefRole.FRAME)
        )
        val (subpath, body) = backend.buildPayload(request, compressed)
        assertEquals("image2video", subpath)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应有 image 字段", root.has("image"))
        assertTrue("应有 image_tail 字段", root.has("image_tail"))
        assertFalse("image 应裸 base64", root.get("image").asString.startsWith("data:"))
        assertFalse("image_tail 应裸 base64", root.get("image_tail").asString.startsWith("data:"))
    }

    @Test
    fun buildPayloadMultiImage2VideoUsesBareBase64InImageList() {
        // reference_images → image_list:[{image:裸base64}]，subpath=multi-image2video
        // 用 v3-omni（caps.referenceImages=true）
        val backend = newBackend("kling-v3-omni")
        val ref1 = tmpJpeg("klingr1")
        val ref2 = tmpJpeg("klingr2")
        val request = VideoGenerationRequest(
            prompt = "多图主体",
            outputPath = File("/tmp/out.mp4"),
            referenceImages = listOf(ref1, ref2)
        )
        val compressed = listOf(
            CompressedRef(ref1, "ref_0", RefRole.ARRAY),
            CompressedRef(ref2, "ref_1", RefRole.ARRAY)
        )
        val (subpath, body) = backend.buildPayload(request, compressed)
        assertEquals("multi-image2video", subpath)
        val root = JsonParser.parseString(body).asJsonObject

        assertTrue("应有 image_list 数组", root.has("image_list"))
        val arr = root.getAsJsonArray("image_list")
        assertEquals(2, arr.size())
        for (i in 0 until arr.size()) {
            val obj = arr[i].asJsonObject
            assertTrue("image_list[$i] 应有 image 字段", obj.has("image"))
            val b64 = obj.get("image").asString
            assertFalse("image_list[$i].image 应是裸 base64（无 data: 前缀），实际：$b64",
                b64.startsWith("data:"))
            assertFalse("image_list[$i].image base64 不应含换行",
                b64.contains("\n") || b64.contains("\r"))
        }
        // multi-image2video 不带 enable_audio（原生 schema 不含）
        assertFalse("multi-image2video 不应有 enable_audio", root.has("enable_audio"))
    }

    // ==================== 子路径派生防御 ====================

    @Test(expected = IllegalStateException::class)
    fun buildPayloadReferenceImagesOnModelWithoutCapsFails(): Unit = try {
        // turbo 不支持参考图，却带 reference_images → 应 fail-loud
        val backend = newBackend("kling-v2-5-turbo")
        val ref1 = tmpJpeg("klingr1")
        val request = VideoGenerationRequest(
            prompt = "错配",
            outputPath = File("/tmp/out.mp4"),
            referenceImages = listOf(ref1)
        )
        val compressed = listOf(CompressedRef(ref1, "ref_0", RefRole.ARRAY))
        backend.buildPayload(request, compressed)
        org.junit.Assert.fail("turbo 不支持参考图，应抛 IllegalStateException")
    } catch (e: IllegalStateException) {
        // 验证错误信息提到 model 与不支持参考图
        assertTrue(e.message!!.contains("不支持多图参考"))
        throw e
    }

    @Test(expected = IllegalStateException::class)
    fun buildPayloadReferenceImagesExceedingMaxFails(): Unit = try {
        // v3-omni max=4，塞 5 张应失败
        val backend = newBackend("kling-v3-omni")
        val refs = (1..5).map { tmpJpeg("klingr$it") }
        val request = VideoGenerationRequest(
            prompt = "超上限",
            outputPath = File("/tmp/out.mp4"),
            referenceImages = refs
        )
        val compressed = refs.mapIndexed { i, f -> CompressedRef(f, "ref_$i", RefRole.ARRAY) }
        backend.buildPayload(request, compressed)
        org.junit.Assert.fail("超上限参考图应抛 IllegalStateException")
    } catch (e: IllegalStateException) {
        assertTrue(e.message!!.contains("超上限"))
        throw e
    }

    @Test(expected = IllegalStateException::class)
    fun buildPayloadText2VideoOnModelWithoutT2VCapsFails(): Unit = try {
        // video-o1 不支持 t2v，纯文本请求应 fail-loud
        val backend = newBackend("kling-video-o1")
        val request = VideoGenerationRequest(
            prompt = "纯文本",
            outputPath = File("/tmp/out.mp4")
        )
        backend.buildPayload(request, emptyList())
        org.junit.Assert.fail("video-o1 不支持 t2v，应抛 IllegalStateException")
    } catch (e: IllegalStateException) {
        assertTrue(e.message!!.contains("不支持文生视频"))
        throw e
    }

    // ==================== resolveMode / effectiveAudio 门控 ====================

    @Test
    fun resolveModeReturns4kFor4kResolution() {
        val backend = newBackend("kling-v3")
        val request = VideoGenerationRequest(
            prompt = "4k", outputPath = File("/tmp/out.mp4"),
            resolution = "4k"
        )
        assertEquals("4k", backend.resolveMode(request))
    }

    @Test
    fun resolveModeReturnsProForProServiceTier() {
        val backend = newBackend("kling-v2-6")
        val request = VideoGenerationRequest(
            prompt = "pro", outputPath = File("/tmp/out.mp4"),
            serviceTier = "pro"
        )
        assertEquals("pro", backend.resolveMode(request))
    }

    @Test
    fun resolveModeReturnsStdForDefaultServiceTier() {
        val backend = newBackend("kling-v2-5-turbo")
        val request = VideoGenerationRequest(
            prompt = "std", outputPath = File("/tmp/out.mp4")
        )
        assertEquals("std", backend.resolveMode(request))
    }

    @Test
    fun effectiveAudioOnlyTrueForV26ProWithRequestAudio() {
        // v2-6 + pro + 请求 generateAudio=true → true
        val v26Pro = newBackend("kling-v2-6")
        val reqAudio = VideoGenerationRequest(
            prompt = "有声", outputPath = File("/tmp/out.mp4"),
            generateAudio = true, serviceTier = "pro"
        )
        assertTrue("v2-6 pro + 请求有声 → 应 true", v26Pro.effectiveAudio(reqAudio))

        // v2-6 + std → false（仅 pro 档有声）
        val reqStd = reqAudio.copy(serviceTier = "std")
        assertFalse("v2-6 std 即使请求有声也 false", v26Pro.effectiveAudio(reqStd))

        // v3 + pro + 请求有声 → false（v3 不具备 generateAudio 能力位）
        val v3Pro = newBackend("kling-v3")
        assertFalse("v3 即使 pro 档也无 generateAudio 能力", v3Pro.effectiveAudio(reqAudio))

        // v2-6 + pro + 请求 generateAudio=false → false
        val reqNoAudio = reqAudio.copy(generateAudio = false)
        assertFalse("请求 generateAudio=false → false", v26Pro.effectiveAudio(reqNoAudio))
    }

    @Test
    fun buildPayloadV26ProCarriesEnableAudioTrue() {
        val backend = newBackend("kling-v2-6")
        val request = VideoGenerationRequest(
            prompt = "pro 有声", outputPath = File("/tmp/out.mp4"),
            generateAudio = true, serviceTier = "pro"
        )
        val (_, body) = backend.buildPayload(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject
        assertTrue("v2-6 pro + 请求有声应有 enable_audio", root.has("enable_audio"))
        assertEquals(true, root.get("enable_audio").asBoolean)
    }

    @Test
    fun buildPayloadV26StdCarriesEnableAudioFalse() {
        val backend = newBackend("kling-v2-6")
        val request = VideoGenerationRequest(
            prompt = "std 无声", outputPath = File("/tmp/out.mp4"),
            generateAudio = true, serviceTier = "std"
        )
        val (_, body) = backend.buildPayload(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject
        // v2-6 有 audioParam → 字段恒携带，但 std 档值为 false
        assertTrue("v2-6 应有 enable_audio 字段（audioParam=true）", root.has("enable_audio"))
        assertEquals(false, root.get("enable_audio").asBoolean)
    }

    @Test
    fun buildPayloadV3HasAudioParamButValueFalse() {
        // v3 有 audioParam=true（接受字段）但 generateAudio=false（无能力位）→ 字段携带值 false
        val backend = newBackend("kling-v3")
        val request = VideoGenerationRequest(
            prompt = "v3", outputPath = File("/tmp/out.mp4"),
            generateAudio = true, serviceTier = "pro"
        )
        val (_, body) = backend.buildPayload(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject
        assertTrue("v3 应有 enable_audio 字段（audioParam=true）", root.has("enable_audio"))
        assertEquals("v3 无 generateAudio 能力位 → 值 false", false, root.get("enable_audio").asBoolean)
    }

    // ==================== jobId 编解码 ====================

    @Test
    fun encodeJobIdProducesThreeSegmentForm() {
        val id = KlingVideoBackend.encodeJobId("image2video", "task-abc", true)
        assertEquals("image2video:task-abc:1", id)
        val idNoAudio = KlingVideoBackend.encodeJobId("text2video", "task-xyz", false)
        assertEquals("text2video:task-xyz:0", idNoAudio)
    }

    @Test
    fun decodeJobIdThreeSegmentFormParsesAllFields() {
        val (subpath, taskId, audio) = KlingVideoBackend.decodeJobId("image2video:task-abc:1")
        assertEquals("image2video", subpath)
        assertEquals("task-abc", taskId)
        assertEquals(true, audio)
    }

    @Test
    fun decodeJobIdTwoSegmentFormReturnsNullAudio() {
        // 旧格式 subpath:task_id（2 段，audio=null）
        val (subpath, taskId, audio) = KlingVideoBackend.decodeJobId("text2video:task-xyz")
        assertEquals("text2video", subpath)
        assertEquals("task-xyz", taskId)
        assertEquals(null, audio)
    }

    @Test
    fun decodeJobIdUnknownFallsBackToText2Video() {
        // 无已知前缀回落 text2video、整串作 task_id
        val (subpath, taskId, audio) = KlingVideoBackend.decodeJobId("plain-task-id-no-colon")
        assertEquals("text2video", subpath)
        assertEquals("plain-task-id-no-colon", taskId)
        assertEquals(null, audio)
    }

    @Test
    fun encodeDecodeJobIdRoundTrip() {
        // 往返一致性（3 段形式）
        val original = Triple("multi-image2video", "task-12345", true)
        val encoded = KlingVideoBackend.encodeJobId(original.first, original.second, original.third)
        val decoded = KlingVideoBackend.decodeJobId(encoded)
        assertEquals(original.first, decoded.first)
        assertEquals(original.second, decoded.second)
        assertEquals(original.third, decoded.third)
    }

    // ==================== typeId / model 默认值 ====================

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals("kling", backend.typeId)
        assertEquals("空 model 应用默认值", "kling-v2-5-turbo", backend.model)
    }

    @Test
    fun capabilitiesReflectsCapsTable() {
        // v3-omni 应有 TEXT_TO_VIDEO + IMAGE_TO_VIDEO（无 GENERATE_AUDIO）
        val omni = newBackend("kling-v3-omni")
        assertTrue(omni.capabilities.toString(), omni.capabilities.any {
            it == io.legado.app.help.ai.backends.VideoCapability.TEXT_TO_VIDEO
        })
        assertTrue(omni.capabilities.any {
            it == io.legado.app.help.ai.backends.VideoCapability.IMAGE_TO_VIDEO
        })

        // v2-6 应有 GENERATE_AUDIO
        val v26 = newBackend("kling-v2-6")
        assertTrue("v2-6 应有 GENERATE_AUDIO 能力",
            v26.capabilities.any { it == io.legado.app.help.ai.backends.VideoCapability.GENERATE_AUDIO })
    }
}
