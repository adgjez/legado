package io.legado.app.ui.main.bookshelf

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.BookType
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.dao.BookTagInfo
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.ActivityBookshelfTagManageBinding
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeDividerColorOrDefault
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.ui.widget.compose.ComposeMultiChoiceDialog
import io.legado.app.ui.widget.compose.ComposeTextInputDialog
import io.legado.app.utils.applyTint
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookshelfTagManageActivity : BaseActivity<ActivityBookshelfTagManageBinding>() {

    override val binding by viewBinding(ActivityBookshelfTagManageBinding::inflate)
    private val focusGroupId by lazy { intent.getLongExtra("groupId", BookGroup.IdAll) }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.tagContainer.applyNavigationBarPadding()
        loadTags()
    }

    private fun loadTags() {
        lifecycleScope.launch {
            val data = withContext(IO) {
                val books = appDb.bookDao.allTagInfos
                val groups = appDb.bookGroupDao.all
                    .filter { it.groupId != BookGroup.IdRoot }
                    .sortedWith(compareBy<BookGroup> { if (it.groupId == focusGroupId) 0 else 1 }
                        .thenBy { it.order })
                val userGroupMask = groups.asSequence()
                    .filter { it.groupId > 0 }
                    .fold(0L) { acc, group -> acc or group.groupId }
                groups.mapNotNull { group ->
                    val groupBooks = booksInGroup(group, books, userGroupMask)
                    val existingTags = groupBooks.flatMap { BookTagHelper.parse(it.customTag) }
                        .distinct()
                        .sorted()
                    val configuredTags = AppConfig.bookshelfGroupTags[group.groupId].orEmpty()
                    val tags = configuredTags.ifEmpty {
                        if (existingTags.isNotEmpty()) {
                            val map = AppConfig.bookshelfGroupTags.toMutableMap()
                            map[group.groupId] = existingTags
                            AppConfig.bookshelfGroupTags = map
                        }
                        existingTags
                    }
                    GroupTags(group, groupBooks, tags)
                }
            }
            render(data)
        }
    }

    private fun render(data: List<GroupTags>) = binding.tagContainer.run {
        removeAllViews()
        if (data.isEmpty()) {
            addView(TextView(this@BookshelfTagManageActivity).apply {
                text = getString(R.string.bookshelf_tag_none)
                setTextColor(secondaryTextColor)
                gravity = Gravity.CENTER
                setPadding(28.dpToPx())
            })
            return@run
        }
        data.forEach { groupTags ->
            addGroupCard(groupTags)
        }
    }

    private fun LinearLayout.addGroupCard(groupTags: GroupTags) {
        val hiddenMap = AppConfig.bookshelfHiddenTags
        val hiddenTags = hiddenMap[groupTags.group.groupId].orEmpty()
        val card = LinearLayout(this@BookshelfTagManageActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(14.dpToPx(), 12.dpToPx(), 14.dpToPx(), 8.dpToPx())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12.dpToPx()
            }
        }
        val header = LinearLayout(this@BookshelfTagManageActivity).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 8.dpToPx())
        }
        header.addView(TextView(this@BookshelfTagManageActivity).apply {
            text = "${groupTags.group.groupName} (${groupTags.books.size})"
            setTextColor(primaryTextColor)
            textSize = 17f
            includeFontPadding = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(TextView(this@BookshelfTagManageActivity).apply {
            text = getString(R.string.add)
            setTextColor(primaryTextColor)
            gravity = Gravity.CENTER
            setPadding(10.dpToPx(), 4.dpToPx(), 10.dpToPx(), 4.dpToPx())
            background = actionBackground()
            setOnClickListener { showAddTagDialog(groupTags.group.groupId) }
        })
        card.addView(header)
        if (groupTags.tags.isEmpty()) {
            card.addView(TextView(this@BookshelfTagManageActivity).apply {
                setText(R.string.bookshelf_tag_none)
                setTextColor(secondaryTextColor)
                setPadding(0, 8.dpToPx(), 0, 8.dpToPx())
            })
        }
        groupTags.tags.forEach { tag ->
            val row = LinearLayout(this@BookshelfTagManageActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                minimumHeight = 44.dpToPx()
            }
            val checkBox = CheckBox(this@BookshelfTagManageActivity).apply {
                text = "$tag (${groupTags.books.count { BookTagHelper.has(it.customTag, tag) }})"
                isChecked = tag !in hiddenTags
                setTextColor(primaryTextColor)
                applyTint(accentColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnCheckedChangeListener { _, isChecked ->
                    setTagVisible(groupTags.group.groupId, tag, isChecked)
                }
            }
            row.addView(checkBox)
            row.addView(TextView(this@BookshelfTagManageActivity).apply {
                text = getString(R.string.bookshelf_tag_edit)
                setTextColor(secondaryTextColor)
                gravity = Gravity.CENTER
                minWidth = 56.dpToPx()
                setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
                setOnClickListener { showAssignBooksDialog(groupTags, tag) }
            })
            row.addView(TextView(this@BookshelfTagManageActivity).apply {
                text = getString(R.string.delete)
                setTextColor(secondaryTextColor)
                gravity = Gravity.CENTER
                minWidth = 48.dpToPx()
                setPadding(8.dpToPx(), 0, 8.dpToPx(), 0)
                setOnClickListener { confirmDeleteTag(groupTags, tag) }
            })
            card.addView(row)
        }
        addView(card)
    }

    private fun showAddTagDialog(groupId: Long) {
        showDialogFragment(
            ComposeTextInputDialog.create(
                title = getString(R.string.bookshelf_tag_edit),
                hint = getString(R.string.bookshelf_tag_new_hint),
                positiveText = getString(android.R.string.ok),
                negativeText = getString(android.R.string.cancel),
                validateInput = { BookTagHelper.parse(it).isNotEmpty() },
                onPositive = {
                    val newTags = BookTagHelper.parse(it)
                    val map = AppConfig.bookshelfGroupTags.toMutableMap()
                    map[groupId] = (map[groupId].orEmpty() + newTags).distinct()
                    AppConfig.bookshelfGroupTags = map
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    loadTags()
                }
            )
        )
    }

    private fun showAssignBooksDialog(groupTags: GroupTags, tag: String) {
        if (groupTags.books.isEmpty()) return
        val checked = BooleanArray(groupTags.books.size) { index ->
            BookTagHelper.has(groupTags.books[index].customTag, tag)
        }
        val labels = groupTags.books.map { it.name }
        showDialogFragment(
            ComposeMultiChoiceDialog.create(
                title = "${groupTags.group.groupName} · $tag",
                labels = labels,
                checked = checked,
                positiveText = getString(android.R.string.ok),
                negativeText = getString(android.R.string.cancel),
                onPositive = { result ->
                    lifecycleScope.launch(IO) {
                        groupTags.books.forEachIndexed { index, book ->
                            val tags = BookTagHelper.parse(book.customTag).toMutableList()
                            val hasTag = tags.any { it.equals(tag, ignoreCase = true) }
                            when {
                                result[index] && !hasTag -> tags.add(tag)
                                !result[index] && hasTag -> tags.removeAll {
                                    it.equals(tag, ignoreCase = true)
                                }
                                else -> return@forEachIndexed
                            }
                            appDb.bookDao.updateCustomTag(book.bookUrl, BookTagHelper.join(tags))
                        }
                        postEvent(EventBus.BOOKSHELF_REFRESH, "")
                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                            loadTags()
                        }
                    }
                }
            )
        )
    }

    private fun setTagVisible(groupId: Long, tag: String, visible: Boolean) {
        val map = AppConfig.bookshelfHiddenTags.toMutableMap()
        val tags = map[groupId].orEmpty().toMutableSet()
        if (visible) {
            tags.remove(tag)
        } else {
            tags.add(tag)
        }
        if (tags.isEmpty()) {
            map.remove(groupId)
        } else {
            map[groupId] = tags
        }
        AppConfig.bookshelfHiddenTags = map
        postEvent(EventBus.BOOKSHELF_REFRESH, "")
    }

    private fun confirmDeleteTag(groupTags: GroupTags, tag: String) {
        alert(
            title = getString(R.string.bookshelf_tag_delete_title),
            message = getString(R.string.bookshelf_tag_delete_message, tag, groupTags.group.groupName)
        ) {
            okButton {
                lifecycleScope.launch(IO) {
                    groupTags.books.forEach { book ->
                        if (BookTagHelper.has(book.customTag, tag)) {
                            val normalizedTag = BookTagHelper.join(
                                BookTagHelper.parse(book.customTag)
                                    .filterNot { it.equals(tag, ignoreCase = true) }
                            )
                            appDb.bookDao.updateCustomTag(book.bookUrl, normalizedTag)
                        }
                    }
                    val map = AppConfig.bookshelfHiddenTags.toMutableMap()
                    map[groupTags.group.groupId]?.let {
                        map[groupTags.group.groupId] = it.filterNot { hidden ->
                            hidden.equals(tag, ignoreCase = true)
                        }.toSet()
                    }
                    AppConfig.bookshelfHiddenTags = map
                    val tagMap = AppConfig.bookshelfGroupTags.toMutableMap()
                    tagMap[groupTags.group.groupId] = tagMap[groupTags.group.groupId].orEmpty()
                        .filterNot { it.equals(tag, ignoreCase = true) }
                    AppConfig.bookshelfGroupTags = tagMap
                    postEvent(EventBus.BOOKSHELF_REFRESH, "")
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        loadTags()
                    }
                }
            }
            cancelButton()
        }
    }

    private fun cardBackground(): GradientDrawable {
        val fill = themeCardColorOrDefault()
        return GradientDrawable().apply {
            cornerRadius = UiCorner.panelRadius(this@BookshelfTagManageActivity)
            setColor(UiCorner.surfaceColor(fill))
            setStroke(
                1.dpToPx(),
                themeDividerColorOrDefault()
            )
        }
    }

    private fun actionBackground() = UiCorner.actionSelector(
        themeCardColorOrDefault(),
        themeMutedColorOrDefault(),
        UiCorner.actionRadius(this)
    )

    private fun booksInGroup(group: BookGroup, books: List<BookTagInfo>, userGroupMask: Long): List<BookTagInfo> {
        return when (group.groupId) {
            BookGroup.IdAll -> books
            BookGroup.IdLocal -> books.filter { it.type and BookType.local > 0 }
            BookGroup.IdAudio -> books.filter { it.type and BookType.audio > 0 }
            BookGroup.IdVideo -> books.filter { it.type and BookType.video > 0 }
            BookGroup.IdError -> books.filter { it.type and BookType.updateError > 0 }
            BookGroup.IdNetNone -> books.filter {
                it.type and BookType.audio == 0 &&
                    it.type and BookType.video == 0 &&
                    it.type and BookType.local == 0 &&
                    (it.group and userGroupMask) == 0L
            }
            BookGroup.IdLocalNone -> books.filter {
                it.type and BookType.audio == 0 &&
                    it.type and BookType.video == 0 &&
                    it.type and BookType.local > 0 &&
                    (it.group and userGroupMask) == 0L
            }
            else -> if (group.groupId > 0) {
                books.filter { it.group and group.groupId > 0 }
            } else {
                emptyList()
            }
        }
    }

    private data class GroupTags(
        val group: BookGroup,
        val books: List<BookTagInfo>,
        val tags: List<String>
    )
}
