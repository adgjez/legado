package io.legado.app.ui.main.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.data.entities.AiVideoAnalysis
import io.legado.app.databinding.DialogAiVideoAnalysisBinding
import io.legado.app.help.ai.AiVideoAnalysisService
import io.legado.app.help.ai.asr.AsrConfig
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 视频分析对话框（P3）。
 *
 * 选择分析类型（摘要/字幕/章节/关键帧/封面）+ 语种 + 启动分析。
 * 分析完成后跳转 [AiVideoAnalysisActivity] 查看结果。
 */
class AiVideoAnalysisDialog : DialogFragment() {

    private var _binding: DialogAiVideoAnalysisBinding? = null
    private val binding get() = _binding!!
    private var job: Job? = null

    private val bookId: String get() = requireArguments().getString(ARG_BOOK_ID) ?: ""
    private val videoPath: String get() = requireArguments().getString(ARG_VIDEO_PATH).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAiVideoAnalysisBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.tvTitle.text = getString(R.string.ai_video_analysis_title)
        binding.progressBar.visibility = View.GONE
        binding.tvProgress.visibility = View.GONE

        binding.btnStart.setOnClickListener { start() }
        binding.btnCancel.setOnClickListener { job?.cancel() }
    }

    private fun selectedKind(): String = when (binding.chipGroupType.checkedChipId) {
        R.id.chip_summary -> AiVideoAnalysis.KIND_SUMMARY
        R.id.chip_subtitle -> AiVideoAnalysis.KIND_SUBTITLE
        R.id.chip_chapters -> AiVideoAnalysis.KIND_CHAPTERS
        R.id.chip_keyframes -> AiVideoAnalysis.KIND_KEYFRAMES
        R.id.chip_cover -> AiVideoAnalysis.KIND_COVER
        else -> AiVideoAnalysis.KIND_SUMMARY
    }

    private fun selectedLanguage(): String = when (binding.chipGroupLang.checkedChipId) {
        R.id.chip_lang_zh -> "zh"
        R.id.chip_lang_en -> "en"
        else -> ""
    }

    private fun start() {
        if (job?.isActive == true) return
        val kind = selectedKind()
        val lang = selectedLanguage()
        if (bookId.isBlank() || videoPath.isBlank()) {
            toastOnUi(R.string.ai_video_analysis_missing_params)
            return
        }
        // 字幕/章节需要 ASR + 视频路径
        binding.btnStart.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgress.visibility = View.VISIBLE
        binding.tvProgress.text = getString(R.string.ai_video_analysis_running)
        job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    when (kind) {
                        AiVideoAnalysis.KIND_SUMMARY -> summarize()
                        AiVideoAnalysis.KIND_SUBTITLE -> AiVideoAnalysisService.extractSubtitles(
                            bookId, videoPath, asrConfig(), lang
                        )
                        AiVideoAnalysis.KIND_CHAPTERS -> {
                            // 先确保有字幕
                            val llmCfg = AppConfig.llmConfigForBook(bookId)
                                ?: AppConfig.defaultLlmConfig()
                                ?: error("LLM provider not configured")
                            AiVideoAnalysisService.detectChapters(
                                bookId = bookId,
                                llmConfig = llmCfg,
                                asrConfig = asrConfig(),
                                videoPath = videoPath,
                                language = lang
                            )
                        }
                        AiVideoAnalysis.KIND_KEYFRAMES -> AiVideoAnalysisService.extractKeyFrames(
                            bookId, videoPath, 8
                        )
                        AiVideoAnalysis.KIND_COVER -> AiVideoAnalysisService.extractCover(
                            bookId, videoPath
                        )
                        else -> error("unknown kind: $kind")
                    }
                }
                if (!isAdded || view == null) return@launch
                if (result.status == AiVideoAnalysis.STATUS_SUCCESS) {
                    val intent = Intent(requireContext(), AiVideoAnalysisActivity::class.java)
                        .putExtra(AiVideoAnalysisActivity.EXTRA_BOOK_ID, bookId)
                        .putExtra(AiVideoAnalysisActivity.EXTRA_VIDEO_PATH, videoPath)
                        .putExtra(AiVideoAnalysisActivity.EXTRA_KIND, kind)
                        .putExtra(AiVideoAnalysisActivity.EXTRA_LANGUAGE, lang)
                    startActivity(intent)
                    dismissAllowingStateLoss()
                } else {
                    alert(getString(R.string.ai_video_analysis_failed, result.failReason)) {
                        okButton()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!isAdded || view == null) return@launch
                alert(getString(R.string.ai_video_analysis_failed, e.message ?: e.javaClass.simpleName)) {
                    okButton()
                }
            } finally {
                if (_binding != null) {
                    binding.btnStart.isEnabled = true
                    binding.progressBar.visibility = View.GONE
                    binding.tvProgress.visibility = View.GONE
                }
            }
        }
    }

    private suspend fun summarize(): AiVideoAnalysis {
        val asrCfg = asrConfig()
        val llmCfg = AppConfig.llmConfigForBook(bookId) ?: AppConfig.defaultLlmConfig()
        return AiVideoAnalysisService.summarize(
            bookId = bookId,
            videoPath = videoPath,
            asrConfig = asrCfg,
            llmConfig = llmCfg,
            language = selectedLanguage()
        )
    }

    private fun asrConfig(): AsrConfig {
        val cfg = AppConfig.asrConfig()
        return cfg ?: AsrConfig(type = AsrConfig.TYPE_WHISPER)
    }

    override fun onDestroyView() {
        job?.cancel()
        job = null
        _binding = null
        super.onDestroyView()
    }

    companion object {
        private const val ARG_BOOK_ID = "bookId"
        private const val ARG_VIDEO_PATH = "videoPath"

        fun show(activity: Activity, bookId: String, videoPath: String) {
            val frag = AiVideoAnalysisDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_BOOK_ID, bookId)
                    putString(ARG_VIDEO_PATH, videoPath)
                }
            }
            // 用 FragmentManager 优先；否则降级
            val fm = (activity as? androidx.fragment.app.FragmentActivity)
                ?.supportFragmentManager
            if (fm != null) {
                frag.show(fm, "AiVideoAnalysisDialog")
            } else {
                // 无 FragmentManager 的 Activity 暂不暴露此入口
            }
        }
    }
}
