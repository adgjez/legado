package io.legado.app.ui.book.character

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacter
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.ItemThemePackageBinding
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.model.ReadBook
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookCharacterManageActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)
    private val adapter = CharacterAdapter()
    private var bookUrl: String = ""
    private var book: Book? = null
    private var expandedId: Long = 0L

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra(EXTRA_BOOK_URL)
            ?: ReadBook.book?.bookUrl
            ?: ""
        book = appDb.bookDao.getBook(bookUrl)
        initView()
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_RELATIONS, 0, "关系网")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_RELATIONS -> {
                openRelations()
                true
            }
            else -> super.onCompatOptionsItemSelected(item)
        }
    }

    private fun initView() = binding.run {
        titleBar.title = "角色资料"
        titleBar.subtitle = book?.name.orEmpty()
        tabBar.visibility = View.GONE
        btnAdd.text = "添加角色"
        btnAdd.background = UiCorner.actionSelector(
            ContextCompat.getColor(this@BookCharacterManageActivity, R.color.background_card),
            ContextCompat.getColor(this@BookCharacterManageActivity, R.color.background_menu),
            UiCorner.actionRadius(this@BookCharacterManageActivity)
        )
        btnAdd.setOnClickListener { openEdit() }
        tvSummary.applyUiLabelStyle(this@BookCharacterManageActivity)
        tvSummary.setTextColor(secondaryTextColor)
        recyclerView.layoutManager = LinearLayoutManager(this@BookCharacterManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
    }

    private fun load() {
        if (bookUrl.isBlank()) {
            binding.tvSummary.text = "当前书籍不存在"
            adapter.items = emptyList()
            return
        }
        lifecycleScope.launch {
            val items = withContext(IO) {
                appDb.bookCharacterDao.characters(bookUrl)
            }
            binding.tvSummary.text = if (items.isEmpty()) {
                "还没有角色，点击底部按钮添加。"
            } else {
                "共 ${items.size} 个角色，点击卡片查看角色卡。"
            }
            adapter.items = items
        }
    }

    private fun openCard(id: Long) {
        if (bookUrl.isBlank() || id <= 0L) {
            toastOnUi("当前角色不存在")
            return
        }
        startActivity<BookCharacterCardActivity> {
            putExtra(EXTRA_BOOK_URL, bookUrl)
            putExtra(EXTRA_CHARACTER_ID, id)
        }
    }

    private fun openEdit(id: Long = 0L) {
        if (bookUrl.isBlank()) {
            toastOnUi("当前书籍不存在")
            return
        }
        startActivity<BookCharacterEditActivity> {
            putExtra(EXTRA_BOOK_URL, bookUrl)
            if (id > 0) putExtra(EXTRA_CHARACTER_ID, id)
        }
    }

    private fun openRelations() {
        if (adapter.items.size < 2) {
            toastOnUi("至少需要两个角色才能编辑关系网")
            return
        }
        startActivity<BookCharacterRelationActivity> {
            putExtra(EXTRA_BOOK_URL, bookUrl)
        }
    }

    private fun delete(character: BookCharacter) {
        alert("删除角色") {
            setMessage("确定删除「${character.displayName()}」？相关关系也会删除。")
            yesButton {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.bookCharacterDao.deleteCharacterWithRelations(character)
                    }
                    if (expandedId == character.id) expandedId = 0L
                    load()
                    setResult(Activity.RESULT_OK)
                }
            }
            noButton()
        }
    }

    private fun summary(character: BookCharacter): String {
        return listOf(character.identity, character.skills, character.attributes)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" · ")
            .ifBlank { "点击查看角色卡片" }
    }

    private inner class CharacterAdapter : RecyclerView.Adapter<CharacterAdapter.Holder>() {
        var items: List<BookCharacter> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int): Long = items[position].id
        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(ItemThemePackageBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position])
        }

        inner class Holder(private val itemBinding: ItemThemePackageBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            fun bind(character: BookCharacter) = itemBinding.run {
                root.background = UiCorner.panelRounded(
                    this@BookCharacterManageActivity,
                    ContextCompat.getColor(this@BookCharacterManageActivity, R.color.background_card),
                    UiCorner.panelRadius(this@BookCharacterManageActivity)
                )
                cardPreview.visibility = View.VISIBLE
                ivPreview.setBackgroundColor(Color.TRANSPARENT)
                ImageLoader.load(this@BookCharacterManageActivity, character.avatar.ifBlank { null })
                    .placeholder(R.drawable.ic_bottom_person)
                    .error(R.drawable.ic_bottom_person)
                    .into(ivPreview)
                tvName.text = character.displayName()
                tvSource.text = character.roleLabel()
                tvInfo.text = summary(character)
                tvInfo.isSingleLine = true
                tvInfo.maxLines = 1
                tvName.applyUiSectionTitleStyle(this@BookCharacterManageActivity)
                tvInfo.applyUiLabelStyle(this@BookCharacterManageActivity)
                tvInfo.setTextColor(secondaryTextColor)
                tvSource.setTextColor(secondaryTextColor)
                btnApply.text = "查看"
                btnEdit.text = "编辑"
                btnMore.text = "删除"
                listOf(btnApply, btnEdit, btnMore).forEach {
                    it.background = UiCorner.actionSelector(
                        Color.TRANSPARENT,
                        ContextCompat.getColor(this@BookCharacterManageActivity, R.color.background_menu),
                        UiCorner.actionRadius(this@BookCharacterManageActivity)
                    )
                    it.typeface = this@BookCharacterManageActivity.uiTypeface()
                }
                btnApply.setTextColor(accentColor)
                btnEdit.setTextColor(primaryTextColor)
                btnMore.setTextColor(primaryTextColor)
                root.setOnClickListener { openCard(character.id) }
                btnApply.setOnClickListener { openCard(character.id) }
                btnEdit.setOnClickListener { openEdit(character.id) }
                btnMore.setOnClickListener { delete(character) }
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_URL = "bookUrl"
        const val EXTRA_CHARACTER_ID = "characterId"
        private const val MENU_RELATIONS = 1
    }
}
