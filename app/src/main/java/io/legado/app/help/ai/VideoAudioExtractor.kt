package io.legado.app.help.ai

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.nio.ByteBuffer

/**
 * 视频音轨抽取器（P3）。
 *
 * 用 Android [MediaExtractor] + [MediaMuxer] 把视频文件的音轨封装为独立的 m4a 文件。
 * 不调用 Media3 Transformer（避免引入额外依赖与构建复杂度）。
 *
 * 输出文件路径：`filesDir/ai_video_analysis/{bookId}/audio.m4a`
 */
object VideoAudioExtractor {

    private const val TAG = "VideoAudioExtractor"

    /**
     * 抽取视频文件的音轨到本地 m4a 文件。
     *
     * @param videoPath 源视频本地路径（http URL 需先下载）。
     * @param bookId 用于定位输出目录的 bookId。
     * @return 输出的 m4a 文件；若源无音轨返回 null。
     */
    suspend fun extract(videoPath: String, bookId: String): File? =
        withContext(Dispatchers.IO) {
            val src = File(videoPath)
            if (!src.isFile) {
                Log.w(TAG, "source video not found: $videoPath")
                return@withContext null
            }
            val outDir = File(appCtx.externalFiles, "ai_video_analysis/$bookId").apply { mkdirs() }
            val outFile = File(outDir, "audio.m4a")
            if (outFile.isFile && outFile.length() > 0) {
                // 已抽取过则复用，避免重复工作
                return@withContext outFile
            }
            var extractor: MediaExtractor? = null
            var muxer: MediaMuxer? = null
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(videoPath)
                var audioTrackIndex = -1
                var audioFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        audioTrackIndex = i
                        audioFormat = format
                        break
                    }
                }
                if (audioTrackIndex < 0 || audioFormat == null) {
                    Log.w(TAG, "no audio track in $videoPath")
                    return@withContext null
                }
                extractor.selectTrack(audioTrackIndex)
                muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val outTrack = muxer.addTrack(audioFormat)
                muxer.start()
                val buffer = ByteBuffer.allocate(MAX_SAMPLE_SIZE)
                val info = android.media.MediaCodec.BufferInfo()
                while (true) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = extractor.sampleTime
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(outTrack, buffer, info)
                    extractor.advance()
                }
                outFile
            } catch (e: Throwable) {
                Log.w(TAG, "extract audio failed: ${e.message}")
                runCatching { outFile.delete() }
                null
            } finally {
                runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
                runCatching { extractor?.release() }
            }
        }

    private const val MAX_SAMPLE_SIZE = 1024 * 1024
}
