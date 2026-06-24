package io.legado.app.ui.main.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.entities.AiGeneratedVideo
import io.legado.app.data.entities.AiVideoGroup
import io.legado.app.databinding.ActivityAiVideoGalleryBinding
import io.legado.app.help.ai.AiCodecCompat
import io.legado.app.help.ai.AiVideoGalleryManager
import io.legado.app.help.ai.AiVideoGalleryManager.GalleryFilter
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiVideoGalleryActivity : BaseActivity<ActivityAiVideoGalleryBinding>() {

    override val binding by viewBinding(ActivityAiVideoGalleryBinding::inflate)

    private var fixedBookKey: String = ""
    private var videos by mutableStateOf<List<AiGeneratedVideo>>(emptyList())
    private var groups by mutableStateOf<List<AiVideoGroup>>(emptyList())
    private var currentFilter by mutableStateOf<GalleryFilter>(GalleryFilter.ALL)
    private var selectedIds by mutableStateOf<Set<String>>(emptySet())
    private var selectionMode by mutableStateOf(false)

    companion object {
        const val EXTRA_BOOK_KEY = "bookKey"
        const val EXTRA_TITLE = "title"

        fun start(context: Context, bookKey: String? = null, title: String? = null) {
            context.startActivity(Intent(context, AiVideoGalleryActivity::class.java).apply {
                bookKey?.let { putExtra(EXTRA_BOOK_KEY, it) }
                title?.let { putExtra(EXTRA_TITLE, it) }
            })
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        fixedBookKey = intent.getStringExtra(EXTRA_BOOK_KEY).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        if (fixedBookKey.isNotBlank()) {
            currentFilter = GalleryFilter.BOOK(fixedBookKey)
        }
        binding.titleBar.title = title.ifBlank { getString(R.string.ai_video_gallery) }

        binding.composeView.setContent {
            VideoGalleryScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                AiVideoGalleryManager.cleanupExpiredTemporary()
                val grps = AiVideoGalleryManager.listGroups()
                val vids = AiVideoGalleryManager.listVideos(currentFilter)
                grps to vids
            }
            groups = data.first
            videos = data.second
            val validIds = data.second.map { it.id }.toSet()
            selectedIds = selectedIds.intersect(validIds)
            if (selectedIds.isEmpty()) selectionMode = false
        }
    }

    @Composable
    private fun VideoGalleryScreen() {
        val palette = rememberAppDialogStyle().toMiuixPalette()
        Column(modifier = Modifier.fillMaxSize()) {
            FilterChips(palette)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(videos, key = { it.id }) { video ->
                    VideoGridItem(video, palette)
                }
            }
            if (selectionMode && selectedIds.isNotEmpty()) {
                BatchActionBar(palette)
            }
        }
    }

    @Composable
    private fun FilterChips(palette: LegadoMiuixPalette) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GalleryFilterChip("全部", currentFilter == GalleryFilter.ALL, palette) {
                currentFilter = GalleryFilter.ALL
                reload()
            }
            GalleryFilterChip("临时", currentFilter == GalleryFilter.TEMPORARY, palette) {
                currentFilter = GalleryFilter.TEMPORARY
                reload()
            }
            GalleryFilterChip("收藏", currentFilter == GalleryFilter.FAVORITE, palette) {
                currentFilter = GalleryFilter.FAVORITE
                reload()
            }
            if (fixedBookKey.isNotBlank()) {
                GalleryFilterChip(
                    "本书",
                    currentFilter is GalleryFilter.BOOK &&
                        (currentFilter as GalleryFilter.BOOK).bookKey == fixedBookKey,
                    palette
                ) {
                    currentFilter = GalleryFilter.BOOK(fixedBookKey)
                    reload()
                }
            }
            groups.forEach { group ->
                GalleryFilterChip(
                    group.name,
                    currentFilter is GalleryFilter.GROUP &&
                        (currentFilter as GalleryFilter.GROUP).groupId == group.id,
                    palette
                ) {
                    currentFilter = GalleryFilter.GROUP(group.id)
                    reload()
                }
            }
        }
    }

    @Composable
    private fun GalleryFilterChip(
        text: String,
        selected: Boolean,
        palette: LegadoMiuixPalette,
        onClick: () -> Unit
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (selected) palette.accent else palette.surfaceVariant,
            contentColor = if (selected) palette.onAccent else palette.primaryText,
            modifier = Modifier.combinedClickable(onClick = onClick)
        ) {
            Text(
                text = text,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }

    @Composable
    private fun VideoGridItem(video: AiGeneratedVideo, palette: LegadoMiuixPalette) {
        val isSelected = video.id in selectedIds
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (selectionMode) {
                            selectedIds = if (isSelected) selectedIds - video.id else selectedIds + video.id
                            if (selectedIds.isEmpty()) selectionMode = false
                        } else {
                            showVideoInfo(video)
                        }
                    },
                    onLongClick = {
                        selectionMode = true
                        selectedIds = selectedIds + video.id
                    }
                ),
            color = palette.surface,
            contentColor = palette.primaryText,
            cornerRadius = 14.dp
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(palette.surfaceVariant)
                ) {
                    if (video.thumbnailPath.isNotBlank()) {
                        AndroidView(
                            factory = { ctx ->
                                android.widget.ImageView(ctx).apply {
                                    scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                                    ImageLoader.load(ctx, video.thumbnailPath).into(this)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (video.duration > 0) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                        ) {
                            Text(
                                text = formatDuration(video.duration),
                                color = Color.White,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Surface(
                        color = if (video.favorite) palette.accent.copy(alpha = 0.85f)
                        else Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                    ) {
                        Text(
                            text = if (video.favorite) "收藏" else "临时",
                            color = Color.White,
                            fontSize = 10.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                    if (isSelected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = Color.White
                            )
                        }
                    }
                }
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = video.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = palette.primaryText
                    )
                    Text(
                        text = buildSubtitle(video),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = palette.secondaryText
                    )
                }
            }
        }
    }

    @Composable
    private fun BatchActionBar(palette: LegadoMiuixPalette) {
        Surface(
            color = palette.surface,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "已选 ${selectedIds.size}",
                    color = palette.primaryText,
                    fontSize = 14.sp
                )
                LegadoMiuixActionButton(
                    text = "全选",
                    palette = palette,
                    onClick = {
                        selectedIds = videos.map { it.id }.toSet()
                    }
                )
                LegadoMiuixActionButton(
                    text = "归组",
                    palette = palette,
                    onClick = {
                        showGroupSelector()
                    }
                )
                LegadoMiuixActionButton(
                    text = "删除",
                    palette = palette,
                    danger = true,
                    onClick = {
                        showDeleteConfirm()
                    }
                )
                LegadoMiuixActionButton(
                    text = "取消",
                    palette = palette,
                    onClick = {
                        selectionMode = false
                        selectedIds = emptySet()
                    }
                )
            }
        }
    }

    private fun showVideoInfo(video: AiGeneratedVideo) {
        lifecycleScope.launch {
            val codecDesc = withContext(Dispatchers.IO) {
                AiCodecCompat.describeCodec(AiCodecCompat.detectCodec(video.localPath))
            }
            alert(
                title = video.name,
                message = "时长: ${formatDuration(video.duration)}\n编码: $codecDesc\n" +
                    "Provider: ${video.providerName}\n模型: ${video.model}\n来源: ${video.sourceType}"
            ) {
                positiveButton("关闭")
                neutralButton(if (video.favorite) "取消收藏" else "收藏") {
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            AiVideoGalleryManager.setFavorite(video.id, !video.favorite, null)
                        }
                        reload()
                    }
                }
            }
        }
    }

    private fun showGroupSelector() {
        if (groups.isEmpty()) {
            toastOnUi("请先创建分组")
            return
        }
        val groupNames: List<CharSequence> = groups.map { it.name }
        selector("选择分组", groupNames) { _, index ->
            val group = groups[index]
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    AiVideoGalleryManager.moveVideosToGroup(selectedIds, group.id)
                }
                selectionMode = false
                selectedIds = emptySet()
                reload()
            }
        }
    }

    private fun showDeleteConfirm() {
        alert(title = "删除选中视频？") {
            okButton {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        AiVideoGalleryManager.deleteVideos(selectedIds)
                    }
                    selectionMode = false
                    selectedIds = emptySet()
                    reload()
                }
            }
            cancelButton()
        }
    }

    private fun buildSubtitle(video: AiGeneratedVideo): String {
        return buildString {
            if (video.bookName.isNotBlank()) append(video.bookName)
            if (video.chapterTitle.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(video.chapterTitle)
            }
            if (video.prompt.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(video.prompt.take(20))
            }
        }.ifBlank { video.providerName }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = totalSeconds / 60
        return if (minutes > 0) "${minutes}:${String.format("%02d", seconds)}"
        else "${seconds}s"
    }
}
