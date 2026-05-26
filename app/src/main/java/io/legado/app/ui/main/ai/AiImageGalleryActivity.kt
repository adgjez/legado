package io.legado.app.ui.main.ai

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.databinding.ActivityAiImageGalleryBinding
import io.legado.app.databinding.ItemAiGeneratedImageBinding
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.ai.AiImageGalleryManager.GalleryFilter
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiLabelStyle
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.utils.dpToPx
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiImageGalleryActivity : BaseActivity<ActivityAiImageGalleryBinding>() {

    override val binding by viewBinding(ActivityAiImageGalleryBinding::inflate)
    private val adapter by lazy { Adapter() }
    private var currentFilter: GalleryFilter = GalleryFilter.ALL

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = getString(R.string.ai_image_gallery)
        binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerView.adapter = adapter
        (binding.recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.recyclerView.setPadding(10.dpToPx(), 0, 10.dpToPx(), 16.dpToPx())
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                AiImageGalleryManager.cleanupExpiredTemporary()
                val groups = AiImageGalleryManager.listGroups()
                val images = AiImageGalleryManager.listImages(currentFilter)
                groups to images
            }
            renderFilters(data.first)
            adapter.setItems(data.second)
            binding.recyclerView.isVisible = data.second.isNotEmpty()
            binding.tvEmpty.isVisible = data.second.isEmpty()
        }
    }

    private fun renderFilters(groups: List<io.legado.app.data.entities.AiImageGroup>) {
        binding.filterContainer.removeAllViews()
        addFilterChip(getString(R.string.ai_image_gallery_all), currentFilter == GalleryFilter.ALL) {
            currentFilter = GalleryFilter.ALL
            reload()
        }
        addFilterChip(getString(R.string.ai_image_gallery_temporary), currentFilter == GalleryFilter.TEMPORARY) {
            currentFilter = GalleryFilter.TEMPORARY
            reload()
        }
        addFilterChip(getString(R.string.favorites), currentFilter == GalleryFilter.FAVORITE) {
            currentFilter = GalleryFilter.FAVORITE
            reload()
        }
        groups.forEach { group ->
            addFilterChip(group.name, currentFilter == GalleryFilter.GROUP(group.id)) {
                currentFilter = GalleryFilter.GROUP(group.id)
                reload()
            }
        }
    }

    private fun addFilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
        val chip = TextView(this).apply {
            this.text = text
            minHeight = 34.dpToPx()
            minWidth = 64.dpToPx()
            gravity = android.view.Gravity.CENTER
            setPadding(14.dpToPx(), 0, 14.dpToPx(), 0)
            setTextColor(if (selected) accentColor else secondaryTextColor)
            background = UiCorner.actionSelector(
                ContextCompat.getColor(this@AiImageGalleryActivity, if (selected) R.color.background_card else R.color.background_menu),
                ContextCompat.getColor(this@AiImageGalleryActivity, R.color.background_card),
                UiCorner.actionRadius(this@AiImageGalleryActivity)
            )
            setOnClickListener { onClick() }
        }
        binding.filterContainer.addView(chip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = 8.dpToPx()
        })
    }

    private inner class Adapter :
        RecyclerAdapter<AiGeneratedImage, ItemAiGeneratedImageBinding>(this@AiImageGalleryActivity) {

        override fun getViewBinding(parent: ViewGroup): ItemAiGeneratedImageBinding {
            return ItemAiGeneratedImageBinding.inflate(inflater, parent, false).apply {
                root.radius = UiCorner.scaledDp(14f)
                root.cardElevation = 0f
                root.setCardBackgroundColor(ContextCompat.getColor(root.context, R.color.background_card))
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemAiGeneratedImageBinding,
            item: AiGeneratedImage,
            payloads: MutableList<Any>
        ) = binding.run {
            ImageLoader.load(this@AiImageGalleryActivity, item.localPath)
                .error(R.drawable.image_loading_error)
                .into(ivImage)
            tvName.text = item.name
            tvPrompt.text = item.prompt
            tvState.text = if (item.favorite) getString(R.string.in_favorites) else getString(R.string.ai_image_gallery_temporary)
            tvName.applyUiSectionTitleStyle(this@AiImageGalleryActivity)
            tvPrompt.applyUiLabelStyle(this@AiImageGalleryActivity)
            tvState.applyUiLabelStyle(this@AiImageGalleryActivity)
            tvPrompt.setTextColor(secondaryTextColor)
            tvState.setTextColor(if (item.favorite) accentColor else ContextCompat.getColor(this@AiImageGalleryActivity, R.color.primaryText))
            tvState.background = UiCorner.actionSelector(
                ContextCompat.getColor(this@AiImageGalleryActivity, R.color.background_card),
                ContextCompat.getColor(this@AiImageGalleryActivity, R.color.background_menu),
                UiCorner.actionRadius(this@AiImageGalleryActivity)
            )
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemAiGeneratedImageBinding) {
            holder.itemView.setOnClickListener {
                getItem(holder.bindingAdapterPosition - getHeaderCount())?.let { image ->
                    val dialog = AiImagePreviewDialog(image.id).apply {
                        setOnDismissListener { reload() }
                    }
                    showDialogFragment(dialog)
                }
            }
        }
    }
}
