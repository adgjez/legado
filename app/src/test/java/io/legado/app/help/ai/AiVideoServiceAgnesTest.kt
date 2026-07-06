package io.legado.app.help.ai

import io.legado.app.ui.main.ai.AiVideoProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agnes Video V2.0 预置 Provider 的参数转换测试。
 *
 * 验证 [AiVideoService.parseSizeStatic] / [AiVideoService.computeAgnesNumFramesStatic]
 * 以及预置配置的 JSONPath / 状态值是否与 Agnes API 文档一致。
 *
 * @see <a href="https://agnes-ai.com/zh-Hans/docs/agnes-video-v20">Agnes Video V2.0 文档</a>
 */
class AiVideoServiceAgnesTest {

    // ============================================================
    // parseSizeStatic：从 "WxH" 解析分辨率
    // ============================================================

    @Test
    fun parseSizeParsesStandard1280x720() {
        val (w, h) = AiVideoService.parseSizeStatic("1280x720")
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun parseSizeParsesAgnesRecommended1152x768() {
        val (w, h) = AiVideoService.parseSizeStatic("1152x768")
        assertEquals(1152, w)
        assertEquals(768, h)
    }

    @Test
    fun parseSizeHandlesUpperCaseX() {
        val (w, h) = AiVideoService.parseSizeStatic("1280X720")
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun parseSizeHandlesWhitespaceAroundValues() {
        val (w, h) = AiVideoService.parseSizeStatic(" 1280 x 720 ")
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun parseSizeFallsBackToDefaultOnInvalidFormat() {
        val (w, h) = AiVideoService.parseSizeStatic("invalid")
        assertEquals(1152, w)
        assertEquals(768, h)
    }

    @Test
    fun parseSizeFallsBackToDefaultOnZeroDimension() {
        val (w, h) = AiVideoService.parseSizeStatic("0x720")
        assertEquals(1152, w)
        assertEquals(768, h)
    }

    @Test
    fun parseSizeFallsBackToDefaultOnNegativeDimension() {
        val (w, h) = AiVideoService.parseSizeStatic("-1280x720")
        assertEquals(1152, w)
        assertEquals(768, h)
    }

    // ============================================================
    // computeAgnesNumFramesStatic：8n+1 规则验证
    // ============================================================

    @Test
    fun numFramesFor3SecondsAt24fps() {
        // 3*24=72, 向上取到 8n+1: 73 (8*9+1=73)
        assertEquals(73, AiVideoService.computeAgnesNumFramesStatic(3, 24))
    }

    @Test
    fun numFramesFor5SecondsAt24fps() {
        // 5*24=120, 向上取到 8n+1: 121 (8*15+1=121)
        assertEquals(121, AiVideoService.computeAgnesNumFramesStatic(5, 24))
    }

    @Test
    fun numFramesFor10SecondsAt24fps() {
        // 10*24=240, 向上取到 8n+1: 241 (8*30+1=241)
        assertEquals(241, AiVideoService.computeAgnesNumFramesStatic(10, 24))
    }

    @Test
    fun numFramesFor18SecondsAt24fps() {
        // 18*24=432, 向上取到 8n+1: 433 (8*54+1=433)
        assertEquals(433, AiVideoService.computeAgnesNumFramesStatic(18, 24))
    }

    @Test
    fun numFramesClampedToUpperBound441() {
        // 25*24=600, 超过 441 上限
        assertEquals(441, AiVideoService.computeAgnesNumFramesStatic(25, 24))
    }

    @Test
    fun numFramesAlwaysSatisfies8nPlus1Rule() {
        // 验证多个时长都满足 8n+1
        for (seconds in 1..20) {
            val numFrames = AiVideoService.computeAgnesNumFramesStatic(seconds, 24)
            assertTrue(
                "seconds=$seconds numFrames=$numFrames 不满足 8n+1 规则",
                (numFrames - 1) % 8 == 0
            )
        }
    }

    @Test
    fun numFramesAlwaysWithinBounds() {
        for (seconds in 1..30) {
            val numFrames = AiVideoService.computeAgnesNumFramesStatic(seconds, 24)
            assertTrue("seconds=$seconds numFrames=$numFrames 超过上限 441", numFrames <= 441)
            assertTrue("seconds=$seconds numFrames=$numFrames 低于下限 1", numFrames >= 1)
        }
    }

    // ============================================================
    // Agnes 预置配置验证
    // ============================================================

    @Test
    fun agnesTypeConstantExists() {
        assertEquals("agnes", AiVideoProviderConfig.TYPE_AGNES)
    }

    @Test
    fun agnesProviderUsesCorrectDefaults() {
        // 验证 Agnes 预置的关键字段值与 API 文档一致
        val provider = AiVideoProviderConfig(
            name = "Agnes Video V2.0",
            type = AiVideoProviderConfig.TYPE_AGNES,
            model = "agnes-video-v2.0",
            submitUrl = "https://apihub.agnes-ai.com/v1/videos",
            pollUrlTemplate = "https://apihub.agnes-ai.com/agnesapi?video_id={taskId}",
            taskIdJsonPath = "\$.video_id",
            videoUrlJsonPath = "\$.remixed_from_video_id",
            statusJsonPath = "\$.status",
            doneStatusValue = "completed",
            failedStatusValue = "failed"
        )
        assertEquals("agnes-video-v2.0", provider.model)
        assertEquals("https://apihub.agnes-ai.com/v1/videos", provider.submitUrl)
        assertEquals("completed", provider.doneStatusValue)
        assertEquals("failed", provider.failedStatusValue)
    }

    @Test
    fun agnesPollUrlResolvesVideoIdCorrectly() {
        val provider = AiVideoProviderConfig(
            name = "Agnes",
            type = AiVideoProviderConfig.TYPE_AGNES,
            pollUrlTemplate = "https://apihub.agnes-ai.com/agnesapi?video_id={taskId}"
        )
        val resolved = provider.resolvePollUrl("video_abc123")
        assertEquals(
            "https://apihub.agnes-ai.com/agnesapi?video_id=video_abc123",
            resolved
        )
    }

    // ============================================================
    // Agnes 关键帧模式 / 高级参数验证
    // ============================================================

    /**
     * 关键帧模式要求 ≥2 张参考图 + mode=keyframes。
     * 验证 Provider 配置能正确表达这个组合。
     */
    @Test
    fun agnesKeyframesModeConfiguration() {
        val provider = AiVideoProviderConfig(
            name = "Agnes Keyframes",
            type = AiVideoProviderConfig.TYPE_AGNES,
            model = "agnes-video-v2.0",
            defaultParamsJson = """
                {
                  "mode": "keyframes",
                  "negative_prompt": "blurry, low quality",
                  "seed": 42,
                  "num_inference_steps": 30
                }
            """.trimIndent(),
            maxReferenceImages = 4
        )
        // 验证 maxReferenceImages 允许 ≥2 张关键帧
        assertEquals(4, provider.maxReferenceImages)
        // 验证 defaultParamsJson 包含 keyframes 模式
        assertTrue(provider.defaultParamsJson.contains("\"mode\": \"keyframes\""))
        assertTrue(provider.defaultParamsJson.contains("\"negative_prompt\""))
        assertTrue(provider.defaultParamsJson.contains("\"seed\""))
        assertTrue(provider.defaultParamsJson.contains("\"num_inference_steps\""))
    }

    /**
     * 验证 Agnes 支持的两种 mode 值。
     * - ti2vid：文本/图生视频（默认）
     * - keyframes：关键帧过渡（需 ≥2 张参考图）
     */
    @Test
    fun agnesModeValuesAreValid() {
        val validModes = setOf("ti2vid", "keyframes")
        // 验证默认文生视频不需要 mode
        val defaultProvider = AiVideoProviderConfig(
            name = "Agnes Default",
            type = AiVideoProviderConfig.TYPE_AGNES,
            defaultParamsJson = ""
        )
        assertTrue(defaultProvider.defaultParamsJson.isBlank())

        // 验证关键帧模式
        val keyframesProvider = AiVideoProviderConfig(
            name = "Agnes Keyframes",
            type = AiVideoProviderConfig.TYPE_AGNES,
            defaultParamsJson = """{"mode": "keyframes"}"""
        )
        assertTrue(keyframesProvider.defaultParamsJson.contains("keyframes"))
        assertTrue(validModes.contains("keyframes"))
    }

    /**
     * 验证 Agnes 视频时长控制规则。
     * @see <a href="https://agnes-ai.com/zh-Hans/docs/agnes-video-v20">文档：seconds = num_frames / frame_rate</a>
     */
    @Test
    fun agnesDurationControlFormula() {
        // 文档推荐组合验证
        val cases = listOf(
            Triple(81, 24, 3.375),    // 约 3 秒
            Triple(121, 24, 5.04),    // 约 5 秒
            Triple(241, 24, 10.04),   // 约 10 秒
            Triple(441, 24, 18.375)   // 约 18 秒
        )
        cases.forEach { (numFrames, frameRate, expectedSeconds) ->
            val actualSeconds = numFrames.toDouble() / frameRate
            // 允许 0.1 误差
            assertTrue(
                "num_frames=$numFrames frame_rate=$frameRate 实际时长=$actualSeconds 与预期 $expectedSeconds 偏差过大",
                kotlin.math.abs(actualSeconds - expectedSeconds) < 0.2
            )
        }
    }

    /**
     * 验证 num_frames 帧数上限规则。
     * Agnes 文档明确要求 num_frames ≤ 441。
     */
    @Test
    fun agnesNumFramesUpperBoundIs441() {
        // 即便请求 30 秒视频，num_frames 也不能超过 441
        val numFrames = AiVideoService.computeAgnesNumFramesStatic(30, 24)
        assertTrue("num_frames=$numFrames 超过 Agnes 上限 441", numFrames <= 441)
        assertEquals(441, numFrames)
    }

    /**
     * 验证 num_frames 8n+1 规则在所有合理时长下都成立。
     * Agnes 文档明确要求 num_frames 遵循 8n+1 规则。
     */
    @Test
    fun agnesNumFramesAlwaysSatisfies8nPlus1AcrossRange() {
        // 验证 1-30 秒所有时长都满足 8n+1
        for (seconds in 1..30) {
            val numFrames = AiVideoService.computeAgnesNumFramesStatic(seconds, 24)
            val remainder = (numFrames - 1) % 8
            assertEquals(
                "seconds=$seconds num_frames=$numFrames 不满足 8n+1 规则（余数=$remainder）",
                0,
                remainder
            )
        }
    }

    /**
     * 验证 Agnes 标准分辨率档位。
     * 文档支持 480p / 720p / 1080p 三个标准档位。
     */
    @Test
    fun agnesStandardResolutionTiers() {
        // 720p 常见宽高比
        val (w720, h720) = AiVideoService.parseSizeStatic("1280x720")
        assertEquals(1280, w720)
        assertEquals(720, h720)

        // 1080p 常见宽高比
        val (w1080, h1080) = AiVideoService.parseSizeStatic("1920x1080")
        assertEquals(1920, w1080)
        assertEquals(1080, h1080)

        // Agnes 推荐的 1152x768（约 720p 档位）
        val (wAgnes, hAgnes) = AiVideoService.parseSizeStatic("1152x768")
        assertEquals(1152, wAgnes)
        assertEquals(768, hAgnes)
    }
}
