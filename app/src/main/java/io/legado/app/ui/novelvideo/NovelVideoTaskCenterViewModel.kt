package io.legado.app.ui.novelvideo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.data.entities.NovelVideoSegmentStatus
import io.legado.app.data.entities.NovelVideoCompilation
import io.legado.app.help.ai.NovelVideoCompiler
import io.legado.app.help.ai.NovelVideoParams
import io.legado.app.service.NovelVideoService
import io.legado.app.constant.AppConst
import io.legado.app.utils.GSON
import io.legado.app.utils.openFileUri
import io.legado.app.utils.share
import splitties.init.appCtx
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 任务中心 VM：通过 Room Flow 监听任务列表；服务状态由 Activity 在 EventBus 回调中调
 * [refreshServiceState] 同步。
 */
class NovelVideoTaskCenterViewModel(app: Application) : AndroidViewModel(app) {

    private val _runningJobs = MutableStateFlow<List<NovelVideoJob>>(emptyList())
    val runningJobs: StateFlow<List<NovelVideoJob>> = _runningJobs.asStateFlow()

    private val _completedJobs = MutableStateFlow<List<NovelVideoJob>>(emptyList())
    val completedJobs: StateFlow<List<NovelVideoJob>> = _completedJobs.asStateFlow()

    private val _failedJobs = MutableStateFlow<List<NovelVideoJob>>(emptyList())
    val failedJobs: StateFlow<List<NovelVideoJob>> = _failedJobs.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _allBooks = MutableStateFlow<List<Book>>(emptyList())
    val allBooks: StateFlow<List<Book>> = _allBooks.asStateFlow()

    /** 整部视频（跨章节拼接产物）列表。子项目 E。 */
    private val _compilations = MutableStateFlow<List<NovelVideoCompilation>>(emptyList())
    val compilations: StateFlow<List<NovelVideoCompilation>> = _compilations.asStateFlow()

    init {
        viewModelScope.launch { appDb.novelVideoDao.getRunningJobsFlow().collectLatest { _runningJobs.value = it } }
        viewModelScope.launch { appDb.novelVideoDao.getCompletedJobsFlow().collectLatest { _completedJobs.value = it } }
        viewModelScope.launch { appDb.novelVideoDao.getFailedJobsFlow().collectLatest { _failedJobs.value = it } }
        viewModelScope.launch { appDb.bookDao.flowAll().collectLatest { _allBooks.value = it } }
        viewModelScope.launch { appDb.novelVideoDao.getCompilationsFlow().collectLatest { _compilations.value = it } }
        _serviceRunning.value = NovelVideoService.isRun
    }

    /** 由 Activity 的 EventBus observer 调用以同步服务运行状态。 */
    fun refreshServiceState() {
        _serviceRunning.value = NovelVideoService.isRun
    }

    fun startService() {
        NovelVideoService.start(getApplication())
        _serviceRunning.value = true
    }

    fun stopService() {
        NovelVideoService.stop(getApplication())
        _serviceRunning.value = false
    }

    fun cancelCurrentJob() {
        NovelVideoService.cancelCurrentJob()
    }

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
        // 用 UUID 防止同毫秒创建碰撞（原 timestamp+random 有 1/10000 概率覆写前一个 job）
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
        NovelVideoService.start(getApplication())
        jobId
    }

    /**
     * 删除任务：FK CASCADE 会清理 segments 和 character sheets。
     * M1：同时清理磁盘上的分镜视频与合并产物文件，避免孤儿文件累积。
     */
    suspend fun deleteJob(jobId: String) = withContext(Dispatchers.IO) {
        NovelVideoJobOps.mutex.withLock {
            appDb.novelVideoDao.deleteJob(jobId)
            // 清理 filesDir/novel_video/<jobId>/ 目录（分镜段 + 合并产物）
            runCatching {
                java.io.File(appCtx.filesDir, "novel_video/$jobId").deleteRecursively()
            }
        }
    }

    /**
     * 重试任务：把失败的 segments 重置为 pending（已完成的保留），
     * job 状态置为 GENERATING，然后启动服务断点续传。
     *
     * R2 修复：补 PARTIAL_FAILED 状态（原仅允许 FAILED/CANCELLED，与 JobDetailViewModel 不一致）。
     * R6 修复：用 Mutex 串行化对同一 job 的操作，避免 retry 与 cancel 并发覆写；
     *        状态推进改用条件更新，避免覆写并发的 CANCELLED。
     */
    suspend fun retryJob(jobId: String) = withContext(Dispatchers.IO) {
        NovelVideoJobOps.mutex.withLock {
            val job = appDb.novelVideoDao.getJob(jobId) ?: return@withLock
            if (job.status == NovelVideoJobStatus.FAILED ||
                job.status == NovelVideoJobStatus.PARTIAL_FAILED ||
                job.status == NovelVideoJobStatus.CANCELLED
            ) {
                // M4+M5：智能重置非 VIDEO_COMPLETED 段，保留已生成的 imageUrl 避免重复生图。
                // - imageUrl 非空 → 重置为 IMAGE_COMPLETED（跳过 Stage 5 直接进 Stage 6）
                // - imageUrl 为空 → 重置为 PENDING（从头开始）
                // 覆盖 FAILED 段和中间态段（IMAGE_GENERATING/VIDEO_GENERATING，CANCELLED 时可能残留）
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
                // N1 修复：用 updateJobStatusForRetry 从终态转换回 GENERATING。
                // updateJobFinalStatusWithErrorIfNotFinished 的 WHERE 排除终态，无法 FAILED→GENERATING。
                appDb.novelVideoDao.updateJobStatusForRetry(
                    jobId, NovelVideoJobStatus.GENERATING, null, System.currentTimeMillis()
                )
            }
        }
        NovelVideoService.start(getApplication())
    }

    /**
     * 取消任务：委托 Service 取消协程（多 worker 下精确取消指定 jobId）+ 条件更新兜底。
     *
     * P6b：不再比较 currentJobId，直接调 [NovelVideoService.cancelJob]。
     * Service 统一处理：在 slots 中则取消协程，不在则直接标 CANCELLED。
     * 条件更新兜底：Service 未运行时确保标 CANCELLED；Service 已标则 0-rows 安全。
     *
     * 第五轮审查 R13 修复：改用 [NovelVideoDao.markJobCancelledIfActive]（清空 workerId/heartbeat），
     * 原 [NovelVideoDao.updateJobFinalStatusWithErrorIfNotFinished] 不清空 workerId，导致终态 job
     * 残留 workerId 数据不一致。
     */
    suspend fun cancelJob(jobId: String) = withContext(Dispatchers.IO) {
        NovelVideoJobOps.mutex.withLock {
            val job = appDb.novelVideoDao.getJob(jobId) ?: return@withLock
            if (job.status in NovelVideoJobStatus.RUNNING_STATES) {
                NovelVideoService.cancelJob(getApplication(), jobId)
                appDb.novelVideoDao.markJobCancelledIfActive(
                    jobId, NovelVideoJobStatus.CANCELLED, System.currentTimeMillis()
                )
            }
        }
    }

    /**
     * 拼接多个已完成 job 为整部视频（子项目 E）。
     *
     * 本地无损 mux、秒级完成，不进调度队列，直接调 [NovelVideoCompiler.compile]。
     * 成功后 `_compilations` Flow 会自动收到新条目（Room Flow 通知）。
     *
     * @return 成功的 [NovelVideoCompilation]；失败返回错误信息。
     */
    suspend fun compileJobs(jobIds: List<String>, title: String? = null): Result<NovelVideoCompilation> =
        withContext(Dispatchers.IO) {
            when (val r = NovelVideoCompiler.compile(jobIds, title)) {
                is NovelVideoCompiler.CompileResult.Success -> Result.success(r.compilation)
                is NovelVideoCompiler.CompileResult.Failed -> Result.failure(IllegalStateException(r.reason))
            }
        }

    /**
     * 删除整部视频（清文件 + DB 行）。子项目 E。
     * 复用 [NovelVideoJobOps.mutex] 避免与 job 删除并发。
     */
    suspend fun deleteCompilation(id: String) = withContext(Dispatchers.IO) {
        NovelVideoJobOps.mutex.withLock {
            val c = appDb.novelVideoDao.getCompilation(id) ?: return@withLock
            runCatching {
                java.io.File(appCtx.filesDir, "novel_video/compilations/$id").deleteRecursively()
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
            val uri = FileProvider.getUriForFile(context, AppConst.authority, file)
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

    companion object {
        // P6：jobOpMutex 已上移至 NovelVideoJobOps 共享单例（修复双 ViewModel 并发覆写）。
    }
}
