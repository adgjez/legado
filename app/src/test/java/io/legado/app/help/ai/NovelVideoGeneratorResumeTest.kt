package io.legado.app.help.ai

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.dao.NovelVideoDao
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.data.entities.NovelVideoJobStatus
import io.legado.app.data.entities.NovelVideoSegment
import io.legado.app.data.entities.NovelVideoSegmentStatus
import io.legado.app.help.ai.backends.ResumeExpiredError
import io.legado.app.help.ai.backends.VideoBackend
import io.legado.app.help.ai.backends.VideoBackendRegistry
import io.legado.app.help.ai.backends.VideoCapability
import io.legado.app.help.ai.backends.VideoCapabilities
import io.legado.app.help.ai.backends.VideoGenerationRequest
import io.legado.app.help.ai.backends.VideoGenerationResult
import io.legado.app.help.ai.backends.VideoProgress
import io.legado.app.help.ai.scheduling.TestSchedulingDatabase
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * P4 resume 路径单测（plan P4 验收）。
 *
 * 验证 [NovelVideoGenerator.resumeVideoSegments] 对各 segment 状态/providerId 的分流：
 * - VIDEO_GENERATING + NON_RESUMABLE（grok/vidu）→ markFailed[resume_unsupported_provider]
 * - VIDEO_GENERATING + null providerJobId → markFailed[restart_lost_no_job_id]
 * - VIDEO_GENERATING + 有效 providerJobId → backend.resumeVideo → 成功落库 / 各类异常分流
 * - IMAGE_GENERATING → markFailed[restart_lost_image]
 *
 * 测试基础设施：
 * - [TestSchedulingDatabase] in-memory Room 注入 [NovelVideoGenerator.daoProvider]
 * - [NovelVideoGenerator.videoProviderLookup] 注入直接返回 fake config（绕过 AppConfig/SharedPreferences）
 * - [VideoBackendRegistry.register] 注册 fake backend（[FakeResumeBackend]），resumeVideo 行为可配
 * - 直接调 [NovelVideoGenerator.resumeVideoSegments]（internal），不触发后续 generate（依赖 bookDao）
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class NovelVideoGeneratorResumeTest {

    private lateinit var db: TestSchedulingDatabase
    private lateinit var dao: NovelVideoDao
    private var originalDaoProvider: (() -> NovelVideoDao)? = null
    private var originalProviderLookup: ((String?) -> AiVideoProviderConfig?)? = null

    @Before
    fun setUp() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, TestSchedulingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.novelVideoDao
        originalDaoProvider = NovelVideoGenerator.daoProvider
        originalProviderLookup = NovelVideoGenerator.videoProviderLookup
        NovelVideoGenerator.daoProvider = { dao }
    }

    @After
    fun tearDown() {
        originalDaoProvider?.let { NovelVideoGenerator.daoProvider = it }
        originalProviderLookup?.let { NovelVideoGenerator.videoProviderLookup = it }
        db.close()
    }

    /** 注册 fake backend + 注入 lookup，返回 provider config 供 segment.providerId 用。 */
    private fun installFakeBackend(
        fakeType: String,
        resumeBehavior: suspend (String, VideoGenerationRequest) -> VideoGenerationResult
    ): AiVideoProviderConfig {
        VideoBackendRegistry.register(fakeType) { cfg ->
            FakeResumeBackend(cfg, resumeBehavior)
        }
        val config = AiVideoProviderConfig(name = "fake", type = fakeType, model = "fake-model")
        NovelVideoGenerator.videoProviderLookup = { type -> if (type == fakeType) config else null }
        return config
    }

    private suspend fun insertJobWithSegment(
        jobId: String,
        segmentStatus: String,
        providerId: String?,
        providerJobId: String?
    ): NovelVideoSegment {
        dao.insertJob(
            NovelVideoJob(
                id = jobId,
                status = NovelVideoJobStatus.GENERATING,
                createdAt = 1000
            )
        )
        val segment = NovelVideoSegment(
            id = "seg_$jobId",
            jobId = jobId,
            sceneId = 1,
            status = segmentStatus,
            providerId = providerId,
            providerJobId = providerJobId,
            videoPrompt = "a calm scene",
            narration = "narration text"
        )
        dao.insertSegment(segment)
        return segment
    }

    private fun fakeVideoResult(): VideoGenerationResult {
        val tmp = File.createTempFile("fake_resume_video", ".mp4")
        tmp.writeText("fake")
        return VideoGenerationResult(
            videoPath = tmp,
            provider = "fake",
            model = "fake-model",
            durationSeconds = 5
        )
    }

    // ============================================================
    // 1. NON_RESUMABLE provider → markFailed[resume_unsupported_provider]
    // ============================================================

    @Test
    fun nonResumableProviderMarksFailed() = runBlocking {
        insertJobWithSegment(
            jobId = "job_grok",
            segmentStatus = NovelVideoSegmentStatus.VIDEO_GENERATING,
            providerId = "grok",
            providerJobId = "task_grok_1"
        )

        NovelVideoGenerator.resumeVideoSegments("job_grok") { false }

        val seg = dao.getSegmentsByJob("job_grok").first()
        assertEquals(NovelVideoSegmentStatus.FAILED, seg.status)
        assertTrue("应含 resume_unsupported_provider", seg.errorMessage?.contains("[resume_unsupported_provider]") == true)
    }

    // ============================================================
    // 2. null providerJobId → markFailed[restart_lost_no_job_id]
    // ============================================================

    @Test
    fun nullProviderJobIdMarksFailed() = runBlocking {
        installFakeBackend("fake_ark_${System.nanoTime()}") { _, _ -> fakeVideoResult() }
        insertJobWithSegment(
            jobId = "job_noid",
            segmentStatus = NovelVideoSegmentStatus.VIDEO_GENERATING,
            providerId = "ark",
            providerJobId = null
        )

        NovelVideoGenerator.resumeVideoSegments("job_noid") { false }

        val seg = dao.getSegmentsByJob("job_noid").first()
        assertEquals(NovelVideoSegmentStatus.FAILED, seg.status)
        assertTrue("应含 restart_lost_no_job_id", seg.errorMessage?.contains("[restart_lost_no_job_id]") == true)
    }

    // ============================================================
    // 3. 有效 providerJobId → resumeVideo → VIDEO_COMPLETED + providerJobId 清空
    // ============================================================

    @Test
    fun validProviderJobIdResumesAndCompletes() = runBlocking {
        val fakeType = "fake_ark_${System.nanoTime()}"
        var resumeCalled = false
        var capturedJobId: String? = null
        installFakeBackend(fakeType) { jobId, _ ->
            resumeCalled = true
            capturedJobId = jobId
            fakeVideoResult()
        }
        insertJobWithSegment(
            jobId = "job_ark",
            segmentStatus = NovelVideoSegmentStatus.VIDEO_GENERATING,
            providerId = fakeType,
            providerJobId = "task_ark_1"
        )

        NovelVideoGenerator.resumeVideoSegments("job_ark") { false }

        assertTrue("backend.resumeVideo 应被调用", resumeCalled)
        assertEquals("resumeVideo jobId 应为 segment.providerJobId",
            "task_ark_1", capturedJobId)
        val seg = dao.getSegmentsByJob("job_ark").first()
        assertEquals("segment 应 VIDEO_COMPLETED（实际=${seg.status}, err=${seg.errorMessage}）",
            NovelVideoSegmentStatus.VIDEO_COMPLETED, seg.status)
        assertEquals("providerJobId 应清空", null, seg.providerJobId)
        assertTrue("localVideoPath 应非空", seg.localVideoPath != null)
    }

    // ============================================================
    // 4. ResumeExpiredError → markFailed[resume_expired_detail]
    // ============================================================

    @Test
    fun resumeExpiredMarksFailed() = runBlocking {
        val fakeType = "fake_ark_${System.nanoTime()}"
        installFakeBackend(fakeType) { _, _ ->
            throw ResumeExpiredError("task expired or not found")
        }
        insertJobWithSegment(
            jobId = "job_exp",
            segmentStatus = NovelVideoSegmentStatus.VIDEO_GENERATING,
            providerId = fakeType,
            providerJobId = "task_exp_1"
        )

        NovelVideoGenerator.resumeVideoSegments("job_exp") { false }

        val seg = dao.getSegmentsByJob("job_exp").first()
        assertEquals(NovelVideoSegmentStatus.FAILED, seg.status)
        assertTrue("应含 resume_expired_detail", seg.errorMessage?.contains("[resume_expired_detail]") == true)
        assertTrue("应含 expired 详情", seg.errorMessage?.contains("expired") == true)
    }

    // ============================================================
    // 5. NotImplementedError → markFailed[resume_unsupported_detail]
    // ============================================================

    @Test
    fun resumeNotImplementedMarksFailed() = runBlocking {
        val fakeType = "fake_ark_${System.nanoTime()}"
        installFakeBackend(fakeType) { _, _ ->
            throw NotImplementedError("resumeVideo not impl for this backend")
        }
        insertJobWithSegment(
            jobId = "job_noimpl",
            segmentStatus = NovelVideoSegmentStatus.VIDEO_GENERATING,
            providerId = fakeType,
            providerJobId = "task_noimpl_1"
        )

        NovelVideoGenerator.resumeVideoSegments("job_noimpl") { false }

        val seg = dao.getSegmentsByJob("job_noimpl").first()
        assertEquals(NovelVideoSegmentStatus.FAILED, seg.status)
        assertTrue("应含 resume_unsupported_detail", seg.errorMessage?.contains("[resume_unsupported_detail]") == true)
    }

    // ============================================================
    // 6. IMAGE_GENERATING → markFailed[restart_lost_image]
    // ============================================================

    @Test
    fun imageGeneratingMarksRestartLostImage() = runBlocking {
        insertJobWithSegment(
            jobId = "job_img",
            segmentStatus = NovelVideoSegmentStatus.IMAGE_GENERATING,
            providerId = null,
            providerJobId = null
        )

        NovelVideoGenerator.resumeVideoSegments("job_img") { false }

        val seg = dao.getSegmentsByJob("job_img").first()
        assertEquals(NovelVideoSegmentStatus.FAILED, seg.status)
        assertTrue("应含 restart_lost_image", seg.errorMessage?.contains("[restart_lost_image]") == true)
    }

    // ============================================================
    // 7. provider 配置未找到（lookup 返回 null）→ markFailed[resume_unsupported_provider]
    // ============================================================

    @Test
    fun providerConfigNotFoundMarksFailed() = runBlocking {
        NovelVideoGenerator.videoProviderLookup = { null }
        insertJobWithSegment(
            jobId = "job_nocfg",
            segmentStatus = NovelVideoSegmentStatus.VIDEO_GENERATING,
            providerId = "deleted_provider",
            providerJobId = "task_1"
        )

        NovelVideoGenerator.resumeVideoSegments("job_nocfg") { false }

        val seg = dao.getSegmentsByJob("job_nocfg").first()
        assertEquals(NovelVideoSegmentStatus.FAILED, seg.status)
        assertTrue("应含 resume_unsupported_provider", seg.errorMessage?.contains("[resume_unsupported_provider]") == true)
    }

    // ============================================================
    // 8. VIDEO_COMPLETED / FAILED segment 不被 resume 触及
    // ============================================================

    @Test
    fun completedAndFailedSegmentsUntouched() = runBlocking {
        val fakeType = "fake_ark_${System.nanoTime()}"
        var resumeCalls = 0
        installFakeBackend(fakeType) { _, _ -> resumeCalls++; fakeVideoResult() }
        dao.insertJob(NovelVideoJob(id = "job_mix", status = NovelVideoJobStatus.GENERATING, createdAt = 1000))
        dao.insertSegment(NovelVideoSegment(id = "seg_done", jobId = "job_mix", sceneId = 1,
            status = NovelVideoSegmentStatus.VIDEO_COMPLETED, providerId = fakeType, providerJobId = "t1"))
        dao.insertSegment(NovelVideoSegment(id = "seg_failed", jobId = "job_mix", sceneId = 2,
            status = NovelVideoSegmentStatus.FAILED, providerId = fakeType, providerJobId = "t2"))

        NovelVideoGenerator.resumeVideoSegments("job_mix") { false }

        assertEquals("已终态 segment 不应触发 resumeVideo", 0, resumeCalls)
        val segs = dao.getSegmentsByJob("job_mix")
        assertEquals(NovelVideoSegmentStatus.VIDEO_COMPLETED, segs.find { it.id == "seg_done" }?.status)
        assertEquals(NovelVideoSegmentStatus.FAILED, segs.find { it.id == "seg_failed" }?.status)
    }

    /** 可配 resumeVideo 行为的 fake backend。 */
    private class FakeResumeBackend(
        cfg: AiVideoProviderConfig,
        private val resumeBehavior: suspend (String, VideoGenerationRequest) -> VideoGenerationResult
    ) : VideoBackend {
        override val typeId: String = cfg.type
        override val model: String = cfg.model.ifBlank { "fake-model" }
        override val capabilities: Set<VideoCapability> = emptySet()
        override val videoCapabilities: VideoCapabilities = VideoCapabilities()
        override suspend fun generate(
            request: VideoGenerationRequest,
            onProgress: (VideoProgress) -> Unit
        ): VideoGenerationResult = throw NotImplementedError("generate not used in resume test")

        override suspend fun resumeVideo(
            jobId: String,
            request: VideoGenerationRequest
        ): VideoGenerationResult = resumeBehavior(jobId, request)
    }
}
