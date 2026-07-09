package io.legado.app.ui.novelvideo

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import io.legado.app.constant.AppConst
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.NovelVideoCompilation
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.data.entities.NovelVideoSegmentStatus
import io.legado.app.help.ai.NovelVideoCompiler
import io.legado.app.help.ai.NovelVideoParams
import io.legado.app.service.NovelVideoService
import io.legado.app.utils.GSON
import io.legado.app.utils.openFileUri
import io.legado.app.utils.share
import splitties.init.appCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 小说视频任务的共享操作（P6 引入互斥锁，子项目 C 抽取操作方法）。
 *
 * **背景**：原先这些操作散落在 [NovelVideoTaskCenterViewModel] 和 [NovelVideoJobDetailViewModel]，
 * 子项目 C 的 [NovelVideoBookDetailViewModel] 也要用。为避免三处复制，集中到本 object。
 *
 * **互斥锁**：[mutex] 串行化对同一 job 的操作（retry/cancel/delete 并发会覆写状态）。
 * 不区分 jobId——不同 job 的操作互不冲突，串行化开销可忽略。若未来锁竞争明显，可改 per-jobId map。
 *
 * **线程约定**：suspend 方法均自包 `withContext(Dispatchers.IO)`，可从任意线程调用。
 */
object NovelVideoJobOps {
    val mutex: Mutex = Mutex()

    /** 加载指定书的章节列表（供 NewTask 配置 sheet 用）。 */
    suspend fun loadChapters(bookUrl: String): List<BookChapter> = withContext(Dispatchers.IO) {
        runCatching { appDb.bookChapterDao.getChapterList(bookUrl) }.getOrDefault(emptyList())
    }

    /**
     * 创建新任务：组装 [NovelVideoJob] 落库 + 拉起服务。
     * @return 新建的 jobId
     */
    suspend fun createJob(
        book: Book,
        chapterStartIndex: Int,
        chapterEndIndex: Int,
        chapters: List<BookChapter>,
        params: NovelVideoParams
    ): String = withContext(Dispatchers.IO) {
        // UUID 防碰撞（原 timestamp+random 有 1/10000 概率覆写前一个 job）
        val jobId = "nv_${java.util.UUID.randomUUID()}"
        val chapterTitles = chapters
            .filter { it.index in chapterStartIndex..chapterEndIndex }
            .map { it.title }
        val job = NovelVideoJob(
            id = jobId,
            bookUrl = book.bookUrl,
            bookName = book.name,
            chapterStartIndex = chapterStartIndex,
            chapterEndIndex = chapterEndIndex,
            chapterTitlesJson = GSON.toJson(chapterTitles),
            status = NovelVideoJobStatus.DRAFTING,
            paramsJson = params.toJson(),
            attachToBookChapter = params.attachToBookChapter,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        appDb.novelVideoDao.insertJob(job)
        NovelVideoService.start(appCtx)
        jobId
    }

    /**
     * 删除任务：FK CASCADE 清理 segments 和 character sheets；同时清磁盘文件。
     */
    suspend fun deleteJob(jobId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            appDb.novelVideoDao.deleteJob(jobId)
            runCatching {
                File(appCtx.filesDir, "novel_video/$jobId").deleteRecursively()
            }
        }
    }

    /**
     * 重试任务：把非 VIDEO_COMPLETED 段重置，job 置 GENERATING，启动服务断点续传。
     */
    suspend fun retryJob(jobId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val job = appDb.novelVideoDao.getJob(jobId) ?: return@withLock
            if (job.status == NovelVideoJobStatus.FAILED ||
                job.status == NovelVideoJobStatus.PARTIAL_FAILED ||
                job.status == NovelVideoJobStatus.CANCELLED
            ) {
                // 智能重置：imageUrl 非空→IMAGE_COMPLETED（跳过生图），空→PENDING（从头）
                appDb.novelVideoDao.getSegmentsByJob(jobId)
                    .filter { it.status != NovelVideoSegmentStatus.VIDEO_COMPLETED }
                    .forEach { seg ->
                        val newStatus = if (seg.imageUrl != null) {
                            NovelVideoSegmentStatus.IMAGE_COMPLETED
                        } else {
                            NovelVideoSegmentStatus.PENDING
                        }
                        appDb.novelVideoDao.updateSegmentStatusIfNotTerminal(seg.id, newStatus, null)
                    }
                appDb.novelVideoDao.updateJobStatusForRetry(
                    jobId, NovelVideoJobStatus.GENERATING, null, System.currentTimeMillis()
                )
            }
        }
        NovelVideoService.start(appCtx)
    }

    /**
     * 取消任务：委托 Service 取消协程 + 条件更新兜底。
     */
    suspend fun cancelJob(jobId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val job = appDb.novelVideoDao.getJob(jobId) ?: return@withLock
            if (job.status in NovelVideoJobStatus.RUNNING_STATES) {
                NovelVideoService.cancelJob(appCtx, jobId)
                appDb.novelVideoDao.markJobCancelledIfActive(
                    jobId, NovelVideoJobStatus.CANCELLED, System.currentTimeMillis()
                )
            }
        }
    }

    /**
     * 拼接多个已完成 job 为整部视频（子项目 E）。本地无损 mux，秒级完成。
     * @return 成功的 [NovelVideoCompilation]；失败返回错误
     */
    suspend fun compileJobs(jobIds: List<String>, title: String? = null): Result<NovelVideoCompilation> =
        withContext(Dispatchers.IO) {
            when (val r = NovelVideoCompiler.compile(jobIds, title)) {
                is NovelVideoCompiler.CompileResult.Success -> Result.success(r.compilation)
                is NovelVideoCompiler.CompileResult.Failed -> Result.failure(IllegalStateException(r.reason))
            }
        }

    /**
     * 删除整部视频（清文件 + DB 行）。
     */
    suspend fun deleteCompilation(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val c = appDb.novelVideoDao.getCompilation(id) ?: return@withLock
            runCatching {
                File(appCtx.filesDir, "novel_video/compilations/$id").deleteRecursively()
            }
            appDb.novelVideoDao.deleteCompilation(id)
        }
    }

    /** 用系统播放器打开整部视频。 */
    fun openCompilation(context: Context, c: NovelVideoCompilation?): Boolean {
        val path = c?.outputPath?.takeIf { it.isNotBlank() } ?: return false
        val file = File(path)
        if (!file.isFile) return false
        return runCatching {
            val uri: Uri = FileProvider.getUriForFile(context, AppConst.authority, file)
            context.openFileUri(uri, "video/*")
            true
        }.getOrElse { false }
    }

    /** 分享整部视频。 */
    fun shareCompilation(context: Context, c: NovelVideoCompilation?): Boolean {
        val path = c?.outputPath?.takeIf { it.isNotBlank() } ?: return false
        val file = File(path)
        if (!file.isFile) return false
        return runCatching {
            context.share(file, "video/*")
            true
        }.getOrElse { false }
    }
}
