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
import io.legado.app.help.ai.NovelVideoParams
import io.legado.app.service.NovelVideoService
import io.legado.app.utils.GSON
import splitties.init.appCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
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

    init {
        viewModelScope.launch { appDb.novelVideoDao.getRunningJobsFlow().collectLatest { _runningJobs.value = it } }
        viewModelScope.launch { appDb.novelVideoDao.getCompletedJobsFlow().collectLatest { _completedJobs.value = it } }
        viewModelScope.launch { appDb.novelVideoDao.getFailedJobsFlow().collectLatest { _failedJobs.value = it } }
        viewModelScope.launch { appDb.bookDao.flowAll().collectLatest { _allBooks.value = it } }
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
        jobOpMutex.withLock {
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
        jobOpMutex.withLock {
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
                        appDb.novelVideoDao.updateSegmentStatus(seg.id, newStatus, null)
                    }
                // R6：用条件更新推进 GENERATING，若期间已被并发置为终态（如 CANCELLED），不覆写
                appDb.novelVideoDao.updateJobFinalStatusWithErrorIfNotFinished(
                    jobId, NovelVideoJobStatus.GENERATING, null, System.currentTimeMillis()
                )
            }
        }
        NovelVideoService.start(getApplication())
    }

    /**
     * 取消任务：当前正在跑的 job 通过 Service 取消；其他 RUNNING_STATES 直接标记 CANCELLED。
     *
     * R6 修复：用 Mutex 与 retryJob 串行化，避免并发覆写。
     */
    suspend fun cancelJob(jobId: String) = withContext(Dispatchers.IO) {
        jobOpMutex.withLock {
            val job = appDb.novelVideoDao.getJob(jobId) ?: return@withLock
            if (job.id == NovelVideoService.currentJobId) {
                NovelVideoService.cancelCurrentJob()
            } else if (job.status in NovelVideoJobStatus.RUNNING_STATES) {
                // 用条件更新：若 getJob 与写入之间状态被并发改变为终态，不覆写
                appDb.novelVideoDao.updateJobFinalStatusWithErrorIfNotFinished(
                    jobId, NovelVideoJobStatus.CANCELLED, "用户手动取消", System.currentTimeMillis()
                )
            }
        }
    }

    companion object {
        /** R6：对同一 job 的 retry/cancel/delete 操作串行化，避免并发覆写状态。 */
        private val jobOpMutex = Mutex()
    }
}
