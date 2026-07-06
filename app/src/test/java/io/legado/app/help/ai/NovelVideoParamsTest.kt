package io.legado.app.help.ai

import io.legado.app.data.entities.NovelVideoJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NovelVideoParams] 的 JSON 序列化往返与 [NovelVideoParams.fromJob] 行为测试。
 */
class NovelVideoParamsTest {

    @Test
    fun defaultParamsHasExpectedDefaults() {
        val params = NovelVideoParams()
        assertEquals(7, params.sceneCountPerChapter)
        assertEquals(5, params.sceneDurationSeconds)
        assertEquals("1280x720", params.resolution)
        assertEquals("anime style, manga art, 2D animation", params.stylePrompt)
        assertNull(params.imageProviderId)
        assertNull(params.videoProviderId)
        assertNull(params.llmModelId)
        assertEquals(2, params.maxCharacters)
        assertEquals(2, params.concurrency)
        assertTrue(params.enableReview)
        assertTrue(params.attachToBookChapter)
        assertTrue(!params.saveToGallery)
        assertEquals(600_000L, params.pollTimeoutMs)
        assertEquals(2_000L, params.pollIntervalMs)
    }

    @Test
    fun toJsonFromJsonRoundTripPreservesAllFields() {
        val original = NovelVideoParams(
            sceneCountPerChapter = 10,
            sceneDurationSeconds = 8,
            resolution = "1920x1080",
            stylePrompt = "watercolor style, soft colors",
            imageProviderId = "img-001",
            videoProviderId = "vid-001",
            llmModelId = "llm-gpt4",
            maxCharacters = 3,
            concurrency = 4,
            enableReview = false,
            attachToBookChapter = false,
            saveToGallery = true,
            pollTimeoutMs = 900_000L,
            pollIntervalMs = 3_000L
        )
        val json = original.toJson()
        val restored = NovelVideoParams.fromJson(json)
        assertEquals(original.sceneCountPerChapter, restored.sceneCountPerChapter)
        assertEquals(original.sceneDurationSeconds, restored.sceneDurationSeconds)
        assertEquals(original.resolution, restored.resolution)
        assertEquals(original.stylePrompt, restored.stylePrompt)
        assertEquals(original.imageProviderId, restored.imageProviderId)
        assertEquals(original.videoProviderId, restored.videoProviderId)
        assertEquals(original.llmModelId, restored.llmModelId)
        assertEquals(original.maxCharacters, restored.maxCharacters)
        assertEquals(original.concurrency, restored.concurrency)
        assertEquals(original.enableReview, restored.enableReview)
        assertEquals(original.attachToBookChapter, restored.attachToBookChapter)
        assertEquals(original.saveToGallery, restored.saveToGallery)
        assertEquals(original.pollTimeoutMs, restored.pollTimeoutMs)
        assertEquals(original.pollIntervalMs, restored.pollIntervalMs)
    }

    @Test
    fun fromJsonReturnsDefaultsOnInvalidJson() {
        val params = NovelVideoParams.fromJson("not a json")
        assertNotNull(params)
        // 应回退到默认值
        assertEquals(7, params.sceneCountPerChapter)
        assertEquals("1280x720", params.resolution)
    }

    @Test
    fun fromJsonReturnsDefaultsOnEmptyString() {
        val params = NovelVideoParams.fromJson("")
        assertEquals(NovelVideoParams(), params)
    }

    @Test
    fun fromJsonHandlesPartialJsonFillingDefaults() {
        val json = """{"sceneCountPerChapter": 5, "resolution": "1024x768"}"""
        val params = NovelVideoParams.fromJson(json)
        assertEquals(5, params.sceneCountPerChapter)
        assertEquals("1024x768", params.resolution)
        // 未提供的字段应回退到默认值
        assertEquals(5, params.sceneDurationSeconds)
        assertEquals("anime style, manga art, 2D animation", params.stylePrompt)
        assertNull(params.imageProviderId)
    }

    @Test
    fun fromJobDelegatesToParamsJsonField() {
        val job = NovelVideoJob(
            id = "job_001",
            paramsJson = NovelVideoParams(
                sceneCountPerChapter = 9,
                resolution = "1920x1080",
                videoProviderId = "vid-xyz"
            ).toJson()
        )
        val params = NovelVideoParams.fromJob(job)
        assertEquals(9, params.sceneCountPerChapter)
        assertEquals("1920x1080", params.resolution)
        assertEquals("vid-xyz", params.videoProviderId)
    }

    @Test
    fun fromJobReturnsDefaultsWhenParamsJsonIsBlankOrInvalid() {
        val job = NovelVideoJob(id = "job_002", paramsJson = "{}")
        val params = NovelVideoParams.fromJob(job)
        assertEquals(NovelVideoParams(), params)

        val job2 = NovelVideoJob(id = "job_003", paramsJson = "garbage")
        val params2 = NovelVideoParams.fromJob(job2)
        assertEquals(NovelVideoParams(), params2)
    }

    // ============================================================
    // coerced() 边界兜底测试（P2-13/P3-7 修复）
    // ============================================================

    @Test
    fun fromJsonClampsSceneDurationSecondsToAtLeast1() {
        // sceneDurationSeconds=0 会导致 0 秒视频，coerce 后应为 1
        val json = """{"sceneDurationSeconds": 0}"""
        val params = NovelVideoParams.fromJson(json)
        assertEquals(1, params.sceneDurationSeconds)
    }

    @Test
    fun fromJsonClampsSceneDurationSecondsToUpperBound30() {
        val json = """{"sceneDurationSeconds": 999}"""
        val params = NovelVideoParams.fromJson(json)
        assertEquals(30, params.sceneDurationSeconds)
    }

    @Test
    fun fromJsonClampsConcurrencyToAtLeast1() {
        // concurrency=0 会导致 chunked(0) 抛异常，coerce 后应为 1
        val json = """{"concurrency": 0}"""
        val params = NovelVideoParams.fromJson(json)
        assertEquals(1, params.concurrency)
    }

    @Test
    fun fromJsonClampsConcurrencyToUpperBound4() {
        val json = """{"concurrency": 99}"""
        val params = NovelVideoParams.fromJson(json)
        assertEquals(4, params.concurrency)
    }

    @Test
    fun fromJsonClampsMaxCharactersToRange0To3() {
        val json = """{"maxCharacters": 99}"""
        val params = NovelVideoParams.fromJson(json)
        assertEquals(3, params.maxCharacters)
    }

    @Test
    fun fromJsonClampsSceneCountPerChapterToRange3To12() {
        // P3-7: sceneCountPerChapter 范围与 PromptBuilder 对齐为 3..12
        val tooLow = NovelVideoParams.fromJson("""{"sceneCountPerChapter": 1}""")
        assertEquals(3, tooLow.sceneCountPerChapter)

        val tooHigh = NovelVideoParams.fromJson("""{"sceneCountPerChapter": 99}""")
        assertEquals(12, tooHigh.sceneCountPerChapter)
    }

    @Test
    fun fromJsonClampsPollIntervalMsToAtLeast500() {
        // pollIntervalMs=0 会导致轮询 spin-loop 空转耗电
        val json = """{"pollIntervalMs": 0}"""
        val params = NovelVideoParams.fromJson(json)
        assertEquals(500L, params.pollIntervalMs)
    }

    @Test
    fun fromJsonClampsPollIntervalMsToUpperBound60000() {
        val json = """{"pollIntervalMs": 99999999}"""
        val params = NovelVideoParams.fromJson(json)
        assertEquals(60_000L, params.pollIntervalMs)
    }

    @Test
    fun fromJsonClampsPollTimeoutMsToAtLeast10000() {
        // pollTimeoutMs 太短会导致视频还没生成就超时失败
        val json = """{"pollTimeoutMs": 100}"""
        val params = NovelVideoParams.fromJson(json)
        assertEquals(10_000L, params.pollTimeoutMs)
    }

    @Test
    fun fromJsonPreservesValidExtremeValuesWithoutClamping() {
        // 合法边界值不应被修改
        val json = """{"sceneCountPerChapter": 3, "sceneDurationSeconds": 1, "concurrency": 1, "maxCharacters": 0, "pollIntervalMs": 500, "pollTimeoutMs": 10000}"""
        val params = NovelVideoParams.fromJson(json)
        assertEquals(3, params.sceneCountPerChapter)
        assertEquals(1, params.sceneDurationSeconds)
        assertEquals(1, params.concurrency)
        assertEquals(0, params.maxCharacters)
        assertEquals(500L, params.pollIntervalMs)
        assertEquals(10_000L, params.pollTimeoutMs)
    }
}
