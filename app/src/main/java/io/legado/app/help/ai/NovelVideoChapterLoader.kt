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
                return WebBook.getContentAwait(
                    bookSource = source,
                    book = book,
                    bookChapter = chapter,
                    needSave = false
                )
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
        val paragraphs = content.split("\n+", "。", "！", "？").map { it.trim() }.filter { it.isNotBlank() }
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
