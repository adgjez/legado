package io.legado.app

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
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
}
