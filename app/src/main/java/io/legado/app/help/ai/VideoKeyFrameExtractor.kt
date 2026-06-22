package io.legado.app.help.ai

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream

/**
 * 视频关键帧抽取器（P3）。
 *
 * v1 实现：等距抽帧（每隔 `durationMs/n` 抓一帧），用 [MediaMetadataRetriever] 的
 * `getFrameAtTime(timeUs, OPTION_CLOSEST_SYNC)` 抓最近同步帧。
 *
 * 输出：`filesDir/ai_video_analysis/{bookId}/keyframe_{idx}.jpg`
 */
object VideoKeyFrameExtractor {

    private const val TAG = "VideoKeyFrameExtractor"

    /**
     * @param videoPath 视频本地路径。
     * @param bookId 用于定位输出目录的 bookId。
     * @param n 期望抽取的关键帧数量。
     * @return 抽出的 jpg 文件绝对路径列表（按时间顺序）。
     */
    suspend fun extract(videoPath: String, bookId: String, n: Int): List<String> =
        withContext(Dispatchers.IO) {
            val count = n.coerceIn(1, 32)
            val src = File(videoPath)
            if (!src.isFile) {
                Log.w(TAG, "source video not found: $videoPath")
                return@withContext emptyList()
            }
            val outDir = File(appCtx.externalFiles, "ai_video_analysis/$bookId").apply { mkdirs() }
            val out = ArrayList<String>(count)
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(videoPath)
                val durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
                if (durationMs <= 0) {
                    Log.w(TAG, "invalid duration: $durationMs")
                    return@withContext emptyList()
                }
                val stepUs = (durationMs * 1000L) / count
                for (i in 0 until count) {
                    val tsUs = stepUs * i
                    val bmp = retriever.getFrameAtTime(
                        tsUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    ) ?: continue
                    val file = File(outDir, "keyframe_${"%02d".format(i)}.jpg")
                    FileOutputStream(file).use { fos ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 80, fos)
                    }
                    bmp.recycle()
                    out.add(file.absolutePath)
                }
                out
            } catch (e: Throwable) {
                Log.w(TAG, "extract keyframes failed: ${e.message}")
                out
            } finally {
                runCatching { retriever.release() }
            }
        }
}
