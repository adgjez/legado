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
 * [ArkVideoBackend] 编码形态 + 能力值单测（P2a）。
 *
 * 核心验证：
 * - 参考图用 data URI（`data:image/jpeg;base64,...`）——不含换行（NO_WRAP）
 * - Seedance 2.0 能力值：lastFrame=true, referenceImages=true, max=9
 * - Seedance 1.5 能力值：默认（firstFrame=true）
 * - 请求体结构：content[]{type:text/image_url, image_url:{url}, role}
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ArkVideoBackendTest {

    private fun newBackend(model: String = "doubao-seedance-2-0"): ArkVideoBackend {
        val cfg = AiVideoProviderConfig(
            name = "ark-test",
            type = AiVideoProviderConfig.TYPE_ARK,
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
            apiKey = "test-key",
            model = model,
            submitUrl = "",
            pollUrlTemplate = ""
        )
        return ArkVideoBackend(cfg)
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
    fun videoCapabilitiesForSeedance2() {
        val caps = ArkVideoBackend.videoCapabilitiesForModel("doubao-seedance-2-0")
        assertTrue("firstFrame 应 true", caps.firstFrame)
        assertTrue("lastFrame 应 true（Seedance 2）", caps.lastFrame)
        assertTrue("referenceImages 应 true", caps.referenceImages)
        assertEquals("maxReferenceImages 应 9", 9, caps.maxReferenceImages)
        assertFalse("withStartFrame 应 false", caps.referenceImagesWithStartFrame)
    }

    @Test
    fun videoCapabilitiesForSeedance15() {
        val caps = ArkVideoBackend.videoCapabilitiesForModel("doubao-seedance-1-5")
        assertTrue("firstFrame 应 true", caps.firstFrame)
        assertFalse("lastFrame 应 false（1.5）", caps.lastFrame)
        assertFalse("referenceImages 应 false（1.5）", caps.referenceImages)
        assertEquals("maxReferenceImages 应 0（1.5）", 0, caps.maxReferenceImages)
    }

    @Test
    fun buildSubmitBodyUsesDataUriForReferenceImages() {
        val backend = newBackend("doubao-seedance-2-0")
        val refFile = tmpJpeg("arkref")
        val request = VideoGenerationRequest(
            prompt = "测试 prompt",
            outputPath = File("/tmp/out.mp4"),
            startImage = refFile
        )
        val compressed = listOf(CompressedRef(refFile, "first_frame", RefRole.FRAME))
        val body = backend.buildSubmitBody(request, compressed)
        val root = JsonParser.parseString(body).asJsonObject

        // content 数组应有 2 项：text + image_url
        val content = root.getAsJsonArray("content")
        assertEquals("content 应有 2 项", 2, content.size())
        assertEquals("第 0 项 type=text", "text", content[0].asJsonObject.get("type").asString)

        val imgObj = content[1].asJsonObject
        assertEquals("第 1 项 type=image_url", "image_url", imgObj.get("type").asString)
        val url = imgObj.getAsJsonObject("image_url").get("url").asString
        assertTrue("参考图应是 data URI（含 data: 前缀）：$url",
            url.startsWith("data:image/jpeg;base64,"))
        // NO_WRAP：base64 部分不应含换行
        val b64 = url.substringAfter("base64,")
        assertFalse("base64 不应含换行（NO_WRAP）", b64.contains("\n") || b64.contains("\r"))
    }

    @Test
    fun buildSubmitBodyIncludesModelAndPrompt() {
        val backend = newBackend("doubao-seedance-2-0")
        val request = VideoGenerationRequest(
            prompt = "hello world",
            outputPath = File("/tmp/out.mp4")
        )
        val body = backend.buildSubmitBody(request, emptyList())
        val root = JsonParser.parseString(body).asJsonObject
        assertEquals("doubao-seedance-2-0", root.get("model").asString)
        // content 至少有 text 项
        val content = root.getAsJsonArray("content")
        assertEquals("text", content[0].asJsonObject.get("type").asString)
        assertEquals("hello world", content[0].asJsonObject.get("text").asString)
    }

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals("ark", backend.typeId)
        assertEquals("空 model 应用默认值", "doubao-seedance-2-0", backend.model)
    }
}
