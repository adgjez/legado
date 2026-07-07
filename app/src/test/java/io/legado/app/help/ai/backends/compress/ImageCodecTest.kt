package io.legado.app.help.ai.backends.compress

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * [ImageCodec] 编码纪律单测（P0 头条：Incorrect padding 修复的证明）。
 *
 * 核心验证 [Base64.NO_WRAP] 纪律：toDataUri/toBareBase64 的 base64 部分无换行——
 * Android 默认 Base64.DEFAULT 每 76 字符插换行会触发 `Incorrect padding` 报错
 * （与 `data:` 前缀同成因，Agnes/Ark 现网报错的根因）。
 *
 * compress 的缩尺寸/EXIF 旋转 smoke 测试在此验证；降档梯子的完整覆盖在 P1
 * ReferenceCompressorTest（含真实图像 fixture + 地板抛错 + 透传不 raise）。
 * Robolectric nativeruntime 不可用时用 [assumeTrue] 跳过压缩测试，不阻断 CI。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ImageCodecTest {

    private fun tmpFile(prefix: String, ext: String, bytes: ByteArray): File {
        val f = File.createTempFile(prefix, ".$ext").apply { writeBytes(bytes) }
        f.deleteOnExit()
        return f
    }

    // ========== toDataUri ==========

    @Test
    fun toDataUriHasDataPrefixAndJpegMime() {
        val f = tmpFile("agnes", "jpg", byteArrayOf(1, 2, 3, 4, 5))
        val uri = ImageCodec.toDataUri(f)
        assertTrue("应带 data: 前缀", uri.startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun toDataUriPngMimeByExtension() {
        val f = tmpFile("ref", "png", byteArrayOf(9, 9))
        val uri = ImageCodec.toDataUri(f)
        assertTrue(uri.startsWith("data:image/png;base64,"))
    }

    @Test
    fun toDataUriBase64HasNoNewlines() {
        // NO_WRAP 纪律：base64 部分不得含换行，否则触发 Incorrect padding
        val payload = ByteArray(500) { (it and 0xFF).toByte() }
        val f = tmpFile("wrap", "jpg", payload)
        val uri = ImageCodec.toDataUri(f)
        val b64 = uri.substringAfter("base64,")
        assertFalse("base64 含 \\n（违反 NO_WRAP）", b64.contains('\n'))
        assertFalse("base64 含 \\r（违反 NO_WRAP）", b64.contains('\r'))
    }

    @Test
    fun toDataUriRoundTripsToOriginalBytes() {
        val payload = "hello agnes 参考图".toByteArray()
        val f = tmpFile("rt", "jpg", payload)
        val uri = ImageCodec.toDataUri(f)
        val b64 = uri.substringAfter("base64,")
        assertArrayEquals(payload, Base64.decode(b64, Base64.NO_WRAP))
    }

    // ========== toBareBase64 ==========

    @Test
    fun toBareBase64HasNoDataPrefix() {
        val f = tmpFile("bare", "jpg", byteArrayOf(1, 2, 3))
        val b64 = ImageCodec.toBareBase64(f)
        assertFalse("裸 base64 不得带 data: 前缀", b64.startsWith("data:"))
    }

    @Test
    fun toBareBase64HasNoNewlinesAndRoundTrips() {
        val payload = ByteArray(500) { (it and 0xFF).toByte() }
        val f = tmpFile("barewrap", "jpg", payload)
        val b64 = ImageCodec.toBareBase64(f)
        assertFalse(b64.contains('\n'))
        assertFalse(b64.contains('\r'))
        assertArrayEquals(payload, Base64.decode(b64, Base64.NO_WRAP))
    }

    // ========== toRawBytes ==========

    @Test
    fun toRawBytesEqualsFileBytes() {
        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        val f = tmpFile("raw", "jpg", payload)
        assertArrayEquals(payload, ImageCodec.toRawBytes(f))
    }

    // ========== mimeByExtension ==========

    @Test
    fun mimeByExtensionMapping() {
        assertEquals("image/jpeg", ImageCodec.mimeByExtension("jpg"))
        assertEquals("image/jpeg", ImageCodec.mimeByExtension("JPEG"))
        assertEquals("image/png", ImageCodec.mimeByExtension("png"))
        assertEquals("image/webp", ImageCodec.mimeByExtension("webp"))
        assertEquals("image/gif", ImageCodec.mimeByExtension("gif"))
        assertEquals("image/jpeg", ImageCodec.mimeByExtension("unknown"))
        assertEquals("image/jpeg", ImageCodec.mimeByExtension(""))
    }

    // ========== compress（nativeruntime 可用时验证缩尺寸 + EXIF 旋转） ==========

    private fun synthJpeg(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun canDecodeJpeg(bytes: ByteArray): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth to o.outHeight
    }

    @Test
    fun compressScalesDownLongEdge() = runTest {
        val src = synthJpeg(400, 200)
        assumeTrue("Robolectric nativeruntime 不可用，跳过缩尺寸验证", canDecodeJpeg(src))
        val out = ImageCodec.compress(src, maxLongEdge = 100, quality = 90)
        assertTrue("压缩输出应为有效 JPEG（FFD8 开头）", out.size >= 2 && out[0] == 0xFF.toByte() && out[1] == 0xD8.toByte())
        val (w, h) = decodeBounds(out)
        assertTrue("长边应 ≤ 100，实际 ${maxOf(w, h)}", maxOf(w, h) <= 100)
    }

    @Test
    fun compressReturnsOriginalWhenUndecodable() = runTest {
        // 不可解码字节应原样返回（压缩是优化，不得让原本能跑通的调用因压缩层新失败）
        val junk = byteArrayOf(0, 1, 2, 3, 4)
        val out = ImageCodec.compress(junk, maxLongEdge = 100, quality = 90)
        assertArrayEquals(junk, out)
    }

    @Test
    fun compressAppliesExifOrientation() = runTest {
        // 40x20 JPEG 注入 EXIF orientation=6（rotate 90）→ 压缩后应为 20x40
        val src = synthJpeg(40, 20)
        assumeTrue("Robolectric nativeruntime 不可用，跳过 EXIF 验证", canDecodeJpeg(src))
        val srcFile = tmpFile("exif", "jpg", src)
        val oriented = try {
            val exif = ExifInterface(srcFile)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            exif.saveAttributes()
            srcFile.readBytes()
        } catch (e: Throwable) {
            assumeNoException("ExifInterface 写入不可用", e); return@runTest
        }
        val out = ImageCodec.compress(oriented, maxLongEdge = 0, quality = 90)
        val (w, h) = decodeBounds(out)
        assertEquals("EXIF ROTATE_90 应将 40x20 旋转为 20x40", 20, w)
        assertEquals("EXIF ROTATE_90 应将 40x20 旋转为 20x40", 40, h)
    }
}
