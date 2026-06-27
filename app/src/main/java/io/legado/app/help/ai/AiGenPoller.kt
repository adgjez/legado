package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGenTask
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

object AiGenPoller {

    private val _isPolling = MutableStateFlow(false)
    val isPolling = _isPolling.asStateFlow()

    private val _backgroundPolling = MutableStateFlow(false)
    val backgroundPolling = _backgroundPolling.asStateFlow()

    private var foregroundJob: Job? = null
    private var backgroundJob: Job? = null
    private val pollScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Max poll duration per modality (ms). Tasks exceeding this are cancelled.
    private val maxPollDurations = mapOf(
        "video" to 10 * 60 * 1000L,
        "audio" to 5 * 60 * 1000L,
        "image" to 2 * 60 * 1000L,
        "text_sanitize" to 60 * 1000L
    )

    // Base poll intervals per modality (ms). Starting point for exponential backoff.
    private val baseIntervals = mapOf(
        "video" to 5_000L,
        "audio" to 5_000L,
        "image" to 3_000L,
        "text_sanitize" to 3_000L
    )

    // Capped poll intervals per modality (ms). Backoff never exceeds these values.
    private val maxIntervals = mapOf(
        "video" to 30_000L,
        "audio" to 15_000L
    )

    private const val BACKOFF_MULTIPLIER = 1.5

    // Foreground loop tick (ms). Keeps the existing 5s foreground polling cadence.
    private const val FOREGROUND_TICK_MS = 5_000L

    // Background loop tick (ms). Simulates the WorkManager background track.
    private const val BACKGROUND_TICK_MS = 60_000L

    /**
     * Per-task polling state used to drive exponential backoff and track elapsed time.
     */
    private class PollState(interval: Long, firstPollAt: Long) {
        @Volatile var pollCount: Int = 0
        @Volatile var interval: Long = interval
        @Volatile var lastPollAt: Long = 0L
        @Volatile var firstPollAt: Long = firstPollAt
    }

    // taskId -> PollState, shared by foreground and background loops.
    private val pollStates = ConcurrentHashMap<Long, PollState>()

    // Per-task mutex to prevent concurrent polling of the same task by the
    // foreground and background loops, which would cause duplicate downloads.
    private val taskLocks = ConcurrentHashMap<Long, Mutex>()

    fun startForegroundPolling() {
        if (foregroundJob?.isActive == true) return
        _isPolling.value = true
        foregroundJob = pollScope.launch {
            while (isActive) {
                try {
                    pollActiveTasks()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.put("Foreground poll error", e)
                }
                delay(FOREGROUND_TICK_MS)
            }
        }
    }

    fun stopForegroundPolling() {
        foregroundJob?.cancel()
        foregroundJob = null
        _isPolling.value = false
    }

    /**
     * Background polling track, simulating WorkManager. Runs on a separate scope
     * with a 60s cadence so generation tasks keep progressing when the app is
     * not in the foreground.
     */
    fun startBackgroundPolling() {
        if (backgroundJob?.isActive == true) return
        _backgroundPolling.value = true
        backgroundJob = backgroundScope.launch {
            while (isActive) {
                try {
                    pollActiveTasks()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AppLog.put("Background poll error", e)
                }
                delay(BACKGROUND_TICK_MS)
            }
        }
    }

    fun stopBackgroundPolling() {
        backgroundJob?.cancel()
        backgroundJob = null
        _backgroundPolling.value = false
    }

    /**
     * Clean up all scopes and jobs. Call this when the app is shutting down
     * or when the poller is no longer needed to prevent coroutine leaks.
     */
    fun cleanup() {
        stopForegroundPolling()
        stopBackgroundPolling()
        pollScope.cancel()
        backgroundScope.cancel()
    }

    private suspend fun pollActiveTasks() {
        val activeTasks = appDb.aiGenTaskDao.activeTasks()
        val now = System.currentTimeMillis()
        val activeIds = HashSet<Long>(activeTasks.size)
        // Skip polling on cellular data for low-priority tasks
        val networkInfo = NetworkQualityInterceptor.currentNetworkInfo()
        for (task in activeTasks) {
            activeIds.add(task.id)
            try {
                // Max poll duration enforcement: cancel stale tasks.
                val maxDuration = maxPollDurations[task.modality]
                if (maxDuration != null && now - task.createdAt > maxDuration) {
                    AppLog.put(
                        "Cancelling task ${task.id} (${task.modality}): " +
                            "exceeded max poll duration ${maxDuration}ms"
                    )
                    AiGenTaskManager.cancelTask(task.id)
                    pollStates.remove(task.id)
                    continue
                }

                // Per-task exponential backoff: only poll when due.
                val baseInterval = baseIntervals[task.modality] ?: FOREGROUND_TICK_MS
                val state = pollStates.computeIfAbsent(task.id) {
                    PollState(interval = baseInterval, firstPollAt = now)
                }
                if (now - state.lastPollAt < state.interval) {
                    continue // not due yet, honour the backoff interval
                }
                state.lastPollAt = now
                state.pollCount = state.pollCount + 1

                // Check if task can execute under current network conditions
                if (!NetworkQualityInterceptor.canExecute(task.priority)) {
                    AppLog.put("Skipping task ${task.id} due to network constraints")
                    continue
                }

                try {
                    when (task.modality) {
                        "video" -> pollVideoTask(task)
                        "audio" -> pollAudioTask(task)
                        // image and text_sanitize are synchronous, skip polling
                    }
                } finally {
                    // Grow the interval for the next poll (exponential backoff, capped).
                    // Placed in finally so backoff is still applied when the poll throws.
                    val cap = maxIntervals[task.modality] ?: 30_000L
                    state.interval = (state.interval * BACKOFF_MULTIPLIER).toLong().coerceAtMost(cap)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.put("Poll error for task ${task.id}", e)
            }
        }
        // Clean up poll states and task locks for tasks that are no longer active.
        pollStates.entries.removeIf { it.key !in activeIds }
        taskLocks.entries.removeIf { it.key !in activeIds }
    }

    private suspend fun pollVideoTask(task: AiGenTask) {
        val mutex = taskLocks.computeIfAbsent(task.id) { Mutex() }
        if (!mutex.tryLock()) return  // Another poller is already handling this task
        try {
            if (task.status == "submitted" || task.status == "processing" || task.status == "downloading") {
                val provider = AiVideoService.providerByIdOrNull(task.providerId)
                    ?: AiVideoService.currentProviderOrNull() ?: return
                val status = AiVideoService.queryStatus(task.remoteTaskId, provider)
                val newStatus = when (status.status) {
                    "succeeded" -> "downloading"
                    "failed" -> "failed"
                    else -> "processing"
                }
                val hint = AiPromptRewriter.generateProgressHint(task.modality, "")
                AiGenTaskManager.updateEmotionalHint(task.id, hint)
                AiGenTaskManager.updateProgress(task.id, newStatus, status.progress, status.previewUrl ?: "")
                if (newStatus == "downloading") {
                    // If resultId is already set, a previous download succeeded but
                    // completeTask failed. Skip re-downloading to prevent duplicates.
                    if (task.resultId.isBlank()) {
                        val file = AiVideoService.download(task.remoteTaskId, provider)
                        val metadata = AiVideoGalleryManager.VideoMetadata(
                            bookName = "", sourceType = task.sourceType
                        )
                        val video = AiVideoGalleryManager.saveGeneratedVideo(
                            videoSource = file.absolutePath,
                            prompt = task.prompt,
                            provider = provider,
                            model = task.model,
                            metadata = metadata
                        )
                        // Store result before completing so retry won't re-download
                        appDb.aiGenTaskDao.updateResult(task.id, "downloading", video.id, video.localPath, 0.0)
                    }
                    // Re-read task to get stored resultId/resultPath
                    val updated = appDb.aiGenTaskDao.get(task.id)
                    if (updated != null && updated.resultId.isNotBlank()) {
                        AiGenTaskManager.completeTask(task.id, updated.resultId, updated.resultPath, 0.0)
                    }
                } else if (newStatus == "failed") {
                    AiGenTaskManager.failTask(task.id, "Video generation failed")
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun pollAudioTask(task: AiGenTask) {
        val mutex = taskLocks.computeIfAbsent(task.id) { Mutex() }
        if (!mutex.tryLock()) return  // Another poller is already handling this task
        try {
            if (task.status == "submitted" || task.status == "processing" || task.status == "downloading") {
                val provider = AiAudioService.providerByIdOrNull(task.providerId)
                    ?: AiAudioService.currentProviderOrNull() ?: return
                val status = AiAudioService.queryStatus(task.remoteTaskId, provider)
                val newStatus = when (status.status) {
                    "succeeded" -> "downloading"
                    "failed" -> "failed"
                    else -> "processing"
                }
                val hint = AiPromptRewriter.generateProgressHint(task.modality, "")
                AiGenTaskManager.updateEmotionalHint(task.id, hint)
                AiGenTaskManager.updateProgress(task.id, newStatus, status.progress, "")
                if (newStatus == "downloading") {
                    // If resultId is already set, a previous download succeeded but
                    // completeTask failed. Skip re-downloading to prevent duplicates.
                    if (task.resultId.isBlank()) {
                        val file = AiAudioService.download(task.remoteTaskId, provider)
                        val metadata = AiAudioGalleryManager.AudioMetadata(
                            sourceType = task.sourceType
                        )
                        val audio = AiAudioGalleryManager.saveGeneratedAudio(
                            audioSource = file.absolutePath,
                            prompt = task.prompt,
                            provider = provider,
                            model = task.model,
                            metadata = metadata
                        )
                        // Store result before completing so retry won't re-download
                        appDb.aiGenTaskDao.updateResult(task.id, "downloading", audio.id, audio.localPath, 0.0)
                    }
                    // Re-read task to get stored resultId/resultPath
                    val updated = appDb.aiGenTaskDao.get(task.id)
                    if (updated != null && updated.resultId.isNotBlank()) {
                        AiGenTaskManager.completeTask(task.id, updated.resultId, updated.resultPath, 0.0)
                    }
                } else if (newStatus == "failed") {
                    AiGenTaskManager.failTask(task.id, "Audio generation failed")
                }
            }
        } finally {
            mutex.unlock()
        }
    }
}
