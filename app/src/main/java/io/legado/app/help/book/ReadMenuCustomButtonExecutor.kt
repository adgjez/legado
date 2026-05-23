package io.legado.app.help.book

import androidx.appcompat.app.AppCompatActivity
import io.legado.app.constant.BookType
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.ReadMenuCustomButton
import io.legado.app.ui.login.SourceLoginJsExtensions
import kotlinx.coroutines.withTimeout

object ReadMenuCustomButtonExecutor {

    suspend fun execute(
        activity: AppCompatActivity,
        button: ReadMenuCustomButton,
        book: Book,
        chapter: BookChapter,
        content: String,
        bookSource: BookSource?
    ): Any? {
        val source = ButtonSource(button)
        val java = SourceLoginJsExtensions(activity, source, BookType.text)
        val baseUrl = chapterRequestUrl(chapter)
        return withTimeout(button.validTimeout()) {
            source.evalJS(buildScript(button)) {
                put("java", java)
                put("book", book)
                put("chapter", chapter)
                put("content", content)
                put("src", content)
                put("result", content)
                put("title", chapter.title)
                put("baseUrl", baseUrl)
                put("bookSource", bookSource)
                put("readSource", bookSource)
                put("button", button)
            }
        }
    }

    private fun buildScript(button: ReadMenuCustomButton): String {
        return buildString {
            append(button.script).append('\n')
            append(
                """
                ;(function(){
                    if (typeof click === 'function') return click();
                    if (typeof run === 'function') return run();
                    if (typeof main === 'function') return main();
                    if (typeof result !== 'undefined') return result;
                    return null;
                })();
                """.trimIndent()
            )
        }
    }

    private fun chapterRequestUrl(chapter: BookChapter): String {
        val absoluteUrl = runCatching { chapter.getAbsoluteURL() }.getOrNull().orEmpty()
        return when {
            absoluteUrl.startsWith("http://", true) || absoluteUrl.startsWith("https://", true) -> absoluteUrl
            chapter.url.startsWith("http://", true) || chapter.url.startsWith("https://", true) -> chapter.url
            chapter.baseUrl.startsWith("http://", true) || chapter.baseUrl.startsWith("https://", true) -> chapter.baseUrl
            else -> ""
        }
    }

    private class ButtonSource(
        private val button: ReadMenuCustomButton
    ) : BaseSource {
        override var concurrentRate: String? = null
        override var loginUrl: String? = button.loginUrl
        override var loginUi: String? = button.loginUi
        override var header: String? = null
        override var enabledCookieJar: Boolean? = button.enabledCookieJar
        override var jsLib: String? = button.jsLib

        override fun getTag(): String = "ReadMenuButton:${button.id}:${button.displayName()}"

        override fun getKey(): String = "read_menu_button_${button.id}"
    }
}
