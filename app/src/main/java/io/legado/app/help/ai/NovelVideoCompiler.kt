package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.dao.NovelVideoDao
import io.legado.app.data.entities.NovelVideoCompilation
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.utils.GSON
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.UUID

/**
 * 跨章节拼接成整部视频编译器。子项目 E。
 *
 * 把 ≥2 个同书已完成 [NovelVideoJob] 的合并产物（`novel_video/<jobId>/merged.mp4`）
 * 经 [VideoMuxer.merge] 无损拼接为 `novel_video/compilations/<nvc_id>/full.mp4`，
 * 落库为 [NovelVideoCompilation]。
 *
 * 设计要点：
 * - 本地无损 mux、无网络、秒级完成，**不进 GenerationQueue 调度**，由 UI 层直接 `lifecycleScope.launch`。
 * - 格式不一致（mime/宽高）直接拒绝，不引入转码（spec §2 YAGNI）。
 * - 失败时不留半成品文件（[finally] 清理产出目录）。
 * - 源 job 删除不影响已产出的整部视频（不建 FK，产物自包含）。
 *
 * 详见 `docs/superpowers/specs/2026-07-08-novel-video-cross-chapter-compilation-design.md`。
 */
object NovelVideoCompiler {

    /** 编译结果。 */
    sealed class CompileResult {
        data class Success(val compilation: NovelVideoCompilation) : CompileResult()
        data class Failed(val reason: String) : CompileResult()
    }

    /**
     * 媒体合并结果（[mediaMerger] 返回）。把 [VideoMuxer.checkFormatConsistency] 与
     * [VideoMuxer.merge] 两步合一，便于测试注入与错误归类。
     */
    sealed class MediaMergeOutcome {
        /** 格式不一致。[rawError] 为 `checkFormatConsistency` 原始返回（含「第 N 段」索引）。 */
        data class FormatInconsistent(val rawError: String) : MediaMergeOutcome()
        /** 合并成功。 */
        data class Merged(
            val outputPath: String,
            val totalDurationMs: Long,
            val segmentCount: Int
        ) : MediaMergeOutcome()
        /** 合并失败（IO/容器兼容性等）。 */
        data class Failed(val message: String) : MediaMergeOutcome()
    }

    /**
     * 编译多个已完成 job 的产物为一部整部视频。
     *
     * 步骤：校验链（spec §5.2）→ 按 chapterStartIndex 排序 → 格式一致性预检 →
     * 复用 VideoMuxer.merge → 落库 NovelVideoCompilation。
     *
     * @param jobIds 源 job ID 列表（顺序无关，内部按 chapterStartIndex 排序）
     * @param title 可选标题；缺省由 bookName + 时间戳生成
     * @param dao NovelVideoDao（默认 appDb；测试注入 in-memory）
     * @param mediaMerger 媒体合并步骤（默认 [defaultMediaMerger]，测试可注入 stub）
     * @return [CompileResult]；失败时不留半成品文件
     */
    suspend fun compile(
        jobIds: List<String>,
        title: String? = null,
        dao: NovelVideoDao = appDb.novelVideoDao,
        mediaMerger: suspend (inputPaths: List<String>, outputPath: String) -> MediaMergeOutcome = ::defaultMediaMerger
    ): CompileResult = withContext(Dispatchers.IO) {
        // ===== 校验链（spec §5.2，按顺序短路）=====
        if (jobIds.size < 2) {
            return@withContext CompileResult.Failed("至少选择 2 个任务才能拼成整部视频")
        }
        val jobs = mutableListOf<NovelVideoJob>()
        for (id in jobIds) {
            val job = dao.getJob(id)
                ?: return@withContext CompileResult.Failed("任务 $id 不存在")
            if (job.status != NovelVideoJobStatus.COMPLETED) {
                return@withContext CompileResult.Failed(
                    "任务「${job.bookName}」尚未完成（当前状态 ${job.status}），仅可拼接已完成任务"
                )
            }
            val path = job.outputPath
            if (path.isNullOrBlank() || !File(path).isFile) {
                return@withContext CompileResult.Failed(
                    "任务「${job.bookName}」的合并产物文件缺失，请重新生成"
                )
            }
            jobs.add(job)
        }
        // 校验同书
        val firstBookUrl = jobs.first().bookUrl
        val mismatched = jobs.firstOrNull { it.bookUrl != firstBookUrl }
        if (mismatched != null) {
            val a = jobs.first { it.bookUrl == firstBookUrl }.bookName.ifBlank { firstBookUrl }
            val b = mismatched.bookName.ifBlank { mismatched.bookUrl }
            return@withContext CompileResult.Failed("只能拼接同一本书的任务（涉及「$a」与「$b」）")
        }

        // ===== 排序：按 chapterStartIndex 升序（同 index 按 createdAt 兜底）=====
        val sortedJobs = jobs.sortedWith(compareBy({ it.chapterStartIndex }, { it.createdAt }))
        val inputPaths = sortedJobs.map { it.outputPath!! }
        val sourceJobIds = sortedJobs.map { it.id }

        // ===== 编译产物路径 =====
        val compilationId = "nvc_${UUID.randomUUID()}"
        val outDir = File(appCtx.filesDir, "novel_video/compilations/$compilationId")
        val outputFile = File(outDir, "full.mp4")
        outDir.mkdirs()

        try {
            // ===== 格式一致性预检 + 合并 =====
            val outcome = mediaMerger(inputPaths, outputFile.absolutePath)
            when (outcome) {
                is MediaMergeOutcome.FormatInconsistent -> {
                    return@withContext CompileResult.Failed(
                        wrapFormatErrorWithJobContext(outcome.rawError, sortedJobs)
                    )
                }
                is MediaMergeOutcome.Failed -> {
                    return@withContext CompileResult.Failed("视频拼接失败：${outcome.message}")
                }
                is MediaMergeOutcome.Merged -> {
                    // ===== 落库 =====
                    val now = System.currentTimeMillis()
                    val bookName = sortedJobs.first().bookName
                    val resolvedTitle = title?.takeIf { it.isNotBlank() }
                        ?: "${bookName.ifBlank { "未命名" }} 整部视频 ${now}"
                    val compilation = NovelVideoCompilation(
                        id = compilationId,
                        title = resolvedTitle,
                        bookUrl = firstBookUrl,
                        bookName = bookName,
                        sourceJobIdsJson = GSON.toJson(sourceJobIds),
                        outputPath = outcome.outputPath,
                        totalDurationMs = outcome.totalDurationMs,
                        segmentCount = outcome.segmentCount,
                        createdAt = now,
                        updatedAt = now
                    )
                    val inserted = runCatching { dao.insertCompilation(compilation) }
                    if (inserted.isFailure) {
                        // Room 写入失败（极罕见）：清理已产出的 mp4 文件
                        outputFile.delete()
                        outDir.delete()
                        return@withContext CompileResult.Failed("整部视频记录写入数据库失败：${inserted.exceptionOrNull()?.message}")
                    }
                    return@withContext CompileResult.Success(compilation)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return@withContext CompileResult.Failed("视频拼接发生异常：${e.message}")
        } finally {
            // 失败/异常时清理空产出目录（成功时目录非空，delete() 无效，安全）
            if (!outputFile.exists()) {
                outDir.delete()
            }
        }
    }

    /**
     * 把 `checkFormatConsistency` 返回的「第 N 段」索引错误信息包装为含 job 标识的描述。
     *
     * 例：原始「第 2 段分辨率 1280x720 与首段 1920x1080 不一致，无法无损合并」
     *   → 「任务「<bookName> 第 X-Y 章」与「<bookName> 第 X-Y 章」的视频格式不一致...」
     */
    private fun wrapFormatErrorWithJobContext(rawError: String, jobs: List<NovelVideoJob>): String {
        // 简单策略：把原始错误透传，但前缀拼接所有候选 job 的标识，便于用户定位。
        val jobLabels = jobs.joinToString("、") {
            "「${it.bookName.ifBlank { "未命名" }} 第${it.chapterStartIndex}-${it.chapterEndIndex}章」"
        }
        return "任务 $jobLabels 之间存在格式不一致（$rawError），无法无损拼接，请用相同 provider/分辨率重新生成"
    }

    /** 默认媒体合并：[VideoMuxer.checkFormatConsistency] + [VideoMuxer.merge]。 */
    private suspend fun defaultMediaMerger(
        inputPaths: List<String>,
        outputPath: String
    ): MediaMergeOutcome {
        // 格式一致性预检
        if (inputPaths.size > 1) {
            val consistencyError = VideoMuxer.checkFormatConsistency(inputPaths)
            if (consistencyError != null) {
                return MediaMergeOutcome.FormatInconsistent(consistencyError)
            }
        }
        // 合并
        return when (val r = VideoMuxer.merge(inputPaths, outputPath)) {
            is VideoMuxer.MergeResult.Success -> MediaMergeOutcome.Merged(
                outputPath = r.outputPath,
                totalDurationMs = r.totalDurationMs,
                segmentCount = r.segmentCount
            )
            is VideoMuxer.MergeResult.Failed -> MediaMergeOutcome.Failed(r.message)
        }
    }
}
