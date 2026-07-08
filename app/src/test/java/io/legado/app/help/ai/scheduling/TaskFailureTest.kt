package io.legado.app.help.ai.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TaskFailure] 单测（移植 ArcReel `task_failure.py` 行为）。
 *
 * - encode 无参 → `[code]`
 * - encode 有参 → `[code] {sorted-json}`
 * - encode 未知 code 抛 IllegalArgumentException（fail-fast）
 * - render 已知 code → 中文文案
 * - render 带参 code → 文案含参数
 * - render 未知格式 → 原样透传（不丢失历史/裸文本）
 * - render null/空 → 原样返回
 */
class TaskFailureTest {

    @Test
    fun encodeNoParamsReturnsBracketedCode() {
        assertEquals("[restart_lost_image]", TaskFailure.encodeFailure("restart_lost_image"))
        assertEquals("[resume_expired_detail]", TaskFailure.encodeFailure("resume_expired_detail"))
    }

    @Test
    fun encodeWithParamsReturnsSortedJsonTail() {
        val encoded = TaskFailure.encodeFailure(
            "resume_unsupported_provider",
            mapOf("provider" to "grok")
        )
        assertEquals("[resume_unsupported_provider] {\"provider\":\"grok\"}", encoded)
    }

    @Test
    fun encodeSortsKeysForDeterministicOutput() {
        val encoded = TaskFailure.encodeFailure(
            "resume_expired_detail",
            mapOf("zeta" to "1", "alpha" to "2")
        )
        // TreeMap 保证 key 字母序
        assertEquals("[resume_expired_detail] {\"alpha\":\"2\",\"zeta\":\"1\"}", encoded)
    }

    @Test
    fun encodeUnknownCodeThrowsForFailFast() {
        assertThrows(IllegalArgumentException::class.java) {
            TaskFailure.encodeFailure("nonexistent_code")
        }
    }

    @Test
    fun encodeEmptyParamsMapReturnsBareCode() {
        assertEquals(
            "[restart_lost_no_job_id]",
            TaskFailure.encodeFailure("restart_lost_no_job_id", emptyMap())
        )
    }

    @Test
    fun renderNullReturnsNull() {
        assertNull(TaskFailure.renderFailure(null))
    }

    @Test
    fun renderBlankReturnsBlank() {
        assertEquals("", TaskFailure.renderFailure(""))
    }

    @Test
    fun renderKnownCodeNoParamsReturnsLocalizedText() {
        val rendered = TaskFailure.renderFailure("[restart_lost_image]")
        assertTrue("应含「图像」关键词", rendered!!.contains("图像"))
    }

    @Test
    fun renderKnownCodeWithParamsReturnsLocalizedTextWithParam() {
        val encoded = TaskFailure.encodeFailure(
            "resume_unsupported_provider",
            mapOf("provider" to "grok")
        )
        val rendered = TaskFailure.renderFailure(encoded)
        assertTrue("应含 provider 名", rendered!!.contains("grok"))
    }

    @Test
    fun renderRawExceptionTextPassesThroughVerbatim() {
        val raw = "java.net.SocketTimeoutException: timeout"
        assertEquals(raw, TaskFailure.renderFailure(raw))
    }

    @Test
    fun renderLegacyNonJsonTailPassesThroughVerbatim() {
        // 历史 `[restart_lost] 中文` 格式（非 JSON 尾）不应匹配
        val legacy = "[restart_lost] 中文"
        assertEquals(legacy, TaskFailure.renderFailure(legacy))
    }

    @Test
    fun renderMalformedJsonTailPassesThroughVerbatim() {
        val malformed = "[resume_expired_detail] {not json}"
        assertEquals(malformed, TaskFailure.renderFailure(malformed))
    }

    @Test
    fun renderUnknownCodeInBracketsPassesThroughVerbatim() {
        val unknown = "[totally_unknown_code]"
        assertEquals(unknown, TaskFailure.renderFailure(unknown))
    }

    @Test
    fun knownCodesContainsAllRegisteredCodes() {
        val expected = setOf(
            "provider_unsupported_media",
            "restart_lost_image",
            "restart_lost_audio",
            "restart_lost_no_job_id",
            "restart_lost_resume_no_job_id",
            "resume_unsupported_provider",
            "resume_unsupported_capacity_zero",
            "resume_unsupported_detail",
            "resume_expired_detail"
        )
        assertEquals(expected, TaskFailure.knownCodes)
    }

    @Test
    fun roundTripEncodeAndRenderPreservesCode() {
        // encode → render 往返：render 出来不是原始 code，但能渲染成对应文案
        val encoded = TaskFailure.encodeFailure("resume_expired_detail")
        val rendered = TaskFailure.renderFailure(encoded)
        assertTrue(rendered!!.contains("过期"))
    }
}
