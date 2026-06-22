package io.legado.app.ui.main.ai

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.chip.Chip
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGeneratedVideo
import io.legado.app.databinding.ActivityAiVideoGalleryBinding
import io.legado.app.databinding.ItemAiGeneratedVideoBinding
import io.legado.app.help.ai.AiVideoGalleryManager
import io.legado.app.help.ai.AiVideoService
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.dialogs.alert
import io.legado.app.ui.video.VideoPlayerActivity
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 视频画廊
 */
class AiVideoGalleryActivity : BaseActivity<ActivityAiVideoGalleryBinding>() {

    override val binding by viewBinding(ActivityAiVideoGalleryBinding::inflate)
    private val adapter by lazy { Adapter(this@AiVideoGalleryActivity) }
    private var refreshJob: Job? = null
    private var currentFilter: Filter = Filter.ALL
    private var searchKeyword: String = ""

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.ai_video_gallery)
        binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerView.adapter = adapter
        setupChips()
        binding.etSearch.doAfterTextChanged { text ->
            searchKeyword = text?.toString().orEmpty()
            refreshList()
        }
        binding.fabGenerate.setOnClickListener {
            showGenerateDialog()
        }
        observeEvents()
        startRefreshing()
        refreshList()
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        super.onDestroy()
    }

    private fun setupChips() {
        val group = binding.chipGroupFilter
        group.removeAllViews()
        listOf(
            Filter.ALL to getString(R.string.ai_video_gallery_all),
            Filter.RUNNING to getString(R.string.ai_video_gallery_running),
            Filter.FAILED to getString(R.string.ai_video_gallery_failed),
            Filter.BY_BOOK to getString(R.string.ai_video_gallery_by_book)
        ).forEach { (filter, label) ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = filter == currentFilter
                setOnClickListener {
                    currentFilter = filter
                    if (filter == Filter.BY_BOOK) {
                        showBookPicker()
                    } else {
                        currentBookKey = null
                        refreshList()
                    }
                }
            }
            group.addView(chip)
        }
    }

    private fun showBookPicker() {
        lifecycleScope.launch {
            val books = withContext(Dispatchers.IO) {
                // 取所有有视频的书籍
                val allVideos = appDb.aiGeneratedVideoDao.all()
                allVideos.map { it.bookKey to it.bookName }
                    .filter { it.first.isNotBlank() }
                    .distinctBy { it.first }
            }
            if (books.isEmpty()) {
                toastOnUi(R.string.ai_video_no_book_videos)
                currentFilter = Filter.ALL
                setupChips()
                refreshList()
                return@launch
            }
            val labels = books.map { it.second }
            alert(R.string.ai_video_select_book) {
                labels.forEachIndexed { index, name ->
                    neutralButton(name) {
                        currentBookKey = books[index].first
                        refreshList()
                    }
                }
                cancelButton {
                    currentFilter = Filter.ALL
                    setupChips()
                    refreshList()
                }
            }
        }
    }

    private fun observeEvents() {
        LiveEventBus.get<String>(EventBus.AI_VIDEO_PROGRESS).observe(this) {
            // 仅做提示
            adapter.notifyDataSetChanged()
        }
        LiveEventBus.get<String>(EventBus.AI_VIDEO_COMPLETED).observe(this) {
            toastOnUi(R.string.ai_video_status_success)
            refreshList()
        }
        LiveEventBus.get<String>(EventBus.AI_VIDEO_FAILED).observe(this) {
            toastOnUi(R.string.ai_video_status_failed)
            refreshList()
        }
    }

    /**
     * 自适应刷新：空闲时降低频率（10s），有进行中任务时 2s 刷新一次。
     * LiveEventBus 事件触发时会额外立即刷新一次，避免漏更新。
     */
    private fun startRefreshing() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                refreshList()
                val active = withContext(Dispatchers.IO) {
                    appDb.aiGeneratedVideoDao.countByStatus(AiGeneratedVideo.STATUS_PENDING) +
                        appDb.aiGeneratedVideoDao.countByStatus(AiGeneratedVideo.STATUS_RUNNING)
                }
                delay(if (active > 0) 2_000L else 10_000L)
            }
        }
    }

    private fun refreshList() = lifecycleScope.launch {
        val items = withContext(Dispatchers.IO) {
            val baseList = when (currentFilter) {
                Filter.ALL -> AiVideoGalleryManager.listVideosWithCleanup()
                Filter.RUNNING -> AiVideoGalleryManager.listByStatus(AiGeneratedVideo.STATUS_RUNNING) +
                    AiVideoGalleryManager.listByStatus(AiGeneratedVideo.STATUS_PENDING)
                Filter.FAILED -> AiVideoGalleryManager.listByStatus(AiGeneratedVideo.STATUS_FAILED)
                Filter.BY_BOOK -> {
                    val key = currentBookKey
                    if (key.isNullOrBlank()) emptyList()
                    else appDb.aiGeneratedVideoDao.byBook(key).sortedBy { it.chapterIndex }
                }
            }
            if (searchKeyword.isBlank()) baseList
            else baseList.filter { v ->
                v.prompt.contains(searchKeyword, ignoreCase = true) ||
                    v.name.contains(searchKeyword, ignoreCase = true) ||
                    v.bookName.contains(searchKeyword, ignoreCase = true) ||
                    v.characterName.contains(searchKeyword, ignoreCase = true) ||
                    v.chapterTitle.contains(searchKeyword, ignoreCase = true)
            }
        }
        adapter.setItems(items)
        binding.tvEmpty.isVisible = items.isEmpty()
    }

    private fun showGenerateDialog() {
        if (AppConfig.aiCurrentVideoProvider == null) {
            toastOnUi(R.string.ai_video_no_provider)
            return
        }
        showDialogFragment(AiVideoGenerateDialog())
    }

    private fun openPlayer(video: AiGeneratedVideo) {
        val intent = Intent(this, VideoPlayerActivity::class.java)
        if (video.localPath.isNotBlank()) {
            intent.putExtra("videoUrl", "file://${video.localPath}")
        } else if (video.remoteUrl.isNotBlank()) {
            intent.putExtra("videoUrl", video.remoteUrl)
        } else {
            toastOnUi(R.string.ai_video_status_failed)
            return
        }
        intent.putExtra("videoName", video.name)
        startActivity(intent)
    }

    private fun showItemMenu(video: AiGeneratedVideo) {
        // 用闭包列表避免 when(index) 错位（retry 项不一定存在）。
        data class Item(val label: String, val action: () -> Unit)
        val items = mutableListOf<Item>(
            Item(getString(R.string.ai_video_rename)) { promptRename(video) },
            Item(
                if (video.favorite) getString(R.string.ai_video_cancel_favorite)
                else getString(R.string.ai_video_favorite_to)
            ) { toggleFavorite(video) }
        )
        if (video.status == AiGeneratedVideo.STATUS_FAILED) {
            items.add(Item(getString(R.string.ai_video_retry)) { retry(video) })
        }
        items.add(Item(getString(R.string.delete)) { confirmDelete(video) })
        alert(R.string.more) {
            items.forEach { item -> neutralButton(item.label) { item.action() } }
        }
    }

    private fun promptRename(video: AiGeneratedVideo) {
        val binding = io.legado.app.databinding.DialogEditTextBinding.inflate(layoutInflater)
        binding.editView.setText(video.name)
        alert(R.string.ai_video_rename) {
            customView { binding.root }
            okButton {
                val newName = binding.editView.text?.toString().orEmpty().trim()
                if (newName.isNotBlank()) {
                    // rename 内部为 Room UPDATE，需 IO 线程。
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            AiVideoGalleryManager.rename(video.id, newName)
                        }
                        refreshList()
                    }
                }
            }
            cancelButton()
        }
    }

    private fun toggleFavorite(video: AiGeneratedVideo) {
        // setFavorite 内部为 Room UPDATE，需 IO 线程。
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AiVideoGalleryManager.setFavorite(video.id, !video.favorite, null)
            }
            refreshList()
        }
    }

    private fun retry(video: AiGeneratedVideo) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AiVideoGalleryManager.delete(video.id)
            }
            try {
                val metadata = AiVideoGalleryManager.VideoMetadata(
                    bookName = video.bookName,
                    bookAuthor = video.bookAuthor,
                    chapterIndex = video.chapterIndex,
                    chapterTitle = video.chapterTitle,
                    characterId = video.characterId,
                    characterName = video.characterName,
                    sourceType = video.sourceType,
                    sourceText = video.sourceText
                )
                AiVideoService.submitAndStore(
                    prompt = video.prompt,
                    negativePrompt = video.negativePrompt,
                    firstFrame = video.firstFrame?.takeIf { it.isNotBlank() },
                    durationSec = video.durationSec,
                    aspectRatio = video.aspectRatio,
                    metadata = metadata
                )
                refreshList()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                toastOnUi(getString(R.string.ai_video_submit_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun confirmDelete(video: AiGeneratedVideo) {
        alert(R.string.ai_video_delete_confirm) {
            okButton {
                // delete 内部包含 Room 写操作 + 文件 I/O，必须切到 IO 线程。
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AiVideoGalleryManager.delete(video.id)
                    }
                    refreshList()
                }
            }
            cancelButton()
        }
    }

    private enum class Filter { ALL, RUNNING, FAILED, BY_BOOK }

    private var currentBookKey: String? = null

    private inner class Adapter(context: android.content.Context) :
        RecyclerAdapter<AiGeneratedVideo, ItemAiGeneratedVideoBinding>(context) {
        override fun getViewBinding(parent: ViewGroup): ItemAiGeneratedVideoBinding {
            return ItemAiGeneratedVideoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemAiGeneratedVideoBinding) {
            holder.itemView.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    getItem(pos)?.let { openPlayer(it) }
                }
            }
            holder.itemView.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                    getItem(pos)?.let { showItemMenu(it) }
                }
                true
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemAiGeneratedVideoBinding,
            item: AiGeneratedVideo,
            payloads: MutableList<Any>
        ) {
            binding.tvName.text = item.name
            // 显示书籍/章节信息
            val subtitle = buildString {
                if (item.bookName.isNotBlank()) {
                    append(item.bookName)
                }
                if (item.chapterIndex >= 0 && item.chapterTitle.isNotBlank()) {
                    if (isNotEmpty()) append(" · ")
                    append("第${item.chapterIndex + 1}章 ${item.chapterTitle}")
                }
            }
            binding.tvBookChapter.text = subtitle
            binding.tvBookChapter.isVisible = subtitle.isNotBlank()
            if (item.coverPath.isNotBlank()) {
                io.legado.app.help.glide.ImageLoader.load(
                    holder.itemView.context,
                    java.io.File(item.coverPath)
                ).into(binding.ivCover)
            } else {
                binding.ivCover.setImageResource(R.drawable.ic_image)
            }
            val statusLabel = when (item.status) {
                AiGeneratedVideo.STATUS_PENDING -> getString(R.string.ai_video_status_pending)
                AiGeneratedVideo.STATUS_RUNNING -> getString(R.string.ai_video_status_running) + " " + item.progress + "%"
                AiGeneratedVideo.STATUS_SUCCESS -> getString(R.string.ai_video_status_success)
                AiGeneratedVideo.STATUS_FAILED -> getString(R.string.ai_video_status_failed) +
                    if (item.failReason.isNotBlank()) " (${item.failReason})" else ""
                AiGeneratedVideo.STATUS_CANCELLED -> getString(R.string.ai_video_status_cancelled)
                else -> item.status
            }
            binding.tvStatus.text = statusLabel
            binding.tvStatus.isVisible = true
            binding.tvDuration.text = if (item.durationMs > 0) {
                DateUtils.formatElapsedTime(item.durationMs / 1000)
            } else ""
            binding.tvDuration.isVisible = item.durationMs > 0
            binding.ivPlayOverlay.isVisible = item.status == AiGeneratedVideo.STATUS_SUCCESS
        }
    }
}
