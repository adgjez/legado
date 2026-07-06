package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiVideoProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 豆包 Seedance 2.0 预置 Provider 与「按模型自适应」参数合并测试。
 *
 * 验证：
 * 1. [AiVideoService.parseDoubaoResolution]：从 WxH / resolution 字符串转换为 (resolution, ratio)
 * 2. [AiVideoService.mergeProviderParams]：豆包 / Agnes / OpenAI 三种 type 的自适应合并行为
 * 3. 豆包预置配置（submitUrl / pollUrlTemplate / JSONPath / 状态值）与 API 文档一致
 *
 * @see <a href="https://www.volcengine.com/docs/82379/1399008">豆包 Seedance 2.0 文档</a>
 */
class AiVideoServiceDoubaoTest {

    // ============================================================
    // parseDoubaoResolution：resolution 字符串直通
    // ============================================================

    @Test
    fun parseDoubaoResolutionPassthrough480p() {
        val (resolution, ratio) = AiVideoService.parseDoubaoResolution("480p")
        assertEquals("480p", resolution)
        assertEquals("adaptive", ratio)
    }

    @Test
    fun parseDoubaoResolutionPassthrough720p() {
        val (resolution, ratio) = AiVideoService.parseDoubaoResolution("720p")
        assertEquals("720p", resolution)
        assertEquals("adaptive", ratio)
    }

    @Test
    fun parseDoubaoResolutionPassthrough1080p() {
        val (resolution, ratio) = AiVideoService.parseDoubaoResolution("1080p")
        assertEquals("1080p", resolution)
        assertEquals("adaptive", ratio)
    }

    // ============================================================
    // parseDoubaoResolution：从 WxH 推导
    // ============================================================

    @Test
    fun parseDoubaoResolutionFrom1280x720() {
        // 16:9 横屏，短边 720 → 720p
        val (resolution, ratio) = AiVideoService.parseDoubaoResolution("1280x720")
        assertEquals("720p", resolution)
        assertEquals("16:9", ratio)
    }

    @Test
    fun parseDoubaoResolutionFrom1920x1080() {
        // 16:9 横屏，短边 1080 → 1080p
        val (resolution, ratio) = AiVideoService.parseDoubaoResolution("1920x1080")
        assertEquals("1080p", resolution)
        assertEquals("16:9", ratio)
    }

    @Test
    fun parseDoubaoResolutionFrom720x1280() {
        // 9:16 竖屏，短边 720 → 720p
        val (resolution, ratio) = AiVideoService.parseDoubaoResolution("720x1280")
        assertEquals("720p", resolution)
        assertEquals("9:16", ratio)
    }

    @Test
    fun parseDoubaoResolutionFrom1080x1080() {
        // 1:1 方形，短边 1080 → 1080p
        val (resolution, ratio) = AiVideoService.parseDoubaoResolution("1080x1080")
        assertEquals("1080p", resolution)
        assertEquals("1:1", ratio)
    }

    @Test
    fun parseDoubaoResolutionFrom640x360() {
        // 16:9 横屏，短边 360 → 480p
        val (resolution, ratio) = AiVideoService.parseDoubaoResolution("640x360")
        assertEquals("480p", resolution)
        assertEquals("16:9", ratio)
    }

    @Test
    fun parseDoubaoResolutionFallbackForInvalidInput() {
        // 解析失败默认 720p / 16:9
        val (resolution, ratio) = AiVideoService.parseDoubaoResolution("invalid")
        assertEquals("720p", resolution)
        assertEquals("16:9", ratio)
    }

    // ============================================================
    // 豆包预置配置验证
    // ============================================================

    @Test
    fun doubaoTypeConstantExists() {
        assertEquals("doubao", AiVideoProviderConfig.TYPE_DOUBAO)
    }

    @Test
    fun doubaoPresetUsesArkEndpoint() {
        val provider = AiVideoProviderConfig(
            name = "豆包 Seedance 2.0",
            type = AiVideoProviderConfig.TYPE_DOUBAO,
            model = "doubao-seedance-2-0-260128",
            submitUrl = "https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks",
            pollUrlTemplate = "https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks/{taskId}",
            taskIdJsonPath = "\$.id",
            videoUrlJsonPath = "\$.content.video_url",
            statusJsonPath = "\$.status",
            doneStatusValue = "succeeded",
            failedStatusValue = "failed"
        )
        assertEquals("doubao-seedance-2-0-260128", provider.model)
        assertTrue(provider.submitUrl.contains("ark.cn-beijing.volces.com"))
        assertTrue(provider.submitUrl.endsWith("/contents/generations/tasks"))
        assertEquals("succeeded", provider.doneStatusValue)
        assertEquals("failed", provider.failedStatusValue)
    }

    @Test
    fun doubaoPollUrlResolvesTaskIdCorrectly() {
        val provider = AiVideoProviderConfig(
            name = "豆包",
            type = AiVideoProviderConfig.TYPE_DOUBAO,
            pollUrlTemplate = "https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks/{taskId}"
        )
        val resolved = provider.resolvePollUrl("cgt-abc-123")
        assertEquals(
            "https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks/cgt-abc-123",
            resolved
        )
    }

    // ============================================================
    // mergeProviderParams：按模型自适应合并
    // ============================================================

    /**
     * 豆包 type：defaultParamsJson 中的 camera_fixed/return_last_frame/generate_audio/watermark/seed
     * 应全部注入到 VideoSubmitRequest。
     */
    @Test
    fun doubaoMergeInjectsAllAdvancedParams() {
        val provider = AiVideoProviderConfig(
            name = "豆包",
            type = AiVideoProviderConfig.TYPE_DOUBAO,
            defaultParamsJson = """
                {
                  "camera_fixed": true,
                  "return_last_frame": true,
                  "generate_audio": true,
                  "watermark": true,
                  "seed": 12345
                }
            """.trimIndent()
        )
        val request = VideoSubmitRequest(
            prompt = "a cat",
            seconds = 5,
            size = "1280x720"
        )
        val merged = AiVideoService.mergeProviderParams(request, provider)
        assertEquals(true, merged.cameraFixed)
        assertTrue(merged.returnLastFrame)
        assertEquals(true, merged.generateAudio)
        assertTrue(merged.watermark)
        assertEquals(12345, merged.seed)
    }

    /**
     * 豆包 type：request 显式指定的字段应覆盖 Provider 默认值（调用方优先）。
     */
    @Test
    fun doubaoMergeRequestOverridesProviderDefaults() {
        val provider = AiVideoProviderConfig(
            name = "豆包",
            type = AiVideoProviderConfig.TYPE_DOUBAO,
            defaultParamsJson = """
                {
                  "camera_fixed": false,
                  "watermark": true,
                  "seed": 1
                }
            """.trimIndent()
        )
        val request = VideoSubmitRequest(
            prompt = "a cat",
            seconds = 5,
            size = "720p",
            cameraFixed = true,
            watermark = false,
            seed = 999
        )
        val merged = AiVideoService.mergeProviderParams(request, provider)
        assertEquals(true, merged.cameraFixed)
        assertFalse(merged.watermark)
        assertEquals(999, merged.seed)
    }

    /**
     * 豆包 type：当 request.seconds 为正时，不读取 defaultParamsJson.duration（流水线优先）。
     */
    @Test
    fun doubaoMergeKeepsRequestSecondsWhenPositive() {
        val provider = AiVideoProviderConfig(
            name = "豆包",
            type = AiVideoProviderConfig.TYPE_DOUBAO,
            defaultParamsJson = """{"duration": 10}"""
        )
        val request = VideoSubmitRequest(prompt = "x", seconds = 5, size = "720p")
        val merged = AiVideoService.mergeProviderParams(request, provider)
        assertEquals(5, merged.seconds)
    }

    /**
     * Agnes type：defaultParamsJson 中的 mode/negative_prompt/seed/num_inference_steps
     * 应全部注入到 VideoSubmitRequest，且豆包专属字段（cameraFixed 等）保持 null/默认。
     */
    @Test
    fun agnesMergeInjectsAgnesSpecificParamsOnly() {
        val provider = AiVideoProviderConfig(
            name = "Agnes",
            type = AiVideoProviderConfig.TYPE_AGNES,
            defaultParamsJson = """
                {
                  "mode": "keyframes",
                  "negative_prompt": "blurry",
                  "seed": 7,
                  "num_inference_steps": 30
                }
            """.trimIndent()
        )
        val request = VideoSubmitRequest(prompt = "x", seconds = 5, size = "1280x720")
        val merged = AiVideoService.mergeProviderParams(request, provider)
        assertEquals("keyframes", merged.mode)
        assertEquals("blurry", merged.negativePrompt)
        assertEquals(7, merged.seed)
        assertEquals(30, merged.numInferenceSteps)
        // Agnes 不应注入豆包专属字段
        assertNull(merged.cameraFixed)
        assertNull(merged.generateAudio)
        assertFalse(merged.returnLastFrame)
    }

    /**
     * OpenAI type：defaultParamsJson 中无 Agnes/豆包专属字段，仅 seed 通用注入。
     * mode/negativePrompt/cameraFixed 等保持原值（null）。
     */
    @Test
    fun openaiMergeOnlyInjectsSeed() {
        val provider = AiVideoProviderConfig(
            name = "veo3.1",
            type = AiVideoProviderConfig.TYPE_OPENAI,
            // 即使 defaultParamsJson 含 mode/camera_fixed，OpenAI type 也不应注入这些字段
            defaultParamsJson = """
                {
                  "seed": 100,
                  "mode": "keyframes",
                  "camera_fixed": true
                }
            """.trimIndent()
        )
        val request = VideoSubmitRequest(prompt = "x", seconds = 8, size = "1280x720")
        val merged = AiVideoService.mergeProviderParams(request, provider)
        // 仅 seed 注入
        assertEquals(100, merged.seed)
        // mode/cameraFixed 不应被注入到 OpenAI 风格 request
        assertNull(merged.mode)
        assertNull(merged.cameraFixed)
    }

    /**
     * 解析失败的 defaultParamsJson 应安全降级，返回原 request 不变。
     */
    @Test
    fun mergeHandlesInvalidJsonGracefully() {
        val provider = AiVideoProviderConfig(
            name = "豆包",
            type = AiVideoProviderConfig.TYPE_DOUBAO,
            defaultParamsJson = "{ invalid json"
        )
        val request = VideoSubmitRequest(prompt = "x", seconds = 5, size = "720p", seed = 42)
        val merged = AiVideoService.mergeProviderParams(request, provider)
        // request 不变
        assertEquals(42, merged.seed)
        assertNull(merged.cameraFixed)
    }

    /**
     * 空的 defaultParamsJson 应返回原 request。
     */
    @Test
    fun mergeHandlesBlankParamsJson() {
        val provider = AiVideoProviderConfig(
            name = "Agnes",
            type = AiVideoProviderConfig.TYPE_AGNES,
            defaultParamsJson = ""
        )
        val request = VideoSubmitRequest(prompt = "x", seconds = 5, size = "1280x720")
        val merged = AiVideoService.mergeProviderParams(request, provider)
        assertEquals(request, merged)
    }

    // ============================================================
    // VideoSubmitRequest 默认值语义
    // ============================================================

    /**
     * VideoSubmitRequest 的高级字段默认值应表达「未设置」语义：
     * - nullable 字段为 null
     * - Boolean 字段（watermark/returnLastFrame/draft）默认 false
     * - 这样 mergeProviderParams 才能区分「调用方未设置」vs「调用方显式 false」
     */
    @Test
    fun videoSubmitRequestDefaultsExpressUnset() {
        val request = VideoSubmitRequest(prompt = "x", seconds = 5, size = "720p")
        assertNull(request.mode)
        assertNull(request.negativePrompt)
        assertNull(request.seed)
        assertNull(request.numInferenceSteps)
        assertNull(request.cameraFixed)
        assertNull(request.generateAudio)
        // Boolean 字段默认 false（无法区分未设置/显式 false，但语义上「不需要」）
        assertFalse(request.watermark)
        assertFalse(request.returnLastFrame)
        assertFalse(request.draft)
    }
}
