package io.legado.app.ui.main.ai

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGeneratedAudio
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.data.entities.AiGeneratedVideo
import io.legado.app.databinding.ActivityAiImageGalleryBinding
import io.legado.app.help.ai.AiAudioGalleryManager
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.ai.AiImageGalleryManager.GalleryFilter as ImageGalleryFilter
import io.legado.app.help.ai.AiVideoGalleryManager
import io.legado.app.help.ai.AiVideoGalleryManager.GalleryFilter as VideoGalleryFilter
import io.legado.app.help.glide.ImageLoader
import io.legado.app.lib.dialogs.alert
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

/**
 * 统一的 AI 创作库页面。
 *
 * 复用 [ActivityAiImageGalleryBinding]（其布局内含 TitleBar 但无 ComposeView），
 * 因此保留 TitleBar 用于展示标题“AI 创作库”，并隐藏布局中其余仅服务于图片画廊的视图，
 * 再动态追加一个 [ComposeView] 承载三个 Tab（图片 / 视频 / 音频）的内容。
 */
class AiUnifiedGalleryActivity : BaseActivity<ActivityAiImageGalleryBinding>() {

    override val binding by viewBinding(ActivityAiImageGalleryBinding::inflate)

    private var images by mutableStateOf<List<AiGeneratedImage>>(emptyList())
    private var videos by mutableStateOf<List<AiGeneratedVideo>>(emptyList())
    private var audios by mutableStateOf<List<AiGeneratedAudio>>(emptyList())
    private var selectedTab by mutableStateOf(0)

    private var mediaPlayer: MediaPlayer? = null
    private var playingAudioId by mutableStateOf<String?>(null)

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, AiUnifiedGalleryActivity::class.java))
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.title = "AI 创作库"
        // 该 Binding 没有 ComposeView，隐藏仅服务于图片画廊的视图，再用 ComposeView 承载内容
        binding.etSearch.isVisible = false
        binding.filterContainer.isVisible = false
        binding.recyclerView.isVisible = false
        binding.batchBar.isVisible = false
        binding.tvEmpty.isVisible = false
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                UnifiedGalleryScreen()
            }
        }
        (binding.root as ViewGroup).addView(
            composeView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            ).apply { weight = 1f }
        )
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudio()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun reload() {
        lifecycleScope.launch {
            val data = withContext(Dispatchers.IO) {
                AiImageGalleryManager.cleanupExpiredTemporary()
                AiVideoGalleryManager.cleanupExpiredTemporary()
                val imgs = AiImageGalleryManager.listImages(ImageGalleryFilter.ALL)
                val vids = AiVideoGalleryManager.listVideos(VideoGalleryFilter.ALL)
                // AiAudioGalleryManager 暂未提供列表查询，直接走 DAO
                val auds = appDb.aiGeneratedAudioDao.all()
                Triple(imgs, vids, auds)
            }
            images = data.first
            videos = data.second
            audios = data.third
            if (playingAudioId != null && audios.none { it.id == playingAudioId }) {
                stopAudio()
            }
        }
    }

    @Composable
    private fun UnifiedGalleryScreen() {
        val palette = rememberAppDialogStyle().toMiuixPalette()
        Column(modifier = Modifier.fillMaxSize()) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = palette.surface,
                contentColor = palette.accent
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { stopAudio(); selectedTab = 0 },
                    text = { Text("图片") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { stopAudio(); selectedTab = 1 },
                    text = { Text("视频") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { stopAudio(); selectedTab = 2 },
                    text = { Text("音频") }
                )
            }
            when (selectedTab) {
                0 -> ImageTab(palette)
                1 -> VideoTab(palette)
                else -> AudioTab(palette)
            }
        }
    }

    // region 图片 Tab

    @Composable
    private fun ImageTab(palette: LegadoMiuixPalette) {
        if (images.isEmpty()) {
            EmptyHint("暂无AI图片", palette)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(images, key = { it.id }) { image ->
                    ImageGridItem(image, palette)
                }
            }
        }
    }

    @Composable
    private fun ImageGridItem(image: AiGeneratedImage, palette: LegadoMiuixPalette) {
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { showImageInfo(image) }),
            color = palette.surface,
            contentColor = palette.primaryText,
            cornerRadius = 14.dp
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .background(palette.surfaceVariant)
            ) {
                AndroidView(
                    factory = { ctx ->
                        android.widget.ImageView(ctx).apply {
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            ImageLoader.load(ctx, image.localPath).into(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                Surface(
                    color = if (image.favorite) palette.accent.copy(alpha = 0.85f)
                    else Color.Black.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                ) {
                    Text(
                        text = if (image.favorite) "收藏" else "临时",
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = image.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.primaryText
                )
                Text(
                    text = buildImageSubtitle(image),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.secondaryText
                )
            }
        }
    }

    private fun showImageInfo(image: AiGeneratedImage) {
        alert(
            title = image.name,
            message = "Provider: ${image.providerName}\n模型: ${image.model}\n来源: ${image.sourceType}"
        ) {
            positiveButton("关闭")
        }
    }

    private fun buildImageSubtitle(item: AiGeneratedImage): String {
        return buildList {
            if (item.bookName.isNotBlank()) add(item.bookName)
            if (item.chapterTitle.isNotBlank()) add(item.chapterTitle)
            if (item.characterName.isNotBlank()) add(item.characterName)
            add(item.prompt.replace(Regex("\\s+"), " ").take(40))
        }.joinToString(" · ")
    }

    // endregion

    // region 视频 Tab

    @Composable
    private fun VideoTab(palette: LegadoMiuixPalette) {
        if (videos.isEmpty()) {
            EmptyHint("暂无AI视频", palette)
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(videos, key = { it.id }) { video ->
                    VideoGridItem(video, palette)
                }
            }
        }
    }

    @Composable
    private fun VideoGridItem(video: AiGeneratedVideo, palette: LegadoMiuixPalette) {
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = { showVideoInfo(video) }),
            color = palette.surface,
            contentColor = palette.primaryText,
            cornerRadius = 14.dp
        ) {
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
                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
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
                    text = buildVideoSubtitle(video),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.secondaryText
                )
            }
        }
    }

    private fun showVideoInfo(video: AiGeneratedVideo) {
        alert(
            title = video.name,
            message = "时长: ${formatDuration(video.duration)}\n" +
                "Provider: ${video.providerName}\n模型: ${video.model}\n来源: ${video.sourceType}"
        ) {
            positiveButton("关闭")
        }
    }

    private fun buildVideoSubtitle(video: AiGeneratedVideo): String {
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

    // endregion

    // region 音频 Tab

    @Composable
    private fun AudioTab(palette: LegadoMiuixPalette) {
        if (audios.isEmpty()) {
            EmptyHint("暂无AI音频", palette)
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(audios, key = { it.id }) { audio ->
                    AudioListItem(audio, palette)
                }
            }
        }
    }

    @Composable
    private fun AudioListItem(audio: AiGeneratedAudio, palette: LegadoMiuixPalette) {
        val isPlaying = playingAudioId == audio.id
        LegadoMiuixCard(
            modifier = Modifier.fillMaxWidth(),
            color = palette.surface,
            contentColor = palette.primaryText,
            cornerRadius = 14.dp,
            insidePadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = audio.name,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = palette.primaryText
                    )
                    Text(
                        text = buildAudioSubtitle(audio),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = palette.secondaryText
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = if (isPlaying) "停止" else "播放",
                    palette = palette,
                    primary = !isPlaying,
                    onClick = { toggleAudio(audio) }
                )
            }
        }
    }

    private fun toggleAudio(audio: AiGeneratedAudio) {
        if (playingAudioId == audio.id) {
            stopAudio()
            return
        }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                AiAudioGalleryManager.resolveAudioFile(AiAudioGalleryManager.audioUri(audio.id))
            }
            if (file == null || !file.isFile) {
                toastOnUi("音频文件不存在")
                return@launch
            }
            try {
                mediaPlayer?.release()
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnPreparedListener { mp -> runCatching { mp.start() } }
                    setOnCompletionListener { stopAudio() }
                    setOnErrorListener { _, _, _ ->
                        stopAudio()
                        true
                    }
                    prepareAsync()
                }
                playingAudioId = audio.id
            } catch (e: Exception) {
                AppLog.put("AI 音频播放失败", e)
                toastOnUi("播放失败")
                stopAudio()
            }
        }
    }

    private fun stopAudio() {
        mediaPlayer?.let { player ->
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.reset() }
        }
        playingAudioId = null
    }

    private fun buildAudioSubtitle(audio: AiGeneratedAudio): String {
        return buildString {
            if (audio.bookName.isNotBlank()) append(audio.bookName)
            if (audio.audioType.isNotBlank()) {
                if (isNotEmpty()) append(" · ")
                append(audioTypeLabel(audio.audioType))
            }
            if (audio.duration > 0) {
                if (isNotEmpty()) append(" · ")
                append(formatDuration(audio.duration))
            }
        }.ifBlank { audio.providerName }
    }

    private fun audioTypeLabel(type: String): String = when (type) {
        "music" -> "音乐"
        "sfx" -> "音效"
        "speech" -> "语音"
        else -> type
    }

    // endregion

    @Composable
    private fun EmptyHint(text: String, palette: LegadoMiuixPalette) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = palette.secondaryText,
                fontSize = 14.sp
            )
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val seconds = totalSeconds % 60
        val minutes = totalSeconds / 60
        return if (minutes > 0) "${minutes}:${String.format("%02d", seconds)}"
        else "${seconds}s"
    }
}
