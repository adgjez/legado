package io.legado.app.help.ai

import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [computeCoverage] 纯函数单测（spec §10.1）。
 *
 * 不依赖 Android/Room——[computeCoverage] 是纯函数，输入 jobs 列表输出 [BookNovelVideoSummary]。
 */
class BookNovelVideoSummaryTest {

    private fun job(
        id: String,
        start: Int,
        end: Int,
        status: String = NovelVideoJobStatus.COMPLETED,
        bookUrl: String = "book_1",
        bookName: String = "测试书"
    ) = NovelVideoJob(
        id = id,
        bookUrl = bookUrl,
        bookName = bookName,
        chapterStartIndex = start,
        chapterEndIndex = end,
        status = status
    )

    @Test
    fun coverageMergesOverlappingJobRanges() {
        // 两 job 区间重叠（1-3, 3-5）→ covered={1,2,3,4,5}
        val jobs = listOf(
            job("j1", 1, 3),
            job("j2", 3, 5)
        )
        val summary = computeCoverage("book_1", "测试书", null, 120, jobs, 0)

        assertEquals(setOf(1, 2, 3, 4, 5), summary.coveredChapterIndices)
        assertEquals(5, summary.coveredCount)
    }

    @Test
    fun coverageSplitsByStatus() {
        // COMPLETED(1-2) + FAILED(3-3) + RUNNING(4-5) → 三集合正确
        val jobs = listOf(
            job("j1", 1, 2, status = NovelVideoJobStatus.COMPLETED),
            job("j2", 3, 3, status = NovelVideoJobStatus.FAILED),
            job("j3", 4, 5, status = NovelVideoJobStatus.GENERATING)
        )
        val summary = computeCoverage("book_1", "测试书", null, 120, jobs, 0)

        assertEquals(setOf(1, 2), summary.completedChapterIndices)
        assertEquals(setOf(3), summary.failedChapterIndices)
        assertEquals(setOf(4, 5), summary.runningChapterIndices)
        assertEquals(setOf(1, 2, 3, 4, 5), summary.coveredChapterIndices)
    }

    @Test
    fun coverageIncludesPartialFailedInFailedBucket() {
        val jobs = listOf(
            job("j1", 1, 2, status = NovelVideoJobStatus.PARTIAL_FAILED)
        )
        val summary = computeCoverage("book_1", "测试书", null, 120, jobs, 0)

        assertEquals(setOf(1, 2), summary.failedChapterIndices)
        assertTrue(summary.completedChapterIndices.isEmpty())
    }

    @Test
    fun coverageExcludesCancelledJobs() {
        val jobs = listOf(
            job("j1", 1, 2, status = NovelVideoJobStatus.COMPLETED),
            job("j2", 3, 4, status = NovelVideoJobStatus.CANCELLED)
        )
        val summary = computeCoverage("book_1", "测试书", null, 120, jobs, 0)

        // 取消的 job 不计入覆盖
        assertEquals(setOf(1, 2), summary.coveredChapterIndices)
        assertFalse(summary.coveredChapterIndices.contains(3))
        assertFalse(summary.coveredChapterIndices.contains(4))
    }

    @Test
    fun progressComputesAgainstTotalChapters() {
        // covered=8, total=120 → progress≈0.0667
        val jobs = listOf(job("j1", 1, 8))
        val summary = computeCoverage("book_1", "测试书", null, 120, jobs, 0)

        assertEquals(8, summary.coveredCount)
        val expected = 8f / 120f
        assertEquals(expected, summary.progress, 0.001f)
    }

    @Test
    fun progressZeroWhenTotalUnknown() {
        // total=0 → progress=0，不除零
        val jobs = listOf(job("j1", 1, 5))
        val summary = computeCoverage("book_1", "测试书", null, 0, jobs, 0)

        assertEquals(0, summary.totalChapters)
        assertEquals(5, summary.coveredCount)
        assertEquals(0f, summary.progress, 0f)
    }

    @Test
    fun ignoresInvalidChapterIndices() {
        // chapterStart=-1 或 end<start 的忽略
        val jobs = listOf(
            job("j1", -1, 5, status = NovelVideoJobStatus.COMPLETED),   // start 异常
            job("j2", 1, -1, status = NovelVideoJobStatus.COMPLETED),   // end 异常
            job("j3", 5, 3, status = NovelVideoJobStatus.COMPLETED),    // end<start
            job("j4", 1, 2, status = NovelVideoJobStatus.COMPLETED)     // 正常
        )
        val summary = computeCoverage("book_1", "测试书", null, 120, jobs, 0)

        // 只有 j4 的 1-2 被计入
        assertEquals(setOf(1, 2), summary.coveredChapterIndices)
    }

    @Test
    fun emptyJobsYieldsEmptyCoverage() {
        val summary = computeCoverage("book_1", "测试书", null, 120, emptyList(), 0)

        assertTrue(summary.coveredChapterIndices.isEmpty())
        assertTrue(summary.completedChapterIndices.isEmpty())
        assertTrue(summary.failedChapterIndices.isEmpty())
        assertTrue(summary.runningChapterIndices.isEmpty())
        assertEquals(0, summary.coveredCount)
        assertEquals(0f, summary.progress, 0f)
        assertEquals(0, summary.jobCount)
    }

    @Test
    fun statusByChapterPrioritizesCompletedOverFailed() {
        // 同一章既被 FAILED job 覆盖又被 COMPLETED job 覆盖（重做成功）→ COMPLETED
        val jobs = listOf(
            job("j1", 1, 2, status = NovelVideoJobStatus.FAILED),
            job("j2", 2, 3, status = NovelVideoJobStatus.COMPLETED)
        )
        val summary = computeCoverage("book_1", "测试书", null, 120, jobs, 0)
        val statusMap = summary.statusByChapter()

        assertEquals(ChapterStatus.FAILED, statusMap[1])   // 只被 FAILED 覆盖
        assertEquals(ChapterStatus.COMPLETED, statusMap[2]) // 两个都覆盖，COMPLETED 优先
        assertEquals(ChapterStatus.COMPLETED, statusMap[3]) // 只被 COMPLETED 覆盖
    }

    @Test
    fun statusByChapterPrioritizesFailedOverRunning() {
        val jobs = listOf(
            job("j1", 1, 2, status = NovelVideoJobStatus.GENERATING),
            job("j2", 2, 3, status = NovelVideoJobStatus.FAILED)
        )
        val summary = computeCoverage("book_1", "测试书", null, 120, jobs, 0)
        val statusMap = summary.statusByChapter()

        assertEquals(ChapterStatus.RUNNING, statusMap[1])
        assertEquals(ChapterStatus.FAILED, statusMap[2]) // 两个都覆盖，FAILED 优先于 RUNNING
        assertEquals(ChapterStatus.FAILED, statusMap[3])
    }

    @Test
    fun jobCountAndCompilationCountPassedThrough() {
        val jobs = listOf(job("j1", 1, 2), job("j2", 3, 4))
        val summary = computeCoverage("book_1", "测试书", null, 120, jobs, 3)

        assertEquals(2, summary.jobCount)
        assertEquals(3, summary.compilationCount)
    }
}
