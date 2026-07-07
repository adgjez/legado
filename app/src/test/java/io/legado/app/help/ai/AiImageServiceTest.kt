package io.legado.app.help.ai

import android.app.Application
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
        // 通过 captureRequest 捕获 backend 实际收到的 request，
        // 但 buildSpecs 在 AiImageService.generate 内部 inline 调用，
        // backend 收到的是经过 withCompressedRefs 处理后的 request.referenceImages
        // （无参考图压缩时 specs 为空 → MediaGenerator 直接调 backend，
        //  传给 backend 的 request.referenceImages 与原 request 相同）
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
                ReferenceImage("/tmp/ref0.jpg", "scene"),
                ReferenceImage("/tmp/ref1.jpg", "character"),
                ReferenceImage("/tmp/ref2.jpg", "")
            )
        )
        val result = AiImageService.generate(request, provider)
        assertSame(expected, result)
        // backend 收到 3 张参考图（无压缩，原路径透传）
        assertEquals(1, captured.size)
        val received = captured[0]
        assertEquals(3, received.referenceImages.size)
        assertEquals("/tmp/ref0.jpg", received.referenceImages[0].path)
        assertEquals("scene", received.referenceImages[0].label)
        assertEquals("/tmp/ref1.jpg", received.referenceImages[1].path)
        assertEquals("character", received.referenceImages[1].label)
        assertEquals("/tmp/ref2.jpg", received.referenceImages[2].path)
        assertEquals("", received.referenceImages[2].label)
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
