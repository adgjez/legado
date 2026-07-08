package io.legado.app.help.ai

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * [VideoMuxer.merge] 的可测分支单元测试。审查 P1-3。
 *
 * 限制：merge 核心多段合并依赖 [android.media.MediaMuxer] / [android.media.MediaExtractor]
 * 解析真实 mp4，Robolectric 下 ShadowMediaExtractor 不解析 mp4（trackCount 恒 0），
 * 故核心 remux 路径无法在 JVM 单测覆盖。本测试只覆盖不依赖真实 mp4 解析的纯 IO 分支。
 *
 * 覆盖分支：
 * - 空输入 / 全不存在路径 → Failed("无有效输入文件")
 * - 单段有效文件 → copyTo 成功 → Success（estimateDurationMs 走 MediaExtractor 返回 0L）
 *
 * 不覆盖分支（需真实 mp4 或会触发 AppLog 崩溃）：
 * - 多段合并核心路径（MediaMuxer remux）— 需 instrumented test
 * - 多段无 video 轨道短路 — merge L65 的 AppLog.put 在 Robolectric 下触发 AppConfig 崩（cae22457）
 * - 单段 copy 失败 — merge L80 的 AppLog.put 同样会崩
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class VideoMuxerMergeBranchTest {

    @Test
    fun mergeReturnsFailedWhenAllInputsEmpty() = runTest {
        val result = VideoMuxer.merge(emptyList(), "/tmp/out.mp4")
        assertTrue("空输入应返回 Failed", result is VideoMuxer.MergeResult.Failed)
        assertEquals(
            "无有效输入文件",
            (result as VideoMuxer.MergeResult.Failed).message
        )
    }

    @Test
    fun mergeReturnsFailedWhenAllPathsNonExistent() = runTest {
        val result = VideoMuxer.merge(
            listOf("/nonexistent/a.mp4", "/nonexistent/b.mp4"),
            "/tmp/out.mp4"
        )
        assertTrue(result is VideoMuxer.MergeResult.Failed)
        assertEquals("无有效输入文件", (result as VideoMuxer.MergeResult.Failed).message)
    }

    @Test
    fun mergeReturnsFailedWhenAllPathsBlank() = runTest {
        val result = VideoMuxer.merge(listOf("", "  "), "/tmp/out.mp4")
        assertTrue(result is VideoMuxer.MergeResult.Failed)
        assertEquals("无有效输入文件", (result as VideoMuxer.MergeResult.Failed).message)
    }

    @Test
    fun mergeSingleSegmentCopiesFileAndReturnsSuccess() = runTest {
        // 单段：filter 后仅 1 个有效文件 → copyTo 分支（不经 MediaMuxer）
        // estimateDurationMs 走 MediaExtractor，Robolectric 下 runCatching 返回 0L
        val tmpDir = Files.createTempDirectory("muxerTest").toFile()
        try {
            val src = File(tmpDir, "src.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val out = File(tmpDir, "sub/out.mp4")

            val result = VideoMuxer.merge(listOf(src.absolutePath), out.absolutePath)

            assertTrue("单段应返回 Success：$result", result is VideoMuxer.MergeResult.Success)
            val success = result as VideoMuxer.MergeResult.Success
            assertEquals(out.absolutePath, success.outputPath)
            assertEquals(1, success.segmentCount)
            // Robolectric 下 MediaExtractor 无法解析，duration 回退 0L
            assertEquals(0L, success.totalDurationMs)
            assertTrue("输出文件应已创建", out.isFile)
            // 内容应与源一致（copyTo 语义）
            assertEquals(src.readBytes().toList(), out.readBytes().toList())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun mergeSingleSegmentCreatesParentDirIfMissing() = runTest {
        val tmpDir = Files.createTempDirectory("muxerTest").toFile()
        try {
            val src = File(tmpDir, "src.mp4").apply { writeBytes(byteArrayOf(0x0A)) }
            val deepOut = File(tmpDir, "a/b/c/out.mp4")

            val result = VideoMuxer.merge(listOf(src.absolutePath), deepOut.absolutePath)

            assertTrue(result is VideoMuxer.MergeResult.Success)
            assertTrue("应自动创建深层父目录", deepOut.isFile)
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
