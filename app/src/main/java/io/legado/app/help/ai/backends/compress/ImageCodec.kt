package io.legado.app.help.ai.backends.compress

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64 as JvmBase64
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 共享编码工具（移植 ArcReel image_backends/base.py + image_utils.py）。
 *
 * 关键纪律：base64 编码用 [JvmBase64.getEncoder]（无换行）防 `Incorrect padding` 报错——
 * ArcReel 注释原话「带 `data:` 前缀会在生成期触发 padding 错误」，
 * Android `Base64.DEFAULT` 每 76 字符插换行也触发同样的 padding 错误，
 * 故所有 base64 编码一律走无换行的 JVM encoder（等价 Android `Base64.NO_WRAP`）。
 *
 * 用 `java.util.Base64` 而非 `android.util.Base64`：本类是纯字节工具，无 Android 运行时
 * 依赖需求；改用 JVM 实现后可在 Robolectric/unit test 下直接运行（Android Base64 默认
 * stub 返回 "not mocked"）。
 *
 * - [toDataUri]：ark/newapi/v2/dashscope/minimax/vidu/grok/agnes(image)/openai(T2I) 用
 * - [toBareBase64]：agnes(video)/kling(video+image) 用
 * - [toRawBytes]：sora/openai(I2I) 用
 */
object ImageCodec {

    /** 文件 → `data:<mime>;base64,<b64>`（无换行）。 */
    fun toDataUri(file: File): String {
        val b64 = JvmBase64.getEncoder().encodeToString(file.readBytes())
        return "data:${mimeByExtension(file.extension)};base64,$b64"
    }

    /** 文件 → 裸 base64（无 `data:` 前缀，无换行）。Agnes/Kling 用此形态。 */
    fun toBareBase64(file: File): String =
        JvmBase64.getEncoder().encodeToString(file.readBytes())

    /** 文件 → 原始字节。Sora/OpenAI I2I multipart 用此形态。 */
    fun toRawBytes(file: File): ByteArray = file.readBytes()

    fun mimeByExtension(ext: String): String = when (ext.lowercase().trim()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/jpeg"
    }

    /**
     * 缩放重编码 + EXIF 矫正（替代 PIL `ImageOps.exif_transpose`）。
     *
     * 仅进入压缩的图（ARRAY 重编码 + FRAME 超字节重编码）走矫正；透传字节不矫正。
     * Android 大图解码用 [BitmapFactory.Options.inSampleSize] 采样避免 OOM
     * （ArcReel PIL 服务端内存充裕无此问题，Android 需此适配）。
     *
     * 不可解码返回原 [bytes]（压缩是优化，不得让原本能跑通的调用因压缩层新失败）。
     */
    suspend fun compress(
        bytes: ByteArray,
        maxLongEdge: Int,
        quality: Int,
        subsampling: Int = 0
    ): ByteArray {
        // 非图片格式（无 JPEG/PNG/GIF/WebP 魔数）直接透传——
        // 避免依赖 BitmapFactory.decodeByteArray 对非图片返回 null 的行为
        // （Robolectric/部分 Android 版本对非图片字节可能返回非 null 占位 Bitmap）
        if (!isLikelyImage(bytes)) return bytes
        val decoded = decodeSampled(bytes, maxLongEdge) ?: return bytes
        val oriented = applyExifOrientation(bytes, decoded) ?: decoded
        val scaled = scaleToLongEdge(oriented, maxLongEdge)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), out)
        if (scaled !== oriented) scaled.recycle()
        if (oriented !== decoded) oriented.recycle()
        decoded.recycle()
        return out.toByteArray()
    }

    /** 粗判字节是否为已知图片格式（JPEG/PNG/GIF/WebP 魔数）。非图片直接透传不压。 */
    fun isLikelyImage(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        // JPEG: FFD8
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) return true
        // PNG: 89 50 4E 47
        if (bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()) return true
        // GIF: 47 49 46 38 (GIF8)
        if (bytes[0] == 0x47.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x38.toByte()) return true
        // WebP: RIFF....WEBP
        if (bytes.size >= 12 &&
            bytes[0] == 0x52.toByte() && bytes[1] == 0x49.toByte() &&
            bytes[2] == 0x46.toByte() && bytes[3] == 0x46.toByte() &&
            bytes[8] == 0x57.toByte() && bytes[9] == 0x45.toByte() &&
            bytes[10] == 0x42.toByte() && bytes[11] == 0x50.toByte()
        ) return true
        return false
    }

    /** inSampleSize 采样解码，避免大图 OOM。 */
    private fun decodeSampled(bytes: ByteArray, maxLongEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (maxLongEdge <= 0) {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longEdge / sample > maxLongEdge * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    /** 读 EXIF Orientation tag 旋转/翻转（替代 PIL ImageOps.exif_transpose）。无 EXIF 返回 null。 */
    private fun applyExifOrientation(bytes: ByteArray, bmp: Bitmap): Bitmap? {
        val exif = runCatching { ExifInterface(bytes.inputStream()) }.getOrNull() ?: return null
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return null
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    private fun scaleToLongEdge(bmp: Bitmap, maxLongEdge: Int): Bitmap {
        if (maxLongEdge <= 0) return bmp
        val longEdge = max(bmp.width, bmp.height)
        if (longEdge <= maxLongEdge) return bmp
        val ratio = maxLongEdge.toFloat() / longEdge
        val w = (bmp.width * ratio).roundToInt().coerceAtLeast(1)
        val h = (bmp.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }
}
