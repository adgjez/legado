package io.legado.app.ui.book.character

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.dpToPx
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookCharacterCardActivity : BaseActivity<ViewBinding>() {

    private lateinit var titleBar: TitleBar
    private lateinit var content: LinearLayout
    private lateinit var avatarView: ImageView
    private lateinit var nameView: TextView
    private lateinit var roleView: TextView
    private var bookUrl: String = ""
    private var characterId: Long = 0L
    private var character: BookCharacter? = null

    override val binding: ViewBinding by lazy {
        SimpleViewBinding(createContentView())
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra(BookCharacterManageActivity.EXTRA_BOOK_URL).orEmpty()
        characterId = intent.getLongExtra(BookCharacterManageActivity.EXTRA_CHARACTER_ID, 0L)
        load()
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_EDIT, 0, "编辑")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_EDIT -> {
                openEdit()
                true
            }
            else -> super.onCompatOptionsItemSelected(item)
        }
    }

    private fun createContentView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        titleBar = TitleBar(this).apply {
            id = R.id.title_bar
            title = "角色卡片"
        }
        root.addView(titleBar)
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18.dpToPx(), 16.dpToPx(), 18.dpToPx(), 28.dpToPx())
        }
        content.addView(createHeroCard())
        scrollView.addView(content)
        root.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        root.applyUiBodyTypefaceDeep(uiTypeface())
        return root
    }

    private fun createHeroCard(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(20.dpToPx(), 20.dpToPx(), 20.dpToPx(), 18.dpToPx())
            background = UiCorner.panelRounded(
                this@BookCharacterCardActivity,
                ContextCompat.getColor(this@BookCharacterCardActivity, R.color.background_card),
                UiCorner.panelRadius(this@BookCharacterCardActivity)
            )
            avatarView = ImageView(this@BookCharacterCardActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = UiCorner.opaqueRounded(
                    ContextCompat.getColor(this@BookCharacterCardActivity, R.color.background_menu),
                    UiCorner.panelRadius(this@BookCharacterCardActivity)
                )
                setImageResource(R.drawable.ic_bottom_person)
            }
            addView(avatarView, LinearLayout.LayoutParams(104.dpToPx(), 104.dpToPx()))
            nameView = TextView(this@BookCharacterCardActivity).apply {
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(primaryTextColor)
                setPadding(0, 14.dpToPx(), 0, 6.dpToPx())
            }
            addView(nameView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            roleView = TextView(this@BookCharacterCardActivity).apply {
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(accentColor)
                setPadding(14.dpToPx(), 5.dpToPx(), 14.dpToPx(), 5.dpToPx())
                background = UiCorner.actionSelector(
                    Color.TRANSPARENT,
                    ContextCompat.getColor(this@BookCharacterCardActivity, R.color.background_menu),
                    UiCorner.actionRadius(this@BookCharacterCardActivity)
                )
            }
            addView(roleView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun load() {
        if (characterId <= 0L) {
            toastOnUi("角色不存在")
            finish()
            return
        }
        lifecycleScope.launch {
            val item = withContext(IO) { appDb.bookCharacterDao.getCharacter(characterId) }
            if (item == null) {
                toastOnUi("角色不存在")
                finish()
                return@launch
            }
            character = item
            render(item)
        }
    }

    private fun render(item: BookCharacter) {
        titleBar.subtitle = item.displayName()
        ImageLoader.load(this, item.avatar.ifBlank { null })
            .placeholder(R.drawable.ic_bottom_person)
            .error(R.drawable.ic_bottom_person)
            .into(avatarView)
        nameView.text = item.displayName()
        roleView.text = item.roleLabel()
        while (content.childCount > 1) {
            content.removeViewAt(1)
        }
        listOf(
            "身份" to item.identity,
            "技能" to item.skills,
            "属性" to item.attributes,
            "形象描述" to item.appearance,
            "性格描述" to item.personality,
            "角色生平" to item.biography
        ).forEach { (label, value) ->
            content.addView(section(label, value))
        }
    }

    private fun section(label: String, value: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 13.dpToPx(), 16.dpToPx(), 14.dpToPx())
            background = UiCorner.panelRounded(
                this@BookCharacterCardActivity,
                ContextCompat.getColor(this@BookCharacterCardActivity, R.color.background_card),
                UiCorner.panelRadius(this@BookCharacterCardActivity)
            )
            addView(TextView(this@BookCharacterCardActivity).apply {
                text = label
                textSize = 13f
                setTextColor(secondaryTextColor)
            })
            addView(TextView(this@BookCharacterCardActivity).apply {
                text = value.ifBlank { "未填写" }
                textSize = 15f
                setTextColor(primaryTextColor)
                setPadding(0, 6.dpToPx(), 0, 0)
                setLineSpacing(3.dpToPx().toFloat(), 1f)
            })
        }.also {
            it.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 12.dpToPx()
            }
        }
    }

    private fun openEdit() {
        val item = character ?: return
        startActivity<BookCharacterEditActivity> {
            putExtra(BookCharacterManageActivity.EXTRA_BOOK_URL, bookUrl.ifBlank { item.bookUrl })
            putExtra(BookCharacterManageActivity.EXTRA_CHARACTER_ID, item.id)
        }
    }

    companion object {
        private const val MENU_EDIT = 1
    }
}
