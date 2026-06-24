package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGenTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

object AiGenTaskManager {

    private val _taskUpdates = MutableStateFlow<AiGenTask?>(null)
    val taskUpdates: Flow<AiGenTask?> get() = _taskUpdates

    suspend fun createTask(
        modality: String,
        prompt: String,
        providerId: String,
        providerName: String,
        model: String,
        priority: Int = 0,
        parentTaskId: Long? = null,
        negativePrompt: String = "",
        inputImageId: String? = null,
        referenceImageId: String? = null,
        bookKey: String = "",
        chapterIndex: Int = -1,
        sourceType: String = "",
        costEstimate: Double = 0.0
    ): Long {
        return withContext(Dispatchers.IO) {
            val task = AiGenTask(
                modality = modality,
                prompt = prompt,
                providerId = providerId,
                providerName = providerName,
                model = model,
                priority = priority,
                parentTaskId = parentTaskId,
                negativePrompt = negativePrompt,
                inputImageId = inputImageId,
                referenceImageId = referenceImageId,
                bookKey = bookKey,
                chapterIndex = chapterIndex,
                sourceType = sourceType,
                costEstimate = costEstimate,
                status = "pending"
            )
            val id = appDb.aiGenTaskDao.insert(task)
            _taskUpdates.emit(appDb.aiGenTaskDao.get(id))
            id
        }
    }

    suspend fun submitTask(taskId: Long, remoteTaskId: String) {
        withContext(Dispatchers.IO) {
            appDb.aiGenTaskDao.updateStatus(taskId, "submitted")
            appDb.aiGenTaskDao.get(taskId)?.let { task ->
                _taskUpdates.emit(task.copy(status = "submitted", remoteTaskId = remoteTaskId))
            }
        }
    }

    suspend fun updateProgress(taskId: Long, status: String, progress: Int, previewUrl: String = "") {
        withContext(Dispatchers.IO) {
            appDb.aiGenTaskDao.updateProgress(taskId, status, progress, previewUrl)
            appDb.aiGenTaskDao.get(taskId)?.let { _taskUpdates.emit(it) }
        }
    }

    suspend fun updateEmotionalHint(taskId: Long, hint: String) {
        withContext(Dispatchers.IO) {
            appDb.aiGenTaskDao.updateEmotionalHint(taskId, hint)
            appDb.aiGenTaskDao.get(taskId)?.let { _taskUpdates.emit(it) }
        }
    }

    suspend fun completeTask(taskId: Long, resultId: String, resultPath: String, costActual: Double) {
        withContext(Dispatchers.IO) {
            appDb.aiGenTaskDao.updateResult(taskId, "done", resultId, resultPath, costActual)
            appDb.aiGenTaskDao.get(taskId)?.let { _taskUpdates.emit(it) }
        }
    }

    suspend fun failTask(taskId: Long, error: String) {
        withContext(Dispatchers.IO) {
            val task = appDb.aiGenTaskDao.get(taskId) ?: return@withContext
            appDb.aiGenTaskDao.updateError(taskId, "failed", error, task.retryCount + 1)
            appDb.aiGenTaskDao.get(taskId)?.let { _taskUpdates.emit(it) }
        }
    }

    suspend fun cancelTask(taskId: Long) {
        withContext(Dispatchers.IO) {
            appDb.aiGenTaskDao.updateStatus(taskId, "cancelled")
            appDb.aiGenTaskDao.get(taskId)?.let { _taskUpdates.emit(it) }
        }
    }

    suspend fun retryTask(taskId: Long) {
        withContext(Dispatchers.IO) {
            val task = appDb.aiGenTaskDao.get(taskId) ?: return@withContext
            appDb.aiGenTaskDao.updateStatus(taskId, "pending")
            appDb.aiGenTaskDao.get(taskId)?.let { _taskUpdates.emit(it) }
        }
    }

    suspend fun getTask(taskId: Long): AiGenTask? {
        return withContext(Dispatchers.IO) { appDb.aiGenTaskDao.get(taskId) }
    }

    suspend fun getActiveTasks(): List<AiGenTask> {
        return withContext(Dispatchers.IO) { appDb.aiGenTaskDao.activeTasks() }
    }
}
