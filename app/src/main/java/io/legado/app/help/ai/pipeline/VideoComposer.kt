package io.legado.app.help.ai.pipeline

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * 视频合成器
 *
 * 将多个视频片段合成为一个完整视频。
 * 使用 Android 原生 MediaMuxer，无需 FFmpeg 依赖。
 * 参考 ArcReel 的 compose-video 脚本，但用纯 Android API 实现。
 */
object VideoComposer {

    private const val TAG = "VideoComposer"
    private const val BUFFER_SIZE = 1024 * 1024 // 1MB

    /**
     * 将多个 MP4 片段拼接为一个视频
     *
     * @param inputPaths 输入视频片段路径列表
     * @param outputPath 输出视频路径
     * @param onProgress 进度回调 (0-100)
     * @return 合成后的文件路径，null 表示失败
     */
    suspend fun compose(
        inputPaths: List<String>,
        outputPath: String,
        onProgress: (Int) -> Unit = {}
    ): String? = withContext(Dispatchers.IO) {
        if (inputPaths.isEmpty()) return@withContext null

        // 验证输入文件存在
        val validPaths = inputPaths.filter { File(it).exists() }
        if (validPaths.isEmpty()) {
            Log.e(TAG, "没有有效的输入视频文件")
            return@withContext null
        }

        // 单个文件直接复制
        if (validPaths.size == 1) {
            val src = File(validPaths[0])
            val dst = File(outputPath)
            src.copyTo(dst, overwrite = true)
            onProgress(100)
            return@withContext dst.absolutePath
        }

        try {
            // 使用 MediaMuxer 合成
            val outputFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            val muxer = MediaMuxer(outputPath, outputFormat)

            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var outputVideoFormat: MediaFormat? = null
            var outputAudioFormat: MediaFormat? = null

            // 从第一个片段获取格式信息
            val firstExtractor = MediaExtractor()
            firstExtractor.setDataSource(validPaths[0])

            for (i in 0 until firstExtractor.trackCount) {
                val format = firstExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                when {
                    mime.startsWith("video/") && videoTrackIndex == -1 -> {
                        outputVideoFormat = format
                    }
                    mime.startsWith("audio/") && audioTrackIndex == -1 -> {
                        outputAudioFormat = format
                    }
                }
            }
            firstExtractor.release()

            // 添加轨道
            if (outputVideoFormat != null) {
                videoTrackIndex = muxer.addTrack(outputVideoFormat)
            }
            if (outputAudioFormat != null) {
                audioTrackIndex = muxer.addTrack(outputAudioFormat)
            }

            if (videoTrackIndex == -1) {
                muxer.release()
                Log.e(TAG, "未找到视频轨道")
                return@withContext null
            }

            muxer.start()

            var timeOffsetUs: Long = 0
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            // 逐片段拼接
            for ((pathIndex, path) in validPaths.withIndex()) {
                val extractor = MediaExtractor()
                extractor.setDataSource(path)

                // 找到视频和音频轨道
                var srcVideoTrack = -1
                var srcAudioTrack = -1

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    when {
                        mime.startsWith("video/") && srcVideoTrack == -1 -> srcVideoTrack = i
                        mime.startsWith("audio/") && srcAudioTrack == -1 -> srcAudioTrack = i
                    }
                }

                // 写入视频轨道
                if (srcVideoTrack >= 0) {
                    extractor.selectTrack(srcVideoTrack)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                    var lastPresentationTimeUs: Long = 0
                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.flags = extractor.sampleFlags
                        bufferInfo.presentationTimeUs = extractor.sampleTime + timeOffsetUs
                        bufferInfo.flags = extractor.sampleFlags

                        lastPresentationTimeUs = extractor.sampleTime

                        muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                        extractor.advance()
                    }

                    // 更新时间偏移（加一小段间隔避免重叠）
                    val duration = extractor.getTrackFormat(srcVideoTrack)
                        .getLong(MediaFormat.KEY_DURATION)
                    timeOffsetUs += if (duration > 0) duration else (lastPresentationTimeUs + 33000)
                }

                // 写入音频轨道
                if (srcAudioTrack >= 0 && audioTrackIndex >= 0) {
                    extractor.selectTrack(srcAudioTrack)
                    extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                    val audioTimeOffset = timeOffsetUs -
                        (extractor.getTrackFormat(srcVideoTrack)
                            .getLong(MediaFormat.KEY_DURATION).let { if (it > 0) it else 0 })

                    while (true) {
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break

                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.flags = extractor.sampleFlags
                        bufferInfo.presentationTimeUs = extractor.sampleTime + audioTimeOffset.coerceAtLeast(0)

                        muxer.writeSampleData(audioTrackIndex, buffer, bufferInfo)
                        extractor.advance()
                    }
                }

                extractor.release()
                onProgress((pathIndex + 1) * 100 / validPaths.size)
            }

            muxer.stop()
            muxer.release()

            Log.i(TAG, "视频合成完成: $outputPath")
            onProgress(100)
            outputPath
        } catch (e: Exception) {
            Log.e(TAG, "视频合成失败", e)
            // 清理可能的不完整输出
            File(outputPath).let { if (it.exists()) it.delete() }
            null
        }
    }

    /**
     * 在视频上叠加字幕
     * 预留接口，后续可用 MediaCodec + Canvas 实现
     */
    fun addSubtitles(
        inputPath: String,
        outputPath: String,
        subtitles: List<SubtitleEntry>
    ): String? {
        // TODO: 后续实现字幕叠加
        return null
    }

    data class SubtitleEntry(
        val text: String,
        val startTimeMs: Long,
        val endTimeMs: Long
    )
}
