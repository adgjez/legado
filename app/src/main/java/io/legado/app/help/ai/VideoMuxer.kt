package io.legado.app.help.ai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import io.legado.app.constant.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * 视频无损合并工具，移植自 director_ai `MainActivity.kt` 的 `mergeWithMediaMuxer` + `copyTrack`。
 *
 * 使用 Android 原生 [MediaMuxer] 将多个 MP4 段落按顺序拼接为单个 MP4 文件，
 * 不重新编码（纯 remux），保留原始画质。
 *
 * 时间戳处理：每段的第一个样本被映射到当前累计偏移量，消除段间空隙。
 *
 * 使用场景：小说→视频流水线 Stage 7，把 [NovelVideoSegment.localVideoPath] 列表合并为
 * [NovelVideoJob.outputPath] 指向的单个 MP4。
 */
object VideoMuxer {

    private const val BUFFER_SIZE = 1024 * 1024 // 1MB，与 director_ai 一致

    /**
     * 合并结果。
     */
    sealed class MergeResult {
        data class Success(
            val outputPath: String,
            val totalDurationMs: Long,
            val segmentCount: Int
        ) : MergeResult()

        data class Failed(val message: String) : MergeResult()
    }

    /**
     * 无损合并多个 MP4 文件到单个输出文件。
     *
     * @param inputPaths 输入文件路径列表（按顺序拼接），需非空且文件存在
     * @param outputPath 输出 MP4 文件路径，父目录会自动创建
     * @return [MergeResult]
     */
    suspend fun merge(inputPaths: List<String>, outputPath: String): MergeResult =
        withContext(Dispatchers.IO) {
            // 校验输入
            val validInputs = inputPaths.filter { it.isNotBlank() && File(it).isFile }
            if (validInputs.isEmpty()) {
                return@withContext MergeResult.Failed("无有效输入文件")
            }
            if (validInputs.size == 1) {
                // 单段无需合并，直接复制
                val single = validInputs[0]
                return@withContext runCatching {
                    val outFile = File(outputPath).apply { parentFile?.mkdirs() }
                    File(single).copyTo(outFile, overwrite = true)
                    val duration = estimateDurationMs(single)
                    MergeResult.Success(outFile.absolutePath, duration, 1)
                }.getOrElse { e ->
                    AppLog.put("单段视频复制失败: $single -> $outputPath", e)
                    MergeResult.Failed("单段视频复制失败：${e.message}")
                }
            }

            // 确保输出目录存在
            val outputFile = File(outputPath).apply { parentFile?.mkdirs() }
            var muxer: MediaMuxer? = null
            try {
                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                // 从第一个视频获取轨道格式
                var videoFormat: MediaFormat? = null
                var audioFormat: MediaFormat? = null
                val firstExtractor = MediaExtractor()
                try {
                    firstExtractor.setDataSource(validInputs[0])
                    for (i in 0 until firstExtractor.trackCount) {
                        val format = firstExtractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                        if (mime.startsWith("video/") && videoFormat == null) {
                            videoFormat = format
                        } else if (mime.startsWith("audio/") && audioFormat == null) {
                            audioFormat = format
                        }
                    }
                } finally {
                    runCatching { firstExtractor.release() }
                }

                if (videoFormat == null) {
                    return@withContext MergeResult.Failed("首个视频无 video 轨道：${validInputs[0]}")
                }

                // 注册输出轨道
                val videoTrackIndex = muxer.addTrack(videoFormat)
                val audioTrackIndex = audioFormat?.let { muxer.addTrack(it) } ?: -1
                muxer.start()

                val buffer = ByteBuffer.allocate(BUFFER_SIZE)
                val info = MediaCodec.BufferInfo()
                var videoTimeOffset = 0L
                var audioTimeOffset = 0L

                // 逐段合并
                for ((index, inputPath) in validInputs.withIndex()) {
                    coroutineContext.ensureActive()
                    AppLog.put("VideoMuxer 合并 ${index + 1}/${validInputs.size}: $inputPath")

                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(inputPath)

                        // 定位当前文件的视频/音频轨道索引
                        var currentVideoTrack = -1
                        var currentAudioTrack = -1
                        for (i in 0 until extractor.trackCount) {
                            val mime = extractor.getTrackFormat(i)
                                .getString(MediaFormat.KEY_MIME).orEmpty()
                            if (mime.startsWith("video/") && videoTrackIndex >= 0) {
                                currentVideoTrack = i
                            } else if (mime.startsWith("audio/") && audioTrackIndex >= 0) {
                                currentAudioTrack = i
                            }
                        }

                        // 合并视频轨道
                        if (currentVideoTrack >= 0) {
                            extractor.selectTrack(currentVideoTrack)
                            val firstVideoTime = readFirstSampleTime(extractor, currentVideoTrack)
                            val (_, endVideo) = copyTrack(
                                extractor, muxer, videoTrackIndex, buffer, info,
                                videoTimeOffset, firstVideoTime
                            )
                            videoTimeOffset = endVideo
                        }

                        // 合并音频轨道
                        if (currentAudioTrack >= 0) {
                            extractor.selectTrack(currentAudioTrack)
                            val firstAudioTime = readFirstSampleTime(extractor, currentAudioTrack)
                            val (_, endAudio) = copyTrack(
                                extractor, muxer, audioTrackIndex, buffer, info,
                                audioTimeOffset, firstAudioTime
                            )
                            audioTimeOffset = endAudio
                        }
                    } finally {
                        runCatching { extractor.release() }
                    }
                }

                muxer.stop()
                val totalDurationMs = maxOf(videoTimeOffset, audioTimeOffset) / 1000
                AppLog.put("VideoMuxer 合并完成: ${validInputs.size} 段, 时长 ${totalDurationMs}ms")
                MergeResult.Success(outputFile.absolutePath, totalDurationMs, validInputs.size)
            } catch (e: Exception) {
                AppLog.put("VideoMuxer 合并失败", e)
                runCatching { outputFile.delete() }
                MergeResult.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { muxer?.release() }
            }
        }

    /**
     * 复制单个轨道的全部样本到 muxer，重新基准时间戳。
     *
     * @param firstSampleTime 当前段第一个样本的时间戳（调用前预读获取）
     * @param timeOffset 当前段应拼接到的累计时间偏移
     * @return Pair(样本数, 结束时间戳)
     */
    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndex: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        timeOffset: Long,
        firstSampleTime: Long
    ): Pair<Int, Long> {
        var sampleCount = 0
        var endTime = timeOffset
        var sawInputEOS = false
        // 使第一个样本映射到 timeOffset 位置
        val actualOffset = timeOffset - firstSampleTime

        while (!sawInputEOS) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                sawInputEOS = true
                info.size = 0
            } else {
                info.size = sampleSize
                info.offset = 0
                val originalTime = extractor.sampleTime
                info.presentationTimeUs = originalTime + actualOffset
                if (originalTime >= 0) {
                    endTime = originalTime + actualOffset
                }
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(trackIndex, buffer, info)
                extractor.advance()
                sampleCount++
            }
        }
        return Pair(sampleCount, endTime)
    }

    /**
     * 预读指定轨道的第一个样本时间戳，然后重置 extractor 到轨道起始。
     * 注意：需先 selectTrack 再调用此方法。
     */
    private fun readFirstSampleTime(extractor: MediaExtractor, trackIndex: Int): Long {
        // 用小 buffer 触发 extractor 内部指针前进到首个样本
        val probe = ByteBuffer.allocate(64)
        extractor.readSampleData(probe, 0)
        val firstTime = extractor.sampleTime
        extractor.unselectTrack(trackIndex)
        extractor.selectTrack(trackIndex)
        return firstTime
    }

    /**
     * 用 MediaExtractor 估算视频时长（毫秒）。
     */
    private fun estimateDurationMs(path: String): Long {
        val extractor = MediaExtractor()
        return runCatching {
            extractor.setDataSource(path)
            var duration = 0L
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    duration = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        (format.getLong(MediaFormat.KEY_DURATION) / 1000).coerceAtLeast(0L)
                    } else 0L
                    break
                }
            }
            duration
        }.getOrDefault(0L).also {
            runCatching { extractor.release() }
        }
    }
}
