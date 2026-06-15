package io.legado.app.ui.main.bookshelf.style2

import android.annotation.SuppressLint
import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.view.isGone
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf2Binding
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoNavigator
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.ui.main.bookshelf.compose.BookshelfBookItemUi
import io.legado.app.ui.main.bookshelf.compose.BookshelfFolderItemUi
import io.legado.app.ui.main.bookshelf.compose.BookshelfGridItem
import io.legado.app.ui.main.bookshelf.compose.BookshelfItemUi
import io.legado.app.ui.main.bookshelf.compose.BookshelfListItem
import io.legado.app.ui.main.bookshelf.compose.buildBookshelfItems
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.main.bookshelf.compose.updateBookshelfItemUpdating
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.cnCompare
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.observeEvent
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 书架界面
 */
class BookshelfFragment2() : BaseBookshelfFragment(R.layout.fragment_bookshelf2),
    SearchView.OnQueryTextListener,
    BaseBooksAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf2Binding::bind)
    private var bookshelfLayout by mutableIntStateOf(AppConfig.bookshelfLayout)
    private val booksAdapter: BaseBooksAdapter<*> by lazy {
        BooksAdapterList(requireContext(), this)
    }
    private var bookGroups: List<BookGroup> = emptyList()
    private var booksFlowJob: Job? = null
    override var groupId = BookGroup.IdRoot
    override var books: List<Book> = emptyList()
    private var enableRefresh = true
    override var onlyUpdateRead = false
    private var bookshelfMargin by mutableIntStateOf(AppConfig.bookshelfMargin)
    private var itemCount = 0
    private var totalRows = 0
    private val useComposeGrid get() = bookshelfLayout >= 2
    private val useComposeList get() = bookshelfLayout < 2
    private val useComposeBookshelf get() = useComposeGrid || useComposeList
    private data class ComposeScrollPosition(val index: Int, val offset: Int)
    private val composeScrollPositions = mutableMapOf<Long, ComposeScrollPosition>()
    private var composeItems by mutableStateOf<List<BookshelfItemUi>>(emptyList())
    private var composeGroupId by mutableStateOf(BookGroup.IdRoot)
    private var composeDataVersion by mutableStateOf(0)
    private var composeCanScrollBackward by mutableStateOf(false)
    private var composePendingScrollRestoreGroupId by mutableStateOf<Long?>(null)
    private var composeScrollToTopTick by mutableStateOf(0)
    private var composeListItemStyle by mutableIntStateOf(AppConfig.bookshelfListItemStyle)

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        installModernBookshelfOverflow(binding.titleBar.toolbar)
        initRecyclerView()
        initComposeBookshelf()
        initBookGroupData()
        initBooksData()
    }

    private fun initRecyclerView() {
        binding.rvBookshelf.setEdgeEffectColor(primaryColor)
        binding.rvBookshelf.clipToPadding = false
        binding.rvBookshelf.applyMainBottomBarPadding()
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
        binding.refreshLayout.setOnChildScrollUpCallback { _, _ ->
            if (useComposeBookshelf) composeCanScrollBackward else binding.rvBookshelf.canScrollVertically(-1)
        }
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            activityViewModel.upToc(books, onlyUpdateRead)
        }
        binding.rvBookshelf.isGone = useComposeBookshelf
        binding.composeBookshelf.isGone = !useComposeBookshelf
        if (!useComposeBookshelf) {
            binding.rvBookshelf.layoutManager = LinearLayoutManager(context)
            binding.rvBookshelf.adapter = booksAdapter
        }
        if (!useComposeBookshelf) {
            /**
             * 采用 layoutManager?.onRestoreInstanceState(layoutState)
             * 恢复滚动位置
             * **/
            binding.rvBookshelf.itemAnimator =  null
            binding.rvBookshelf.addItemDecoration( object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                    outRect: Rect,
                    view: View,
                    parent: RecyclerView,
                    state: RecyclerView.State
                ) {
                    val position = parent.getChildAdapterPosition(view)
                    when (position) {
                        0 -> {
                            outRect.set(0, bookshelfMargin + 24, 0, bookshelfMargin)
                        }
                        itemCount - 1 -> {
                            outRect.set(0, bookshelfMargin, 0, bookshelfMargin)
                        }
                        else -> {
                            outRect.set(0, bookshelfMargin, 0, bookshelfMargin)
                        }
                    }
                }
            })
        }
    }

    private fun initComposeBookshelf() {
        if (!useComposeBookshelf) return
        binding.composeBookshelf.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeBookshelf.setContent {
            if (useComposeGrid) {
                BookshelfGridContent()
            } else {
                BookshelfListContent()
            }
        }
    }

    @Composable
    private fun BookshelfGridContent() {
        val gridState = rememberLazyGridState()
        val currentGroupId = composeGroupId
        val pendingScrollRestoreGroupId = composePendingScrollRestoreGroupId
        val canScrollBackward by remember {
            derivedStateOf { gridState.canScrollBackward }
        }
        val marginDp = with(LocalDensity.current) { bookshelfMargin.toDp() }
        val bottomBarPadding = with(LocalDensity.current) {
            resources.getDimensionPixelSize(R.dimen.main_content_bottom_bar_padding).toDp()
        }
        DisposableEffect(currentGroupId) {
            onDispose {
                composeScrollPositions[currentGroupId] = ComposeScrollPosition(
                    index = gridState.firstVisibleItemIndex,
                    offset = gridState.firstVisibleItemScrollOffset
                )
            }
        }
        LaunchedEffect(canScrollBackward) {
            composeCanScrollBackward = canScrollBackward
        }
        LaunchedEffect(currentGroupId, pendingScrollRestoreGroupId, composeDataVersion) {
            if (pendingScrollRestoreGroupId == currentGroupId && composeDataVersion > 0) {
                val scrollPosition = composeScrollPositions[currentGroupId]
                if (scrollPosition != null && composeItems.isNotEmpty()) {
                    val targetIndex = scrollPosition.index.coerceAtMost(composeItems.lastIndex)
                    val targetOffset = if (targetIndex == scrollPosition.index) {
                        scrollPosition.offset
                    } else {
                        0
                    }
                    gridState.scrollToItem(targetIndex, targetOffset)
                } else {
                    gridState.scrollToItem(0)
                }
                composePendingScrollRestoreGroupId = null
            }
        }
        LaunchedEffect(composeScrollToTopTick) {
            if (composeScrollToTopTick > 0) {
                if (AppConfig.isEInkMode) {
                    gridState.scrollToItem(0)
                } else {
                    gridState.animateScrollToItem(0)
                }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(bookshelfLayout),
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 8.dp,
                top = marginDp + 24.dp,
                end = 8.dp,
                bottom = marginDp + bottomBarPadding + 12.dp
            )
        ) {
            items(
                items = composeItems,
                key = { it.key },
                contentType = { it.contentType }
            ) { item ->
                BookshelfGridItem(
                    item = item,
                    modifier = Modifier,
                    fragment = this@BookshelfFragment2,
                    lifecycle = viewLifecycleOwner.lifecycle,
                    onClick = ::onComposeItemClick,
                    onLongClick = ::onComposeItemLongClick
                )
            }
        }
    }

    @Composable
    private fun BookshelfListContent() {
        val listState = rememberLazyListState()
        val currentGroupId = composeGroupId
        val pendingScrollRestoreGroupId = composePendingScrollRestoreGroupId
        val canScrollBackward by remember {
            derivedStateOf { listState.canScrollBackward }
        }
        val marginDp = with(LocalDensity.current) { bookshelfMargin.toDp() }
        val bottomBarPadding = with(LocalDensity.current) {
            resources.getDimensionPixelSize(R.dimen.main_content_bottom_bar_padding).toDp()
        }
        val renderConfig = rememberBookshelfListRenderConfig()
        DisposableEffect(currentGroupId) {
            onDispose {
                composeScrollPositions[currentGroupId] = ComposeScrollPosition(
                    index = listState.firstVisibleItemIndex,
                    offset = listState.firstVisibleItemScrollOffset
                )
            }
        }
        LaunchedEffect(canScrollBackward) {
            composeCanScrollBackward = canScrollBackward
        }
        LaunchedEffect(currentGroupId, pendingScrollRestoreGroupId, composeDataVersion) {
            if (pendingScrollRestoreGroupId == currentGroupId && composeDataVersion > 0) {
                val scrollPosition = composeScrollPositions[currentGroupId]
                if (scrollPosition != null && composeItems.isNotEmpty()) {
                    val targetIndex = scrollPosition.index.coerceAtMost(composeItems.lastIndex)
                    val targetOffset = if (targetIndex == scrollPosition.index) {
                        scrollPosition.offset
                    } else {
                        0
                    }
                    listState.scrollToItem(targetIndex, targetOffset)
                } else {
                    listState.scrollToItem(0)
                }
                composePendingScrollRestoreGroupId = null
            }
        }
        LaunchedEffect(composeScrollToTopTick) {
            if (composeScrollToTopTick > 0) {
                if (AppConfig.isEInkMode) {
                    listState.scrollToItem(0)
                } else {
                    listState.animateScrollToItem(0)
                }
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 8.dp,
                top = marginDp + 24.dp,
                end = 8.dp,
                bottom = marginDp + bottomBarPadding + 12.dp
            )
        ) {
            items(
                items = composeItems,
                key = { it.key },
                contentType = { it.contentType }
            ) { item ->
                BookshelfListItem(
                    item = item,
                    listLayout = bookshelfLayout,
                    cardStyle = composeListItemStyle,
                    renderConfig = renderConfig,
                    modifier = Modifier.padding(vertical = marginDp.coerceAtLeast(2.dp)),
                    fragment = this@BookshelfFragment2,
                    lifecycle = viewLifecycleOwner.lifecycle,
                    onClick = ::onComposeItemClick,
                    onLongClick = ::onComposeItemLongClick
                )
            }
        }
    }

    override fun upGroup(data: List<BookGroup>) {
        if (data != bookGroups) {
            bookGroups = data
            if (useComposeBookshelf) {
                updateComposeItems()
            } else {
                booksAdapter.updateItems(groupId)
            }
            itemCount = getItemCount()
            val spanCount = bookshelfLayout
            if (!useComposeBookshelf && spanCount >= 2) {
                totalRows = if (itemCount % spanCount == 0) itemCount / spanCount else itemCount / spanCount + 1
            }
            binding.tvEmptyMsg.isGone = itemCount > 0
            binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
        }
    }

    override fun upSort() {
        initBooksData()
    }

    private fun initBooksData() {
        if (groupId == BookGroup.IdRoot) {
            if (isAdded) {
                binding.titleBar.title = getString(R.string.bookshelf)
                binding.refreshLayout.isEnabled = true
                enableRefresh = true
                onlyUpdateRead = false
            }
        } else {
            bookGroups.firstOrNull {
                groupId == it.groupId
            }?.let {
                binding.titleBar.title = "${getString(R.string.bookshelf)}(${it.groupName})"
                binding.refreshLayout.isEnabled = it.enableRefresh
                enableRefresh = it.enableRefresh
                onlyUpdateRead = it.onlyUpdateRead
            }
        }
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowByGroup(groupId).map { list ->
                //排序
                when (AppConfig.getBookSortByGroupId(groupId)) {
                    1 -> list.sortedByDescending {
                        it.latestChapterTime
                    }

                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> list.sortedBy {
                        it.order
                    }

                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }

                    else -> list.sortedByDescending {
                        it.durChapterTime
                    }
                }
            }.flowWithLifecycleAndDatabaseChangeFirst(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { list ->
                books = list
                if (useComposeBookshelf) {
                    updateComposeItems()
                } else {
                    booksAdapter.updateItems(groupId)
                }
                itemCount = getItemCount()
                val spanCount = bookshelfLayout
                if (!useComposeBookshelf && spanCount >= 2) {
                    totalRows = if (itemCount % spanCount == 0) itemCount / spanCount else itemCount / spanCount + 1
                }
                binding.tvEmptyMsg.isGone = itemCount > 0
                binding.refreshLayout.isEnabled = enableRefresh && itemCount > 0
                delay(100)
            }
        }
    }

    fun back(): Boolean {
        if (groupId != BookGroup.IdRoot) {
            switchGroup(BookGroup.IdRoot)
            return true
        }
        return false
    }

    fun switchToGroupId(targetGroupId: Long) {
        switchGroup(targetGroupId)
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        SearchActivity.start(requireContext(), query)
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return false
    }

    override fun gotoTop() {
        if (useComposeBookshelf) {
            composeScrollToTopTick++
            return
        }
        if (AppConfig.isEInkMode) {
            binding.rvBookshelf.scrollToPosition(0)
        } else {
            binding.rvBookshelf.smoothScrollToPosition(0)
        }
    }

    override fun onItemClick(item: Any) {
        when (item) {
            is Book -> startActivityForBook(item)

            is BookGroup -> {
                switchGroup(item.groupId)
            }
        }
    }

    override fun onItemLongClick(item: Any) {
        when (item) {
            is Book -> BookInfoNavigator.open(requireContext(), item)

            is BookGroup -> showDialogFragment(GroupEditDialog(item))
        }
    }

    override fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    private fun updateComposeItems() {
        composeItems = buildBookshelfItems(
            groups = bookGroups,
            books = books,
            isRootGroup = groupId == BookGroup.IdRoot,
            groupId = groupId,
            isUpdating = ::isUpdate
        )
        composeDataVersion++
    }

    private fun onComposeItemClick(item: BookshelfItemUi) {
        when (item) {
            is BookshelfBookItemUi -> startActivityForBook(item.book)
            is BookshelfFolderItemUi -> {
                switchGroup(item.group.groupId)
            }
        }
    }

    private fun switchGroup(targetGroupId: Long) {
        if (groupId == targetGroupId) {
            return
        }
        groupId = targetGroupId
        if (useComposeBookshelf) {
            composeGroupId = targetGroupId
            composePendingScrollRestoreGroupId = targetGroupId
            composeItems = emptyList()
            composeDataVersion = 0
            composeCanScrollBackward = false
            binding.tvEmptyMsg.isGone = true
        }
        initBooksData()
    }

    private fun onComposeItemLongClick(item: BookshelfItemUi) {
        when (item) {
            is BookshelfBookItemUi -> BookInfoNavigator.open(requireContext(), item.book)
            is BookshelfFolderItemUi -> showDialogFragment(GroupEditDialog(item.group))
        }
    }

    fun getItemCount(): Int {
        return if (groupId == BookGroup.IdRoot) {
            bookGroups.size + books.size
        } else {
            books.size
        }
    }

    override fun getItems(): List<Any> {
        if (groupId != BookGroup.IdRoot) {
            return books
        }
        return bookGroups + books
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            if (useComposeBookshelf) {
                updateComposeItemUpdating(it)
            } else {
                booksAdapter.notification(it)
            }
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            if (useComposeBookshelf) {
                bookshelfMargin = AppConfig.bookshelfMargin
                composeListItemStyle = AppConfig.bookshelfListItemStyle
                updateComposeItems()
            } else {
                booksAdapter.notifyDataSetChanged()
            }
        }
        observeEvent<String>(EventBus.BOOKSHELF_STRUCTURE_CHANGED) {
            rebuildBookshelfContent()
        }
    }

    private fun rebuildBookshelfContent() {
        if (!isAdded) return
        val targetGroupId = groupId
        bookshelfLayout = AppConfig.bookshelfLayout.coerceIn(0, 6)
        bookshelfMargin = AppConfig.bookshelfMargin
        composeListItemStyle = AppConfig.bookshelfListItemStyle
        composeScrollPositions.clear()
        composeItems = emptyList()
        composeCanScrollBackward = false
        composePendingScrollRestoreGroupId = targetGroupId
        composeDataVersion = 0
        composeGroupId = targetGroupId
        binding.rvBookshelf.adapter = null
        binding.rvBookshelf.isGone = useComposeBookshelf
        binding.composeBookshelf.isGone = !useComposeBookshelf
        binding.tvEmptyMsg.isGone = true
        initBooksData()
    }

    private fun updateComposeItemUpdating(bookUrl: String) {
        composeItems = updateBookshelfItemUpdating(
            items = composeItems,
            bookUrl = bookUrl,
            isUpdating = ::isUpdate
        )
    }
}
