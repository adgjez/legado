package io.legado.app.ui.main.bookshelf.compose

import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.toTimeAgo

sealed interface BookshelfItemUi {
    val key: String
    val contentType: String
}

data class BookshelfFolderItemUi(
    val group: BookGroup
) : BookshelfItemUi {
    override val key: String = "folder:${group.groupId}"
    override val contentType: String = "folder"
}

data class BookshelfBookItemUi(
    val book: Book,
    val isUpdating: Boolean,
    val unreadCount: Int,
    val hasNewChapter: Boolean,
    val intro: String?,
    val tags: List<String>,
    val lastUpdateText: String?
) : BookshelfItemUi {
    override val key: String = "book:${book.bookUrl}"
    override val contentType: String = "book"
}

fun buildBookshelfItems(
    groups: List<BookGroup>,
    books: List<Book>,
    isRootGroup: Boolean,
    isUpdating: (String) -> Boolean
): List<BookshelfItemUi> {
    val bookItems = books.map { book ->
        BookshelfBookItemUi(
            book = book,
            isUpdating = !book.isLocal && isUpdating(book.bookUrl),
            unreadCount = book.getUnreadChapterNum(),
            hasNewChapter = book.lastCheckCount > 0,
            intro = book.cleanBookshelfIntro(),
            tags = (BookTagHelper.parse(book.customTag) + BookTagHelper.parse(book.kind))
                .distinctBy { it.lowercase() }
                .take(4),
            lastUpdateText = if (AppConfig.showLastUpdateTime && !book.isLocal) {
                book.latestChapterTime.toTimeAgo()
            } else {
                null
            }
        )
    }
    if (!isRootGroup) {
        return bookItems
    }
    return groups.map(::BookshelfFolderItemUi) + bookItems
}

fun updateBookshelfItemUpdating(
    items: List<BookshelfItemUi>,
    bookUrl: String,
    isUpdating: (String) -> Boolean
): List<BookshelfItemUi> {
    var changed = false
    val updatedItems = items.map { item ->
        if (item is BookshelfBookItemUi && item.book.bookUrl == bookUrl) {
            val nextUpdating = !item.book.isLocal && isUpdating(bookUrl)
            if (nextUpdating != item.isUpdating) {
                changed = true
                item.copy(isUpdating = nextUpdating)
            } else {
                item
            }
        } else {
            item
        }
    }
    return if (changed) updatedItems else items
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookshelfGridItem(
    item: BookshelfItemUi,
    modifier: Modifier = Modifier,
    fragment: Fragment? = null,
    lifecycle: Lifecycle? = null,
    onClick: (BookshelfItemUi) -> Unit,
    onLongClick: (BookshelfItemUi) -> Unit
) {
    val showBookName = AppConfig.showBookname
    val titleFontFamily = FontFamily(LocalContext.current.titleTypeface())
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onClick(item) },
                onLongClick = { onLongClick(item) }
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.TopEnd
        ) {
            BookshelfCover(
                item = item,
                modifier = Modifier.fillMaxWidth(),
                fragment = fragment,
                lifecycle = lifecycle
            )
            if (item is BookshelfBookItemUi) {
                BookshelfStatusBadge(item)
            }
            if (showBookName == 2) {
                BookshelfOverlayTitle(item)
            }
        }
        if (showBookName == 0) {
            Text(
                text = item.displayName,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                color = Color(LocalContext.current.accentColor).copy(alpha = 0.92f),
                fontSize = 12.sp,
                fontFamily = titleFontFamily,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BookshelfCover(
    item: BookshelfItemUi,
    modifier: Modifier,
    fragment: Fragment?,
    lifecycle: Lifecycle?
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            CoverImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        },
        update = { coverView ->
            when (item) {
                is BookshelfBookItemUi -> coverView.loadThumb(item.book, false, fragment, lifecycle)
                is BookshelfFolderItemUi -> coverView.load(
                    path = item.group.cover,
                    name = item.group.groupName,
                    loadOnlyWifi = false,
                    fragment = fragment,
                    lifecycle = lifecycle,
                    preferThumb = true
                )
            }
        }
    )
}

@Composable
private fun BookshelfStatusBadge(item: BookshelfBookItemUi) {
    if (item.isUpdating) {
        CircularProgressIndicator(
            modifier = Modifier
                .padding(5.dp)
                .size(20.dp),
            strokeWidth = 2.dp,
            color = Color(LocalContext.current.accentColor)
        )
        return
    }
    if (!AppConfig.showUnread || item.unreadCount <= 0) return
    val badgeColor = if (item.hasNewChapter) {
        Color(LocalContext.current.accentColor)
    } else {
        Color.Black.copy(alpha = 0.55f)
    }
    Text(
        text = item.unreadCount.coerceAtMost(999).toString(),
        modifier = Modifier
            .padding(5.dp)
            .clip(CircleShape)
            .background(badgeColor)
            .widthIn(min = 20.dp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        maxLines = 1
    )
}

@Composable
private fun BoxScope.BookshelfOverlayTitle(item: BookshelfItemUi) {
    val titleFontFamily = FontFamily(LocalContext.current.titleTypeface())
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f)),
        contentAlignment = Alignment.BottomStart
    ) {
        Text(
            text = item.displayName,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.32f))
                .padding(start = 5.dp, end = 5.dp, top = 8.dp, bottom = 5.dp),
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = titleFontFamily,
            maxLines = 2,
            minLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val BookshelfItemUi.displayName: String
    get() = when (this) {
        is BookshelfBookItemUi -> book.name
        is BookshelfFolderItemUi -> group.groupName
    }

private fun Book.cleanBookshelfIntro(): String? {
    val rawIntro = getDisplayIntro()?.trim().orEmpty()
    if (rawIntro.isBlank()) return null
    val normalized = rawIntro
        .replace(Regex("(?is)<br\\s*/?>"), "\n")
        .replace(Regex("(?is)</?(useweb|usehtml|md)>"), "\n")
        .replace(Regex("(?is)<script.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?</style>"), " ")
        .replace(Regex("(?is)<[^>]+>"), " ")
    val candidates = normalized
        .split(Regex("[\\r\\n]+"))
        .map { it.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotBlank() && !it.looksLikeRuleIntroPlaceholder() }
    return candidates
        .maxByOrNull { it.length }
        ?.take(180)
}

private fun String.looksLikeRuleIntroPlaceholder(): Boolean {
    val value = trim()
    return value.equals("useweb", ignoreCase = true) ||
        value.equals("usehtml", ignoreCase = true) ||
        value.equals("md", ignoreCase = true) ||
        value.length < 12 ||
        value.startsWith("@") ||
        value.startsWith("//") ||
        value.startsWith("http://", ignoreCase = true) ||
        value.startsWith("https://", ignoreCase = true) ||
        value.startsWith("{{") ||
        value.startsWith("function", ignoreCase = true) ||
        value.contains("@js:", ignoreCase = true) ||
        value.contains("document.", ignoreCase = true) ||
        value.contains("querySelector", ignoreCase = true) ||
        value.contains("{{") ||
        value.contains("}}")
}
