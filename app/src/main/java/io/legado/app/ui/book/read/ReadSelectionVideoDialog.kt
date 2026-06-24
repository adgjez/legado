package io.legado.app.ui.book.read

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppConst
import io.legado.app.constant.EventBus
import io.legado.app.data.entities.AiGeneratedVideo
import io.legado.app.databinding.DialogReadSelectionVideoBinding
import io.legado.app.help.ai.AiVideoGalleryManager
import io.legado.app.help.ai.AiVideoService
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.glide.ImageLoader
import io.legado.app.model.ReadBook
import io.legado.app.utils.gone
import io.legado.app.utils.postEvent
import io.legado.app.utils.setLayout
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReadSelectionVideoDialog() : BaseDialogFragment(R.layout.dialog_read_selection_video) {

    constructor(prompt: String, paragraphIndex: Int, paragraphText: String) : this() {
        arguments = Bundle().apply {
            putString(EXTRA_PROMPT, prompt)
            putInt(EXTRA_PARAGRAPH_INDEX, paragraphIndex)
            putString(EXTRA_PARAGRAPH_TEXT, paragraphText)
        }
    }

    private val binding by viewBinding(DialogReadSelectionVideoBinding::bind)
    private var currentVideo: AiGeneratedVideo? = null
    private var generateJob: Job? = null
    private val prompt: String
        get() = arguments?.getString(EXTRA_PROMPT).orEmpty()
    private val paragraphIndex: Int
        get() = arguments?.getInt(EXTRA_PARAGRAPH_INDEX, -1) ?: -1
    private val paragraphText: String
        get() = arguments?.getString(EXTRA_PARAGRAPH_TEXT).orEmpty()

    override fun onStart() {
        super.onStart()
        setLayout(0.96f, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.etPrompt.setText(prompt)
        binding.btnCancel.setOnClickListener { dismissAllowingStateLoss() }
        binding.btnGenerate.setOnClickListener { generateVideo() }
        binding.btnInsert.setOnClickListener { insertVideoToChapter() }
        binding.ivThumbnail.setOnClickListener { playVideo() }
        generateVideo()
    }

    override fun onDestroyView() {
        generateJob?.cancel()
        super.onDestroyView()
    }

    private fun generateVideo() {
        val content = binding.etPrompt.text?.toString().orEmpty().trim()
        if (content.isBlank()) {
            showError(getString(R.string.ai_image_no_selection))
            return
        }
        generateJob?.cancel()
        currentVideo = null
        setLoading(true)
        generateJob = lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    AiVideoService.generateAndStore(
                        content,
                        provider = AiVideoService.currentProviderOrNull(),
                        metadata = readSelectionMetadata()
                    )
                }
            }.onSuccess { video ->
                currentVideo = video
                if (video.thumbnailPath.isNotBlank()) {
                    ImageLoader.load(requireContext(), video.thumbnailPath)
                        .error(R.drawable.image_loading_error)
                        .into(binding.ivThumbnail)
                }
                binding.ivThumbnail.visible()
                binding.btnInsert.visible()
                binding.btnInsert.isEnabled = true
                setLoading(false)
                toastOnUi("视频生成成功")
            }.onFailure { error ->
                showError(error.localizedMessage ?: getString(R.string.ai_image_generate_failed))
            }
        }
    }

    private fun insertVideoToChapter() {
        val video = currentVideo ?: return
        binding.btnInsert.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    insertVideoToCurrentChapter(video)
                }
            }.onSuccess { inserted ->
                if (inserted) {
                    ReadBook.clearTextChapter()
                    postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                    toastOnUi("视频已插入正文")
                    dismissAllowingStateLoss()
                } else {
                    showError(getString(R.string.ai_image_insert_failed))
                }
            }.onFailure { error ->
                showError(error.localizedMessage ?: getString(R.string.ai_image_insert_failed))
            }
            binding.btnInsert.isEnabled = currentVideo != null
        }
    }

    private fun insertVideoToCurrentChapter(video: AiGeneratedVideo): Boolean {
        val book = ReadBook.book ?: return false
        val chapter = ReadBook.curTextChapter?.chapter ?: return false
        val rawContent = BookHelp.getContent(book, chapter).orEmpty()
        if (rawContent.isBlank()) return false
        val contentProcessor = ContentProcessor.get(book.name, book.origin)
        val lines = contentProcessor.getContent(book, chapter, rawContent, includeTitle = false)
            .textList
            .toMutableList()
        if (lines.isEmpty()) return false
        val targetIndex = paragraphIndex.takeIf { it in lines.indices }
            ?: findParagraphIndex(lines, paragraphText)
            ?: return false
        val videoTag = "[video=${AiVideoGalleryManager.videoUri(video.id)}]"
        if (!lines[targetIndex].contains(videoTag)) {
            lines[targetIndex] = lines[targetIndex].trimEnd() + videoTag
            BookHelp.saveText(book, chapter, lines.joinToString("\n"))
        }
        AiVideoGalleryManager.setFavorite(video.id, true, null)
        return true
    }

    private fun readSelectionMetadata(): AiVideoGalleryManager.VideoMetadata {
        val book = ReadBook.book
        val chapter = ReadBook.curTextChapter?.chapter
        return AiVideoGalleryManager.VideoMetadata(
            bookName = book?.name.orEmpty(),
            bookAuthor = book?.author.orEmpty(),
            chapterIndex = chapter?.index ?: ReadBook.durChapterIndex,
            chapterTitle = chapter?.title.orEmpty(),
            sourceType = AiVideoGalleryManager.SOURCE_TYPE_READ_INSERT,
            sourceText = paragraphText
        )
    }

    private fun playVideo() {
        val video = currentVideo ?: return
        val file = File(video.localPath)
        if (!file.isFile) {
            toastOnUi("视频文件不存在")
            return
        }
        runCatching {
            val uri = FileProvider.getUriForFile(requireContext(), AppConst.authority, file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }.onFailure {
            toastOnUi("无法播放视频")
        }
    }

    private fun findParagraphIndex(lines: List<String>, target: String): Int? {
        val normalizedTarget = normalizeParagraph(target)
        if (normalizedTarget.isBlank()) return null
        return lines.indexOfFirst { line ->
            val normalizedLine = normalizeParagraph(line)
            normalizedLine == normalizedTarget ||
                normalizedLine.contains(normalizedTarget) ||
                normalizedTarget.contains(normalizedLine)
        }.takeIf { it >= 0 }
    }

    private fun normalizeParagraph(text: String): String {
        return text.replace(Regex("""\s+"""), "")
            .replace("\u3000", "")
            .trim()
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.isVisible = loading
        binding.btnGenerate.isEnabled = !loading
        if (loading) {
            binding.ivThumbnail.gone()
        }
    }

    private fun showError(message: String) {
        setLoading(false)
        binding.ivThumbnail.gone()
        toastOnUi(message)
    }

    companion object {
        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_PARAGRAPH_INDEX = "paragraphIndex"
        private const val EXTRA_PARAGRAPH_TEXT = "paragraphText"
    }
}
