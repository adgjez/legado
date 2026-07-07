package io.legado.app.help.ai.backends.image

import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.ImageCapability
import io.legado.app.help.ai.backends.ImageGenerationRequest
import io.legado.app.help.ai.backends.ReferenceImage
import io.legado.app.ui.main.ai.AiImageProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [GeminiImageBackend] 单测——验证 REST 兜底 payload 形态、label 解析、endpoint 解析。
 *
 * 不发真实 HTTP；只测纯函数 [buildPayload]、[resolveLabel]、[extractNameFromPath]、
 * [normalizeBaseUrl]、[resolveEndpoint]。
 *
 * 关键纪律：
 * - 参考图用 `inline_data.data` base64（SDK Image 对象的 REST 等价）
 * - contents=[label?, image, ..., prompt]（label 优先 ReferenceImage.label，否则文件名推断）
 * - 跳过 scene_/storyboard_/output_ 前缀的文件名作为 label
 * - generationConfig 含 responseModalities=["IMAGE"] + imageConfig={aspectRatio, image_size?}
 *
 * 用 Robolectric：I2I 测试调 [buildPayload] 内部走 [ImageCodec.toBareBase64] →
 * `android.util.Base64.encodeToString`，纯 JUnit 不可用，需 Robolectric 提供 Android 桩。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class GeminiImageBackendTest {

    private fun backend(model: String = "", baseUrl: String = ""): GeminiImageBackend =
        GeminiImageBackend(
            AiImageProviderConfig(
                name = "gemini-test",
                type = AiImageProviderConfig.TYPE_GEMINI,
                baseUrl = baseUrl,
                apiKey = "k",
                model = model
            )
        )

    private fun tmpJpeg(name: String): File {
        // 最小有效 JPEG
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            0, 1, 0, 0, 1, 0, 1, 0, 0,
            0xFF.toByte(), 0xD9.toByte()
        )
        return File.createTempFile(name, ".jpg").apply { writeBytes(bytes); deleteOnExit() }
    }

    /**
     * 创建文件名 stem 恰好为 [stem] 的临时 JPEG（[File.createTempFile] 会追加随机数字，
     * 导致 extractNameFromPath 得到 `stem<random>` 而非 `stem`，故需自建临时目录 + 精确文件名）。
     */
    private fun tmpJpegExactStem(stem: String): File {
        val dir = kotlin.io.path.createTempDirectory(prefix = "gemini-i2i-").toFile()
        val f = File(dir, "$stem.jpg")
        f.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0xFF.toByte(), 0xD9.toByte()))
        f.deleteOnExit()
        dir.deleteOnExit()
        return f
    }

    @Test
    fun capabilitiesSupportsTextAndImage() {
        val caps = backend().capabilities
        assertTrue(caps.contains(ImageCapability.TEXT_TO_IMAGE))
        assertTrue(caps.contains(ImageCapability.IMAGE_TO_IMAGE))
    }

    @Test
    fun typeIdAndModelDefaults() {
        val b = backend()
        assertEquals(AiImageProviderConfig.TYPE_GEMINI, b.typeId)
        assertEquals("gemini-3.1-flash-image-preview", b.model)
    }

    @Test
    fun typeIdAndModelCustom() {
        val b = backend(model = "gemini-3.0-pro-image")
        assertEquals("gemini-3.0-pro-image", b.model)
    }

    // ---- buildPayload T2I ----

    @Test
    fun buildPayloadText2ImageHasOnlyPromptPart() {
        // T2I 无参考图：contents[0].parts 只有 prompt 的 {text} part
        val request = ImageGenerationRequest(
            prompt = "一只橘猫",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16"
        )
        val payload = backend().buildPayload(request)
        val root = JsonParser.parseString(payload).asJsonObject
        // contents[0] 直接是 {parts:[...]}（buildPayload 构造形态）
        val parts = root.getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
        assertEquals(1, parts.size())
        assertEquals("一只橘猫", parts[0].asJsonObject.get("text").asString)
    }

    @Test
    fun buildPayloadText2ImageHasGenerationConfig() {
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "16:9",
            imageSize = "2K"
        )
        val payload = backend().buildPayload(request)
        val root = JsonParser.parseString(payload).asJsonObject
        val genConfig = root.getAsJsonObject("generationConfig")
        val modalities = genConfig.getAsJsonArray("responseModalities")
        assertEquals(1, modalities.size())
        assertEquals("IMAGE", modalities[0].asString)
        val imageConfig = genConfig.getAsJsonObject("imageConfig")
        assertEquals("16:9", imageConfig.get("aspectRatio").asString)
        assertEquals("2K", imageConfig.get("imageSize").asString)
    }

    @Test
    fun buildPayloadText2ImageOmitsImageSizeWhenNull() {
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "1:1",
            imageSize = null
        )
        val payload = backend().buildPayload(request)
        val root = JsonParser.parseString(payload).asJsonObject
        val imageConfig = root.getAsJsonObject("generationConfig").getAsJsonObject("imageConfig")
        assertEquals("1:1", imageConfig.get("aspectRatio").asString)
        assertFalse(imageConfig.has("imageSize"))
    }

    // ---- buildPayload I2I（带参考图）----

    @Test
    fun buildPayloadImage2ImageUsesInlineDataForRefs() {
        // I2I：每张参考图 → {inline_data:{mime_type, data}} part
        val ref1 = tmpJpeg("char1")
        val ref2 = tmpJpeg("char2")
        val request = ImageGenerationRequest(
            prompt = "保持构图",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(
                ReferenceImage(ref1.absolutePath, "Alice"),
                ReferenceImage(ref2.absolutePath, "Bob")
            ),
            aspectRatio = "9:16"
        )
        val payload = backend().buildPayload(request)
        val root = JsonParser.parseString(payload).asJsonObject
        val parts = root.getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
        // parts: [text=Alice, inline_data, text=Bob, inline_data, text=prompt]
        assertEquals(5, parts.size())
        assertEquals("Alice", parts[0].asJsonObject.get("text").asString)
        assertTrue(parts[1].asJsonObject.has("inline_data"))
        assertEquals("image/jpeg", parts[1].asJsonObject.getAsJsonObject("inline_data").get("mime_type").asString)
        assertTrue(parts[1].asJsonObject.getAsJsonObject("inline_data").has("data"))
        assertEquals("Bob", parts[2].asJsonObject.get("text").asString)
        assertTrue(parts[3].asJsonObject.has("inline_data"))
        assertEquals("保持构图", parts[4].asJsonObject.get("text").asString)
    }

    @Test
    fun buildPayloadImage2ImageLabelFromFilenameWhenRefLabelEmpty() {
        // ReferenceImage.label 为空 → 从文件名推断 stem
        // 用 tmpJpegExactStem 保证 stem 恰好为 "protagonist"（tmpJpeg 会追加随机数字）
        val ref = tmpJpegExactStem("protagonist")
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(ReferenceImage(ref.absolutePath, "")),  // label 空
            aspectRatio = "9:16"
        )
        val payload = backend().buildPayload(request)
        val root = JsonParser.parseString(payload).asJsonObject
        val parts = root.getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
        // parts: [text=protagonist, inline_data, text=prompt]
        assertEquals(3, parts.size())
        assertEquals("protagonist", parts[0].asJsonObject.get("text").asString)
        assertTrue(parts[1].asJsonObject.has("inline_data"))
    }

    @Test
    fun buildPayloadImage2ImageNoLabelForScenePrefixFiles() {
        // 文件名以 scene_/storyboard_/output_ 开头 → 不加 label（仅 inline_data）
        val ref = File.createTempFile("scene_001", ".jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())); deleteOnExit() }
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(ReferenceImage(ref.absolutePath, "")),  // label 空，文件名 scene_001
            aspectRatio = "9:16"
        )
        val payload = backend().buildPayload(request)
        val root = JsonParser.parseString(payload).asJsonObject
        val parts = root.getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
        // parts: [inline_data, text=prompt]——无 label part
        assertEquals(2, parts.size())
        assertTrue(parts[0].asJsonObject.has("inline_data"))
        assertEquals("p", parts[1].asJsonObject.get("text").asString)
    }

    @Test
    fun buildPayloadImage2ImageNoLabelForStoryboardPrefixFiles() {
        val ref = File.createTempFile("storyboard_01", ".jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())); deleteOnExit() }
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(ReferenceImage(ref.absolutePath, "")),
            aspectRatio = "9:16"
        )
        val payload = backend().buildPayload(request)
        val root = JsonParser.parseString(payload).asJsonObject
        val parts = root.getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
        assertEquals(2, parts.size())
        assertTrue(parts[0].asJsonObject.has("inline_data"))
    }

    @Test
    fun buildPayloadImage2ImageNoLabelForOutputPrefixFiles() {
        val ref = File.createTempFile("output_x", ".jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte())); deleteOnExit() }
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(ReferenceImage(ref.absolutePath, "")),
            aspectRatio = "9:16"
        )
        val payload = backend().buildPayload(request)
        val root = JsonParser.parseString(payload).asJsonObject
        val parts = root.getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
        assertEquals(2, parts.size())
        assertTrue(parts[0].asJsonObject.has("inline_data"))
    }

    @Test
    fun buildPayloadSkipsNonExistentRefFiles() {
        // 参考图文件不存在 → 跳过（ArcReel FileNotFoundError 仅 warn）
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(ReferenceImage("/tmp/nonexistent_xyz.jpg", "Ghost")),
            aspectRatio = "9:16"
        )
        val payload = backend().buildPayload(request)
        val root = JsonParser.parseString(payload).asJsonObject
        val parts = root.getAsJsonArray("contents")[0].asJsonObject.getAsJsonArray("parts")
        // label part 仍加（label 非空），但 inline_data 跳过（文件不存在）
        // parts: [text=Ghost, text=prompt]
        assertEquals(2, parts.size())
        assertEquals("Ghost", parts[0].asJsonObject.get("text").asString)
        assertEquals("p", parts[1].asJsonObject.get("text").asString)
    }

    // ---- resolveLabel / extractNameFromPath ----

    @Test
    fun resolveLabelPrefersExplicitLabel() {
        val ref = ReferenceImage("/tmp/scene_001.jpg", "Alice")
        assertEquals("Alice", backend().resolveLabel(ref))
    }

    @Test
    fun resolveLabelTrimWhitespace() {
        val ref = ReferenceImage("/tmp/x.jpg", "  Bob  ")
        assertEquals("Bob", backend().resolveLabel(ref))
    }

    @Test
    fun resolveLabelFromFilenameWhenLabelEmpty() {
        val ref = ReferenceImage("/tmp/protagonist.jpg", "")
        assertEquals("protagonist", backend().resolveLabel(ref))
    }

    @Test
    fun resolveLabelNullForScenePrefixFile() {
        val ref = ReferenceImage("/tmp/scene_001.jpg", "")
        assertNull(backend().resolveLabel(ref))
    }

    @Test
    fun resolveLabelNullForStoryboardPrefixFile() {
        val ref = ReferenceImage("/tmp/storyboard_01.jpg", "")
        assertNull(backend().resolveLabel(ref))
    }

    @Test
    fun resolveLabelNullForOutputPrefixFile() {
        val ref = ReferenceImage("/tmp/output_x.jpg", "")
        assertNull(backend().resolveLabel(ref))
    }

    @Test
    fun extractNameFromPathStripsExtension() {
        val b = backend()
        assertEquals("protagonist", b.extractNameFromPath("/tmp/protagonist.jpg"))
        assertEquals("char1", b.extractNameFromPath("/tmp/char1.png"))
        assertEquals("hero", b.extractNameFromPath("hero.jpeg"))
    }

    @Test
    fun extractNameFromPathNullForScenePrefix() {
        assertNull(backend().extractNameFromPath("/tmp/scene_001.jpg"))
        assertNull(backend().extractNameFromPath("/tmp/storyboard_02.png"))
        assertNull(backend().extractNameFromPath("/tmp/output_final.jpeg"))
    }

    // ---- normalizeBaseUrl / resolveEndpoint ----

    @Test
    fun normalizeBaseUrlEmptyFallsBackToDefault() {
        assertEquals(
            "https://generativelanguage.googleapis.com",
            backend(baseUrl = "").normalizeBaseUrl("")
        )
    }

    @Test
    fun normalizeBaseUrlStripsTrailingSlash() {
        assertEquals(
            "https://gemini.example.com",
            backend().normalizeBaseUrl("https://gemini.example.com/")
        )
    }

    @Test
    fun normalizeBaseUrlStripsV1betaSuffix() {
        // 剥 /v1beta 后缀（resolveEndpoint 会重新补）
        assertEquals(
            "https://generativelanguage.googleapis.com",
            backend().normalizeBaseUrl("https://generativelanguage.googleapis.com/v1beta")
        )
        assertEquals(
            "https://gw.example.com",
            backend().normalizeBaseUrl("https://gw.example.com/v1beta/")
        )
    }

    @Test
    fun normalizeBaseUrlKeepsCustomBaseWithoutV1beta() {
        assertEquals(
            "https://gw.example.com",
            backend().normalizeBaseUrl("https://gw.example.com")
        )
    }

    @Test
    fun resolveEndpointDefaultBase() {
        val url = backend().resolveEndpoint()
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-image-preview:generateContent",
            url
        )
    }

    @Test
    fun resolveEndpointCustomModel() {
        val url = backend(model = "gemini-3.0-pro-image").resolveEndpoint()
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.0-pro-image:generateContent",
            url
        )
    }

    @Test
    fun resolveEndpointCustomBase() {
        val url = backend(baseUrl = "https://gw.example.com/v1beta").resolveEndpoint()
        assertEquals(
            "https://gw.example.com/v1beta/models/gemini-3.1-flash-image-preview:generateContent",
            url
        )
    }
}
