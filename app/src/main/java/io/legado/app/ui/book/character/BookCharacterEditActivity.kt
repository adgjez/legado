package io.legado.app.ui.book.character

import android.net.Uri
import android.os.Bundle
import android.text.InputType
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
import androidx.viewbinding.ViewBinding
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.lib.theme.view.ThemeEditText
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.TitleBar
import io.legado.app.ui.widget.text.TextInputLayout
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.dpToPx
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.readUri
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import java.io.FileOutputStream

class BookCharacterEditActivity : BaseActivity<ViewBinding>() {

    private lateinit var rootLayout: LinearLayout
    private lateinit var avatarView: ImageView
    private lateinit var nameEdit: ThemeEditText
    private lateinit var avatarEdit: ThemeEditText
    private lateinit var identityEdit: ThemeEditText
    private lateinit var skillsEdit: ThemeEditText
    private lateinit var attributesEdit: ThemeEditText
    private lateinit var appearanceEdit: ThemeEditText
    private lateinit var personalityEdit: ThemeEditText
    private lateinit var biographyEdit: ThemeEditText

    override val binding: ViewBinding by lazy {
        SimpleViewBinding(createContentView())
    }

    private var bookUrl: String = ""
    private var characterId: Long = 0L
    private var character = BookCharacter()

    private val selectAvatar = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let(::copyAvatar)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra(BookCharacterManageActivity.EXTRA_BOOK_URL).orEmpty()
        characterId = intent.getLongExtra(BookCharacterManageActivity.EXTRA_CHARACTER_ID, 0L)
        load()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_SAVE, 0, R.string.save)
            .setIcon(R.drawable.ic_save)
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_SAVE -> {
                save()
                true
            }
            else -> super.onCompatOptionsItemSelected(item)
        }
    }

    private fun createContentView(): View {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(TitleBar(this).apply {
            id = R.id.title_bar
            title = "编辑角色"
        })
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            isFillViewport = true
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 24.dpToPx())
        }
        content.addView(createAvatarHeader())
        nameEdit = content.addInput("角色名称", singleLine = true)
        avatarEdit = content.addInput("角色头像 URL 或本地路径", singleLine = true) {
            updateAvatar(it)
        }
        identityEdit = content.addInput("角色身份", singleLine = true)
        skillsEdit = content.addInput("角色技能")
        attributesEdit = content.addInput("角色属性")
        appearanceEdit = content.addInput("角色形象描述")
        personalityEdit = content.addInput("角色性格描述")
        biographyEdit = content.addInput("角色生平", minLines = 4)
        scrollView.addView(content)
        rootLayout.addView(scrollView)
        rootLayout.applyUiBodyTypefaceDeep(uiTypeface())
        return rootLayout
    }

    private fun createAvatarHeader(): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 12.dpToPx())
            avatarView = ImageView(this@BookCharacterEditActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = UiCorner.opaqueRounded(
                    ContextCompat.getColor(this@BookCharacterEditActivity, R.color.background_card),
                    UiCorner.panelRadius(this@BookCharacterEditActivity)
                )
                setImageResource(R.drawable.ic_bottom_person)
            }
            addView(avatarView, LinearLayout.LayoutParams(78.dpToPx(), 78.dpToPx()))
            addView(LinearLayout(this@BookCharacterEditActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(14.dpToPx(), 0, 0, 0)
                addView(TextView(this@BookCharacterEditActivity).apply {
                    text = "角色头像"
                    textSize = 16f
                    setTextColor(primaryTextColor)
                })
                addView(TextView(this@BookCharacterEditActivity).apply {
                    text = "可填写网络图片，也可以选择本地图片。"
                    textSize = 13f
                    setTextColor(secondaryTextColor)
                    setPadding(0, 4.dpToPx(), 0, 8.dpToPx())
                })
                addView(TextView(this@BookCharacterEditActivity).apply {
                    text = "选择图片"
                    gravity = Gravity.CENTER
                    minHeight = 34.dpToPx()
                    setPadding(14.dpToPx(), 0, 14.dpToPx(), 0)
                    setTextColor(primaryTextColor)
                    background = UiCorner.actionSelector(
                        ContextCompat.getColor(this@BookCharacterEditActivity, R.color.background_card),
                        ContextCompat.getColor(this@BookCharacterEditActivity, R.color.background_menu),
                        UiCorner.actionRadius(this@BookCharacterEditActivity)
                    )
                    setOnClickListener {
                        selectAvatar.launch {
                            mode = HandleFileContract.IMAGE
                        }
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, 36.dpToPx()))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun LinearLayout.addInput(
        hint: String,
        singleLine: Boolean = false,
        minLines: Int = 2,
        afterTextChanged: ((String) -> Unit)? = null
    ): ThemeEditText {
        val inputLayout = TextInputLayout(this@BookCharacterEditActivity, null).apply {
            this.hint = hint
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8.dpToPx()
            }
        }
        val editText = ThemeEditText(this@BookCharacterEditActivity).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(singleLine)
            if (!singleLine) {
                this.minLines = minLines
                gravity = Gravity.TOP or Gravity.START
            }
            afterTextChanged?.let { callback ->
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        callback(s?.toString().orEmpty())
                    }
                    override fun afterTextChanged(s: android.text.Editable?) = Unit
                })
            }
        }
        inputLayout.addView(editText, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        addView(inputLayout)
        return editText
    }

    private fun load() {
        lifecycleScope.launch {
            character = withContext(IO) {
                appDb.bookCharacterDao.getCharacter(characterId)
            } ?: BookCharacter(bookUrl = bookUrl)
            fill(character)
        }
    }

    private fun fill(character: BookCharacter) {
        nameEdit.setText(character.name)
        avatarEdit.setText(character.avatar)
        identityEdit.setText(character.identity)
        skillsEdit.setText(character.skills)
        attributesEdit.setText(character.attributes)
        appearanceEdit.setText(character.appearance)
        personalityEdit.setText(character.personality)
        biographyEdit.setText(character.biography)
        updateAvatar(character.avatar)
    }

    private fun save() {
        val name = nameEdit.text?.toString().orEmpty().trim()
        if (bookUrl.isBlank()) {
            toastOnUi("当前书籍不存在")
            return
        }
        if (name.isBlank()) {
            toastOnUi("角色名称不能为空")
            return
        }
        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val result = withContext(IO) {
                val duplicated = appDb.bookCharacterDao.getCharacter(bookUrl, name)
                    ?.takeIf { it.id != character.id }
                if (duplicated != null) {
                    return@withContext false
                }
                val saving = character.copy(
                    bookUrl = bookUrl,
                    name = name,
                    avatar = avatarEdit.text?.toString().orEmpty().trim(),
                    identity = identityEdit.text?.toString().orEmpty().trim(),
                    skills = skillsEdit.text?.toString().orEmpty().trim(),
                    attributes = attributesEdit.text?.toString().orEmpty().trim(),
                    appearance = appearanceEdit.text?.toString().orEmpty().trim(),
                    personality = personalityEdit.text?.toString().orEmpty().trim(),
                    biography = biographyEdit.text?.toString().orEmpty().trim(),
                    sortOrder = character.sortOrder.takeIf { it > 0 }
                        ?: ((appDb.bookCharacterDao.maxCharacterOrder(bookUrl) ?: -1) + 1),
                    createdAt = character.createdAt.takeIf { it > 0 } ?: now,
                    updatedAt = now
                )
                if (saving.id > 0) {
                    appDb.bookCharacterDao.updateCharacter(saving)
                } else {
                    appDb.bookCharacterDao.insertCharacter(saving)
                }
                true
            }
            if (!result) {
                toastOnUi("已存在同名角色")
                return@launch
            }
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun copyAvatar(uri: Uri) {
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            avatarEdit.setText(uri.toString())
            updateAvatar(uri.toString())
            return
        }
        readUri(uri) { fileDoc, inputStream ->
            runCatching {
                inputStream.use {
                    val suffix = "." + fileDoc.name.substringAfterLast(".", "png")
                    val fileName = uri.inputStream(this).getOrThrow().use { stream ->
                        MD5Utils.md5Encode(stream) + suffix
                    }
                    val file = FileUtils.createFileIfNotExist(
                        externalFiles,
                        "bookCharacters",
                        "avatars",
                        fileName
                    )
                    FileOutputStream(file).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                    avatarEdit.setText(file.absolutePath)
                    updateAvatar(file.absolutePath)
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: "头像导入失败")
            }
        }
    }

    private fun updateAvatar(path: String) {
        ImageLoader.load(this, path.ifBlank { null })
            .placeholder(R.drawable.ic_bottom_person)
            .error(R.drawable.ic_bottom_person)
            .into(avatarView)
    }

    companion object {
        private const val MENU_SAVE = 1
    }
}
