package io.legado.app.help.ai

import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus

/**
 * 单个章节在 novel-video 流水线中的覆盖状态（[ChapterCoverageGrid] 渲染色块用）。
 */
enum class ChapterStatus {
    /** 该章无任何 job 覆盖。 */
    NONE,

    /** 该章被某个进行中 job 覆盖（drafting/generating/merging/paused/待审阅）。 */
    RUNNING,

    /** 该章被某个 COMPLETED job 覆盖（有可用产出）。 */
    COMPLETED,

    /** 该章被某个 FAILED/PARTIAL_FAILED job 覆盖（无可用产出）。 */
    FAILED
}

/**
 * 一本书的 novel-video 聚合视图（纯数据，不落库；由 [computeCoverage] 从 jobs 计算）。
 *
 * 用于书架卡片和书详情页概要：让用户一眼看到「这本书做到哪了、还差哪些章节」。
 *
 * @see computeCoverage
 */
data class BookNovelVideoSummary(
    val bookUrl: String,
    val bookName: String,
    val coverPath: String?,
    /** 书的总章节数（[io.legado.app.data.dao.BookChapterDao.getChapterCount]）；0 表示未知。 */
    val totalChapters: Int,
    /** 所有 job 覆盖的章节索引并集（running + completed + failed）。 */
    val coveredChapterIndices: Set<Int>,
    /** 状态 COMPLETED 的 job 覆盖的章节。 */
    val completedChapterIndices: Set<Int>,
    /** 状态 FAILED/PARTIAL_FAILED 的 job 覆盖的章节。 */
    val failedChapterIndices: Set<Int>,
    /** 进行中状态（drafting/generating/...）的 job 覆盖的章节。 */
    val runningChapterIndices: Set<Int>,
    val jobCount: Int,
    val compilationCount: Int
) {
    val coveredCount: Int get() = coveredChapterIndices.size
    val progress: Float get() = if (totalChapters <= 0) 0f else coveredCount.toFloat() / totalChapters

    /**
     * 每章的状态映射（[ChapterCoverageGrid] 渲染色块用）。
     *
     * 优先级：COMPLETED > FAILED > RUNNING > NONE。
     * 同一章被多个 job 覆盖时，取「最有价值的终态」——例如某章既被一个 FAILED job 覆盖，
     * 又被一个 COMPLETED job 覆盖（重做成功），应显示 COMPLETED。
     */
    fun statusByChapter(): Map<Int, ChapterStatus> {
        val result = HashMap<Int, ChapterStatus>(coveredChapterIndices.size)
        // 顺序：先写低优先级，后写高优先级覆盖
        for (idx in runningChapterIndices) result[idx] = ChapterStatus.RUNNING
        for (idx in failedChapterIndices) result[idx] = ChapterStatus.FAILED
        for (idx in completedChapterIndices) result[idx] = ChapterStatus.COMPLETED
        return result
    }
}

/**
 * 从 jobs + 元数据计算 [BookNovelVideoSummary]（纯函数，无副作用，可单测）。
 *
 * 算法：遍历 jobs，对每个 job 把 `[chapterStartIndex, chapterEndIndex]` 展开为整数集，
 * 按 [NovelVideoJob.status] 归入对应集合。覆盖并集 = completed ∪ failed ∪ running。
 *
 * 边界：
 * - `chapterStartIndex`/`chapterEndIndex` 为 -1（异常 job）的忽略，不并入任何集合。
 * - `chapterEndIndex < chapterStartIndex` 的忽略。
 * - 状态归集：completed=[NovelVideoJobStatus.COMPLETED]；
 *   failed=[NovelVideoJobStatus.FAILED]/[NovelVideoJobStatus.PARTIAL_FAILED]；
 *   running=[NovelVideoJobStatus.DRAFTING]/SCREENPLAY_*/[NovelVideoJobStatus.GENERATING]/[NovelVideoJobStatus.MERGING]/[NovelVideoJobStatus.PAUSED]；
 *   [NovelVideoJobStatus.CANCELLED] 不计入覆盖（被取消=没做）。
 */
fun computeCoverage(
    bookUrl: String,
    bookName: String,
    coverPath: String?,
    totalChapters: Int,
    jobs: List<NovelVideoJob>,
    compilationCount: Int
): BookNovelVideoSummary {
    val completed = HashSet<Int>()
    val failed = HashSet<Int>()
    val running = HashSet<Int>()

    for (job in jobs) {
        val start = job.chapterStartIndex
        val end = job.chapterEndIndex
        // 异常区间忽略（-1 或 end<start）
        if (start < 0 || end < 0 || end < start) continue

        val bucket: HashSet<Int> = when (job.status) {
            NovelVideoJobStatus.COMPLETED -> completed
            NovelVideoJobStatus.FAILED, NovelVideoJobStatus.PARTIAL_FAILED -> failed
            NovelVideoJobStatus.DRAFTING,
            NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW,
            NovelVideoJobStatus.SCREENPLAY_CONFIRMED,
            NovelVideoJobStatus.GENERATING,
            NovelVideoJobStatus.MERGING,
            NovelVideoJobStatus.PAUSED -> running
            NovelVideoJobStatus.CANCELLED -> continue  // 取消=没做，不计入覆盖
            else -> continue
        }
        for (i in start..end) bucket.add(i)
    }

    val covered = HashSet<Int>(completed.size + failed.size + running.size).apply {
        addAll(completed)
        addAll(failed)
        addAll(running)
    }

    return BookNovelVideoSummary(
        bookUrl = bookUrl,
        bookName = bookName,
        coverPath = coverPath,
        totalChapters = totalChapters,
        coveredChapterIndices = covered,
        completedChapterIndices = completed,
        failedChapterIndices = failed,
        runningChapterIndices = running,
        jobCount = jobs.size,
        compilationCount = compilationCount
    )
}
