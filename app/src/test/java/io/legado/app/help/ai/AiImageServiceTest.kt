package io.legado.app.help.ai

import android.app.Application
import android.graphics.Bitmap
import io.legado.app.help.ai.backends.ImageBackend
import io.legado.app.help.ai.backends.ImageBackendRegistry
import io.legado.app.help.ai.backends.ImageCapability
import io.legado.app.help.ai.backends.ImageGenerationRequest
import io.legado.app.help.ai.backends.ImageGenerationResult
import io.legado.app.help.ai.backends.ReferenceImage
import io.legado.app.help.ai.backends.compress.PayloadLimits
import io.legado.app.ui.main.ai.AiImageProviderConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * P4 服务薄壳化单测（plan P4 验收）：
 * - [AiImageService.generate] 路径分发到 Registry 注册的 backend
 * - [AiImageService.generate] 所有参考图按 [io.legado.app.help.ai.backends.compress.RefRole.ARRAY] 角色
 * - [AiImageService.resolvePayloadLimits] 默认 / 覆盖
 *
 * 老调用点（[NovelVideoGenerator] Stage 5）仍用老 generate/generateAndStore（String prompt），
 * 不在此测试范围（由 [AiImageServiceAgnesImageTest] / [AiImageServiceChatVisionTest] /
 * [AiImageServiceAgnesFallbackTest] 覆盖）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AiImageServiceTest {

    // ============================================================
    // generate：路径分发 + 参考图 ARRAY 角色
    // ============================================================

    @Test
    fun generateDispatchesToRegisteredBackendNoRefs() = runTest {
        val expected = ImageGenerationResult(
            imagePath = File("/tmp/out.jpg"),
            provider = "fake",
            model = "fake-model"
        )
        val fakeType = "fake_image_p4_${System.nanoTime()}"
        ImageBackendRegistry.register(fakeType) { cfg ->
            FakeImageBackend(cfg, expected, captureRequest = null)
        }
        val provider = AiImageProviderConfig(
            name = "fake", type = fakeType, model = "fake-model"
        )
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.jpg")
        )
        val result = AiImageService.generate(request, provider)
        assertSame(expected, result)
    }

    @Test
    fun generatePassesAllReferenceImagesAsArrayRole() = runTest {
        // 用真实临时 JPEG 文件：避免 ReferenceCompressor 读源失败触发 AppLog.put
        // （AppLog.put 在 Robolectric 下触发 AppConfig init 失败）。
        // 合规小 JPEG（<1MB）走 step0 透传，原路径透传，backend 收到的路径与原 request 相同。
        // 通过 captureRequest 捕获 backend 实际收到的 request，验证参考图按 ARRAY 角色透传。
        val refFiles = (0..2).map { i ->
            val raw = synthJpeg(100, 100)
            File.createTempFile("ref$i", ".jpg").apply { writeBytes(raw); deleteOnExit() }
        }
        val captured = ArrayList<ImageGenerationRequest>()
        val expected = ImageGenerationResult(
            imagePath = File("/tmp/out.jpg"),
            provider = "fake",
            model = "fake-model"
        )
        val fakeType = "fake_image_array_${System.nanoTime()}"
        ImageBackendRegistry.register(fakeType) { cfg ->
            FakeImageBackend(cfg, expected, captureRequest = captured)
        }
        val provider = AiImageProviderConfig(
            name = "fake", type = fakeType, model = "fake-model"
        )
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.jpg"),
            referenceImages = listOf(
                ReferenceImage(refFiles[0].absolutePath, "scene"),
                ReferenceImage(refFiles[1].absolutePath, "character"),
                ReferenceImage(refFiles[2].absolutePath, "")
            )
        )
        val result = AiImageService.generate(request, provider)
        assertSame(expected, result)
        // backend 收到 3 张参考图（合规小 JPEG 走压缩梯子，写临时文件 _step0.jpg，
        // 路径变为压缩后临时文件路径；核心验证 label 保留 + 数量正确 + ARRAY 角色透传）
        assertEquals(1, captured.size)
        val received = captured[0]
        assertEquals(3, received.referenceImages.size)
        assertEquals("scene", received.referenceImages[0].label)
        assertEquals("character", received.referenceImages[1].label)
        assertEquals("", received.referenceImages[2].label)
        // 路径应为压缩后临时文件（含 _step0 后缀），证明走了压缩管线
        assertTrue(received.referenceImages[0].path.contains("_step0"))
        assertTrue(received.referenceImages[1].path.contains("_step0"))
        assertTrue(received.referenceImages[2].path.contains("_step0"))
    }

    @Test
    fun generateEmptyReferenceImagesStillDispatches() = runTest {
        val expected = ImageGenerationResult(
            imagePath = File("/tmp/out.jpg"),
            provider = "fake",
            model = "fake-model"
        )
        val fakeType = "fake_image_empty_${System.nanoTime()}"
        ImageBackendRegistry.register(fakeType) { cfg ->
            FakeImageBackend(cfg, expected, captureRequest = null)
        }
        val provider = AiImageProviderConfig(
            name = "fake", type = fakeType, model = "fake-model"
        )
        // 空 referenceImages（默认值）
        val request = ImageGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.jpg")
        )
        val result = AiImageService.generate(request, provider)
        assertSame(expected, result)
        // 空 specs → MediaGenerator 直接调 backend.generate(request)
        // backend 收到的 request.referenceImages 仍是空
        assertTrue(request.referenceImages.isEmpty())
    }

    // ============================================================
    // resolvePayloadLimits
    // ============================================================

    @Test
    fun resolvePayloadLimitsUsesDefaultsWhenBlank() {
        val provider = AiImageProviderConfig(
            name = "p", type = AiImageProviderConfig.TYPE_ARK,
            defaultParamsJson = ""
        )
        val limits = AiImageService.resolvePayloadLimits(provider)
        assertEquals(8L * 1024 * 1024, limits.totalMaxBytes)
        assertEquals(4L * 1024 * 1024, limits.singleMaxBytes)
    }

    @Test
    fun resolvePayloadLimitsUsesDefaultsWhenJsonInvalid() {
        val provider = AiImageProviderConfig(
            name = "p", type = AiImageProviderConfig.TYPE_ARK,
            defaultParamsJson = "{not json"
        )
        val limits = AiImageService.resolvePayloadLimits(provider)
        assertEquals(PayloadLimits().totalMaxBytes, limits.totalMaxBytes)
        assertEquals(PayloadLimits().singleMaxBytes, limits.singleMaxBytes)
    }

    @Test
    fun resolvePayloadLimitsOverridesFromParams() {
        val provider = AiImageProviderConfig(
            name = "p", type = AiImageProviderConfig.TYPE_ARK,
            defaultParamsJson = """{"reference_total_max_bytes": 1000000, "reference_single_max_bytes": 500000}"""
        )
        val limits = AiImageService.resolvePayloadLimits(provider)
        assertEquals(1000000L, limits.totalMaxBytes)
        assertEquals(500000L, limits.singleMaxBytes)
    }

    @Test
    fun resolvePayloadLimitsPartialOverrideKeepsOtherDefault() {
        val provider = AiImageProviderConfig(
            name = "p", type = AiImageProviderConfig.TYPE_ARK,
            defaultParamsJson = """{"reference_total_max_bytes": 2048}"""
        )
        val limits = AiImageService.resolvePayloadLimits(provider)
        assertEquals(2048L, limits.totalMaxBytes)
        assertEquals(4L * 1024 * 1024, limits.singleMaxBytes)
    }

    // ============================================================
    // 测试桩
    // ============================================================

    /** 合成真实 JPEG 字节，供参考图临时文件用（避免 ReferenceCompressor 读源失败）。 */
    private fun synthJpeg(w: Int, h: Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** 捕获 request 并返回固定 result 的 stub backend。 */
    private class FakeImageBackend(
        cfg: AiImageProviderConfig,
        private val result: ImageGenerationResult,
        private val captureRequest: ArrayList<ImageGenerationRequest>?
    ) : ImageBackend {
        override val typeId: String = "fake"
        override val model: String = cfg.model.ifBlank { "fake-model" }
        override val capabilities: Set<ImageCapability> = setOf(
            ImageCapability.TEXT_TO_IMAGE, ImageCapability.IMAGE_TO_IMAGE
        )
        override suspend fun generate(request: ImageGenerationRequest): ImageGenerationResult {
            captureRequest?.add(request)
            return result
        }
    }
}
