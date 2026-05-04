package io.legado.app.ui.book.cache

import android.content.Context
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import io.legado.app.R
import io.legado.app.base.adapter.DiffRecyclerAdapter
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.databinding.ItemCacheChapterBinding

class CacheChapterAdapter(context: Context) :
    DiffRecyclerAdapter<CacheChapterItem, ItemCacheChapterBinding>(context) {

    override val diffItemCallback: DiffUtil.ItemCallback<CacheChapterItem> =
        object : DiffUtil.ItemCallback<CacheChapterItem>() {
            override fun areItemsTheSame(oldItem: CacheChapterItem, newItem: CacheChapterItem): Boolean {
                return oldItem.chapter.bookUrl == newItem.chapter.bookUrl &&
                    oldItem.chapter.url == newItem.chapter.url
            }

            override fun areContentsTheSame(oldItem: CacheChapterItem, newItem: CacheChapterItem): Boolean {
                return oldItem.chapter.title == newItem.chapter.title &&
                    oldItem.chapter.index == newItem.chapter.index &&
                    oldItem.cached == newItem.cached
            }
        }

    override fun getViewBinding(parent: ViewGroup): ItemCacheChapterBinding {
        return ItemCacheChapterBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemCacheChapterBinding,
        item: CacheChapterItem,
        payloads: MutableList<Any>
    ) = binding.run {
        tvTitle.text = "${item.chapter.index + 1}. ${item.chapter.title}"
        tvState.setText(if (item.cached) R.string.cache_manage_cached else R.string.cache_manage_not_cached)
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemCacheChapterBinding) {
    }
}
