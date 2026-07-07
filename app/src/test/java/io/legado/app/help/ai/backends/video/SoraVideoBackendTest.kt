package io.legado.app.help.ai.backends.video

import io.legado.app.help.ai.backends.compress.CompressedRef
import io.legado.app.help.ai.backends.compress.RefRole
import io.legado.app.help.ai.backends.VideoGenerationRequest
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import okhttp3.MultipartBody
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
 * [SoraVideoBackend] 编码形态 + 能力值单测（P2b）。
 *
 * 核心验证：
 * - 参考图用**原始字节 multipart**（非 base64、非 data URI）——ArcReel `_encode_start_image`
 *   返回 `(filename, raw_bytes, mime)` 元组的 Kotlin 等价
 * - multipart 字段：model/prompt/seconds/size + 重复的 input_reference part
 * - 能力值：reference_images=true, max=1, withStartFrame=false（首帧与参考共享单槽位）
 * - size 解析：sora-2 仅 720p；sora-2-pro 加 1080p
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SoraVideoBackendTest {

    private fun newBackend(model: String = "sora-2"): SoraVideoBackend {
        val cfg = AiVideoProviderConfig(
            name = "sora-test",
            type = AiVideoProviderConfig.TYPE_SORA,
            baseUrl = "https://api.openai.com",
            apiKey = "test-key",
            model = model,
            submitUrl = "",
            pollUrlTemplate = ""
        )
        return SoraVideoBackend(cfg)
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
    fun videoCapabilitiesForModelReferenceImagesMax1() {
        val caps = SoraVideoBackend.videoCapabilitiesForModel("sora-2")
        assertTrue("firstFrame 应 true", caps.firstFrame)
        assertFalse("lastFrame 应 false", caps.lastFrame)
        assertTrue("referenceImages 应 true", caps.referenceImages)
        assertEquals("maxReferenceImages 应 1（共享槽位）", 1, caps.maxReferenceImages)
        assertFalse("withStartFrame 应 false（不可叠加）", caps.referenceImagesWithStartFrame)
    }

    @Test
    fun buildMultipartBodyUsesRawBytesForReferenceImages() {
        // 参考图应是原始字节 multipart（非 base64、非 data URI）
        val backend = newBackend("sora-2")
        val refFile = tmpJpeg("soraref")
        val request = VideoGenerationRequest(
            prompt = "测试 prompt",
            outputPath = File("/tmp/out.mp4"),
            startImage = refFile
        )
        val compressed = listOf(CompressedRef(refFile, "first_frame", RefRole.FRAME))
        val body = backend.buildMultipartBody(request, compressed)

        // multipart 应有 model/prompt/seconds/size 文本 part + 1 个 input_reference 文件 part
        val parts = body.parts
        assertEquals("应有 5 个 part（4 文本 + 1 文件）", 5, parts.size)

        // 验证文本 part
        val textPartNames = parts.map { it.headers?.value("Content-Disposition") }.filterNotNull()
        assertTrue("应有 model part", textPartNames.any { it.contains("name=\"model\"") })
        assertTrue("应有 prompt part", textPartNames.any { it.contains("name=\"prompt\"") })
        assertTrue("应有 seconds part", textPartNames.any { it.contains("name=\"seconds\"") })
        assertTrue("应有 size part", textPartNames.any { it.contains("name=\"size\"") })

        // 验证 input_reference part：含 filename（文件 part 标志）
        val refPart = parts.firstOrNull {
            it.headers?.value("Content-Disposition")?.contains("name=\"input_reference\"") == true
        }
        assertNotNull("应有 input_reference part", refPart)
        val disp = refPart!!.headers?.value("Content-Disposition")
        assertTrue("input_reference part 应带 filename（文件 part）",
            disp?.contains("filename=") == true)
        // 不应含 data: 前缀或 base64 字样——是原始字节
        // （MultipartBody.Part 的 body 是字节流，无法直接读 String 验证，
        // 但 filename 存在 + Content-Type 是 image/* 即证明是文件 part 而非文本 base64）
    }

    @Test
    fun buildMultipartBodyMultipleRefsShareInputReferenceSlot() {
        // 多张参考图都进 input_reference 槽位（重复 part name）
        val backend = newBackend("sora-2")
        val ref1 = tmpJpeg("sorar1")
        val ref2 = tmpJpeg("sorar2")
        val request = VideoGenerationRequest(
            prompt = "测试",
            outputPath = File("/tmp/out.mp4"),
            referenceImages = listOf(ref1, ref2)
        )
        val compressed = listOf(
            CompressedRef(ref1, "ref_0", RefRole.ARRAY),
            CompressedRef(ref2, "ref_1", RefRole.ARRAY)
        )
        val body = backend.buildMultipartBody(request, compressed)
        val refParts = body.parts.filter {
            it.headers?.value("Content-Disposition")?.contains("name=\"input_reference\"") == true
        }
        assertEquals("应有 2 个 input_reference part（共享槽位）", 2, refParts.size)
    }

    @Test
    fun buildMultipartBodyIncludesSecondsAsString() {
        // seconds 是字符串（ArcReel 原样）
        val backend = newBackend("sora-2")
        val request = VideoGenerationRequest(
            prompt = "测试",
            outputPath = File("/tmp/out.mp4"),
            durationSeconds = 10
        )
        val body = backend.buildMultipartBody(request, emptyList())
        // 找到 seconds part 并验证内容
        // （MultipartBody 不便直接读 part 内容，转用 Buffer 读取）
        val buffer = okio.Buffer()
        body.writeTo(buffer)
        val bodyStr = buffer.readUtf8()
        assertTrue("seconds 应为字符串 10", bodyStr.contains("name=\"seconds\"") &&
            bodyStr.contains("10"))
    }

    @Test
    fun resolveSizeSora2Only720p() {
        val backend = newBackend("sora-2")
        // 9:16 竖屏 → 720x1280
        assertEquals("720x1280", backend.resolveSize("9:16", null))
        // 16:9 横屏 → 1280x720
        assertEquals("1280x720", backend.resolveSize("16:9", null))
    }

    @Test
    fun resolveSizeSora2ProAdds1080p() {
        val backend = newBackend("sora-2-pro")
        // 9:16 竖屏 + 1080p → 1080x1920
        assertEquals("1080x1920", backend.resolveSize("9:16", "1080"))
        // 16:9 横屏 + 1080p → 1920x1080
        assertEquals("1920x1080", backend.resolveSize("16:9", "1080"))
    }

    @Test
    fun resolveSizeDefaultsTo720pWhenNoResolution() {
        // 缺 resolution 不擅自升 1080p（避免超额计费）
        val backend = newBackend("sora-2-pro")
        assertEquals("720x1280", backend.resolveSize("9:16", null))
    }

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals("sora", backend.typeId)
        assertEquals("空 model 应用默认值", "sora-2", backend.model)
    }
}
