package io.legado.app.help.ai

import io.legado.app.constant.AppLog
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
     * 安全日志：[AppLog.put] 内部触发 [io.legado.app.help.config.AppConfig] 静态初始化，
     * 在 Robolectric（appCtx 未初始化）下抛 ExceptionInInitializerError。
     * 用 runCatching 吞掉，保证日志失败不影响业务流程（对齐 OrphanRecoveryWorker 模式 + cae22457 修复）。
     */
    private fun log(msg: String, throwable: Throwable? = null) {
        runCatching { AppLog.put(msg, throwable) }
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
     * @param outputDir 产物父目录（默认 `appCtx.filesDir/novel_video/compilations`，内部再建 `<nvc_id>/full.mp4`）；
     *                  测试注入临时目录以隔离 [appCtx]（普通 Application 下未初始化）
     * @return [CompileResult]；失败时不留半成品文件
     */
    suspend fun compile(
        jobIds: List<String>,
        title: String? = null,
        dao: NovelVideoDao = appDb.novelVideoDao,
        mediaMerger: suspend (inputPaths: List<String>, outputPath: String) -> MediaMergeOutcome = ::defaultMediaMerger,
        outputDir: File? = null
    ): CompileResult = withContext(Dispatchers.IO) {
        log("NovelVideoCompiler 开始编译：${jobIds.size} 个任务")
        // ===== 校验链（spec §5.2，按顺序短路）=====
        if (jobIds.size < 2) {
            log("NovelVideoCompiler 拒绝：任务数 < 2")
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
        val baseDir = outputDir ?: File(appCtx.filesDir, "novel_video/compilations")
        val outDir = File(baseDir, compilationId)
        val outputFile = File(outDir, "full.mp4")
        outDir.mkdirs()

        try {
            // ===== 格式一致性预检 + 合并 =====
            val outcome = mediaMerger(inputPaths, outputFile.absolutePath)
            when (outcome) {
                is MediaMergeOutcome.FormatInconsistent -> {
                    log("NovelVideoCompiler 格式不一致：${outcome.rawError}")
                    return@withContext CompileResult.Failed(
                        wrapFormatErrorWithJobContext(outcome.rawError, sortedJobs)
                    )
                }
                is MediaMergeOutcome.Failed -> {
                    log("NovelVideoCompiler 媒体合并失败：${outcome.message}")
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
                    val inserted = try {
                        dao.insertCompilation(compilation)
                        true
                    } catch (e: CancellationException) {
                        // 协程取消必须向上传播，不能误判为 DB 写入失败并删除已产出的 mp4
                        throw e
                    } catch (e: Throwable) {
                        log("NovelVideoCompiler 整部视频记录写入数据库失败：${compilation.id}", e)
                        false
                    }
                    if (!inserted) {
                        // Room 写入失败（极罕见）：清理已产出的 mp4 文件
                        outputFile.delete()
                        outDir.delete()
                        return@withContext CompileResult.Failed("整部视频记录写入数据库失败")
                    }
                    log("NovelVideoCompiler 编译成功：${compilation.id}，${compilation.segmentCount} 段，${compilation.totalDurationMs ?: 0}ms")
                    return@withContext CompileResult.Success(compilation)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log("NovelVideoCompiler 编译异常", e)
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
     * spec §5.2 要求错误格式：`任务 <X> 与 <Y> 的视频格式不一致（<detail>）`，
     * 故把原始错误中的「第 N 段」替换为对应 job 的 bookName + chapterRange，
     * 「首段」「首个文件」替换为首个 job 的标识，精确到具体不一致的两章。
     *
     * 例：原始「第 2 段分辨率 1280x720 与首段 1920x1080 不一致，无法无损合并」
     *   → 「任务「甲书 第0-1章」与「甲书 第2-3章」的视频格式不一致（分辨率 1280x720 与 1920x1080 不一致），无法无损拼接...」
     */
    private fun wrapFormatErrorWithJobContext(rawError: String, jobs: List<NovelVideoJob>): String {
        if (jobs.isEmpty()) return rawError
        val firstLabel = jobLabel(jobs[0])
        // 「第 N 段」的 N 是 1-based 索引，对应 jobs[N-1]
        var resolved = rawError
        resolved = resolved.replace("首个文件", firstLabel)
        resolved = resolved.replace("首段", firstLabel)
        // 正则匹配「第 N 段」并替换为对应 job 标识
        val segmentPattern = Regex("第 (\\d+) 段")
        resolved = segmentPattern.replace(resolved) { m ->
            val idx = m.groupValues[1].toIntOrNull()
            if (idx != null && idx in 1..jobs.size) jobLabel(jobs[idx - 1]) else m.value
        }
        return "任务的视频格式不一致（$resolved），无法无损拼接，请用相同 provider/分辨率重新生成"
    }

    private fun jobLabel(job: NovelVideoJob): String {
        return "「${job.bookName.ifBlank { "未命名" }} 第${job.chapterStartIndex}-${job.chapterEndIndex}章」"
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
