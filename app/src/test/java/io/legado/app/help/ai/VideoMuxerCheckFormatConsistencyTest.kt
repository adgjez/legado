package io.legado.app.help.ai

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [VideoMuxer.checkFormatConsistency] 与 [VideoMuxer.compareTrackKeys] 的单元测试。子项目 E P2 回归。
 *
 * 限制：[VideoMuxer.checkFormatConsistency] 依赖 [android.media.MediaExtractor] 解析真实 mp4，
 * Robolectric 下 ShadowMediaExtractor 不解析 mp4 文件，readTrackKey 恒返回 null，
 * 故核心比对逻辑抽为 [VideoMuxer.compareTrackKeys] 纯函数后在此直接验证三个分支
 * （一致 / mime 不同 / 分辨率不同）。public 入口仅覆盖可验证的契约与 null 短路分支。
 *
 * 用 Robolectric runner：[VideoMuxer.checkFormatConsistency] 会构造 [android.media.MediaExtractor]，
 * 纯 JVM 下抛「Method not mocked」，需 ShadowMediaExtractor 提供空实现。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class VideoMuxerCheckFormatConsistencyTest {

    // ============================================================
    // compareTrackKeys —— 核心比对逻辑三分支
    // ============================================================

    @Test
    fun compareTrackKeysReturnsNullWhenAllFieldsMatch() {
        val a = VideoMuxer.TrackKey("video/avc", 1920, 1080)
        val b = VideoMuxer.TrackKey("video/avc", 1920, 1080)
        assertNull("mime/width/height 全等应返回 null", VideoMuxer.compareTrackKeys(a, b, 2))
    }

    @Test
    fun compareTrackKeysReturnsErrorWhenMimeDiffers() {
        val a = VideoMuxer.TrackKey("video/avc", 1920, 1080)
        val b = VideoMuxer.TrackKey("video/hevc", 1920, 1080)
        val err = VideoMuxer.compareTrackKeys(a, b, 3)
        assertTrue("错误应含段索引：$err", err?.contains("第 3 段") == true)
        assertTrue("错误应含两个 mime：$err", err?.contains("video/avc") == true && err?.contains("video/hevc") == true)
        assertTrue("错误应提示编码不一致：$err", err?.contains("编码") == true)
    }

    @Test
    fun compareTrackKeysReturnsErrorWhenResolutionDiffers() {
        val a = VideoMuxer.TrackKey("video/avc", 1920, 1080)
        val b = VideoMuxer.TrackKey("video/avc", 1280, 720)
        val err = VideoMuxer.compareTrackKeys(a, b, 2)
        assertTrue("错误应含段索引：$err", err?.contains("第 2 段") == true)
        assertTrue("错误应含两个分辨率：$err", err?.contains("1920x1080") == true && err?.contains("1280x720") == true)
        assertTrue("错误应提示分辨率不一致：$err", err?.contains("分辨率") == true)
    }

    @Test
    fun compareTrackKeysChecksMimeBeforeResolution() {
        // mime 和分辨率都不同时，应优先报 mime（短路）
        val a = VideoMuxer.TrackKey("video/avc", 1920, 1080)
        val b = VideoMuxer.TrackKey("video/hevc", 1280, 720)
        val err = VideoMuxer.compareTrackKeys(a, b, 2)
        assertTrue("应优先报 mime：$err", err?.contains("编码") == true)
    }

    // ============================================================
    // checkFormatConsistency —— public 入口契约与 null 短路分支
    // ============================================================

    @Test
    fun checkFormatConsistencyReturnsFirstFileErrorWhenPathInvalid() {
        // Robolectric 下 MediaExtractor.setDataSource 对不存在的文件，readTrackKey 返回 null
        // → 走「首个文件无 video 轨道」短路分支
        val err = VideoMuxer.checkFormatConsistency(listOf("/nonexistent/a.mp4", "/nonexistent/b.mp4"))
        assertTrue("应提示首个文件无 video 轨道：$err", err?.contains("首个文件无 video 轨道") == true)
        assertTrue("错误应含路径：$err", err?.contains("/nonexistent/a.mp4") == true)
    }

    @Test
    fun checkFormatConsistencySingleElementReturnsFirstFileErrorWhenPathInvalid() {
        // 单元素 list 不应 IndexOutOfBounds，走相同的 readTrackKey null 短路
        val err = VideoMuxer.checkFormatConsistency(listOf("/nonexistent/single.mp4"))
        assertTrue(err?.contains("首个文件无 video 轨道") == true)
    }
}
