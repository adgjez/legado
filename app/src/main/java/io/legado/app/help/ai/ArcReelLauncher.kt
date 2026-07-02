package io.legado.app.help.ai

import android.content.Context
import android.content.Intent
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.ui.main.ai.arcreel.ArcReelActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * ArcReel 集成入口 — 从阅读页/AI聊天页启动ArcReel
 *
 * 根据环境安装状态，自动路由到：
 * 1. 首次使用 → ArcReelActivity (Setup 模式)
 * 2. 已安装 → ArcReelWebViewActivity (WebView 模式)
 */
object ArcReelLauncher {

    fun launch(context: Context, book: Book, content: String) {
        val intent = Intent(context, ArcReelActivity::class.java).apply {
            putExtra("bookName", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
            putExtra("content", content)
        }
        context.startActivity(intent)
    }

    fun launchWithChapters(
        context: Context,
        book: Book,
        chapters: List<Pair<BookChapter, String>>
    ) {
        val allContent = chapters.joinToString("\n\n") { (_, content) -> content }
        val chapterContents = chapters.mapIndexed { index, (chapter, content) ->
            Pair(chapter.index, content)
        }
        val intent = Intent(context, ArcReelActivity::class.java).apply {
            putExtra("bookName", book.name)
            putExtra("author", book.author)
            putExtra("bookUrl", book.bookUrl)
            putExtra("content", allContent)
            putExtra("chapterContentsJson", encodeChapterContents(chapterContents))
        }
        context.startActivity(intent)
    }

    private fun encodeChapterContents(chapters: List<Pair<Int, String>>): String {
        return JSONArray().apply {
            chapters.forEach { (index, content) ->
                put(JSONObject().apply {
                    put("index", index)
                    put("content", content)
                })
            }
        }.toString()
    }
}