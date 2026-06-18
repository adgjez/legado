package io.legado.app.ui.book.toc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppManagementIconAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementMoreActionButton
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TocPage {
    Chapters,
    Bookmarks
}

@Composable
fun TocComposeScreen(
    bookUrl: String,
    cacheRefreshTick: Int,
    viewModel: TocViewModel,
    onBack: () -> Unit,
    onBookLoaded: (Book) -> Unit,
    onOpenChapter: (Book, List<BookChapter>, BookChapter) -> Unit,
    onEditBookmark: (Bookmark, Int) -> Unit,
    onShowTocRule: (Book?) -> Unit,
    onUpdateToc: (Book) -> Unit,
    onExportBookmark: () -> Unit,
    onExportBookmarkMd: () -> Unit,
    onShowLog: () -> Unit
) {
    LegadoComposeTheme {
        val context = LocalContext.current
        val palette = rememberAppManagementPalette()
        var selectedPage by remember { mutableStateOf(TocPage.Chapters) }
        var searchQuery by remember { mutableStateOf("") }
        var refreshTick by remember { mutableIntStateOf(0) }
        val chapterList = remember { mutableStateListOf<BookChapter>() }
        val visibleChapters = remember { mutableStateListOf<BookChapter>() }
        val bookmarks = remember { mutableStateListOf<Bookmark>() }
        val displayTitles = remember { mutableStateMapOf<String, String>() }
        val cacheFileNames = remember { mutableStateListOf<String>() }
        val collapsedVolumeIndexes = remember { linkedSetOf<Int>() }
        val chapterListState = rememberLazyListState()
        val bookmarkListState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val exportText = stringResource(R.string.export)
        val exportMdText = stringResource(R.string.export_md)
        val logText = stringResource(R.string.log)
        val chapterActionLabels = TocChapterActionLabels(
            txtTocRule = stringResource(R.string.txt_toc_rule),
            splitLongChapter = stringResource(R.string.split_long_chapter),
            reverseToc = stringResource(R.string.reverse_toc),
            useReplace = stringResource(R.string.use_replace),
            loadWordCount = stringResource(R.string.load_word_count),
            log = logText
        )

        val book by produceState<Book?>(initialValue = null, bookUrl, refreshTick) {
            value = withContext(Dispatchers.IO) {
                bookUrl.takeIf { it.isNotBlank() }?.let { appDb.bookDao.getBook(it) }
            }
        }

        LaunchedEffect(book) {
            book?.let(onBookLoaded)
        }

        LaunchedEffect(book, searchQuery, refreshTick) {
            val currentBook = book ?: return@LaunchedEffect
            val end = currentBook.simulatedTotalChapterNum() - 1
            val chapters = withContext(Dispatchers.IO) {
                if (searchQuery.isBlank()) {
                    appDb.bookChapterDao.getChapterList(currentBook.bookUrl, 0, end)
                } else {
                    appDb.bookChapterDao.search(currentBook.bookUrl, searchQuery, 0, end)
                }
            }
            if (searchQuery.isBlank()) {
                chapterList.clear()
                chapterList.addAll(chapters)
                resetCollapsedVolumes(
                    chapters = chapters,
                    durChapterIndex = currentBook.durChapterIndex,
                    collapsedVolumeIndexes = collapsedVolumeIndexes
                )
            }
            displayTitles.clear()
            displayTitles.putAll(
                withContext(Dispatchers.Default) {
                    buildChapterDisplayTitles(currentBook, chapters)
                }
            )
            visibleChapters.clear()
            visibleChapters.addAll(visibleChapters(chapters, searchQuery, collapsedVolumeIndexes))
            if (selectedPage == TocPage.Chapters && visibleChapters.isNotEmpty()) {
                val pos = visiblePositionOf(visibleChapters, currentBook.durChapterIndex)
                chapterListState.scrollToItem(pos.coerceIn(0, visibleChapters.lastIndex))
            }
        }

        LaunchedEffect(book, cacheRefreshTick) {
            val currentBook = book ?: return@LaunchedEffect
            cacheFileNames.clear()
            cacheFileNames.addAll(withContext(Dispatchers.IO) { BookHelp.getChapterFiles(currentBook) })
        }

        LaunchedEffect(book, searchQuery, selectedPage, refreshTick) {
            val currentBook = book ?: return@LaunchedEffect
            if (selectedPage != TocPage.Bookmarks) return@LaunchedEffect
            val result = withContext(Dispatchers.IO) {
                if (searchQuery.isBlank()) {
                    appDb.bookmarkDao.getByBook(currentBook.name, currentBook.author)
                } else {
                    appDb.bookmarkDao.search(currentBook.name, currentBook.author, searchQuery)
                }
            }
            bookmarks.clear()
            bookmarks.addAll(result)
            val pos = bookmarks.indexOfLast { it.chapterIndex < currentBook.durChapterIndex }
                .coerceAtLeast(0)
            if (bookmarks.isNotEmpty()) {
                bookmarkListState.scrollToItem(pos.coerceIn(0, bookmarks.lastIndex))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        ) {
            TocTopBar(
                book = book,
                selectedPage = selectedPage,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onBack = onBack,
                onSelectPage = { selectedPage = it },
                chapterActions = {
                    buildChapterActions(
                        book = book,
                        labels = chapterActionLabels,
                        onShowTocRule = { onShowTocRule(book) },
                        onReverseToc = {
                            viewModel.reverseToc {
                                refreshTick++
                                (context as? android.app.Activity)?.setResult(
                                    android.app.Activity.RESULT_OK,
                                    android.content.Intent()
                                        .putExtra("index", it.durChapterIndex)
                                        .putExtra("chapterPos", 0)
                                )
                                scope.launch {
                                    chapterListState.scrollToItem(
                                        visiblePositionOf(visibleChapters, it.durChapterIndex)
                                    )
                                }
                            }
                        },
                        onToggleUseReplace = { refreshTick++ },
                        onToggleWordCount = { refreshTick++ },
                        onToggleSplitLongChapter = { targetBook ->
                            targetBook.setSplitLongChapter(!targetBook.getSplitLongChapter())
                            onUpdateToc(targetBook)
                            refreshTick++
                        },
                        onShowLog = onShowLog
                    )
                },
                bookmarkActions = {
                    listOf(
                        AppManagementMenuAction(text = exportText, onClick = onExportBookmark),
                        AppManagementMenuAction(text = exportMdText, onClick = onExportBookmarkMd),
                        AppManagementMenuAction(text = logText, onClick = onShowLog)
                    )
                }
            )

            Box(modifier = Modifier.weight(1f)) {
                when (selectedPage) {
                    TocPage.Chapters -> TocChapterList(
                        book = book,
                        chapters = visibleChapters,
                        displayTitles = displayTitles,
                        collapsedVolumeIndexes = collapsedVolumeIndexes,
                        cacheFileNames = cacheFileNames,
                        listState = chapterListState,
                        onToggleVolume = { chapter ->
                            if (!chapter.isVolume || searchQuery.isNotBlank()) return@TocChapterList
                            if (!collapsedVolumeIndexes.add(chapter.index)) {
                                collapsedVolumeIndexes.remove(chapter.index)
                            }
                            val updated = visibleChapters(chapterList, searchQuery, collapsedVolumeIndexes)
                            visibleChapters.clear()
                            visibleChapters.addAll(updated)
                        },
                        onOpenChapter = { chapter ->
                            book?.let { onOpenChapter(it, chapterList, chapter) }
                        }
                    )

                    TocPage.Bookmarks -> TocBookmarkList(
                        bookmarks = bookmarks,
                        listState = bookmarkListState,
                        onClick = { bookmark ->
                            (context as? android.app.Activity)?.run {
                                setResult(
                                    android.app.Activity.RESULT_OK,
                                    android.content.Intent()
                                        .putExtra("index", bookmark.chapterIndex)
                                        .putExtra("chapterPos", bookmark.chapterPos)
                                )
                                finish()
                            }
                        },
                        onLongClick = onEditBookmark
                    )
                }
            }

            if (selectedPage == TocPage.Chapters) {
                TocBottomBar(
                    book = book,
                    onCurrentClick = {
                        book?.let {
                            scope.launch {
                                chapterListState.scrollToItem(
                                    visiblePositionOf(visibleChapters, it.durChapterIndex)
                                )
                            }
                        }
                    },
                    onTopClick = {
                        scope.launch { chapterListState.scrollToItem(0) }
                    },
                    onBottomClick = {
                        scope.launch {
                            if (visibleChapters.isNotEmpty()) {
                                chapterListState.scrollToItem(visibleChapters.lastIndex)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TocTopBar(
    book: Book?,
    selectedPage: TocPage,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSelectPage: (TocPage) -> Unit,
    chapterActions: @Composable () -> List<AppManagementMenuAction>,
    bookmarkActions: @Composable () -> List<AppManagementMenuAction>
) {
    val palette = rememberAppManagementPalette()
    val moreActions = if (selectedPage == TocPage.Bookmarks) {
        bookmarkActions()
    } else {
        chapterActions()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppManagementIconAction(
                iconRes = R.drawable.ic_arrow_back,
                contentDescription = null,
                tint = palette.settings.primaryText,
                onClick = onBack
            )
            Text(
                text = book?.name ?: stringResource(R.string.chapter_list),
                color = palette.settings.primaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = palette.settings.titleFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            AppManagementMoreActionButton(
                actionsProvider = { moreActions },
                palette = palette,
                contentDescription = stringResource(R.string.more_menu)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        TocSearchField(
            query = searchQuery,
            onQueryChange = onSearchQueryChange
        )
        Spacer(modifier = Modifier.height(8.dp))
        TocTabs(
            selectedPage = selectedPage,
            onSelectPage = onSelectPage
        )
    }
}

@Composable
private fun TocSearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    val palette = rememberAppManagementPalette()
    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = Color(palette.settings.row),
        contentColor = palette.settings.primaryText,
        cornerRadius = palette.miuix.actionRadius ?: 12.dp,
        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = R.drawable.ic_search),
                contentDescription = null,
                tint = palette.settings.secondaryText,
                modifier = Modifier.size(18.dp)
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = palette.settings.primaryText,
                    fontSize = 14.sp,
                    fontFamily = palette.settings.bodyFontFamily
                ),
                cursorBrush = SolidColor(palette.settings.accent),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                decorationBox = { inner ->
                    if (query.isBlank()) {
                        Text(
                            text = stringResource(R.string.search),
                            color = palette.settings.secondaryText,
                            fontSize = 14.sp,
                            fontFamily = palette.settings.bodyFontFamily
                        )
                    }
                    inner()
                }
            )
        }
    }
}

@Composable
private fun TocTabs(
    selectedPage: TocPage,
    onSelectPage: (TocPage) -> Unit
) {
    val palette = rememberAppManagementPalette()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TocTabButton(
            text = stringResource(R.string.chapter_list),
            selected = selectedPage == TocPage.Chapters,
            onClick = { onSelectPage(TocPage.Chapters) },
            modifier = Modifier.weight(1f)
        )
        TocTabButton(
            text = stringResource(R.string.bookmark),
            selected = selectedPage == TocPage.Bookmarks,
            onClick = { onSelectPage(TocPage.Bookmarks) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TocTabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberAppManagementPalette()
    val shape = RoundedCornerShape(palette.miuix.actionRadius ?: 12.dp)
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(if (selected) palette.settings.accent.copy(alpha = 0.18f) else Color(palette.settings.row))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) palette.settings.accent else palette.settings.primaryText,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = palette.settings.bodyFontFamily,
            maxLines = 1
        )
    }
}

@Composable
private fun TocChapterList(
    book: Book?,
    chapters: List<BookChapter>,
    displayTitles: Map<String, String>,
    collapsedVolumeIndexes: Set<Int>,
    cacheFileNames: List<String>,
    listState: LazyListState,
    onToggleVolume: (BookChapter) -> Unit,
    onOpenChapter: (BookChapter) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        itemsIndexed(
            items = chapters,
            key = { _, chapter -> "${chapter.bookUrl}\u0000${chapter.index}\u0000${chapter.url}" },
            contentType = { _, chapter -> if (chapter.isVolume) "volume" else "chapter" }
        ) { _, chapter ->
            TocChapterRow(
                book = book,
                chapter = chapter,
                title = displayTitles[chapter.primaryStr()] ?: chapter.title,
                collapsed = collapsedVolumeIndexes.contains(chapter.index),
                cached = isChapterCached(book, chapter, cacheFileNames),
                onClick = {
                    if (chapter.isVolume) {
                        onToggleVolume(chapter)
                    } else {
                        onOpenChapter(chapter)
                    }
                }
            )
        }
    }
}

@Composable
private fun TocChapterRow(
    book: Book?,
    chapter: BookChapter,
    title: String,
    collapsed: Boolean,
    cached: Boolean,
    onClick: () -> Unit
) {
    val palette = rememberAppManagementPalette()
    val isCurrent = book?.durChapterIndex == chapter.index
    AppManagementCard(
        palette = palette,
        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        drawPanelImage = true,
        onClick = onClick
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (chapter.isVip && !chapter.isPay) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_lock_outline),
                    contentDescription = null,
                    tint = palette.settings.secondaryText,
                    modifier = Modifier
                        .size(20.dp)
                        .padding(end = 4.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = when {
                        isCurrent -> palette.settings.accent
                        chapter.isVolume -> palette.settings.primaryText
                        else -> palette.settings.primaryText
                    },
                    fontSize = if (chapter.isVolume) 15.sp else 14.sp,
                    fontWeight = if (chapter.isVolume || isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                    fontFamily = palette.settings.bodyFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val meta = listOfNotNull(
                    chapter.tag?.takeIf { it.isNotBlank() },
                    chapter.wordCount?.takeIf { AppConfig.tocCountWords && it.isNotBlank() && !chapter.isVolume }
                ).joinToString("  ")
                if (meta.isNotBlank()) {
                    Text(
                        text = meta,
                        color = palette.settings.secondaryText,
                        fontSize = 12.sp,
                        fontFamily = palette.settings.bodyFontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
            val iconRes = when {
                chapter.isVolume -> if (collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less
                isCurrent -> R.drawable.ic_check
                !cached -> R.drawable.ic_outline_cloud_24
                else -> null
            }
            iconRes?.let {
                Icon(
                    painter = painterResource(id = it),
                    contentDescription = null,
                    tint = if (isCurrent) palette.settings.accent else palette.settings.secondaryText,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun TocBookmarkList(
    bookmarks: List<Bookmark>,
    listState: LazyListState,
    onClick: (Bookmark) -> Unit,
    onLongClick: (Bookmark, Int) -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        itemsIndexed(
            items = bookmarks,
            key = { _, bookmark -> bookmark.time }
        ) { index, bookmark ->
            TocBookmarkRow(
                bookmark = bookmark,
                onClick = { onClick(bookmark) },
                onLongClick = { onLongClick(bookmark, index) }
            )
        }
    }
}

@Composable
private fun TocBookmarkRow(
    bookmark: Bookmark,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val palette = rememberAppManagementPalette()
    AppManagementCard(
        palette = palette,
        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        drawPanelImage = true,
        onClick = onClick,
        onLongClick = onLongClick
    ) {
        Text(
            text = bookmark.chapterName,
            color = palette.settings.primaryText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.settings.bodyFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (bookmark.bookText.isNotBlank()) {
            Text(
                text = bookmark.bookText,
                color = palette.settings.primaryText,
                fontSize = 13.sp,
                fontFamily = palette.settings.bodyFontFamily,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (bookmark.content.isNotBlank()) {
            Text(
                text = bookmark.content,
                color = palette.settings.secondaryText,
                fontSize = 12.sp,
                fontFamily = palette.settings.bodyFontFamily,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun TocBottomBar(
    book: Book?,
    onCurrentClick: () -> Unit,
    onTopClick: () -> Unit,
    onBottomClick: () -> Unit
) {
    val palette = rememberAppManagementPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppManagementCard(
            palette = palette,
            modifier = Modifier.weight(1f),
            insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            drawPanelImage = true,
            onClick = onCurrentClick
        ) {
            Text(
                text = book?.let { "${it.durChapterTitle}(${it.durChapterIndex + 1}/${it.simulatedTotalChapterNum()})" }
                    ?: stringResource(R.string.chapter_list),
                color = palette.settings.primaryText,
                fontSize = 12.sp,
                fontFamily = palette.settings.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
        }
        IconButton(onClick = onTopClick) {
            Icon(
                painter = painterResource(id = R.drawable.ic_arrow_drop_up),
                contentDescription = stringResource(R.string.go_to_top),
                tint = palette.settings.primaryText
            )
        }
        IconButton(onClick = onBottomClick) {
            Icon(
                painter = painterResource(id = R.drawable.ic_arrow_drop_down),
                contentDescription = stringResource(R.string.go_to_bottom),
                tint = palette.settings.primaryText
            )
        }
    }
}

@Composable
private fun buildChapterActions(
    book: Book?,
    labels: TocChapterActionLabels,
    onShowTocRule: () -> Unit,
    onReverseToc: () -> Unit,
    onToggleUseReplace: () -> Unit,
    onToggleWordCount: () -> Unit,
    onToggleSplitLongChapter: (Book) -> Unit,
    onShowLog: () -> Unit
): List<AppManagementMenuAction> {
    val actions = arrayListOf<AppManagementMenuAction>()
    if (book?.isLocalTxt == true) {
        actions.add(AppManagementMenuAction(text = labels.txtTocRule, onClick = onShowTocRule))
        actions.add(
            AppManagementMenuAction(
                text = labels.splitLongChapter,
                checked = book.getSplitLongChapter(),
                onClick = { onToggleSplitLongChapter(book) }
            )
        )
    }
    actions.add(AppManagementMenuAction(text = labels.reverseToc, onClick = onReverseToc))
    actions.add(
        AppManagementMenuAction(
            text = labels.useReplace,
            checked = AppConfig.tocUiUseReplace,
            onClick = {
                AppConfig.tocUiUseReplace = !AppConfig.tocUiUseReplace
                onToggleUseReplace()
            }
        )
    )
    actions.add(
        AppManagementMenuAction(
            text = labels.loadWordCount,
            checked = AppConfig.tocCountWords,
            onClick = {
                AppConfig.tocCountWords = !AppConfig.tocCountWords
                onToggleWordCount()
            }
        )
    )
    actions.add(AppManagementMenuAction(text = labels.log, onClick = onShowLog))
    return actions
}

private data class TocChapterActionLabels(
    val txtTocRule: String,
    val splitLongChapter: String,
    val reverseToc: String,
    val useReplace: String,
    val loadWordCount: String,
    val log: String
)

private fun resetCollapsedVolumes(
    chapters: List<BookChapter>,
    durChapterIndex: Int,
    collapsedVolumeIndexes: MutableSet<Int>
) {
    collapsedVolumeIndexes.clear()
    val currentVolumeIndex = chapters
        .filter { it.isVolume && it.index <= durChapterIndex }
        .maxByOrNull { it.index }
        ?.index
    chapters.filter { it.isVolume }.forEach { chapter ->
        if (chapter.index != currentVolumeIndex) {
            collapsedVolumeIndexes.add(chapter.index)
        }
    }
}

private fun visibleChapters(
    chapters: List<BookChapter>,
    searchKey: String,
    collapsedVolumeIndexes: Set<Int>
): List<BookChapter> {
    if (searchKey.isNotBlank() || collapsedVolumeIndexes.isEmpty()) return chapters
    val visible = arrayListOf<BookChapter>()
    var hideUntilNextVolume = false
    chapters.forEach { chapter ->
        if (chapter.isVolume) {
            visible.add(chapter)
            hideUntilNextVolume = collapsedVolumeIndexes.contains(chapter.index)
        } else if (!hideUntilNextVolume) {
            visible.add(chapter)
        }
    }
    return visible
}

private fun visiblePositionOf(chapters: List<BookChapter>, chapterIndex: Int): Int {
    val exact = chapters.indexOfFirst { it.index == chapterIndex }
    if (exact >= 0) return exact
    return chapters.indexOfLast { it.index < chapterIndex }.coerceAtLeast(0)
}

private fun buildChapterDisplayTitles(
    book: Book,
    chapters: List<BookChapter>
): Map<String, String> {
    val replaceRules = ContentProcessor.get(book.name, book.origin).getTitleReplaceRules()
    val replaceBook = book.toReplaceBook()
    val useReplace = AppConfig.tocUiUseReplace && book.getUseReplaceRule()
    return chapters.associate { chapter ->
        chapter.primaryStr() to chapter.getDisplayTitle(
            replaceRules = replaceRules,
            useReplace = useReplace,
            replaceBook = replaceBook
        )
    }
}

private fun isChapterCached(
    book: Book?,
    chapter: BookChapter,
    cacheFileNames: List<String>
): Boolean {
    if (book == null) return false
    return book.isLocal ||
            chapter.isVolume ||
            if (book.isAudio) {
                ExoPlayerHelper.isMediaCached(chapter.resourceUrl)
            } else {
                BookHelp.getChapterCacheFileNames(book, chapter).any(cacheFileNames::contains)
            }
}
