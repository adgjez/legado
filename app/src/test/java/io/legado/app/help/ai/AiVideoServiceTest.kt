package io.legado.app.help.ai

import android.app.Application
import io.legado.app.help.ai.backends.VideoBackend
import io.legado.app.help.ai.backends.VideoBackendRegistry
import io.legado.app.help.ai.backends.VideoCapability
import io.legado.app.help.ai.backends.VideoCapabilities
import io.legado.app.help.ai.backends.VideoGenerationRequest
import io.legado.app.help.ai.backends.VideoGenerationResult
import io.legado.app.help.ai.backends.VideoProgress
import io.legado.app.help.ai.backends.compress.CompressedRef
import io.legado.app.help.ai.backends.compress.PayloadLimits
import io.legado.app.help.ai.backends.compress.RefRole
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * P4 服务薄壳化单测（plan P4 验收）：
 * - [AiVideoService.buildSpecs] 三分支：全支持 / 尾帧降级 / 尾帧忽略
 * - [AiVideoService.withCompressedRefs] label 还原回 startImage/endImage/referenceImages
 * - [AiVideoService.resolvePayloadLimits] 默认 / 覆盖
 * - [AiVideoService.generate] 路径分发到 Registry 注册的 backend
 *
 * 老调用点（[AiVideoTaskPoller]）仍用老 submit/poll/downloadToLocal API，不在此测试
 * 范围（其行为由 [AiVideoServiceAgnesTest] / [AiVideoServiceDoubaoTest] / [AiVideoServiceBuildSubmitUrlTest] 覆盖）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AiVideoServiceTest {

    // ============================================================
    // buildSpecs：尾帧三级 fallback
    // ============================================================

    @Test
    fun buildSpecsAllocatesFirstLastRefsWhenAllSupported() {
        // caps: firstFrame/lastFrame/refs 全 true
        val backend = FakeVideoBackend(
            videoCapabilities = VideoCapabilities(
                firstFrame = true, lastFrame = true, referenceImages = true,
                maxReferenceImages = 4, referenceImagesWithStartFrame = true
            )
        )
        val request = VideoGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.mp4"),
            startImage = File("/tmp/start.jpg"),
            endImage = File("/tmp/end.jpg"),
            referenceImages = listOf(File("/tmp/ref0.jpg"), File("/tmp/ref1.jpg"))
        )
        val specs = AiVideoService.buildSpecs(request, backend)
        assertEquals(4, specs.size)
        assertEquals("first_frame", specs[0].label)
        assertEquals(RefRole.FRAME, specs[0].role)
        assertEquals("/tmp/start.jpg", specs[0].source.absolutePath)
        assertEquals("last_frame", specs[1].label)
        assertEquals(RefRole.FRAME, specs[1].role)
        assertEquals("/tmp/end.jpg", specs[1].source.absolutePath)
        assertEquals("ref_0", specs[2].label)
        assertEquals(RefRole.ARRAY, specs[2].role)
        assertEquals("ref_1", specs[3].label)
        assertEquals(RefRole.ARRAY, specs[3].role)
    }

    @Test
    fun buildSpecsDowngradesLastFrameWhenOnlyRefsSupported() {
        // caps: lastFrame=false, referenceImages=true → 尾帧降级为 ARRAY
        val backend = FakeVideoBackend(
            videoCapabilities = VideoCapabilities(
                firstFrame = true, lastFrame = false, referenceImages = true,
                maxReferenceImages = 4
            )
        )
        val request = VideoGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.mp4"),
            startImage = File("/tmp/start.jpg"),
            endImage = File("/tmp/end.jpg")
        )
        val specs = AiVideoService.buildSpecs(request, backend)
        assertEquals(2, specs.size)
        assertEquals("first_frame", specs[0].label)
        assertEquals(RefRole.FRAME, specs[0].role)
        // 尾帧降级为 ARRAY，label 标记 downgraded
        assertEquals("last_frame_downgraded", specs[1].label)
        assertEquals(RefRole.ARRAY, specs[1].role)
        assertEquals("/tmp/end.jpg", specs[1].source.absolutePath)
    }

    @Test
    fun buildSpecsDropsLastFrameWhenNeitherSupported() {
        // caps: lastFrame=false, referenceImages=false → 尾帧忽略
        val backend = FakeVideoBackend(
            videoCapabilities = VideoCapabilities(
                firstFrame = true, lastFrame = false, referenceImages = false
            )
        )
        val request = VideoGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.mp4"),
            startImage = File("/tmp/start.jpg"),
            endImage = File("/tmp/end.jpg")
        )
        val specs = AiVideoService.buildSpecs(request, backend)
        // 尾帧被忽略，仅首帧
        assertEquals(1, specs.size)
        assertEquals("first_frame", specs[0].label)
        assertEquals(RefRole.FRAME, specs[0].role)
    }

    @Test
    fun buildSpecsHandlesNoFramesNoRefs() {
        // 全空 request → 空 specs
        val backend = FakeVideoBackend(
            videoCapabilities = VideoCapabilities(
                firstFrame = true, lastFrame = true, referenceImages = true
            )
        )
        val request = VideoGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.mp4")
        )
        val specs = AiVideoService.buildSpecs(request, backend)
        assertTrue("无帧无参考图应返回空 specs", specs.isEmpty())
    }

    // ============================================================
    // withCompressedRefs：label 还原回 request
    // ============================================================

    @Test
    fun withCompressedRefsRestoresStartEndAndRefs() {
        val request = VideoGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.mp4"),
            startImage = File("/tmp/orig_start.jpg"),
            endImage = File("/tmp/orig_end.jpg"),
            referenceImages = listOf(File("/tmp/orig_ref.jpg"))
        )
        val compressed = listOf(
            CompressedRef(File("/tmp/c_start.jpg"), "first_frame", RefRole.FRAME),
            CompressedRef(File("/tmp/c_end.jpg"), "last_frame", RefRole.FRAME),
            // 降级尾帧 + ref_* 应一并归入 referenceImages
            CompressedRef(File("/tmp/c_down.jpg"), "last_frame_downgraded", RefRole.ARRAY),
            CompressedRef(File("/tmp/c_ref0.jpg"), "ref_0", RefRole.ARRAY)
        )
        val merged = AiVideoService.run { request.withCompressedRefs(compressed) }
        assertEquals(File("/tmp/c_start.jpg"), merged.startImage)
        assertEquals(File("/tmp/c_end.jpg"), merged.endImage)
        assertEquals(
            listOf(File("/tmp/c_down.jpg"), File("/tmp/c_ref0.jpg")),
            merged.referenceImages
        )
    }

    @Test
    fun withCompressedRefsEmptyCompressedKeepsRequestShape() {
        val request = VideoGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.mp4"),
            startImage = File("/tmp/orig_start.jpg"),
            endImage = File("/tmp/orig_end.jpg"),
            referenceImages = listOf(File("/tmp/orig_ref.jpg"))
        )
        val merged = AiVideoService.run { request.withCompressedRefs(emptyList()) }
        assertNull(merged.startImage)
        assertNull(merged.endImage)
        assertTrue(merged.referenceImages.isNullOrEmpty())
        // prompt 与 outputPath 应保留
        assertEquals("p", merged.prompt)
    }

    // ============================================================
    // resolvePayloadLimits
    // ============================================================

    @Test
    fun resolvePayloadLimitsUsesDefaultsWhenBlank() {
        val provider = AiVideoProviderConfig(
            name = "p", type = AiVideoProviderConfig.TYPE_ARK,
            defaultParamsJson = ""
        )
        val limits = AiVideoService.resolvePayloadLimits(provider)
        assertEquals(8L * 1024 * 1024, limits.totalMaxBytes)
        assertEquals(4L * 1024 * 1024, limits.singleMaxBytes)
    }

    @Test
    fun resolvePayloadLimitsUsesDefaultsWhenJsonInvalid() {
        val provider = AiVideoProviderConfig(
            name = "p", type = AiVideoProviderConfig.TYPE_ARK,
            defaultParamsJson = "{not json"
        )
        val limits = AiVideoService.resolvePayloadLimits(provider)
        assertEquals(PayloadLimits().totalMaxBytes, limits.totalMaxBytes)
        assertEquals(PayloadLimits().singleMaxBytes, limits.singleMaxBytes)
    }

    @Test
    fun resolvePayloadLimitsOverridesFromParams() {
        val provider = AiVideoProviderConfig(
            name = "p", type = AiVideoProviderConfig.TYPE_ARK,
            defaultParamsJson = """{"reference_total_max_bytes": 1000000, "reference_single_max_bytes": 500000}"""
        )
        val limits = AiVideoService.resolvePayloadLimits(provider)
        assertEquals(1000000L, limits.totalMaxBytes)
        assertEquals(500000L, limits.singleMaxBytes)
    }

    @Test
    fun resolvePayloadLimitsPartialOverrideKeepsOtherDefault() {
        val provider = AiVideoProviderConfig(
            name = "p", type = AiVideoProviderConfig.TYPE_ARK,
            defaultParamsJson = """{"reference_single_max_bytes": 999}"""
        )
        val limits = AiVideoService.resolvePayloadLimits(provider)
        // total 用默认，single 用 override
        assertEquals(8L * 1024 * 1024, limits.totalMaxBytes)
        assertEquals(999L, limits.singleMaxBytes)
    }

    // ============================================================
    // generate：路径分发到 Registry 注册的 backend
    // ============================================================

    @Test
    fun generateDispatchesToRegisteredBackendNoRefs() = runTest {
        // 注册 fake backend 返回固定 result
        val expected = VideoGenerationResult(
            videoPath = File("/tmp/out.mp4"),
            provider = "fake",
            model = "fake-model",
            durationSeconds = 5
        )
        val fakeType = "fake_video_p4_${System.nanoTime()}"
        VideoBackendRegistry.register(fakeType) { cfg ->
            FakeGeneratingBackend(cfg, expected)
        }
        val provider = AiVideoProviderConfig(
            name = "fake", type = fakeType,
            model = "fake-model"
        )
        val request = VideoGenerationRequest(
            prompt = "p",
            outputPath = File("/tmp/out.mp4"),
            durationSeconds = 5
        )
        val result = AiVideoService.generate(request, provider) { /* onProgress 忽略 */ }
        // 直接拿到 backend 返回的 result（无参考图时 MediaGenerator 单次调用）
        assertSame(expected, result)
    }

    @Test
    fun generatePassesOnProgressToBackend() = runTest {
        val progressCollector = mutableListOf<VideoProgress>()
        val fakeType = "fake_video_progress_${System.nanoTime()}"
        val expected = VideoGenerationResult(
            videoPath = File("/tmp/out.mp4"),
            provider = "fake", model = "fake-model", durationSeconds = 5
        )
        VideoBackendRegistry.register(fakeType) { cfg ->
            object : VideoBackend {
                override val typeId: String = fakeType
                override val model: String = "fake-model"
                override val capabilities: Set<VideoCapability> = emptySet()
                override val videoCapabilities: VideoCapabilities = VideoCapabilities()
                override suspend fun generate(
                    request: VideoGenerationRequest,
                    onProgress: (VideoProgress) -> Unit
                ): VideoGenerationResult {
                    onProgress(VideoProgress("submitted", "test"))
                    onProgress(VideoProgress("done", null))
                    return expected
                }
                override suspend fun resumeVideo(
                    jobId: String,
                    request: VideoGenerationRequest
                ): VideoGenerationResult = throw NotImplementedError()
            }
        }
        val provider = AiVideoProviderConfig(name = "fake", type = fakeType)
        val request = VideoGenerationRequest(
            prompt = "p", outputPath = File("/tmp/out.mp4"), durationSeconds = 5
        )
        val result = AiVideoService.generate(request, provider) { p ->
            progressCollector.add(p)
        }
        assertSame(expected, result)
        assertEquals(2, progressCollector.size)
        assertEquals("submitted", progressCollector[0].status)
        assertEquals("done", progressCollector[1].status)
    }

    // ============================================================
    // 测试桩
    // ============================================================

    /** 用于 buildSpecs/withCompressedRefs/resolvePayloadLimits 测试的 stub backend。 */
    private class FakeVideoBackend(
        override val typeId: String = "fake",
        override val model: String = "fake-model",
        override val capabilities: Set<VideoCapability> = emptySet(),
        override val videoCapabilities: VideoCapabilities
    ) : VideoBackend {
        override suspend fun generate(
            request: VideoGenerationRequest,
            onProgress: (VideoProgress) -> Unit
        ): VideoGenerationResult = throw UnsupportedOperationException("not used in buildSpecs tests")

        override suspend fun resumeVideo(
            jobId: String,
            request: VideoGenerationRequest
        ): VideoGenerationResult = throw UnsupportedOperationException("not used")
    }

    /** 用于 generate 路径分发测试的 stub backend，返回固定 result。 */
    private class FakeGeneratingBackend(
        cfg: AiVideoProviderConfig,
        private val result: VideoGenerationResult
    ) : VideoBackend {
        override val typeId: String = "fake"
        override val model: String = cfg.model.ifBlank { "fake-model" }
        override val capabilities: Set<VideoCapability> = emptySet()
        override val videoCapabilities: VideoCapabilities = VideoCapabilities()
        override suspend fun generate(
            request: VideoGenerationRequest,
            onProgress: (VideoProgress) -> Unit
        ): VideoGenerationResult = result

        override suspend fun resumeVideo(
            jobId: String,
            request: VideoGenerationRequest
        ): VideoGenerationResult = throw NotImplementedError()
    }
}
