package io.legado.app

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.library.LibraryCloudContent
import io.legado.app.help.book.library.LibraryCloudKeys
import io.legado.app.help.book.library.LibraryCloudPaths
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryCloudKeysTest {

    @Test
    fun relaxedTitleMatchesArabicAndChineseChapterNumbers() {
        assertEquals(
            LibraryCloudKeys.relaxedTitle("第1章 宝库。"),
            LibraryCloudKeys.relaxedTitle("第一章 宝 库")
        )
    }

    @Test
    fun relaxedTitleKeepsSemanticTitleText() {
        assertEquals("714人头树", LibraryCloudKeys.relaxedTitle("第七百一十四章 人头树"))
    }

    @Test
    fun sharedBookKeyIgnoresAuthorForCrossSourceReads() {
        val first = Book(name = "超神机械师", author = "齐佩甲")
        val second = Book(name = "超神机械师", author = "")
        assertEquals(LibraryCloudKeys.sharedBookKey(first), LibraryCloudKeys.sharedBookKey(second))
    }

    @Test
    fun variantPathIsStableWhenChapterUrlChanges() {
        val sourceKey = LibraryCloudKeys.sourceKey("https://example.com/source.json")
        val chapter = BookChapter(title = "第一章 宝库", url = "/a")
        val changedUrlChapter = chapter.copy(url = "/b")
        val matchKey = LibraryCloudKeys.variantMatchKeys(chapter).first()
        val changedMatchKey = LibraryCloudKeys.variantMatchKeys(changedUrlChapter).first()
        assertEquals(matchKey, changedMatchKey)
        assertEquals(
            LibraryCloudPaths.variantChapterPath("book", matchKey, sourceKey),
            LibraryCloudPaths.variantChapterPath("book", changedMatchKey, sourceKey)
        )
    }

    @Test
    fun relaxedTitleIgnoresInvisibleAndDecorativeChars() {
        val clean = BookChapter(title = "第31章 婚姻")
        val dirty = BookChapter(title = "\uFE0F 第\u200B31章\u00A0婚姻")
        assertEquals(LibraryCloudKeys.relaxedTitle(clean.title), LibraryCloudKeys.relaxedTitle(dirty.title))
        assertEquals(LibraryCloudKeys.relaxedTitleKey(clean), LibraryCloudKeys.relaxedTitleKey(dirty))
    }

    @Test
    fun ordinalTitleMatchesArabicAndChineseNumbers() {
        assertEquals("31", LibraryCloudKeys.chapterOrdinal("第31章 婚姻"))
        assertEquals("31", LibraryCloudKeys.chapterOrdinal("第三十一章 婚姻"))
        assertEquals(
            LibraryCloudKeys.ordinalTitleKey(BookChapter(title = "第031章 婚姻")),
            LibraryCloudKeys.ordinalTitleKey(BookChapter(title = "第三十一章 婚姻"))
        )
    }

    @Test
    fun uploadTextDropsHtmlAndParagraphCommentImages() {
        val html = """
            <p>正文<img src="dp:12,{&quot;status&quot;:&quot;normal&quot;}">一</p>
            <style>.x{color:red}</style>
            <p>二<br>三</p>
        """.trimIndent()
        assertEquals("正文一\n二\n三", LibraryCloudContent.toUploadText(html))
    }
}
