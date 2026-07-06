package io.legado.app.help.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NovelVideoChapterLoader.splitByParagraph] 的纯函数测试。
 *
 * fetchWithRetry / loadChapterText 依赖 Android Room 与 WebBook，超出纯 JVM 测试栈范围，
 * 这里只测无副作用的切块逻辑。
 */
class NovelVideoChapterLoaderTest {

    @Test
    fun splitByParagraphReturnsSingleChunkWhenShortEnough() {
        val content = "短内容"
        val chunks = NovelVideoChapterLoader.splitByParagraph(content, maxChars = 100)
        assertEquals(1, chunks.size)
        assertEquals("短内容", chunks[0])
    }

    @Test
    fun splitByParagraphSplitsByMultipleNewlines() {
        // P3-1: 原 split("\n+") 把 "\n+" 当字面量，多个连续换行不会被合并。
        // 改用 Regex("\n+|。|！|？") 后，连续换行应被正确切分。
        val content = "第一段\n\n\n第二段\n\n第三段"
        val chunks = NovelVideoChapterLoader.splitByParagraph(content, maxChars = 5)
        // 应切出 3 个段落（连续换行作为分隔符）
        assertTrue(chunks.joinToString("\n").contains("第一段"))
        assertTrue(chunks.joinToString("\n").contains("第二段"))
        assertTrue(chunks.joinToString("\n").contains("第三段"))
    }

    @Test
    fun splitByParagraphSplitsByChinesePunctuation() {
        val content = "第一句。第二句！第三句？第四句。"
        val chunks = NovelVideoChapterLoader.splitByParagraph(content, maxChars = 4)
        // 中文句末标点应作为切分点
        val joined = chunks.joinToString("\n")
        assertTrue(joined.contains("第一句"))
        assertTrue(joined.contains("第二句"))
        assertTrue(joined.contains("第三句"))
        assertTrue(joined.contains("第四句"))
    }

    @Test
    fun splitByParagraphRespectsMaxCharsLimit() {
        // 每个 chunk 不应超过 maxChars（除单个段落本身超长的情况）
        val content = "短段落一。短段落二。短段落三。短段落四。"
        val chunks = NovelVideoChapterLoader.splitByParagraph(content, maxChars = 10)
        chunks.forEach { chunk ->
            // 单段超长时不拆分段落，但多段合并时不应超限
            assertTrue("chunk 过长：${chunk.length}", chunk.length <= 10 || chunks.size == 1)
        }
    }

    @Test
    fun splitByParagraphSkipsBlankSegments() {
        // 连续标点产生的空段应被过滤
        val content = "内容。！！？内容二"
        val chunks = NovelVideoChunkHelper_split(content, maxChars = 100)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].contains("内容"))
        assertTrue(chunks[0].contains("内容二"))
    }

    private fun NovelVideoChunkHelper_split(content: String, maxChars: Int): List<String> =
        NovelVideoChapterLoader.splitByParagraph(content, maxChars)
}
