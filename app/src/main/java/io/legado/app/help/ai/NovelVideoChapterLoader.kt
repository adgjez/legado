package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookHelp
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * 取章节正文 + 简单切块，参考 [AiChapterSummaryService] 的模式。
 *
 * - 优先用 [BookHelp.getContent] 读本地缓存；
 * - 缓存不存在则走 [WebBook.getContentAwait] 拉网络，**不落盘**（避免污染书籍缓存）；
 * - 网络失败重试 3 次，指数退避。
 */
object NovelVideoChapterLoader {

    private const val MAX_RETRY = 3
    private const val CHUNK_MAX_CHARS = 12_000

    suspend fun loadChapterText(book: Book, chapter: BookChapter): String {
        BookHelp.getContent(book, chapter)?.takeIf { it.isNotBlank() }?.let { return it }
        return fetchWithRetry(book, chapter)
    }

    private suspend fun fetchWithRetry(book: Book, chapter: BookChapter): String {
        val source = appDb.bookSourceDao.getBookSource(book.origin)
            ?: throw IllegalStateException("找不到书源：${book.name}")
        var lastError: Throwable? = null
        repeat(MAX_RETRY) { attempt ->
            try {
                val content = WebBook.getContentAwait(
                    bookSource = source,
                    book = book,
                    bookChapter = chapter,
                    needSave = false
                )
                // 书源返回空内容（解析规则失效或章节本身为空）视为失败，触发重试
                // 避免空内容传给 LLM 生成垃圾剧本
                if (content.isBlank()) {
                    throw IllegalStateException("章节正文为空：${chapter.title}")
                }
                return content
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                if (attempt < MAX_RETRY - 1) {
                    delay(1_000L * (1 shl attempt)) // 1s/2s/4s
                }
            }
        }
        throw lastError ?: IllegalStateException("章节正文获取失败：${chapter.title}")
    }

    /**
     * 长章节切块（按段落）。返回 chunks；若总长不超 [CHUNK_MAX_CHARS] 则只返回一个 chunk。
     */
    fun splitByParagraph(content: String, maxChars: Int = CHUNK_MAX_CHARS): List<String> {
        if (content.length <= maxChars) return listOf(content)
        // 注意：split(String) 把 "\n+" 当字面量而非正则，多个连续换行不会被合并。
        // 用 Regex 切分：一个或多个换行、或中文句末标点
        val paragraphs = content.split(Regex("\n+|。|！|？")).map { it.trim() }.filter { it.isNotBlank() }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (para in paragraphs) {
            if (current.length + para.length + 1 > maxChars && current.isNotEmpty()) {
                chunks.add(current.toString())
                current.setLength(0)
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(para)
        }
        if (current.isNotEmpty()) chunks.add(current.toString())
        return chunks
    }
}
