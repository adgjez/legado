package io.legado.app.ui.book.character

import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import androidx.viewbinding.ViewBinding
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.data.entities.BookCharacter
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.ai.AiImageGalleryManager.GalleryFilter
import io.legado.app.help.ai.AiImageService
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.book.character.compose.CharacterEditDraft
import io.legado.app.ui.book.character.compose.CharacterEditScreen
import io.legado.app.ui.book.character.compose.toDraft
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.dialog.WaitDialog
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.inputStream
import io.legado.app.utils.readUri
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

class BookCharacterEditActivity : BaseActivity<ViewBinding>(
    fullScreen = false,
    imageBg = false
) {

    private lateinit var composeView: ComposeView
    override val binding: ViewBinding by lazy {
        composeView = ComposeView(this)
        SimpleViewBinding(composeView)
    }

    private var bookUrl: String = ""
    private var characterId: Long = 0L
    private var character = BookCharacter()
    private var draft by mutableStateOf(CharacterEditDraft())
    private val waitDialog by lazy { WaitDialog(this) }

    private val selectAvatar = registerForActivityResult(HandleFileContract()) { result ->
        result.uri?.let(::copyAvatar)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        bookUrl = intent.getStringExtra(BookCharacterManageActivity.EXTRA_BOOK_URL).orEmpty()
        characterId = intent.getLongExtra(BookCharacterManageActivity.EXTRA_CHARACTER_ID, 0L)
        composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        composeView.setContent {
            CharacterEditScreen(
                title = if (characterId > 0L) "编辑角色" else "添加角色",
                draft = draft,
                onDraftChange = { draft = it },
                onBack = ::finish,
                onSave = ::save,
                onPickLocalAvatar = {
                    selectAvatar.launch {
                        mode = HandleFileContract.IMAGE
                    }
                },
                onPickOnlineAvatar = ::showOnlineAvatarDialog,
                onPickGalleryAvatar = ::showGalleryAvatarSelector,
                onRegenerateAvatar = ::showRegenerateAvatarDialog,
                onClearAvatar = { draft = draft.copy(avatar = "") }
            )
        }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            character = withContext(IO) {
                appDb.bookCharacterDao.getCharacter(characterId)
            } ?: BookCharacter(bookUrl = bookUrl)
            draft = character.toDraft()
        }
    }

    private fun save() {
        val name = draft.name.trim()
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
                    avatar = draft.avatar.trim(),
                    roleLevel = draft.roleLevel.coerceIn(
                        BookCharacter.ROLE_NORMAL,
                        BookCharacter.ROLE_MAIN
                    ),
                    identity = draft.identity.trim(),
                    skills = draft.skills.trim(),
                    attributes = draft.attributes.trim(),
                    appearance = draft.appearance.trim(),
                    personality = draft.personality.trim(),
                    biography = draft.biography.trim(),
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

    private fun showOnlineAvatarDialog() {
        val dialogBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = "输入在线图片链接"
            editView.setText(draft.avatar.takeIf { it.startsWith("http", ignoreCase = true) }.orEmpty())
        }
        alert("在线头像") {
            customView { dialogBinding.root }
            okButton {
                val value = dialogBinding.editView.text?.toString()?.trim().orEmpty()
                if (value.isNotBlank()) {
                    draft = draft.copy(avatar = value)
                }
            }
            cancelButton()
        }
    }

    private fun showGalleryAvatarSelector() {
        lifecycleScope.launch {
            val images = withContext(IO) {
                AiImageGalleryManager.listImages(GalleryFilter.ALL)
            }
            if (images.isEmpty()) {
                toastOnUi("AI 图库暂无图片")
                return@launch
            }
            val items: List<CharSequence> = images.map { image ->
                "${image.name}\n${image.prompt.replace(Regex("\\s+"), " ").take(60)}"
            }
            selector("选择 AI 图库头像", items) { _, index ->
                images.getOrNull(index)?.let(::setGalleryAvatar)
            }
        }
    }

    private fun setGalleryAvatar(image: AiGeneratedImage) {
        lifecycleScope.launch {
            withContext(IO) {
                AiImageGalleryManager.setFavorite(image.id, true, null)
            }
            draft = draft.copy(avatar = image.localPath)
            toastOnUi("已设置角色头像")
        }
    }

    private fun showRegenerateAvatarDialog() {
        if (AppConfig.aiEnabledImageProviders.isEmpty()) {
            toastOnUi("未配置生图供应商")
            return
        }
        val dialogBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = "输入角色头像提示词"
            editView.minLines = 5
            editView.setText(buildAvatarPrompt())
        }
        alert("生成角色头像") {
            customView { dialogBinding.root }
            okButton {
                val prompt = dialogBinding.editView.text?.toString()?.trim().orEmpty()
                if (prompt.isNotBlank()) generateAvatar(prompt)
            }
            cancelButton()
        }
    }

    private fun buildAvatarPrompt(): String {
        return buildList {
            add("为小说角色生成一张角色头像，头像构图，清晰，适合角色资料卡。")
            add("角色名：${draft.name.ifBlank { "未命名角色" }}")
            draft.identity.takeIf { it.isNotBlank() }?.let { add("身份：$it") }
            draft.skills.takeIf { it.isNotBlank() }?.let { add("技能：$it") }
            draft.attributes.takeIf { it.isNotBlank() }?.let { add("属性：$it") }
            draft.appearance.takeIf { it.isNotBlank() }?.let { add("形象：$it") }
            draft.personality.takeIf { it.isNotBlank() }?.let { add("性格：$it") }
            draft.biography.takeIf { it.isNotBlank() }?.let { add("生平：$it") }
        }.joinToString("\n")
    }

    private fun generateAvatar(prompt: String) {
        waitDialog.setText("正在生成角色头像...")
        waitDialog.show()
        lifecycleScope.launch {
            val result = withContext(IO) {
                runCatching {
                    val book = appDb.bookDao.getBook(bookUrl)
                    AiImageService.generateAndStore(
                        prompt,
                        metadata = AiImageGalleryManager.ImageMetadata(
                            bookName = book?.name.orEmpty(),
                            bookAuthor = book?.author.orEmpty(),
                            characterId = character.id,
                            characterName = draft.name,
                            sourceType = AiImageGalleryManager.SOURCE_TYPE_CHARACTER_AVATAR,
                            sourceText = prompt
                        )
                    ).also { image ->
                        AiImageGalleryManager.setFavorite(image.id, true, null)
                    }
                }
            }
            waitDialog.dismiss()
            result.onSuccess { image ->
                draft = draft.copy(avatar = image.localPath)
                toastOnUi("已生成并收藏角色头像")
            }.onFailure {
                toastOnUi("生成角色头像失败：${it.localizedMessage ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun copyAvatar(uri: Uri) {
        if (uri.scheme?.lowercase() in listOf("http", "https")) {
            draft = draft.copy(avatar = uri.toString())
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
                    draft = draft.copy(avatar = file.absolutePath)
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: "头像导入失败")
            }
        }
    }
}
