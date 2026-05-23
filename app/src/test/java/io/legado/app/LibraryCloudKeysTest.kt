package io.legado.app

import io.legado.app.help.book.library.LibraryCloudKeys
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
}
