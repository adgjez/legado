package io.legado.app.ui.main.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AiVideoProviderConfig] 边界值与 URL 解析的纯函数测试。
 *
 * 不依赖 Android 运行时，仅验证 [AiVideoProviderConfig.validSubmitTimeout] /
 * [AiVideoProviderConfig.validPollTimeout] / [AiVideoProviderConfig.validPollInterval] /
 * [AiVideoProviderConfig.resolvePollUrl] / [AiVideoProviderConfig.displayName]。
 */
class AiVideoProviderConfigTest {

    @Test
    fun displayNameFallsBackToTypeWhenNameBlank() {
        val provider = AiVideoProviderConfig(name = "", type = AiVideoProviderConfig.TYPE_ARK)
        assertEquals(AiVideoProviderConfig.TYPE_ARK, provider.displayName())
    }

    @Test
    fun displayNameReturnsNameWhenPresent() {
        val provider = AiVideoProviderConfig(name = "Veo 3.1 Test", type = AiVideoProviderConfig.TYPE_ARK)
        assertEquals("Veo 3.1 Test", provider.displayName())
    }

    // ============================================================
    // validSubmitTimeout: coerceIn(10_000, 300_000)，0/null 兜底 60_000
    // ============================================================

    @Test
    fun validSubmitTimeoutFallsBackTo60sWhenZero() {
        val provider = AiVideoProviderConfig(name = "t", submitTimeoutMillisecond = 0L)
        assertEquals(60_000L, provider.validSubmitTimeout())
    }

    @Test
    fun validSubmitTimeoutFallsBackTo60sWhenNegative() {
        val provider = AiVideoProviderConfig(name = "t", submitTimeoutMillisecond = -5L)
        assertEquals(60_000L, provider.validSubmitTimeout())
    }

    @Test
    fun validSubmitTimeoutClampsLowerBoundTo10s() {
        val provider = AiVideoProviderConfig(name = "t", submitTimeoutMillisecond = 1L)
        assertEquals(10_000L, provider.validSubmitTimeout())
    }

    @Test
    fun validSubmitTimeoutClampsUpperBoundTo300s() {
        val provider = AiVideoProviderConfig(name = "t", submitTimeoutMillisecond = 600_000L)
        assertEquals(300_000L, provider.validSubmitTimeout())
    }

    @Test
    fun validSubmitTimeoutPassesThroughValidValue() {
        val provider = AiVideoProviderConfig(name = "t", submitTimeoutMillisecond = 120_000L)
        assertEquals(120_000L, provider.validSubmitTimeout())
    }

    // ============================================================
    // validPollTimeout: coerceIn(60_000, 1_800_000)，0 兜底 600_000
    // ============================================================

    @Test
    fun validPollTimeoutFallsBackTo600sWhenZero() {
        val provider = AiVideoProviderConfig(name = "t", pollTimeoutMillisecond = 0L)
        assertEquals(600_000L, provider.validPollTimeout())
    }

    @Test
    fun validPollTimeoutClampsLowerBoundTo60s() {
        val provider = AiVideoProviderConfig(name = "t", pollTimeoutMillisecond = 5_000L)
        assertEquals(60_000L, provider.validPollTimeout())
    }

    @Test
    fun validPollTimeoutClampsUpperBoundTo1800s() {
        val provider = AiVideoProviderConfig(name = "t", pollTimeoutMillisecond = 3_600_000L)
        assertEquals(1_800_000L, provider.validPollTimeout())
    }

    @Test
    fun validPollTimeoutPassesThroughValidValue() {
        val provider = AiVideoProviderConfig(name = "t", pollTimeoutMillisecond = 900_000L)
        assertEquals(900_000L, provider.validPollTimeout())
    }

    // ============================================================
    // validPollInterval: coerceIn(1_000, 30_000)，0 兜底 2_000
    // ============================================================

    @Test
    fun validPollIntervalFallsBackTo2sWhenZero() {
        val provider = AiVideoProviderConfig(name = "t", pollIntervalMillisecond = 0L)
        assertEquals(2_000L, provider.validPollInterval())
    }

    @Test
    fun validPollIntervalClampsLowerBoundTo1s() {
        val provider = AiVideoProviderConfig(name = "t", pollIntervalMillisecond = 100L)
        assertEquals(1_000L, provider.validPollInterval())
    }

    @Test
    fun validPollIntervalClampsUpperBoundTo30s() {
        val provider = AiVideoProviderConfig(name = "t", pollIntervalMillisecond = 60_000L)
        assertEquals(30_000L, provider.validPollInterval())
    }

    @Test
    fun validPollIntervalPassesThroughValidValue() {
        val provider = AiVideoProviderConfig(name = "t", pollIntervalMillisecond = 5_000L)
        assertEquals(5_000L, provider.validPollInterval())
    }

    // ============================================================
    // resolvePollUrl: {taskId} 占位符替换
    // ============================================================

    @Test
    fun resolvePollUrlReplacesTaskIdPlaceholder() {
        val provider = AiVideoProviderConfig(
            name = "t",
            pollUrlTemplate = "https://api.example.com/v1/tasks/{taskId}/status"
        )
        assertEquals(
            "https://api.example.com/v1/tasks/task_abc123/status",
            provider.resolvePollUrl("task_abc123")
        )
    }

    @Test
    fun resolvePollUrlReturnsTemplateWhenNoPlaceholder() {
        val provider = AiVideoProviderConfig(
            name = "t",
            pollUrlTemplate = "https://api.example.com/v1/status"
        )
        assertEquals(
            "https://api.example.com/v1/status",
            provider.resolvePollUrl("task_abc123")
        )
    }

    @Test
    fun resolvePollUrlReplacesAllOccurrencesOfPlaceholder() {
        val provider = AiVideoProviderConfig(
            name = "t",
            pollUrlTemplate = "https://api.example.com/{taskId}/tasks/{taskId}"
        )
        val resolved = provider.resolvePollUrl("abc")
        assertTrue(resolved == "https://api.example.com/abc/tasks/abc")
    }

    @Test
    fun resolvePollUrlHandlesTaskIdWithSpecialCharacters() {
        val provider = AiVideoProviderConfig(
            name = "t",
            pollUrlTemplate = "https://api.example.com/tasks/{taskId}"
        )
        // taskId 含路径段和数字，但不应被 URL 编码（resolvePollUrl 是纯字符串替换）
        assertEquals(
            "https://api.example.com/tasks/task-2026-07-05_001",
            provider.resolvePollUrl("task-2026-07-05_001")
        )
    }
}
