package io.legado.app.help.ai

import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGenFailureLog
import io.legado.app.data.entities.AiGenVoucher
import io.legado.app.ui.main.ai.AiAudioProviderConfig
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.putPrefLong
import com.script.ScriptBindings
import com.script.rhino.RhinoScriptEngine
import splitties.init.appCtx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AiAssetManager {

    private const val DEFAULT_IMAGE_COST_USD = 0.04
    private const val DEFAULT_CLEANUP_THRESHOLD_BYTES = 2L * 1024 * 1024 * 1024 // 2GB
    private const val DEFAULT_CLEANUP_MAX_AGE_DAYS = 30L

    data class StorageUsage(
        val imagesBytes: Long,
        val videosBytes: Long,
        val audiosBytes: Long,
        val totalBytes: Long
    )

    fun storageUsage(): StorageUsage {
        val imagesDir = File(appCtx.filesDir, "ai_images")
        val videosDir = File(appCtx.filesDir, "ai_videos")
        val audiosDir = File(appCtx.filesDir, "ai_audios")
        val imagesBytes = dirSize(imagesDir)
        val videosBytes = dirSize(videosDir)
        val audiosBytes = dirSize(audiosDir)
        return StorageUsage(imagesBytes, videosBytes, audiosBytes, imagesBytes + videosBytes + audiosBytes)
    }

    fun cleanup(thresholdBytes: Long, maxAgeDays: Long = 30) {
        // Clean expired temporary files
        cleanupExpiredTemporary(maxAgeDays)
        // LRU cleanup if over threshold
        val usage = storageUsage()
        if (usage.totalBytes > thresholdBytes) {
            lruCleanup(usage.totalBytes - thresholdBytes)
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun cleanupExpiredTemporary(maxAgeDays: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24 * 60 * 60 * 1000
        // Images
        appDb.aiGeneratedImageDao.expiredTemporary(cutoff).forEach {
            AiImageGalleryManager.deleteImage(it.id)
        }
        // Videos
        appDb.aiGeneratedVideoDao.expiredTemporary(cutoff).forEach {
            AiVideoGalleryManager.deleteVideo(it.id)
        }
    }

    private fun lruCleanup(targetBytes: Long) {
        var freed = 0L
        // LRU from videos first (largest)
        val cutoff = System.currentTimeMillis()
        while (freed < targetBytes) {
            val candidates = appDb.aiGeneratedVideoDao.lruCandidates(cutoff, 10)
            if (candidates.isEmpty()) break
            candidates.forEach {
                AiVideoGalleryManager.deleteVideo(it.id)
                freed += File(it.localPath).let { f -> if (f.exists()) f.length() else 0L }
            }
        }
    }

    fun exportToMediaStore(file: File, mimeType: String, displayName: String): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, when {
                        mimeType.startsWith("video") -> Environment.DIRECTORY_MOVIES + "/AI Generated"
                        mimeType.startsWith("audio") -> Environment.DIRECTORY_MUSIC + "/AI Generated"
                        else -> Environment.DIRECTORY_PICTURES + "/AI Generated"
                    })
                }
                val uri = appCtx.contentResolver.insert(
                    when {
                        mimeType.startsWith("video") -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        mimeType.startsWith("audio") -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                        else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }, values
                ) ?: error("Failed to create MediaStore entry")
                appCtx.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                }
            } else {
                @Suppress("DEPRECATION")
                val destDir = when {
                    mimeType.startsWith("video") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    mimeType.startsWith("audio") -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                    else -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                }
                val dest = File(destDir, "AI Generated")
                dest.mkdirs()
                file.copyTo(File(dest, displayName), overwrite = true)
                MediaScannerConnection.scanFile(appCtx, arrayOf(file.absolutePath), arrayOf(mimeType), null)
            }
            true
        }.getOrElse { e ->
            AppLog.put("Export to MediaStore failed", e)
            false
        }
    }

    // region cost estimation

    /**
     * Estimate the cost of a video generation task.
     * Uses [AiVideoProviderConfig.costPerSecond] * [durationSeconds], or evaluates
     * [AiVideoProviderConfig.costExpression] if it is not blank.
     */
    fun estimateCost(provider: AiVideoProviderConfig, durationSeconds: Int): Double {
        if (provider.costExpression.isNotBlank()) {
            return evalCostExpression(provider.costExpression) {
                this["durationSeconds"] = durationSeconds
                this["provider"] = provider
            }
        }
        return provider.costPerSecond * durationSeconds
    }

    /**
     * Estimate the cost of an audio generation task.
     * Evaluates [AiAudioProviderConfig.costExpression] if it is not blank, otherwise
     * falls back to 0.0 (AiAudioProviderConfig has no costPerSecond field).
     */
    fun estimateCost(provider: AiAudioProviderConfig, durationSeconds: Int): Double {
        if (provider.costExpression.isNotBlank()) {
            return evalCostExpression(provider.costExpression) {
                this["durationSeconds"] = durationSeconds
                this["provider"] = provider
            }
        }
        // AiAudioProviderConfig has no costPerSecond field; fall back to 0.0
        return 0.0
    }

    /**
     * Estimate the cost of an image generation task using a fixed rate per image.
     */
    fun estimateCost(provider: AiImageProviderConfig, count: Int): Double {
        return DEFAULT_IMAGE_COST_USD * count
    }

    /**
     * Evaluate a cost expression (JS) and convert the result to a [Double].
     * Returns 0.0 if evaluation fails.
     */
    private fun evalCostExpression(
        expression: String,
        bindingsConfig: ScriptBindings.() -> Unit
    ): Double {
        return runCatching {
            val result = RhinoScriptEngine.eval(expression, bindingsConfig)
            when (result) {
                is Number -> result.toDouble()
                is Boolean -> if (result) 1.0 else 0.0
                is String -> result.toDoubleOrNull() ?: 0.0
                else -> result?.toString()?.toDoubleOrNull() ?: 0.0
            }
        }.getOrElse { e ->
            AppLog.put("Evaluate cost expression failed: $expression", e)
            0.0
        }
    }

    // endregion

    // region voucher & failure logging

    /**
     * Record a generation voucher (cost tracking) for a completed or failed task.
     */
    suspend fun recordVoucher(
        taskId: Long,
        modality: String,
        providerId: String,
        providerName: String,
        model: String,
        costEstimate: Double,
        costActual: Double,
        durationSeconds: Int,
        success: Boolean
    ) {
        withContext(Dispatchers.IO) {
            val voucher = AiGenVoucher(
                taskId = taskId,
                modality = modality,
                providerId = providerId,
                providerName = providerName,
                model = model,
                costEstimate = costEstimate,
                costActual = costActual,
                durationSeconds = durationSeconds,
                success = success
            )
            appDb.aiGenVoucherDao().insert(voucher)
        }
    }

    /**
     * Record a generation failure log for diagnostics and retry analysis.
     */
    suspend fun recordFailure(
        modality: String,
        providerId: String,
        providerName: String,
        model: String,
        prompt: String,
        errorMessage: String,
        errorType: String,
        bookKey: String,
        chapterIndex: Int
    ) {
        withContext(Dispatchers.IO) {
            val log = AiGenFailureLog(
                modality = modality,
                providerId = providerId,
                providerName = providerName,
                model = model,
                prompt = prompt,
                errorMessage = errorMessage,
                errorType = errorType,
                bookKey = bookKey,
                chapterIndex = chapterIndex
            )
            appDb.aiGenFailureLogDao().insert(log)
        }
    }

    /**
     * Total actual cost of all successful vouchers created after [since].
     */
    suspend fun totalCostSince(since: Long): Double {
        return withContext(Dispatchers.IO) {
            appDb.aiGenVoucherDao().totalCostSince(since)
        }
    }

    /**
     * Total actual cost of all successful vouchers for [modality] created after [since].
     */
    suspend fun costByModality(modality: String, since: Long): Double {
        return withContext(Dispatchers.IO) {
            appDb.aiGenVoucherDao().costByModalitySince(modality, since)
        }
    }

    /**
     * Recent generation failure logs, newest first.
     */
    suspend fun recentFailures(limit: Int = 50): List<AiGenFailureLog> {
        return withContext(Dispatchers.IO) {
            appDb.aiGenFailureLogDao().recent(limit)
        }
    }

    // endregion

    // region cleanup scheduling

    /**
     * Persist cleanup thresholds to AppConfig (SharedPreferences) and trigger an
     * immediate cleanup pass with the given thresholds.
     */
    fun scheduleCleanup(thresholdBytes: Long, maxAgeDays: Long) {
        appCtx.putPrefLong(PreferKey.aiCleanupThresholdBytes, thresholdBytes)
        appCtx.putPrefLong(PreferKey.aiCleanupMaxAgeDays, maxAgeDays)
        cleanup(thresholdBytes, maxAgeDays)
    }

    /**
     * The persisted cleanup threshold in bytes, or a sensible default.
     */
    val cleanupThresholdBytes: Long
        get() = appCtx.getPrefLong(
            PreferKey.aiCleanupThresholdBytes,
            DEFAULT_CLEANUP_THRESHOLD_BYTES
        )

    /**
     * The persisted cleanup max age in days, or a sensible default.
     */
    val cleanupMaxAgeDays: Long
        get() = appCtx.getPrefLong(
            PreferKey.aiCleanupMaxAgeDays,
            DEFAULT_CLEANUP_MAX_AGE_DAYS
        )

    // endregion
}
