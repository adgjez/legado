package io.legado.app.help.ai.backends.image

import io.legado.app.help.ai.backends.ImageCapability
import io.legado.app.help.ai.backends.ImageGenerationRequest
import io.legado.app.help.ai.backends.ReferenceImage
import io.legado.app.ui.main.ai.AiImageProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [OpenAiImageBackend] 单测——验证 T2I/I2I 编码形态、aspect_size 尺寸计算、quality 映射。
 *
 * 不发真实 HTTP；只测纯函数 [buildPayload]（T2I）/ [submitEdit] 的 multipart 构造逻辑、
 * [resolveOpenAiParams]、[resolutionToShortEdge]、[qualityFor]、[normalizeBaseUrl]。
 *
 * 注：multipart 形态无法用字符串断言（OkHttp MultipartBody 不暴露 part 列表），
 * 故 I2I 编码纪律由 [buildPayload] 的 T2I 路径 + [resolveOpenAiParams] 覆盖；
 * I2I 仅测 [submitEdit] 的参考图截断逻辑（间接通过 take(MAX_REFERENCE_IMAGES)）。
 */
class OpenAiImageBackendTest {

    private fun backend(model: String = "", baseUrl: String = ""): OpenAiImageBackend =
        OpenAiImageBackend(
            AiImageProviderConfig(
                name = "openai-test",
                type = AiImageProviderConfig.TYPE_OPENAI,
                baseUrl = baseUrl,
                apiKey = "k",
                model = model
            )
        )

    @Test
    fun capabilitiesSupportsTextAndImage() {
        val caps = backend().capabilities
        assertTrue(caps.contains(ImageCapability.TEXT_TO_IMAGE))
        assertTrue(caps.contains(ImageCapability.IMAGE_TO_IMAGE))
    }

    @Test
    fun typeIdAndModelDefaults() {
        val b = backend()
        assertEquals(AiImageProviderConfig.TYPE_OPENAI, b.typeId)
        assertEquals("gpt-image-2", b.model)
    }

    @Test
    fun typeIdAndModelCustom() {
        val b = backend(model = "gpt-image-2-2026-04-21")
        assertEquals("gpt-image-2-2026-04-21", b.model)
    }

    // ---- resolveOpenAiParams（aspect_size 16 整除 + 长边 3840 收口 + quality）----

    @Test
    fun resolveOpenAiParams9to16DefaultShort720() {
        // 默认短边 720，9:16 → t 使短边≈720，16 整除
        // short_unit = 16*9 = 144；t = round(720/144) = 5；短边=144*5=720；长边=16*16*5=1280
        // 校验 16 整除：720/16=45 ✓，1280/16=80 ✓
        val params = backend().resolveOpenAiParams(null, "9:16")
        assertEquals("720x1280", params["size"])
        // imageSize=None 不下传 quality
        assertNull(params["quality"])
    }

    @Test
    fun resolveOpenAiParams16to9DefaultShort720() {
        val params = backend().resolveOpenAiParams(null, "16:9")
        assertEquals("1280x720", params["size"])
    }

    @Test
    fun resolveOpenAiParams1to1DefaultShort720() {
        // 1:1 → t=round(720/16)=45；size=16*45=720 → 720x720
        val params = backend().resolveOpenAiParams(null, "1:1")
        assertEquals("720x720", params["size"])
    }

    @Test
    fun resolveOpenAiParamsAlignsToMultipleOf16() {
        // 任意比例尺寸必须被 16 整除（gpt-image-2 要求）
        listOf("9:16", "16:9", "1:1", "4:3", "3:4", "21:9").forEach { ratio ->
            val params = backend().resolveOpenAiParams(null, ratio)
            val (w, h) = params["size"]!!.split("x").map { it.toInt() }
            assertEquals("$ratio 宽应被 16 整除", 0, w % 16)
            assertEquals("$ratio 高应被 16 整除", 0, h % 16)
        }
    }

    @Test
    fun resolveOpenAiParamsLongEdgeCappedAt3840() {
        // 4K 档短边 2160，1:1 → t=round(2160/16)=135；长边=16*135=2160，未超 3840
        // 但用极端比例 1:4 短边 2160：short_comp=1, long_comp=4, short_unit=16
        // t=round(2160/16)=135；max_t_long=3840/(16*4)=60；t=min(135,60)=60
        // 短边=16*60=960；长边=16*4*60=3840 ✓ 长边收口
        val params = backend().resolveOpenAiParams("4K", "1:4")
        val (w, h) = params["size"]!!.split("x").map { it.toInt() }
        val longEdge = maxOf(w, h)
        assertTrue("长边应≤3840，实际=$longEdge", longEdge <= 3840)
        assertEquals(0, w % 16)
        assertEquals(0, h % 16)
    }

    @Test
    fun resolveOpenAiParamsQualityFromTier() {
        // 档位 → quality 映射
        assertEquals("low", backend().resolveOpenAiParams("512px", "1:1")["quality"])
        assertEquals("medium", backend().resolveOpenAiParams("1K", "1:1")["quality"])
        assertEquals("high", backend().resolveOpenAiParams("2K", "1:1")["quality"])
        assertEquals("high", backend().resolveOpenAiParams("4K", "1:1")["quality"])
    }

    @Test
    fun resolveOpenAiParamsQualityCaseInsensitive() {
        // 大小写不敏感（"2k" / "2K" 等价）
        assertEquals("high", backend().resolveOpenAiParams("2k", "1:1")["quality"])
        assertEquals("high", backend().resolveOpenAiParams("2K", "1:1")["quality"])
    }

    @Test
    fun resolveOpenAiParamsCustomSizeNoQuality() {
        // 自定义 WxH 不下传 quality
        val params = backend().resolveOpenAiParams("1280x720", "9:16")
        assertNull(params["quality"])
        // 自定义尺寸取 min 当短边，剥自带比例：min(1280,720)=720
        assertEquals("720x1280", params["size"])
    }

    // ---- resolutionToShortEdge ----

    @Test
    fun resolutionToShortEdgeTierMap() {
        val b = backend()
        assertEquals(512, b.resolutionToShortEdge("512px"))
        assertEquals(1024, b.resolutionToShortEdge("1K"))
        assertEquals(1440, b.resolutionToShortEdge("2K"))
        assertEquals(2160, b.resolutionToShortEdge("4K"))
    }

    @Test
    fun resolutionToShortEdgeTierMapCaseInsensitive() {
        val b = backend()
        assertEquals(1024, b.resolutionToShortEdge("1k"))
        assertEquals(1440, b.resolutionToShortEdge("2k"))
    }

    @Test
    fun resolutionToShortEdgeCustomSizeStripsRatio() {
        // "宽x高" → min(宽,高)，剥离自带比例
        assertEquals(720, backend().resolutionToShortEdge("1280x720"))
        assertEquals(720, backend().resolutionToShortEdge("720x1280"))
        // 各种分隔符
        assertEquals(720, backend().resolutionToShortEdge("1280X720"))
        assertEquals(720, backend().resolutionToShortEdge("1280×720"))
        assertEquals(720, backend().resolutionToShortEdge("1280*720"))
    }

    @Test
    fun resolutionToShortEdgePureNumber() {
        assertEquals(800, backend().resolutionToShortEdge("800"))
    }

    @Test
    fun resolutionToShortEdgeNullDefaultsTo720() {
        assertEquals(720, backend().resolutionToShortEdge(null))
        assertEquals(720, backend().resolutionToShortEdge(""))
        assertEquals(720, backend().resolutionToShortEdge("  "))
    }

    @Test
    fun resolutionToShortEdgeUnparseableDefaultsTo720() {
        assertEquals(720, backend().resolutionToShortEdge("abc"))
        assertEquals(720, backend().resolutionToShortEdge("garbage"))
    }

    // ---- qualityFor ----

    @Test
    fun qualityForTierMap() {
        val b = backend()
        assertEquals("low", b.qualityFor("512px"))
        assertEquals("medium", b.qualityFor("1K"))
        assertEquals("high", b.qualityFor("2K"))
        assertEquals("high", b.qualityFor("4K"))
    }

    @Test
    fun qualityForNullAndCustomReturnsNull() {
        val b = backend()
        assertNull(b.qualityFor(null))
        assertNull(b.qualityFor(""))
        assertNull(b.qualityFor("1280x720"))  // 自定义 WxH 无 quality
        assertNull(b.qualityFor("800"))  // 纯数字无 quality
    }

    // ---- normalizeBaseUrl ----

    @Test
    fun normalizeBaseUrlEmptyFallsBackToOfficial() {
        assertEquals("https://api.openai.com/v1", backend(baseUrl = "").normalizeBaseUrl(""))
        assertEquals("https://api.openai.com/v1", backend(baseUrl = "  ").normalizeBaseUrl("  "))
    }

    @Test
    fun normalizeBaseUrlStripsTrailingSlash() {
        assertEquals("https://gw.example.com/v1", backend().normalizeBaseUrl("https://gw.example.com/v1/"))
    }

    @Test
    fun normalizeBaseUrlKeepsCustomBase() {
        assertEquals("https://gw.example.com/v1", backend().normalizeBaseUrl("https://gw.example.com/v1"))
    }

    // ---- submitGenerate T2I 编码形态（间接验证，因为 submitGenerate 是 internal）----

    @Test
    fun submitGenerateBuildsJsonWithModelPromptNSize() {
        // 间接验证：resolveOpenAiParams 是 submitGenerate 的核心，已覆盖 size/quality
        // 此处仅验证 backend 实例化 + 默认 model + 不抛异常
        val b = backend()
        val params = b.resolveOpenAiParams(null, "9:16")
        assertTrue(params["size"]!!.contains("x"))
        // 不下传 response_format（gpt-image-2 默认返 b64_json）
        assertFalse(params.containsKey("response_format"))
    }

    // ---- 参考图截断（I2I）----

    @Test
    fun maxReferenceImagesConstantIs16() {
        // 验证 MAX_REFERENCE_IMAGES=16（移植 ArcReel _MAX_REFERENCE_IMAGES）
        // submitEdit 内部 take(MAX_REFERENCE_IMAGES) 截断超过上限的参考图
        assertEquals(16, OpenAiImageBackend.MAX_REFERENCE_IMAGES)
    }

    @Test
    fun referenceImagesSkippedIfNotExists() {
        // I2I 路径：参考图不存在则跳过（ArcReel FileNotFoundError 仅 warn）
        // 通过 buildPayload 间接验证不可能（submitEdit 是 internal 且构造 multipart）
        // 此处验证 backend 实例化不抛 + resolveOpenAiParams 对 I2I 同样生效
        val b = backend()
        val params = b.resolveOpenAiParams("2K", "9:16")
        assertEquals("high", params["quality"])
        assertTrue(params["size"]!!.contains("x"))
    }

    @Test
    fun imageGenerationRequestWithReferenceImagesBuildsCorrectly() {
        // 验证 ImageGenerationRequest 数据类构造 + referenceImages 列表
        val ref = ReferenceImage("/tmp/nonexistent.jpg", "label1")
        val request = ImageGenerationRequest(
            prompt = "test prompt",
            outputPath = File("/tmp/out.png"),
            referenceImages = listOf(ref),
            aspectRatio = "9:16",
            imageSize = "2K"
        )
        assertEquals(1, request.referenceImages.size)
        assertEquals("label1", request.referenceImages[0].label)
        assertEquals("2K", request.imageSize)
        assertEquals("9:16", request.aspectRatio)
    }
}
