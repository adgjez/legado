package io.legado.app.help.ai

import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import io.legado.app.constant.AppLog

object AiCodecCompat {

    data class CodecInfo(
        val mimeType: String,
        val codecName: String,
        val isHardwareSupported: Boolean
    )

    fun detectCodec(filePath: String): CodecInfo? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(filePath)
            val mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"
            retriever.release()
            val codecName = when {
                mimeType.contains("hevc", true) || mimeType.contains("h265", true) -> "H.265/HEVC"
                mimeType.contains("av1", true) -> "AV1"
                mimeType.contains("vp9", true) || mimeType.contains("vp09", true) -> "VP9"
                mimeType.contains("avc", true) || mimeType.contains("h264", true) -> "H.264/AVC"
                else -> mimeType
            }
            val supported = isCodecSupported(mimeType)
            CodecInfo(mimeType, codecName, supported)
        }.onFailure {
            AppLog.put("Codec detection failed: $filePath", it)
        }.getOrNull()
    }

    fun isCodecSupported(mimeType: String): Boolean {
        return runCatching {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            codecList.findDecoderForFormat(MediaFormat.createVideoFormat(mimeType, 1920, 1080)) != null
        }.getOrDefault(false)
    }

    fun shouldUseSoftwareDecode(codecInfo: CodecInfo?): Boolean {
        return codecInfo != null && !codecInfo.isHardwareSupported
    }

    fun describeCodec(codecInfo: CodecInfo?): String {
        if (codecInfo == null) return "未知编码"
        val support = if (codecInfo.isHardwareSupported) "硬件解码支持" else "需软解降级"
        return "${codecInfo.codecName} ($support)"
    }
}
