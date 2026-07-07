package io.legado.app.help.ai.backends.image

import com.google.gson.JsonParser
import io.legado.app.help.ai.backends.ImageCapability
import io.legado.app.help.ai.backends.ImageGenerationRequest
import io.legado.app.help.ai.backends.ReferenceImage
import io.legado.app.ui.main.ai.AiImageProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [DashScopeImageBackend] 单测（P3c）——验证：
 * - 能力值（qwen-image-2.0 → {T2I,I2I}；qwen-image-edit 系 → 仅 {I2I}）
 * - buildPayload 编码形态：messages[0].content 数组（I2I 先 image dataURI 后 text；T2I 仅 text），
 *   size 用星号 `*` 分隔，parameters 含 n/watermark/prompt_extend/size(/seed)
 * - 参考图上限截断（qwen 系 3 / wan 系 9）+ fail-loud（文件不可读抛错）
 * - resolveSize 三族尺寸计算（wan 总像素约束 + 4K 门控 / edit 长边收口 / fusion 总像素约束）
 * - normalizeBaseUrl 剥 `/compatible-mode/v1`、`/api/v1` 后补 `/api/v1`
 * - extractImageUrl 提取 choices[0].message.content[*].image / 错误形态报错
 *
 * Robolectric 必需：I2I 测试调 buildPayload → ImageCodec.toDataUri → android.util.Base64。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DashScopeImageBackendTest {

    private fun newBackend(model: String = "qwen-image-2.0"): DashScopeImageBackend {
        val cfg = AiImageProviderConfig(
            name = "dashscope-test",
            type = AiImageProviderConfig.TYPE_DASHSCOPE,
            baseUrl = "https://dashscope.aliyuncs.com",
            apiKey = "test-key",
            model = model
        )
        return DashScopeImageBackend(cfg)
    }

    /** 最小 JPEG 魔数字节临时文件（ImageCodec.toDataUri 读盘 + Base64）。 */
    private fun tmpJpeg(name: String): File {
        val bytes = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            0, 1, 0, 0, 1, 0, 1, 0, 0,
            0xFF.toByte(), 0xD9.toByte()
        )
        return File.createTempFile(name, ".jpg").apply { writeBytes(bytes); deleteOnExit() }
    }

    /**
     * 文件名 stem 恰好为 [stem] 的临时 JPEG（[File.createTempFile] 会追加随机数字，
     * 故自建临时目录 + 精确文件名）。
     */
    private fun tmpJpegExactStem(stem: String): File {
        val dir = kotlin.io.path.createTempDirectory(prefix = "dashscope-i2i-").toFile()
        val f = File(dir, "$stem.jpg")
        f.writeBytes(
            byteArrayOf(
                0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
                0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
                0, 1, 0, 0, 1, 0, 1, 0, 0,
                0xFF.toByte(), 0xD9.toByte()
            )
        )
        f.deleteOnExit()
        dir.deleteOnExit()
        return f
    }

    private fun contentArrayOf(body: String): com.google.gson.JsonArray =
        JsonParser.parseString(body).asJsonObject
            .getAsJsonObject("input")
            .getAsJsonArray("messages")[0].asJsonObject
            .getAsJsonArray("content")

    // ==================== 能力值 ====================

    @Test
    fun capabilitiesFusionSupportsTextAndImage() {
        val backend = newBackend("qwen-image-2.0")
        assertTrue("fusion 应有 T2I", ImageCapability.TEXT_TO_IMAGE in backend.capabilities)
        assertTrue("fusion 应有 I2I", ImageCapability.IMAGE_TO_IMAGE in backend.capabilities)
    }

    @Test
    fun capabilitiesEditSupportsOnlyImage() {
        val backend = newBackend("qwen-image-edit-plus")
        assertFalse("edit 系无 T2I", ImageCapability.TEXT_TO_IMAGE in backend.capabilities)
        assertTrue("edit 系应有 I2I", ImageCapability.IMAGE_TO_IMAGE in backend.capabilities)
    }

    @Test
    fun resolveCapsEditSeriesIsImageOnly() {
        val backend = newBackend()
        assertEquals(
            "edit 子串匹配 → 仅 I2I",
            setOf(ImageCapability.IMAGE_TO_IMAGE),
            backend.resolveCaps("qwen-image-edit-plus")
        )
        assertEquals(
            "非 edit → {T2I, I2I}",
            setOf(ImageCapability.TEXT_TO_IMAGE, ImageCapability.IMAGE_TO_IMAGE),
            backend.resolveCaps("qwen-image-2.0")
        )
    }

    // ==================== typeId / model 默认值 ====================

    @Test
    fun typeIdAndModelDefaults() {
        val backend = newBackend("")
        assertEquals(AiImageProviderConfig.TYPE_DASHSCOPE, backend.typeId)
        assertEquals("空 model 应用默认值", "qwen-image-2.0", backend.model)
    }

    // ==================== T2I 编码形态 ====================

    @Test
    fun buildPayloadText2ImageHasOnlyTextPart() {
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "一只橘猫",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16"
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject

        assertEquals("qwen-image-2.0", root.get("model").asString)
        val messages = root.getAsJsonObject("input").getAsJsonArray("messages")
        assertEquals("messages 应 1 条", 1, messages.size())
        val msg = messages[0].asJsonObject
        assertEquals("user", msg.get("role").asString)

        val content = msg.getAsJsonArray("content")
        assertEquals("T2I content 应仅 1 项（text）", 1, content.size())
        assertTrue("唯一项应是 text", content[0].asJsonObject.has("text"))
        assertEquals("一只橘猫", content[0].asJsonObject.get("text").asString)
        assertFalse("T2I 不应有 image part", content[0].asJsonObject.has("image"))

        val parameters = root.getAsJsonObject("parameters")
        assertEquals("n 应 1", 1, parameters.get("n").asInt)
        assertEquals("watermark 应 false", false, parameters.get("watermark").asBoolean)
        assertEquals("prompt_extend 应 false", false, parameters.get("prompt_extend").asBoolean)
        val size = parameters.get("size").asString
        assertTrue("size 应用星号分隔: $size", size.contains("*"))
        assertFalse("size 不应用 x/× 分隔: $size", size.contains("x") || size.contains("×"))
        assertFalse("T2I 不应下发 seed", parameters.has("seed"))
    }

    @Test
    fun buildPayloadSeedIncludedWhenProvided() {
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "1:1",
            seed = 123L
        )
        val body = backend.buildPayload(request)
        val root = JsonParser.parseString(body).asJsonObject
        assertEquals(123L, root.getAsJsonObject("parameters").get("seed").asLong)
    }

    // ==================== I2I 编码形态（data URI in content） ====================

    @Test
    fun buildPayloadImage2ImagePutsDataUriBeforeText() {
        val backend = newBackend()
        val ref1 = tmpJpeg("ds_r1")
        val ref2 = tmpJpeg("ds_r2")
        val request = ImageGenerationRequest(
            prompt = "参考图生成",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(
                ReferenceImage(ref1.path, "ref_0"),
                ReferenceImage(ref2.path, "ref_1")
            )
        )
        val body = backend.buildPayload(request)
        val content = contentArrayOf(body)

        assertEquals("应 2 image + 1 text = 3 项", 3, content.size())
        assertTrue("第 1 项应是 image", content[0].asJsonObject.has("image"))
        assertTrue(
            "image[0] 应是 data URI",
            content[0].asJsonObject.get("image").asString.startsWith("data:image/jpeg;base64,")
        )
        assertTrue("第 2 项应是 image", content[1].asJsonObject.has("image"))
        assertTrue("第 3 项应是 text（prompt 放最后）", content[2].asJsonObject.has("text"))
        assertEquals("参考图生成", content[2].asJsonObject.get("text").asString)
    }

    @Test
    fun buildPayloadI2iQwenTruncatesToThreeRefs() {
        val backend = newBackend("qwen-image-2.0")
        val refs = (1..5).map { i -> ReferenceImage(tmpJpegExactStem("ds_qref_$i").path) }
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            referenceImages = refs
        )
        val body = backend.buildPayload(request)
        val content = contentArrayOf(body)
        val imageCount = (0 until content.size()).count { content[it].asJsonObject.has("image") }
        assertEquals(
            "qwen 系应截断到 ${DashScopeImageBackend._QWEN_REF_LIMIT}",
            DashScopeImageBackend._QWEN_REF_LIMIT,
            imageCount
        )
    }

    @Test
    fun buildPayloadI2iWanTruncatesToNineRefs() {
        val backend = newBackend("wan2.7-image-pro")
        val refs = (1..11).map { i -> ReferenceImage(tmpJpeg("ds_wref_$i").path) }
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            referenceImages = refs
        )
        val body = backend.buildPayload(request)
        val content = contentArrayOf(body)
        val imageCount = (0 until content.size()).count { content[it].asJsonObject.has("image") }
        assertEquals(
            "wan 系应截断到 ${DashScopeImageBackend._WAN_REF_LIMIT}",
            DashScopeImageBackend._WAN_REF_LIMIT,
            imageCount
        )
    }

    @Test(expected = IllegalStateException::class)
    fun buildPayloadI2iFailsLoudOnMissingRef() {
        val backend = newBackend()
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(ReferenceImage("/nonexistent/dashscope_ref_missing.jpg"))
        )
        backend.buildPayload(request)
    }

    // ==================== resolveSize：fusion 系 ====================

    @Test
    fun resolveSizeFusion9to16AlignedAndWithinPixels() {
        // qwen-image-2.0 默认短边 2048，max_total_pixels=2048*2048，round_to=16
        // 9:16 → shortUnit=16*9=144；t=round(2048/144)=14；max_t=sqrt(2048*2048/(9*16*16*16))=10
        // t=min(14,10)=10 → 9*16*10=1440, 16*16*10=2560；总像素 1440*2560=3,686,400 <= 4,194,304
        val backend = newBackend("qwen-image-2.0")
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16"
        )
        val (w, h) = backend.resolveSize(request)
        assertEquals("宽应 16 整除", 0, w % 16)
        assertEquals("高应 16 整除", 0, h % 16)
        assertTrue("总像素应 <= 2048*2048：${w}*${h}", w.toLong() * h.toLong() <= 2048L * 2048L)
        assertEquals("9:16 fusion 宽应 1440", 1440, w)
        assertEquals("9:16 fusion 高应 2560", 2560, h)
    }

    // ==================== resolveSize：wan 系 + 4K 门控 ====================

    @Test
    fun resolveSizeWanStandardWithinPixels() {
        val backend = newBackend("wan2.7-image")
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16"
        )
        val (w, h) = backend.resolveSize(request)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertTrue("总像素应 <= 2048*2048：${w}*${h}", w.toLong() * h.toLong() <= 2048L * 2048L)
    }

    @Test(expected = IllegalStateException::class)
    fun resolveSizeWan4kNonProErrors() {
        val backend = newBackend("wan2.7-image")
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16",
            imageSize = "4k"
        )
        backend.resolveSize(request)
    }

    @Test(expected = IllegalStateException::class)
    fun resolveSizeWanPro4kImage2ImageErrors() {
        val backend = newBackend("wan2.7-image-pro")
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16",
            imageSize = "4k",
            referenceImages = listOf(ReferenceImage("/tmp/whatever.jpg"))
        )
        backend.resolveSize(request)
    }

    @Test
    fun resolveSizeWanPro4kText2ImageSucceeds() {
        // wan2.7-image-pro T2I + 4K：max_total_pixels=4096*4096
        // 9:16 → shortEdge=2160(4K 档)；shortUnit=144；t=round(2160/144)=15
        // max_t=sqrt(4096*4096/(9*16*16*16))=21；t=min(15,21)=15 → 2160*3840
        val backend = newBackend("wan2.7-image-pro")
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16",
            imageSize = "4k"
        )
        val (w, h) = backend.resolveSize(request)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertTrue("4K 档总像素应 <= 4096*4096：${w}*${h}", w.toLong() * h.toLong() <= 4096L * 4096L)
        assertEquals("9:16 pro 4K 宽应 2160", 2160, w)
        assertEquals("9:16 pro 4K 高应 3840", 3840, h)
    }

    // ==================== resolveSize：edit 系长边收口 ====================

    @Test
    fun resolveSizeEdit9to16LongEdgeCappedAt2048() {
        // qwen-image-edit-plus：max_long_edge=2048，round_to=16，默认短边 2048
        // 9:16 → shortUnit=144；t=round(2048/144)=14；max_t_long=2048/(16*16)=8
        // t=min(14,8)=8 → 9*16*8=1152, 16*16*8=2048
        val backend = newBackend("qwen-image-edit-plus")
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.png"),
            aspectRatio = "9:16"
        )
        val (w, h) = backend.resolveSize(request)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
        assertTrue("长边应 <= 2048：${maxOf(w, h)}", maxOf(w, h) <= 2048)
        assertEquals("9:16 edit 宽应 1152", 1152, w)
        assertEquals("9:16 edit 高应 2048（长边收口）", 2048, h)
    }

    @Test
    fun resolveSizeAllRatiosAlignToMultipleOf16() {
        val fusion = newBackend("qwen-image-2.0")
        val edit = newBackend("qwen-image-edit-plus")
        val wan = newBackend("wan2.7-image")
        listOf("9:16", "16:9", "1:1", "4:3", "3:4", "21:9").forEach { ratio ->
            val req = ImageGenerationRequest(
                prompt = "p", outputPath = File("/tmp/o.png"), aspectRatio = ratio
            )
            val (fw, fh) = fusion.resolveSize(req)
            val (ew, eh) = edit.resolveSize(req)
            val (ww, wh) = wan.resolveSize(req)
            assertEquals("fusion $ratio width 应对齐 16", 0, fw % 16)
            assertEquals("fusion $ratio height 应对齐 16", 0, fh % 16)
            assertEquals("edit $ratio width 应对齐 16", 0, ew % 16)
            assertEquals("edit $ratio height 应对齐 16", 0, eh % 16)
            assertEquals("wan $ratio width 应对齐 16", 0, ww % 16)
            assertEquals("wan $ratio height 应对齐 16", 0, wh % 16)
        }
    }

    // ==================== is4kTier / resolutionToShortEdge ====================

    @Test
    fun is4kTierDetection() {
        val backend = newBackend("wan2.7-image-pro")
        assertFalse("null 非 4K", backend.is4kTier(null))
        assertFalse("2k 非 4K", backend.is4kTier("2k"))
        assertTrue("4k 是 4K", backend.is4kTier("4k"))
        assertTrue("4K 大小写不敏感", backend.is4kTier("4K"))
        assertTrue("自定义总像素 > 2048*2048 是 4K", backend.is4kTier("3000x3000"))
        assertFalse("自定义总像素 <= 2048*2048 非 4K", backend.is4kTier("1024x1024"))
    }

    @Test
    fun resolutionToShortEdgeTiersAndCustom() {
        val backend = newBackend()
        assertEquals("512px → 512", 512, backend.resolutionToShortEdge("512px", 2048))
        assertEquals("1K → 1024", 1024, backend.resolutionToShortEdge("1K", 2048))
        assertEquals("2K → 1440", 1440, backend.resolutionToShortEdge("2K", 2048))
        assertEquals("4K → 2160", 2160, backend.resolutionToShortEdge("4K", 2048))
        assertEquals("null → family 默认", 2048, backend.resolutionToShortEdge(null, 2048))
        assertEquals("空串 → family 默认", 1440, backend.resolutionToShortEdge("", 1440))
        assertEquals("自定义 WxH → min", 1080, backend.resolutionToShortEdge("1920x1080", 2048))
        assertEquals("全角叉号", 720, backend.resolutionToShortEdge("1280×720", 2048))
        assertEquals("星号", 720, backend.resolutionToShortEdge("720*1280", 2048))
        assertEquals("纯数字", 800, backend.resolutionToShortEdge("800", 2048))
        assertEquals("无法解析 → family 默认", 2048, backend.resolutionToShortEdge("abc", 2048))
    }

    // ==================== normalizeBaseUrl ====================

    @Test
    fun normalizeBaseUrlStripsKnownSuffixesAndReappends() {
        val backend = newBackend()
        val expected = "https://dashscope.aliyuncs.com/api/v1"
        assertEquals("空串回落默认 base", expected, backend.normalizeBaseUrl(""))
        assertEquals("无后缀应补", expected, backend.normalizeBaseUrl("https://dashscope.aliyuncs.com"))
        assertEquals("带尾斜杠应处理", expected, backend.normalizeBaseUrl("https://dashscope.aliyuncs.com/"))
        assertEquals(
            "剥 /compatible-mode/v1 后补回",
            expected,
            backend.normalizeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1")
        )
        assertEquals(
            "剥 /compatible-mode/v1（带尾斜杠）",
            expected,
            backend.normalizeBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1/")
        )
        assertEquals(
            "剥 /api/v1 后补回",
            expected,
            backend.normalizeBaseUrl("https://dashscope.aliyuncs.com/api/v1")
        )
        assertEquals(
            "剥 /api/v1（带尾斜杠）",
            expected,
            backend.normalizeBaseUrl("https://dashscope.aliyuncs.com/api/v1/")
        )
    }

    // ==================== extractImageUrl 响应解析 ====================

    @Test
    fun extractImageUrlPicksImageFromContent() {
        val backend = newBackend()
        val resp = """{"output":{"choices":[{"message":{"content":[{"image":"https://cdn/img/x.png"}]}}]}}"""
        assertEquals("应取 choices[0].message.content[0].image", "https://cdn/img/x.png", backend.extractImageUrl(resp))
    }

    @Test
    fun extractImageUrlPicksFirstNonBlankImage() {
        val backend = newBackend()
        val resp = """{"output":{"choices":[{"message":{"content":[{"image":""},{"image":"https://cdn/img/y.png"}]}}]}}"""
        assertEquals("应跳过空 image 取首个非空", "https://cdn/img/y.png", backend.extractImageUrl(resp))
    }

    @Test(expected = IllegalStateException::class)
    fun extractImageUrlErrorsOnCodeForm() {
        val backend = newBackend()
        val resp = """{"code":"InvalidApiKey","message":"bad key","request_id":"abc"}"""
        backend.extractImageUrl(resp)
    }

    @Test(expected = IllegalStateException::class)
    fun extractImageUrlErrorsWhenResultsEmptyAndNoCode() {
        val backend = newBackend()
        val resp = """{"output":{"results":[]}}"""
        backend.extractImageUrl(resp)
    }
}
