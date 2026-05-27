package io.legado.app.ui.main.compose.bookshelf

import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.isNotShelf
import io.legado.app.utils.cnCompare
import kotlin.math.max

internal fun bookshelfLayoutMode(configLayout: Int): BookshelfLayoutMode {
    return if (configLayout >= 2) {
        BookshelfLayoutMode.Grid(configLayout)
    } else {
        BookshelfLayoutMode.List
    }
}

internal fun visibleBookshelfGroups(
    groups: List<BookGroup>,
    allBooks: List<Book>,
): List<BookGroup> {
    val shelfBooks = allBooks.filterNot { it.isNotShelf }
    val userGroupMask = groups
        .asSequence()
        .map { it.groupId }
        .filter { it > 0 }
        .fold(0L) { acc, groupId -> acc or groupId }
    return groups
        .asSequence()
        .filter { it.show }
        .filter { group ->
            when (group.groupId) {
                BookGroup.IdAll -> true
                BookGroup.IdLocal -> shelfBooks.any { it.type and BookType.local > 0 }
                BookGroup.IdAudio -> shelfBooks.any { it.type and BookType.audio > 0 }
                BookGroup.IdVideo -> shelfBooks.any { it.type and BookType.video > 0 }
                BookGroup.IdError -> shelfBooks.any { it.type and BookType.updateError > 0 }
                BookGroup.IdNetNone -> shelfBooks.any {
                    it.type and BookType.audio == 0 &&
                        it.type and BookType.video == 0 &&
                        it.type and BookType.local == 0 &&
                        userGroupMask and it.group == 0L
                }
                BookGroup.IdLocalNone -> shelfBooks.any {
                    it.type and BookType.audio == 0 &&
                        it.type and BookType.video == 0 &&
                        it.type and BookType.local > 0 &&
                        userGroupMask and it.group == 0L
                }
                else -> group.groupId >= 0 && shelfBooks.any { it.group and group.groupId > 0 }
            }
        }
        .sortedBy { it.order }
        .toList()
}

internal fun sortBookshelfBooks(
    books: List<Book>,
    sort: Int,
): List<Book> {
    return when (sort) {
        1 -> books.sortedByDescending { it.latestChapterTime }
        2 -> books.sortedWith { o1, o2 -> o1.name.cnCompare(o2.name) }
        3 -> books.sortedBy { it.order }
        4 -> books.sortedByDescending { max(it.latestChapterTime, it.durChapterTime) }
        5 -> books.sortedWith { o1, o2 -> o1.author.cnCompare(o2.author) }
        else -> books.sortedByDescending { it.durChapterTime }
    }
}

internal fun BookGroup.displayName(): String {
    return groupName.ifBlank {
        when (groupId) {
            BookGroup.IdAll -> "All"
            BookGroup.IdLocal -> "Local"
            BookGroup.IdAudio -> "Audio"
            BookGroup.IdVideo -> "Video"
            BookGroup.IdError -> "Update error"
            BookGroup.IdNetNone -> "Net ungrouped"
            BookGroup.IdLocalNone -> "Local ungrouped"
            else -> "Group"
        }
    }
}

internal fun Book.unreadChapterCount(): Int {
    return runCatching { getUnreadChapterNum() }.getOrDefault(0)
}
