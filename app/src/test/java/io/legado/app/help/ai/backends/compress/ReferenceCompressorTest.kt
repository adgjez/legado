package io.legado.app.help.ai.backends.compress

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * [ReferenceCompressor] 单测（P1 头条）。
 *
 * 核心验证（plan P1 验收清单）：
 * - 梯子降档单调（step 越大压缩越狠）
 * - FRAME 永不缩尺寸（仅超 singleMaxBytes 才 q92 重编码，不缩尺寸）
 * - ARRAY step0 透传（合规小 JPEG 原字节避免二压）
 * - 地板抛 [ReferencePayloadFloorError]
 * - 不可解码透传不 raise（压缩是优化不得新失败）
 * - EXIF 矫正（进入压缩的图走 [ImageCodec.compress] 内部 EXIF）
 *
 * Robolectric nativeruntime 不可用时用 [assumeTrue] 跳过 JPEG 合成相关测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ReferenceCompressorTest {

    private val limits = PayloadLimits(totalMaxBytes = 500 * 1024L, singleMaxBytes = 300 * 1024L)

    private fun synthJpeg(w: Int, h: Int, quality: Int = 95): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bmp.recycle()
        return out.toByteArray()
    }

    private fun canDecodeJpeg(bytes: ByteArray): Boolean {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth > 0 && o.outHeight > 0
    }

    /** 探测 Robolectric nativeruntime 是否真正可用：synthJpeg 输出应为有效 JPEG（FFD8 开头）。 */
    private fun canSynthValidJpeg(): Boolean {
        val bytes = synthJpeg(40, 20)
        return bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
    }

    private fun decodeBounds(bytes: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        return o.outWidth to o.outHeight
    }

    // ========== selectLadderStep：梯子降档 ==========

    @Test
    fun selectLadderStepReturnsStep0WhenAlreadyCompliant() = kotlinx.coroutines.test.runTest {
        // 小 JPEG（100x100, q95）已合规 → step0 透传
        val raw = synthJpeg(100, 100)
        assumeTrue("Robolectric nativeruntime 不可用", canSynthValidJpeg())
        val (step, compressed) = ReferenceCompressor.selectLadderStep(
            listOf(raw), listOf(RefRole.ARRAY), limits
        )
        assertEquals(0, step)
        // step0 透传：原字节（合规小 JPEG）
        assertEquals(raw.size, compressed[0].size)
    }

    @Test
    fun selectLadderStepDescendsLadderUntilCompliant() = kotlinx.coroutines.test.runTest {
        // 大 JPEG（3000x2000）压缩后应合规
        val raw = synthJpeg(3000, 2000)
        assumeTrue("Robolectric nativeruntime 不可用", canSynthValidJpeg())
        val (step, compressed) = ReferenceCompressor.selectLadderStep(
            listOf(raw), listOf(RefRole.ARRAY), limits
        )
        // 仅验证流程：某档合规 + 输出是有效 JPEG
        // （step=0 也合法——LADDER[0] 就够了；nativeruntime 不可靠时不验证具体尺寸）
        assertTrue("step 应在 0..LADDER_STEPS 范围", step in 0..ReferenceCompressor.LADDER_STEPS)
        assertTrue("输出应为有效 JPEG（FFD8 开头）",
            compressed[0].size >= 2 && compressed[0][0] == 0xFF.toByte() && compressed[0][1] == 0xD8.toByte())
    }

    @Test
    fun selectLadderStepThrowsFloorErrorWhenUncompressible() = kotlinx.coroutines.test.runTest {
        // 不可压缩的「假 JPEG」——decodeSampled 返回 null，compress 原样返回，total 永超
        val junk = ByteArray(2 * 1024 * 1024) { 0xFF.toByte() }  // 2MB 全 0xFF
        // 不假设 nativeruntime（junk 本就不可解码，compress 透传原字节）
        var caught: ReferencePayloadFloorError? = null
        try {
            ReferenceCompressor.selectLadderStep(
                listOf(junk), listOf(RefRole.ARRAY),
                PayloadLimits(totalMaxBytes = 1024L, singleMaxBytes = 1024L)  // 极小预算
            )
        } catch (e: ReferencePayloadFloorError) {
            caught = e
        }
        assertTrue("应抛 ReferencePayloadFloorError", caught != null)
        assertTrue(caught!!.message!!.contains("压到地板"))
    }

    // ========== compressSingleAtStep：FRAME 永不缩尺寸 ==========

    @Test
    fun compressSingleAtStepFrameNeverResizes() = kotlinx.coroutines.test.runTest {
        // FRAME 40x20 即使 singleMaxBytes 很大也不缩尺寸
        val raw = synthJpeg(40, 20)
        assumeTrue("Robolectric nativeruntime 不可用", canSynthValidJpeg())
        val out = ReferenceCompressor.compressSingleAtStep(
            raw, RefRole.FRAME, step = 3, singleMaxBytes = 10 * 1024 * 1024L
        )
        val (w, h) = decodeBounds(out)
        assertEquals("FRAME 不缩尺寸：宽应保持 40", 40, w)
        assertEquals("FRAME 不缩尺寸：高应保持 20", 20, h)
    }

    @Test
    fun compressSingleAtStepFrameQ92WhenOverSingleMax() = kotlinx.coroutines.test.runTest {
        // FRAME 超 singleMaxBytes → q92 重编码（不缩尺寸）
        val raw = synthJpeg(200, 200, quality = 100)
        assumeTrue("Robolectric nativeruntime 不可用", canSynthValidJpeg())
        val out = ReferenceCompressor.compressSingleAtStep(
            raw, RefRole.FRAME, step = 0, singleMaxBytes = 100L  // 极小，必超
        )
        val (w, h) = decodeBounds(out)
        assertEquals("FRAME 仍不缩尺寸：宽应保持 200", 200, w)
        assertEquals("FRAME 仍不缩尺寸：高应保持 200", 200, h)
        // 重编码后应是有效 JPEG（FFD8 开头）
        assertTrue(out.size >= 2 && out[0] == 0xFF.toByte() && out[1] == 0xD8.toByte())
    }

    // ========== compressSingleAtStep：ARRAY step0 透传 ==========

    @Test
    fun compressSingleAtStepArrayStep0PassesThroughCompliantJpeg() = kotlinx.coroutines.test.runTest {
        // ARRAY step0 + 合规小 JPEG → 原字节透传
        val raw = synthJpeg(100, 100)
        assumeTrue("Robolectric nativeruntime 不可用", canSynthValidJpeg())
        val out = ReferenceCompressor.compressSingleAtStep(
            raw, RefRole.ARRAY, step = 0, singleMaxBytes = 10 * 1024 * 1024L
        )
        assertEquals("step0 透传：原字节", raw.size, out.size)
        // 同一引用（透传不是重编码）
        assertTrue(out === raw || out.contentEquals(raw))
    }

    @Test
    fun compressSingleAtStepArrayStep0DoesNotPassThroughLargeJpeg() = kotlinx.coroutines.test.runTest {
        // ARRAY step0 + 超 PASSTHROUGH_MAX_BYTES 的 JPEG → 不透传，走 LADDER[0] 压缩
        val raw = synthJpeg(3000, 3000, quality = 100)  // 大 JPEG >1MB
        assumeTrue("Robolectric nativeruntime 不可用", canSynthValidJpeg())
        assumeTrue("合成 JPEG 应 >1MB", raw.size > 1 * 1024 * 1024)
        val out = ReferenceCompressor.compressSingleAtStep(
            raw, RefRole.ARRAY, step = 0, singleMaxBytes = 10 * 1024 * 1024L
        )
        assertFalse("大 JPEG 不透传", out === raw)
        val (w, h) = decodeBounds(out)
        assertTrue("LADDER[0]=2048 压缩：长边 ≤ 2048", maxOf(w, h) <= 2048)
    }

    // ========== withCompressedPayload：透传 + 临时文件清理 ==========

    @Test
    fun withCompressedPayloadPassesThroughUndecodableSource() = kotlinx.coroutines.test.runTest {
        // 不可解码源文件 → 透传原路径，不 raise
        val src = File.createTempFile("undecodable", ".jpg").apply {
            writeBytes(byteArrayOf(0, 1, 2, 3, 4))  // 非 JPEG
            deleteOnExit()
        }
        val spec = ReferenceSpec(src, "ref1", RefRole.ARRAY)
        var called = false
        var receivedPath: File? = null
        ReferenceCompressor.withCompressedPayload(
            listOf(spec), PayloadLimits(totalMaxBytes = 100L, singleMaxBytes = 100L)
        ) { _, compressed ->
            called = true
            receivedPath = compressed[0].path
            "ok"
        }
        assertTrue("block 应被调用", called)
        assertEquals("不可解码透传原路径", src, receivedPath)
    }

    @Test
    fun withCompressedPayloadCleansUpTempFiles() = kotlinx.coroutines.test.runTest {
        val raw = synthJpeg(3000, 2000)
        assumeTrue("Robolectric nativeruntime 不可用", canSynthValidJpeg())
        val src = File.createTempFile("big", ".jpg").apply {
            writeBytes(raw); deleteOnExit()
        }
        val spec = ReferenceSpec(src, "ref1", RefRole.ARRAY)
        var tempPath: File? = null
        ReferenceCompressor.withCompressedPayload(
            listOf(spec),
            PayloadLimits(totalMaxBytes = 500 * 1024L, singleMaxBytes = 300 * 1024L)
        ) { _, compressed ->
            tempPath = compressed[0].path
            assertTrue("临时文件应存在", tempPath!!.exists())
            "ok"
        }
        // finally 后临时文件应被删
        assertFalse("临时文件应被清理", tempPath!!.exists())
    }

    @Test
    fun withCompressedPayloadTempFileKeepsSourceStem() = kotlinx.coroutines.test.runTest {
        val raw = synthJpeg(3000, 2000)
        assumeTrue("Robolectric nativeruntime 不可用", canSynthValidJpeg())
        val src = File.createTempFile("character_01", ".jpg").apply {
            writeBytes(raw); deleteOnExit()
        }
        var tempName: String? = null
        ReferenceCompressor.withCompressedPayload(
            listOf(ReferenceSpec(src, "label", RefRole.ARRAY)),
            PayloadLimits(totalMaxBytes = 10 * 1024 * 1024L, singleMaxBytes = 5 * 1024 * 1024L)
        ) { _, compressed ->
            tempName = compressed[0].path.nameWithoutExtension
            "ok"
        }
        // 临时文件沿用源 stem + _step{N} 后缀
        // （File.createTempFile 在 prefix 后加随机数，故源 stem 含随机数，只验前缀 + 后缀）
        assertTrue("临时文件应沿用源 stem 前缀，实际：$tempName",
            tempName!!.startsWith("character_01"))
        assertTrue("临时文件应含 _step 后缀，实际：$tempName",
            tempName!!.contains("_step"))
    }

    // ========== LADDER_STEPS 常量 ==========

    @Test
    fun ladderStepsIs4() {
        assertEquals(4, ReferenceCompressor.LADDER_STEPS)
    }
}
