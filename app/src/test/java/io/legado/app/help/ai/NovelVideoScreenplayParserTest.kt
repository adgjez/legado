package io.legado.app.help.ai

import com.google.gson.JsonSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [NovelVideoScreenplayParser] 的清洗、JSON 提取、解析、校验测试。
 *
 * 使用 `src/test/resources/screenplay/` 下的样本文件模拟真实 LLM 响应：
 * - 纯 JSON（最理想情况）
 * - 包裹在 markdown ```json 围栏中的响应
 * - 含中文引号/冒号的响应
 */
class NovelVideoScreenplayParserTest {

    private fun loadResource(name: String): String {
        return this::class.java.getResourceAsStream(name)!!.bufferedReader().use { it.readText() }
    }

    // ============================================================
    // cleanResponse
    // ============================================================

    @Test
    fun cleanResponseStripsMarkdownJsonFence() {
        val raw = "```json\n{\"a\":1}\n```"
        val cleaned = NovelVideoScreenplayParser.cleanResponse(raw)
        assertEquals("{\"a\":1}", cleaned)
    }

    @Test
    fun cleanResponseStripsMarkdownPlainFence() {
        val raw = "```\n{\"a\":1}\n```"
        val cleaned = NovelVideoScreenplayParser.cleanResponse(raw)
        assertEquals("{\"a\":1}", cleaned)
    }

    @Test
    fun cleanResponseReplacesChineseDoubleQuotes() {
        val raw = "“key”:“value”"
        val cleaned = NovelVideoScreenplayParser.cleanResponse(raw)
        assertEquals("\"key\":\"value\"", cleaned)
    }

    @Test
    fun cleanResponseReplacesChineseSingleQuotes() {
        val raw = "‘key’"
        val cleaned = NovelVideoScreenplayParser.cleanResponse(raw)
        assertEquals("'key'", cleaned)
    }

    @Test
    fun cleanResponseReplacesChineseColons() {
        val raw = "key：value"
        val cleaned = NovelVideoScreenplayParser.cleanResponse(raw)
        assertEquals("key:value", cleaned)
    }

    @Test
    fun cleanResponseTrimsWhitespace() {
        val raw = "   \n  {\"a\":1}  \n  "
        val cleaned = NovelVideoScreenplayParser.cleanResponse(raw)
        assertEquals("{\"a\":1}", cleaned)
    }

    // ============================================================
    // extractJson
    // ============================================================

    @Test
    fun extractJsonReturnsNullWhenNoBracePresent() {
        val text = "no json here at all"
        assertNull(NovelVideoScreenplayParser.extractJson(text))
    }

    @Test
    fun extractJsonFindsTaskIdStrategy() {
        val text = "前缀文字 {\"task_id\":\"abc\",\"title\":\"t\",\"scenes\":[]} 后缀"
        val json = NovelVideoScreenplayParser.extractJson(text)
        assertNotNull(json)
        assertTrue(json!!.contains("\"task_id\":\"abc\""))
    }

    @Test
    fun extractJsonFallsBackToScenesStrategyWhenNoTaskId() {
        val text = "{\"title\":\"t\",\"scenes\":[{\"scene_id\":1}]}"
        val json = NovelVideoScreenplayParser.extractJson(text)
        assertNotNull(json)
        assertTrue(json!!.contains("\"scenes\""))
    }

    @Test
    fun extractJsonFallsBackToScriptTitleStrategy() {
        val text = "{\"script_title\":\"legacy\",\"scenes\":[{\"scene_id\":1}]}"
        val json = NovelVideoScreenplayParser.extractJson(text)
        assertNotNull(json)
        assertTrue(json!!.contains("\"script_title\""))
    }

    @Test
    fun extractJsonIgnoresBracesInsideStringValues() {
        val text = "{\"image_prompt\":\"a scene with {curly} braces\",\"scenes\":[]}"
        val json = NovelVideoScreenplayParser.extractJson(text)
        assertNotNull(json)
        // 提取的应该是完整的最外层对象
        assertTrue(json!!.startsWith("{") && json.endsWith("}"))
        assertTrue(json.contains("curly"))
    }

    @Test
    fun extractJsonHandlesEscapedQuotesInStrings() {
        val text = "{\"narration\":\"she said \\\"hi\\\"\",\"scenes\":[]}"
        val json = NovelVideoScreenplayParser.extractJson(text)
        assertNotNull(json)
        assertTrue(json!!.contains("\\\"hi\\\""))
    }

    // ============================================================
    // parse（端到端，使用样本文件）
    // ============================================================

    @Test
    fun parsePureJsonSampleSucceeds() {
        val raw = loadResource("/screenplay/sample_screenplay.json")
        val draft = NovelVideoScreenplayParser.parse(raw)
        assertEquals("nv_test_001", draft.taskId)
        assertEquals("校园清晨", draft.title)
        assertEquals("校园日常", draft.genre)
        assertEquals(35, draft.estimatedDurationSeconds)
        assertEquals(listOf("宁静", "期待", "温暖"), draft.emotionalArc)
        assertEquals(3, draft.scenes.size)
        assertEquals(1, draft.scenes[0].sceneId)
        assertTrue(draft.scenes[0].narration.contains("苏晓"))
        assertTrue(draft.scenes[0].imagePrompt.startsWith("anime style"))
        assertTrue(draft.scenes[2].characterDescription.isNotBlank())
    }

    @Test
    fun parseMarkdownWrappedSampleSucceeds() {
        val raw = loadResource("/screenplay/sample_llm_response_markdown.txt")
        val draft = NovelVideoScreenplayParser.parse(raw)
        assertEquals("nv_test_002", draft.taskId)
        assertEquals("图书馆的午后", draft.title)
        assertEquals(2, draft.scenes.size)
        assertEquals("校园治愈", draft.genre)
        assertEquals(25, draft.estimatedDurationSeconds)
        // 验证 markdown 围栏被剥离（解析成功即说明围栏已剥离）
        assertTrue(draft.scenes[1].narration.contains("梅"))
    }

    @Test
    fun parseChineseQuotesSampleSucceeds() {
        val raw = loadResource("/screenplay/sample_llm_response_chinese_quotes.txt")
        val draft = NovelVideoScreenplayParser.parse(raw)
        assertEquals("nv_test_003", draft.taskId)
        assertEquals("雨天相遇", draft.title)
        assertEquals(1, draft.scenes.size)
        assertEquals("青春甜系", draft.genre)
        assertTrue(draft.scenes[0].narration.contains("雨丝"))
    }

    @Test
    fun parseScreenplayBuildsScreenplayFromDraft() {
        val raw = loadResource("/screenplay/sample_screenplay.json")
        val screenplay = NovelVideoScreenplayParser.parseScreenplay(raw)
        assertEquals("nv_test_001", screenplay.taskId)
        assertEquals("校园清晨", screenplay.title)
        assertEquals(3, screenplay.scenes.size)
    }

    // ============================================================
    // parse 错误路径
    // ============================================================

    @Test
    fun parseThrowsWhenNoJsonFound() {
        try {
            NovelVideoScreenplayParser.parse("纯文字，没有任何 JSON 结构")
            fail("expected JsonSyntaxException")
        } catch (e: JsonSyntaxException) {
            assertTrue(e.message?.contains("未找到合法 JSON") == true)
        }
    }

    @Test
    fun parseThrowsWhenScenesIsEmpty() {
        val raw = """{"task_id":"x","title":"t","scenes":[]}"""
        try {
            NovelVideoScreenplayParser.parse(raw)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("剧本没有场景") == true)
        }
    }

    @Test
    fun parseThrowsWhenSceneMissingNarration() {
        val raw = """
            {"task_id":"x","title":"t","scenes":[
              {"scene_id":1,"image_prompt":"i","video_prompt":"v"}
            ]}
        """.trimIndent()
        try {
            NovelVideoScreenplayParser.parse(raw)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("缺少 narration") == true)
        }
    }

    @Test
    fun parseThrowsWhenSceneMissingImagePrompt() {
        val raw = """
            {"task_id":"x","title":"t","scenes":[
              {"scene_id":1,"narration":"n","video_prompt":"v"}
            ]}
        """.trimIndent()
        try {
            NovelVideoScreenplayParser.parse(raw)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("缺少 image_prompt") == true)
        }
    }

    @Test
    fun parseThrowsWhenSceneMissingVideoPrompt() {
        val raw = """
            {"task_id":"x","title":"t","scenes":[
              {"scene_id":1,"narration":"n","image_prompt":"i"}
            ]}
        """.trimIndent()
        try {
            NovelVideoScreenplayParser.parse(raw)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("缺少 video_prompt") == true)
        }
    }

    @Test
    fun parseThrowsWhenAllIdentifiersBlank() {
        val raw = """{"task_id":"","title":"","script_title":"","scenes":[{"scene_id":1,"narration":"n","image_prompt":"i","video_prompt":"v"}]}"""
        try {
            NovelVideoScreenplayParser.parse(raw)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("task_id/title/script_title") == true)
        }
    }

    @Test
    fun parseAcceptsBlankTaskIdWhenTitlePresent() {
        val raw = """{"task_id":"","title":"有标题","scenes":[{"scene_id":1,"narration":"n","image_prompt":"i","video_prompt":"v"}]}"""
        val draft = NovelVideoScreenplayParser.parse(raw)
        assertEquals("有标题", draft.title)
        assertEquals("", draft.taskId)
    }
}
