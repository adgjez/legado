package io.legado.app.help.ai.backends

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.legado.app.help.ai.backends.compress.PayloadLimits
import io.legado.app.help.ai.backends.compress.ReferencePayloadFloorError
import io.legado.app.help.ai.backends.compress.ReferenceSpec
import io.legado.app.help.ai.backends.compress.RefRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * [MediaGenerator] 单测（P1 头条：413 续档逻辑）。
 *
 * 核心验证（plan P1 验收清单）：
 * - **无参考图 413 不降档**：specs 空 + buildAndCall 抛 413 → 直接抛 413（不续档，无图可压）
 * - **413 续档从 landed+1**：specs 非空 + buildAndCall 抛 413 → 续档重试
 * - **地板耗尽抛 [ReferencePayloadFloorError] 保 cause**：永远 413 → 续档耗尽抛错，cause 是 413 异常
 *
 * 续档测试依赖 [ReferenceCompressor] 能成功压缩（nativeruntime 可用）。nativeruntime
 * 不可用时用 [assumeTrue] 跳过，不阻断 CI。无参考图测试不依赖压缩，始终跑。
 */
class MediaGeneratorTest {

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

    private fun four13Exception(msg: String = "HTTP 413 Payload Too Large"): Throwable =
        IllegalStateException(msg)

    // ========== 无参考图：413 不降档直接抛 ==========

    @Test
    fun noSpecs413DoesNotDescend() = kotlinx.coroutines.test.runTest {
        // specs 空 + buildAndCall 抛 413 → 直接抛 413（不续档）
        var calls = 0
        val ex = try {
            MediaGenerator.runWithReferenceCompression(
                emptyList(),
                PayloadLimits()
            ) { _ ->
                calls++
                throw four13Exception()
            }
            null
        } catch (e: Throwable) {
            e
        }
        assertEquals("无参考图只调一次 buildAndCall", 1, calls)
        assertNotNull("应抛异常", ex)
        // 直接抛原始 413，不包装成 ReferencePayloadFloorError
        assertFalse("无参考图 413 不应包装成 ReferencePayloadFloorError",
            ex is ReferencePayloadFloorError)
        assertTrue("应含 413 信息", ex!!.message!!.contains("413"))
    }

    @Test
    fun noSpecsSuccessReturnsDirectly() = kotlinx.coroutines.test.runTest {
        // specs 空 + buildAndCall 成功 → 直接返回
        var calls = 0
        val result = MediaGenerator.runWithReferenceCompression(
            emptyList(), PayloadLimits()
        ) { _ ->
            calls++
            "success"
        }
        assertEquals(1, calls)
        assertEquals("success", result)
    }

    // ========== 有参考图：413 续档 ==========

    @Test
    fun four13RetriesFromLandedPlusOne() = kotlinx.coroutines.test.runTest {
        // 第一次抛 413 → 续档；第二次成功
        val raw = synthJpeg(100, 100)
        assumeTrue("Robolectric nativeruntime 不可用", canDecodeJpeg(raw))
        val src = File.createTempFile("ref", ".jpg").apply { writeBytes(raw); deleteOnExit() }
        val spec = ReferenceSpec(src, "ref", RefRole.ARRAY)

        var calls = 0
        val result = MediaGenerator.runWithReferenceCompression(
            listOf(spec),
            PayloadLimits(totalMaxBytes = 500 * 1024L, singleMaxBytes = 300 * 1024L)
        ) { _ ->
            calls++
            if (calls == 1) throw four13Exception()
            "recovered"
        }
        assertEquals("第一次 413 后续档，第二次成功 → 调 2 次", 2, calls)
        assertEquals("recovered", result)
    }

    @Test
    fun four13FloorExhaustionThrowsReferencePayloadFloorErrorPreservingCause() = kotlinx.coroutines.test.runTest {
        // 永远 413 → 续档耗尽（step 0→1→2→3→FLOOR 共 5 次）抛 ReferencePayloadFloorError，cause 是 413
        val raw = synthJpeg(100, 100)
        assumeTrue("Robolectric nativeruntime 不可用", canDecodeJpeg(raw))
        val src = File.createTempFile("ref", ".jpg").apply { writeBytes(raw); deleteOnExit() }
        val spec = ReferenceSpec(src, "ref", RefRole.ARRAY)

        var calls = 0
        val ex = try {
            MediaGenerator.runWithReferenceCompression(
                listOf(spec),
                PayloadLimits(totalMaxBytes = 500 * 1024L, singleMaxBytes = 300 * 1024L)
            ) { _ ->
                calls++
                throw four13Exception()
            }
            null
        } catch (e: ReferencePayloadFloorError) {
            e
        }
        // LADDER_STEPS=4：step 0,1,2,3（LADDER）+ 4（FLOOR）= 5 次
        assertEquals("应续档 5 次（4 档 LADDER + FLOOR）", 5, calls)
        assertNotNull("应抛 ReferencePayloadFloorError", ex)
        assertNotNull("cause 应保留（原始 413 异常）", ex!!.cause)
        assertTrue("cause message 应含 413", ex.cause!!.message!!.contains("413"))
    }

    @Test
    fun non413ErrorDoesNotRetry() = kotlinx.coroutines.test.runTest {
        // 非 413 错误（如 500）→ 直接抛，不续档
        val raw = synthJpeg(100, 100)
        assumeTrue("Robolectric nativeruntime 不可用", canDecodeJpeg(raw))
        val src = File.createTempFile("ref", ".jpg").apply { writeBytes(raw); deleteOnExit() }
        val spec = ReferenceSpec(src, "ref", RefRole.ARRAY)

        var calls = 0
        val ex = try {
            MediaGenerator.runWithReferenceCompression(
                listOf(spec), PayloadLimits()
            ) { _ ->
                calls++
                throw IllegalStateException("HTTP 500 Internal Server Error")
            }
            null
        } catch (e: IllegalStateException) {
            e
        }
        assertEquals("非 413 不续档，只调 1 次", 1, calls)
        assertTrue(ex!!.message!!.contains("500"))
    }
}
