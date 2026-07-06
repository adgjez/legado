package io.legado.app.ui.novelvideo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.help.ai.NovelVideoGenerator
import io.legado.app.help.ai.ScreenplayDraft
import io.legado.app.help.ai.Scene
import io.legado.app.service.NovelVideoService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 剧本审阅 VM。
 *
 * - [bind] 后从 DB 加载 [NovelVideoJob.draftJson]，解析为 [ScreenplayDraft]
 * - UI 可对 [Scene] 进行增删改；提交时调 [NovelVideoGenerator.confirmScreenplay]
 *   把编辑后的 draft 写回 [NovelVideoJob.screenplayJson] 并解除 Generator 中的挂起
 * - 用户取消时调 [NovelVideoGenerator.cancelFromReview]
 *
 * 注意：状态过滤——仅当 job.status == [NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW]
 * 时才允许确认/取消。其他状态只读。
 */
class NovelVideoScreenplayReviewViewModel(app: Application) : AndroidViewModel(app) {

    private val _job = MutableStateFlow<NovelVideoJob?>(null)
    val job: StateFlow<NovelVideoJob?> = _job.asStateFlow()

    private val _draft = MutableStateFlow<ScreenplayDraft?>(null)
    val draft: StateFlow<ScreenplayDraft?> = _draft.asStateFlow()

    /** 提交中标记，避免重复点击。 */
    private val _submitting = MutableStateFlow(false)
    val submitting: StateFlow<Boolean> = _submitting.asStateFlow()

    private var lastSeenStatus: String? = null
    private var boundJobId: String? = null

    /**
     * M9：bind 幂等——同一 jobId 不重复订阅 Flow，避免旋转后收集器累积。
     */
    fun bind(jobId: String) {
        if (boundJobId == jobId) return
        boundJobId = jobId
        viewModelScope.launch {
            appDb.novelVideoDao.getJobFlow(jobId).collectLatest { j ->
                _job.value = j
                // 仅在首次加载或 status 变化时重载 draft，避免用户编辑期间被 DB 推送覆写
                val statusChanged = j?.status != lastSeenStatus
                lastSeenStatus = j?.status
                if (!statusChanged && _draft.value != null) return@collectLatest

                if (j?.draftJson?.isNotBlank() == true) {
                    // draftJson 损坏时不让 collectLatest 终止，降级为 null 让 UI 显示空态
                    _draft.value = runCatching {
                        ScreenplayDraft.fromJson(j.draftJson)
                    }.getOrNull()
                } else if (j?.screenplayJson?.isNotBlank() == true) {
                    // 已确认过的剧本也能查看（只读）
                    runCatching {
                        val sp = io.legado.app.help.ai.Screenplay.fromJson(j.screenplayJson)
                        ScreenplayDraft(
                            taskId = sp.taskId,
                            title = sp.title,
                            scenes = sp.scenes
                        )
                    }.getOrNull()?.let { _draft.value = it }
                } else {
                    _draft.value = null
                }
            }
        }
    }

    /** 是否允许编辑/确认/取消。 */
    fun canEdit(): Boolean = _job.value?.status == NovelVideoJobStatus.SCREENPLAY_PENDING_REVIEW

    /** 更新某个 scene 的字段。 */
    fun updateScene(sceneId: Int, updater: (Scene) -> Scene) {
        val cur = _draft.value ?: return
        val newScenes = cur.scenes.map { if (it.sceneId == sceneId) updater(it) else it }
        _draft.value = cur.copy(scenes = newScenes)
    }

    /** 更新剧本标题。 */
    fun updateTitle(title: String) {
        val cur = _draft.value ?: return
        _draft.value = cur.copy(title = title)
    }

    /** 新增一个场景（sceneId 取当前最大值 + 1）。 */
    fun addScene() {
        val cur = _draft.value ?: return
        val nextId = (cur.scenes.maxOfOrNull { it.sceneId } ?: 0) + 1
        _draft.value = cur.copy(
            scenes = cur.scenes + Scene(sceneId = nextId)
        )
    }

    /** 删除一个场景。 */
    fun deleteScene(sceneId: Int) {
        val cur = _draft.value ?: return
        _draft.value = cur.copy(scenes = cur.scenes.filterNot { it.sceneId == sceneId })
    }

    /** 确认剧本，进入生成阶段。 */
    fun confirm(onDone: () -> Unit) {
        val j = _job.value ?: return
        val d = _draft.value ?: return
        if (!_submitting.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                NovelVideoGenerator.confirmScreenplay(j.id, d)
                NovelVideoService.start(getApplication())
            } finally {
                _submitting.value = false
                onDone()
            }
        }
    }

    /** 取消任务。 */
    fun cancel(onDone: () -> Unit) {
        val j = _job.value ?: return
        if (!_submitting.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            try {
                NovelVideoGenerator.cancelFromReview(j.id)
            } finally {
                _submitting.value = false
                onDone()
            }
        }
    }
}
