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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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

    /** 删除任务：FK CASCADE 会清理 segments 和 character sheets。 */
    suspend fun deleteJob(jobId: String) = withContext(Dispatchers.IO) {
        appDb.novelVideoDao.deleteJob(jobId)
    }

    /**
     * 重试任务：把失败的 segments 重置为 pending（已完成的保留），
     * job 状态置为 GENERATING，然后启动服务断点续传。
     */
    suspend fun retryJob(jobId: String) = withContext(Dispatchers.IO) {
        val job = appDb.novelVideoDao.getJob(jobId) ?: return@withContext
        if (job.status == NovelVideoJobStatus.FAILED || job.status == NovelVideoJobStatus.CANCELLED) {
            val segments = appDb.novelVideoDao.getSegmentsByJob(jobId)
            segments.filter { it.status == NovelVideoSegmentStatus.FAILED }.forEach { seg ->
                appDb.novelVideoDao.updateSegmentStatus(seg.id, NovelVideoSegmentStatus.PENDING, null)
            }
            appDb.novelVideoDao.updateJobStatusWithError(
                jobId, NovelVideoJobStatus.GENERATING, null
            )
        }
        NovelVideoService.start(getApplication())
    }

    /**
     * 取消任务：当前正在跑的 job 通过 Service 取消；其他 RUNNING_STATES 直接标记 CANCELLED。
     */
    suspend fun cancelJob(jobId: String) = withContext(Dispatchers.IO) {
        val job = appDb.novelVideoDao.getJob(jobId) ?: return@withContext
        if (job.id == NovelVideoService.currentJobId) {
            NovelVideoService.cancelCurrentJob()
        } else if (job.status in NovelVideoJobStatus.RUNNING_STATES) {
            appDb.novelVideoDao.updateJobStatusWithError(
                jobId, NovelVideoJobStatus.CANCELLED, "用户手动取消", System.currentTimeMillis()
            )
        }
    }
}
