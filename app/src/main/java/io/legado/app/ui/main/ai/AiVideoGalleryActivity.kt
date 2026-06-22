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
    private val adapter by lazy { Adapter() }
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
            Filter.FAILED to getString(R.string.ai_video_gallery_failed)
        ).forEach { (filter, label) ->
            val chip = Chip(this).apply {
                text = label
                isCheckable = true
                isChecked = filter == currentFilter
                setOnClickListener {
                    currentFilter = filter
                    refreshList()
                }
            }
            group.addView(chip)
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

    private fun startRefreshing() {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch {
            while (isActive) {
                delay(2000)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun refreshList() = lifecycleScope.launch {
        val items = withContext(Dispatchers.IO) {
            val baseList = when (currentFilter) {
                Filter.ALL -> AiVideoGalleryManager.listVideos()
                Filter.RUNNING -> AiVideoGalleryManager.listByStatus(AiGeneratedVideo.STATUS_RUNNING) +
                    AiVideoGalleryManager.listByStatus(AiGeneratedVideo.STATUS_PENDING)
                Filter.FAILED -> AiVideoGalleryManager.listByStatus(AiGeneratedVideo.STATUS_FAILED)
            }
            if (searchKeyword.isBlank()) baseList
            else baseList.filter { v ->
                v.prompt.contains(searchKeyword, ignoreCase = true) ||
                    v.name.contains(searchKeyword, ignoreCase = true) ||
                    v.bookName.contains(searchKeyword, ignoreCase = true) ||
                    v.characterName.contains(searchKeyword, ignoreCase = true)
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
        val items = mutableListOf(
            getString(R.string.ai_video_rename),
            if (video.favorite) getString(R.string.ai_video_cancel_favorite) else getString(R.string.ai_video_favorite_to)
        )
        if (video.status == AiGeneratedVideo.STATUS_FAILED) {
            items.add(getString(R.string.ai_video_retry))
        }
        items.add(getString(R.string.delete))
        alert(R.string.more) {
            items.forEachIndexed { index, label ->
                neutralButton(label) {
                    when (index) {
                        0 -> promptRename(video)
                        1 -> toggleFavorite(video)
                        2 -> retry(video)
                        3 -> confirmDelete(video)
                    }
                }
            }
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
                    AiVideoGalleryManager.rename(video.id, newName)
                    refreshList()
                }
            }
            cancelButton()
        }
    }

    private fun toggleFavorite(video: AiGeneratedVideo) {
        AiVideoGalleryManager.setFavorite(video.id, !video.favorite, null)
        refreshList()
    }

    private fun retry(video: AiGeneratedVideo) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                AiVideoGalleryManager.delete(video.id)
            }
            try {
                AiVideoService.submitAndStore(prompt = video.prompt, negativePrompt = video.negativePrompt)
                refreshList()
            } catch (e: Throwable) {
                toastOnUi(getString(R.string.ai_video_submit_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun confirmDelete(video: AiGeneratedVideo) {
        alert(R.string.ai_video_delete_confirm) {
            okButton { AiVideoGalleryManager.delete(video.id); refreshList() }
            cancelButton()
        }
    }

    private enum class Filter { ALL, RUNNING, FAILED }

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
