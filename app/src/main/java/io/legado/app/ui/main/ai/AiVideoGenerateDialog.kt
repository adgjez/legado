package io.legado.app.ui.main.ai

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.DialogAiVideoGenerateBinding
import io.legado.app.help.ai.AiVideoGalleryManager
import io.legado.app.help.ai.AiVideoService
import io.legado.app.help.ai.AiVideoTool
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * AI 视频生成对话框
 *
 * 支持两种模式：
 * 1. 手动输入 prompt 生成
 * 2. 按章节生成（选择书籍 + 章节区间，LLM 自动提取场景）
 */
class AiVideoGenerateDialog : BaseDialogFragment(R.layout.dialog_ai_video_generate) {

    private val binding by viewBinding(DialogAiVideoGenerateBinding::bind)

    private var chapterMode = false
    private var selectedBook: Book? = null
    private var selectedFromChapter: Int = -1
    private var selectedToChapter: Int = -1

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.tvProviderName.text = AppConfig.aiCurrentVideoProvider?.displayName()
            ?: getString(R.string.ai_video_no_provider)
        binding.btnSubmit.setOnClickListener { submit() }
        binding.btnChapterMode.setOnClickListener { toggleChapterMode() }
        binding.btnSelectBook.setOnClickListener { showBookPicker() }
        binding.btnSelectChapterRange.setOnClickListener { showChapterRangePicker() }
        binding.chipGenMode.setOnCheckedStateChangeListener { group, _ ->
            updateGenModeUI()
        }
        updateModeUI()
        updateGenModeUI()
    }

    private fun toggleChapterMode() {
        chapterMode = !chapterMode
        updateModeUI()
    }

    private fun updateModeUI() {
        if (chapterMode) {
            binding.layoutManual.visibility = View.GONE
            binding.layoutChapter.visibility = View.VISIBLE
            binding.btnChapterMode.text = getString(R.string.ai_video_mode_manual)
            binding.btnSubmit.text = getString(R.string.ai_video_generate_range)
        } else {
            binding.layoutManual.visibility = View.VISIBLE
            binding.layoutChapter.visibility = View.GONE
            binding.btnChapterMode.text = getString(R.string.ai_video_mode_chapter)
            binding.btnSubmit.text = getString(R.string.ai_video_submit)
        }
    }

    /**
     * 根据选中的生成模式更新 UI：
     * - 文生视频: 隐藏首帧和多图
     * - 图生视频: 显示首帧，隐藏多图
     * - 多图视频: 隐藏首帧，显示多图
     * - 关键帧动画: 隐藏首帧，显示多图
     */
    private fun updateGenModeUI() {
        when (binding.chipGenMode.checkedChipId) {
            R.id.chip_mode_i2v -> {
                binding.tilFirstFrame.visibility = View.VISIBLE
                binding.tilImages.visibility = View.GONE
            }
            R.id.chip_mode_multi, R.id.chip_mode_keyframes -> {
                binding.tilFirstFrame.visibility = View.GONE
                binding.tilImages.visibility = View.VISIBLE
            }
            else -> {
                // 文生视频
                binding.tilFirstFrame.visibility = View.GONE
                binding.tilImages.visibility = View.GONE
            }
        }
    }

    private fun showBookPicker() {
        lifecycleScope.launch {
            val books = withContext(Dispatchers.IO) {
                appDb.bookDao.all
            }
            if (books.isEmpty()) {
                toastOnUi(R.string.ai_video_no_book)
                return@launch
            }
            alert(R.string.ai_video_select_book) {
                books.take(50).forEach { book ->
                    neutralButton("${book.name} - ${book.author}") {
                        selectedBook = book
                        selectedFromChapter = -1
                        selectedToChapter = -1
                        binding.tvSelectedBook.text = "${book.name} - ${book.author}"
                        binding.tvSelectedChapters.text = ""
                    }
                }
                cancelButton()
            }
        }
    }

    private fun showChapterRangePicker() {
        val book = selectedBook ?: run {
            toastOnUi(R.string.ai_video_select_book_first)
            return
        }
        lifecycleScope.launch {
            val chapters = withContext(Dispatchers.IO) {
                appDb.bookChapterDao.getChapterList(book.bookUrl)
            }
            if (chapters.isEmpty()) {
                toastOnUi(R.string.ai_video_no_chapters)
                return@launch
            }
            // 选择起始章节
            alert(R.string.ai_video_select_from_chapter) {
                chapters.take(100).forEach { ch ->
                    neutralButton("${ch.index + 1}. ${ch.title}") {
                        selectedFromChapter = ch.index
                        // 选择结束章节
                        showToChapterPicker(chapters, ch.index)
                    }
                }
                cancelButton()
            }
        }
    }

    private fun showToChapterPicker(chapters: List<BookChapter>, fromIndex: Int) {
        val book = selectedBook ?: return
        val fromChapter = chapters.find { it.index == fromIndex }
        alert(R.string.ai_video_select_to_chapter) {
            chapters.filter { it.index >= fromIndex }.take(10).forEach { ch ->
                neutralButton("${ch.index + 1}. ${ch.title}") {
                    selectedToChapter = ch.index
                    binding.tvSelectedChapters.text = getString(
                        R.string.ai_video_chapter_range_summary,
                        fromIndex + 1, fromChapter?.title ?: "",
                        ch.index + 1, ch.title,
                        ch.index - fromIndex + 1
                    )
                }
            }
            // 也允许只生成单章
            neutralButton(getString(R.string.ai_video_single_chapter)) {
                selectedToChapter = fromIndex
                binding.tvSelectedChapters.text = getString(
                    R.string.ai_video_chapter_single_summary,
                    fromIndex + 1, fromChapter?.title ?: ""
                )
            }
            cancelButton()
        }
    }

    private fun submit() {
        if (chapterMode) {
            submitChapterMode()
        } else {
            submitManualMode()
        }
    }

    private fun submitManualMode() {
        val prompt = binding.etPrompt.text?.toString().orEmpty().trim()
        if (prompt.isBlank()) {
            toastOnUi(R.string.ai_video_prompt_hint)
            return
        }
        val negative = binding.etNegativePrompt.text?.toString().orEmpty().trim()
        val duration = binding.etDuration.text?.toString()?.trim()?.toIntOrNull() ?: 0
        val aspect = when (binding.chipAspect.checkedChipId) {
            R.id.chip_16_9 -> "16:9"
            R.id.chip_9_16 -> "9:16"
            R.id.chip_1_1 -> "1:1"
            R.id.chip_4_3 -> "4:3"
            else -> "16:9"
        }
        val firstFrame = binding.etFirstFrame.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        // 多图 URL：每行一个或逗号分隔
        val images = binding.etImages.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
            ?.split("\n", ",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
        // 生成模式
        val genMode = when (binding.chipGenMode.checkedChipId) {
            R.id.chip_mode_i2v -> "ti2vid"
            R.id.chip_mode_keyframes -> "keyframes"
            R.id.chip_mode_multi -> ""
            else -> ""
        }
        val metadata = AiVideoGalleryManager.VideoMetadata(
            sourceType = "user_prompt"
        )
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                AiVideoService.submitAndStore(
                    prompt = prompt,
                    negativePrompt = negative,
                    firstFrame = firstFrame,
                    images = images,
                    mode = genMode,
                    durationSec = duration,
                    aspectRatio = aspect,
                    metadata = metadata
                )
                if (!isAdded || view == null) return@launch
                toastOnUi(R.string.ai_video_submit)
                dismissAllowingStateLoss()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!isAdded || view == null) return@launch
                alert(getString(R.string.ai_video_submit_failed, e.message ?: e.javaClass.simpleName)) {
                    okButton()
                }
            }
        }
    }

    private fun submitChapterMode() {
        val book = selectedBook ?: run {
            toastOnUi(R.string.ai_video_select_book_first)
            return
        }
        if (selectedFromChapter < 0 || selectedToChapter < 0) {
            toastOnUi(R.string.ai_video_select_chapter_range)
            return
        }
        val stylePrompt = binding.etStylePrompt.text?.toString().orEmpty().trim()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val args = JSONObject().apply {
                    put("bookUrl", book.bookUrl)
                    put("fromChapter", selectedFromChapter)
                    put("toChapter", selectedToChapter)
                    if (stylePrompt.isNotBlank()) put("stylePrompt", stylePrompt)
                    put("maxScenesPerChapter", 2)
                }
                // 直接调用 AiVideoTool 的批量生成
                val result = withContext(Dispatchers.IO) {
                    AiVideoTool.runChapterVideoRange(args)
                }
                if (!isAdded || view == null) return@launch
                val resultJson = JSONObject(result)
                if (resultJson.optBoolean("ok")) {
                    val count = resultJson.optInt("chapterCount")
                    toastOnUi(getString(R.string.ai_video_range_submitted, count))
                    dismissAllowingStateLoss()
                } else {
                    val error = resultJson.optString("error", "未知错误")
                    alert(error) { okButton() }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!isAdded || view == null) return@launch
                alert(getString(R.string.ai_video_submit_failed, e.message ?: e.javaClass.simpleName)) {
                    okButton()
                }
            }
        }
    }
}
