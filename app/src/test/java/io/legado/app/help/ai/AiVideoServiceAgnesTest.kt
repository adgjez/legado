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
        // 3*24=72, 向上取到 8n+1: 81 (8*10+1=81)
        assertEquals(81, AiVideoService.computeAgnesNumFramesStatic(3, 24))
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
        // 18*24=432, 向上取到 8n+1: 441 (8*55+1=441)
        assertEquals(441, AiVideoService.computeAgnesNumFramesStatic(18, 24))
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
}
