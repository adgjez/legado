package io.legado.app.ui.main.ai

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiVideoAnalysis
import io.legado.app.databinding.ActivityAiVideoAnalysisBinding
import io.legado.app.databinding.ItemAiVideoChapterBinding
import io.legado.app.databinding.ItemAiGeneratedImageBinding
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * AI 视频分析结果展示（P3）。
 *
 * 通过 [EXTRA_BOOK_ID] / [EXTRA_VIDEO_PATH] / [EXTRA_KIND] / [EXTRA_LANGUAGE] 进入：
 * - 顶部是 kind 切换 chip（每个 kind 对应一行缓存）。
 * - 中间是结果区（文本 / 章节 / 关键帧 / 封面）。
 * - 底部为空态 + "开始分析"按钮。
 */
class AiVideoAnalysisActivity : BaseActivity<ActivityAiVideoAnalysisBinding>() {

    override val binding by viewBinding(ActivityAiVideoAnalysisBinding::inflate)

    private val bookId by lazy { intent.getStringExtra(EXTRA_BOOK_ID).orEmpty() }
    private val videoPath by lazy { intent.getStringExtra(EXTRA_VIDEO_PATH).orEmpty() }
    private var language: String = ""
    private var currentKind: String = AiVideoAnalysis.KIND_SUMMARY

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        language = intent.getStringExtra(EXTRA_LANGUAGE).orEmpty()
        currentKind = intent.getStringExtra(EXTRA_KIND)?.takeIf {
            it in setOf(
                AiVideoAnalysis.KIND_SUMMARY,
                AiVideoAnalysis.KIND_SUBTITLE,
                AiVideoAnalysis.KIND_CHAPTERS,
                AiVideoAnalysis.KIND_KEYFRAMES,
                AiVideoAnalysis.KIND_COVER
            )
        } ?: AiVideoAnalysis.KIND_SUMMARY
        binding.titleBar.title = getString(R.string.ai_video_analysis_result_title)
        setupKindChips()
        binding.btnRun.setOnClickListener { showAnalysisDialog() }
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        loadCurrentKind()
        // 触发 30 天缓存清理（fire-and-forget；不阻塞 UI）
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { io.legado.app.help.ai.AiVideoAnalysisService.cleanupOld() }
        }
    }

    private fun setupKindChips() {
        val group = binding.chipGroupKinds
        group.removeAllViews()
        val kinds = listOf(
            AiVideoAnalysis.KIND_SUMMARY to R.string.ai_video_analysis_kind_summary,
            AiVideoAnalysis.KIND_SUBTITLE to R.string.ai_video_analysis_kind_subtitle,
            AiVideoAnalysis.KIND_CHAPTERS to R.string.ai_video_analysis_kind_chapters,
            AiVideoAnalysis.KIND_KEYFRAMES to R.string.ai_video_analysis_kind_keyframes,
            AiVideoAnalysis.KIND_COVER to R.string.ai_video_analysis_kind_cover
        )
        kinds.forEach { (kind, labelRes) ->
            val chip = Chip(this).apply {
                text = getString(labelRes)
                isCheckable = true
                isChecked = kind == currentKind
                setOnClickListener {
                    if (!isChecked) isChecked = true
                    if (currentKind != kind) {
                        currentKind = kind
                        loadCurrentKind()
                    }
                }
            }
            group.addView(chip)
        }
    }

    private fun loadCurrentKind() {
        // 同步 group 选中态
        for (i in 0 until binding.chipGroupKinds.childCount) {
            val chip = binding.chipGroupKinds.getChildAt(i) as? Chip ?: continue
            chip.isChecked = (chip.text == labelOf(currentKind))
        }
        binding.tvText.text = ""
        binding.ivCover.setImageDrawable(null)
        binding.recyclerView.adapter = null
        binding.scrollText.isVisible = false
        binding.recyclerView.isVisible = false
        binding.ivCover.isVisible = false
        binding.emptyView.isVisible = false
        binding.tvStatus.text = getString(R.string.ai_video_analysis_loading)
        lifecycleScope.launch {
            val row = withContext(Dispatchers.IO) {
                appDb.aiVideoAnalysisDao.byBookAndKind(
                    bookId, currentKind, langForQuery()
                )
            }
            if (row == null) {
                showEmpty()
                return@launch
            }
            render(row)
        }
    }

    private fun langForQuery(): String = when (currentKind) {
        AiVideoAnalysis.KIND_SUBTITLE, AiVideoAnalysis.KIND_CHAPTERS -> language
        else -> ""
    }

    private fun render(row: AiVideoAnalysis) {
        binding.tvStatus.text = formatStatus(row)
        when (row.status) {
            AiVideoAnalysis.STATUS_RUNNING -> {
                binding.tvStatus.append(" · " + getString(R.string.ai_video_analysis_running))
                binding.emptyView.isVisible = true
                binding.tvEmpty.setText(R.string.ai_video_analysis_pending)
            }
            AiVideoAnalysis.STATUS_FAILED -> {
                binding.tvStatus.append(" · " + row.failReason)
                binding.emptyView.isVisible = true
                binding.tvEmpty.text = getString(R.string.ai_video_analysis_failed_short, row.failReason)
            }
            AiVideoAnalysis.STATUS_CANCELLED -> {
                binding.tvStatus.append(" · " + getString(R.string.ai_video_analysis_cancelled))
                binding.emptyView.isVisible = true
                binding.tvEmpty.setText(R.string.ai_video_analysis_cancelled)
            }
            AiVideoAnalysis.STATUS_SUCCESS -> renderPayload(row.payloadJson)
            else -> showEmpty()
        }
    }

    private fun renderPayload(payloadJson: String) {
        val obj = runCatching { JSONObject(payloadJson) }.getOrNull()
        if (obj == null) {
            showEmpty()
            return
        }
        when (currentKind) {
            AiVideoAnalysis.KIND_SUMMARY -> {
                val text = obj.optString("summary")
                if (text.isBlank()) {
                    showEmpty()
                } else {
                    binding.scrollText.isVisible = true
                    binding.tvText.text = text
                }
            }
            AiVideoAnalysis.KIND_SUBTITLE -> {
                val srt = obj.optString("srt")
                if (srt.isBlank()) {
                    showEmpty()
                } else {
                    binding.scrollText.isVisible = true
                    binding.tvText.text = srt
                }
            }
            AiVideoAnalysis.KIND_CHAPTERS -> {
                val arr = obj.optJSONArray("chapters")
                val items = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                    val o = arr?.optJSONObject(i) ?: return@mapNotNull null
                    val start = o.optLong("startMs", 0L)
                    val title = o.optString("title")
                    if (title.isBlank()) null else start to title
                }
                if (items.isEmpty()) {
                    showEmpty()
                } else {
                    binding.recyclerView.layoutManager = LinearLayoutManager(this)
                    binding.recyclerView.isVisible = true
                    binding.recyclerView.adapter = ChapterAdapter().apply { submit(items) }
                }
            }
            AiVideoAnalysis.KIND_KEYFRAMES -> {
                val arr = obj.optJSONArray("paths")
                val paths = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                    arr?.optString(i)?.takeIf { it.isNotBlank() }
                }
                if (paths.isEmpty()) {
                    showEmpty()
                } else {
                    binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
                    binding.recyclerView.isVisible = true
                    binding.recyclerView.adapter = KeyFrameAdapter().apply { submit(paths) }
                }
            }
            AiVideoAnalysis.KIND_COVER -> {
                val path = obj.optString("coverPath")
                if (path.isBlank() || !File(path).exists()) {
                    showEmpty()
                } else {
                    binding.ivCover.isVisible = true
                    Glide.with(this).load(File(path)).into(binding.ivCover)
                }
            }
            else -> showEmpty()
        }
    }

    private fun showEmpty() {
        binding.scrollText.isVisible = false
        binding.recyclerView.isVisible = false
        binding.ivCover.isVisible = false
        binding.emptyView.isVisible = true
        binding.tvEmpty.setText(R.string.ai_video_analysis_empty)
    }

    private fun showAnalysisDialog() {
        if (videoPath.isBlank()) {
            toastOnUi(R.string.ai_video_analysis_missing_params)
            return
        }
        AiVideoAnalysisDialog.show(this, bookId, videoPath)
    }

    private fun labelOf(kind: String): String = when (kind) {
        AiVideoAnalysis.KIND_SUMMARY -> getString(R.string.ai_video_analysis_kind_summary)
        AiVideoAnalysis.KIND_SUBTITLE -> getString(R.string.ai_video_analysis_kind_subtitle)
        AiVideoAnalysis.KIND_CHAPTERS -> getString(R.string.ai_video_analysis_kind_chapters)
        AiVideoAnalysis.KIND_KEYFRAMES -> getString(R.string.ai_video_analysis_kind_keyframes)
        AiVideoAnalysis.KIND_COVER -> getString(R.string.ai_video_analysis_kind_cover)
        else -> ""
    }

    private fun formatStatus(row: AiVideoAnalysis): String {
        val time = DateUtils.getRelativeTimeSpanString(
            row.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS
        )
        val status = when (row.status) {
            AiVideoAnalysis.STATUS_SUCCESS -> getString(R.string.ai_video_status_success)
            AiVideoAnalysis.STATUS_FAILED -> getString(R.string.ai_video_status_failed)
            AiVideoAnalysis.STATUS_RUNNING -> getString(R.string.ai_video_status_running)
            AiVideoAnalysis.STATUS_PENDING -> getString(R.string.ai_video_status_pending)
            AiVideoAnalysis.STATUS_CANCELLED -> getString(R.string.ai_video_status_cancelled)
            else -> row.status
        }
        return "$status · $time"
    }

    /**
     * 章节列表适配器。
     */
    private inner class ChapterAdapter : RecyclerView.Adapter<ChapterAdapter.VH>() {
        private val items = mutableListOf<Pair<Long, String>>()

        fun submit(list: List<Pair<Long, String>>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemAiVideoChapterBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (ms, title) = items[position]
            holder.binding.tvStart.text = formatMs(ms)
            holder.binding.tvTitle.text = title
        }

        inner class VH(val binding: ItemAiVideoChapterBinding) : RecyclerView.ViewHolder(binding.root)
    }

    /**
     * 关键帧网格适配器。
     */
    private inner class KeyFrameAdapter : RecyclerView.Adapter<KeyFrameAdapter.VH>() {
        private val items = mutableListOf<String>()

        fun submit(list: List<String>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = ItemAiGeneratedImageBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(binding)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val path = items[position]
            Glide.with(holder.itemView).load(File(path)).into(holder.binding.ivImage)
        }

        inner class VH(val binding: ItemAiGeneratedImageBinding) : RecyclerView.ViewHolder(binding.root)
    }

    private fun formatMs(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val h = safe / 3_600_000L
        val m = (safe % 3_600_000L) / 60_000L
        val s = (safe % 60_000L) / 1_000L
        return if (h > 0) "%02d:%02d:%02d".format(h, m, s)
        else "%02d:%02d".format(m, s)
    }

    companion object {
        const val EXTRA_BOOK_ID = "bookId"
        const val EXTRA_VIDEO_PATH = "videoPath"
        const val EXTRA_KIND = "kind"
        const val EXTRA_LANGUAGE = "language"
    }
}
