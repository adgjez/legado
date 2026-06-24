package io.legado.app.ui.main.ai

import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiStoryPlaylist
import io.legado.app.data.entities.AiStoryScene
import io.legado.app.help.ai.AiStoryPipeline
import io.legado.app.help.glide.ImageLoader
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AiStoryResultScreen(playlistId: String, onPlayAll: () -> Unit, onClose: () -> Unit) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val scope = rememberCoroutineScope()

    var playlist by remember { mutableStateOf<AiStoryPlaylist?>(null) }
    var scenes by remember { mutableStateOf<List<AiStoryScene>>(emptyList()) }
    var imagePaths by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var videoThumbnails by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }

    suspend fun loadData() {
        val data = withContext(Dispatchers.IO) {
            val pl = appDb.aiStoryPlaylistDao.get(playlistId)
            val sc = appDb.aiStorySceneDao.byPlaylist(playlistId)
            val imgPaths = mutableMapOf<String, String>()
            val vidThumbs = mutableMapOf<String, String>()
            sc.forEach { scene ->
                if (scene.imageId.isNotBlank()) {
                    appDb.aiGeneratedImageDao.get(scene.imageId)?.let { img ->
                        imgPaths[scene.id] = img.localPath
                    }
                }
                if (scene.videoId.isNotBlank()) {
                    appDb.aiGeneratedVideoDao.get(scene.videoId)?.let { vid ->
                        vidThumbs[scene.id] = vid.thumbnailPath.ifBlank { vid.localPath }
                    }
                }
            }
            StoryResultData(pl, sc, imgPaths, vidThumbs)
        }
        playlist = data.playlist
        scenes = data.scenes
        imagePaths = data.imagePaths
        videoThumbnails = data.videoThumbnails
        loading = false
    }

    LaunchedEffect(playlistId) {
        loading = true
        loadData()
        refreshKey++
    }

    // Auto-refresh while scenes are still generating, using scope.launch
    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) {
            val stillGenerating =
                scenes.any { it.status !in setOf("video_done", "failed") }
            if (stillGenerating) {
                delay(3000)
                scope.launch {
                    loadData()
                    refreshKey++
                }
            }
        }
    }

    val totalScenes = scenes.size
    val doneScenes = scenes.count { it.status == "video_done" }
    val failedScenes = scenes.count { it.status == "failed" }
    val isGenerating =
        scenes.any { it.status !in setOf("video_done", "failed") } &&
            playlist?.status == "processing"

    val progress = if (totalScenes == 0) {
        AiStoryPipeline.PipelineProgress("planning", 0, 0, "加载中...")
    } else if (isGenerating) {
        AiStoryPipeline.PipelineProgress(
            "generating",
            doneScenes,
            totalScenes,
            "生成中 $doneScenes/$totalScenes"
        )
    } else if (failedScenes > 0 && doneScenes < totalScenes) {
        AiStoryPipeline.PipelineProgress(
            "failed",
            doneScenes,
            totalScenes,
            "部分场景生成失败 ($failedScenes 个失败)"
        )
    } else {
        AiStoryPipeline.PipelineProgress("done", totalScenes, totalScenes, "生成完成")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ResultTopBar(onClose = onClose)

        if (loading && playlist == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = palette.accent,
                    strokeWidth = 2.5.dp
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                PlaylistInfoCard(playlist, palette)
            }
            if (isGenerating || progress.stage == "failed") {
                item {
                    ProgressCard(progress, palette)
                }
            }
            items(scenes, key = { it.id }) { scene ->
                SceneCard(
                    scene = scene,
                    imagePath = imagePaths[scene.id].orEmpty(),
                    videoThumbnail = videoThumbnails[scene.id].orEmpty(),
                    palette = palette
                )
            }
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        LegadoMiuixActionButton(
            text = "播放全部",
            palette = palette,
            onClick = onPlayAll,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            primary = true,
            cornerRadius = style.actionRadius,
            minHeight = 46.dp
        )
    }
}

private data class StoryResultData(
    val playlist: AiStoryPlaylist?,
    val scenes: List<AiStoryScene>,
    val imagePaths: Map<String, String>,
    val videoThumbnails: Map<String, String>
)

@Composable
private fun ResultTopBar(onClose: () -> Unit) {
    val style = rememberAppDialogStyle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier
                .size(42.dp)
                .clickable(onClick = onClose),
            shape = RoundedCornerShape(style.actionRadius),
            color = Color.Transparent,
            contentColor = style.primaryText,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = style.primaryText,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            text = "分镜结果",
            color = style.primaryText,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = style.titleFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(42.dp))
    }
}

@Composable
private fun PlaylistInfoCard(
    playlist: AiStoryPlaylist?,
    palette: LegadoMiuixPalette
) {
    val style = rememberAppDialogStyle()
    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = palette.surface,
        contentColor = palette.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = playlist?.bookName?.ifBlank { "未知书名" } ?: "未知书名",
                color = palette.primaryText,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!playlist?.chapterTitle.isNullOrBlank()) {
                Text(
                    text = playlist!!.chapterTitle,
                    color = palette.secondaryText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                InfoChip("场景数", "${playlist?.sceneCount ?: 0}", palette)
                InfoChip("总时长", formatDuration(playlist?.totalDuration ?: 0L), palette)
                InfoChip("状态", playlistStatusText(playlist?.status), palette)
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String, palette: LegadoMiuixPalette) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = palette.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                color = palette.secondaryText,
                fontSize = 11.sp
            )
            Text(
                text = value,
                color = palette.primaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ProgressCard(
    progress: AiStoryPipeline.PipelineProgress,
    palette: LegadoMiuixPalette
) {
    val style = rememberAppDialogStyle()
    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = palette.surface,
        contentColor = palette.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (progress.stage != "done" && progress.stage != "failed") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = palette.accent,
                        strokeWidth = 2.dp
                    )
                }
                Text(
                    text = progress.message,
                    color = if (progress.stage == "failed") palette.danger else palette.primaryText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = {
                        (progress.current.toFloat() / progress.total).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (progress.stage == "failed") palette.danger else palette.accent,
                    trackColor = palette.surfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SceneCard(
    scene: AiStoryScene,
    imagePath: String,
    videoThumbnail: String,
    palette: LegadoMiuixPalette
) {
    val style = rememberAppDialogStyle()
    var promptExpanded by remember { mutableStateOf(false) }

    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = palette.surface,
        contentColor = palette.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "场景 ${scene.sceneIndex + 1}",
                    color = palette.accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                SceneStatusBadge(scene.status, palette)
            }

            if (scene.narrativeText.isNotBlank()) {
                Text(
                    text = scene.narrativeText,
                    color = palette.primaryText,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (scene.visualPrompt.isNotBlank()) {
                VisualPromptSection(
                    prompt = scene.visualPrompt,
                    expanded = promptExpanded,
                    onToggle = { promptExpanded = !promptExpanded },
                    palette = palette
                )
            }

            if (imagePath.isNotBlank()) {
                ThumbnailImage(imagePath, palette)
            }

            if (videoThumbnail.isNotBlank()) {
                VideoThumbnail(videoThumbnail, palette)
            }

            if (scene.error.isNotBlank() && scene.status == "failed") {
                Text(
                    text = "错误: ${scene.error}",
                    color = palette.danger,
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun VisualPromptSection(
    prompt: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    palette: LegadoMiuixPalette
) {
    val style = rememberAppDialogStyle()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "画面提示",
                color = palette.secondaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                painter = painterResource(id = R.drawable.ic_expand_more),
                contentDescription = null,
                tint = palette.secondaryText,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = if (expanded) 180f else 0f }
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Surface(
                shape = RoundedCornerShape(style.actionRadius),
                color = palette.surfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Text(
                    text = prompt,
                    color = palette.secondaryText,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun ThumbnailImage(path: String, palette: LegadoMiuixPalette) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = palette.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        AndroidView(
            factory = { ctx ->
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }
            },
            update = { imageView ->
                ImageLoader.load(imageView.context, path).into(imageView)
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun VideoThumbnail(path: String, palette: LegadoMiuixPalette) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = palette.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                update = { imageView ->
                    ImageLoader.load(imageView.context, path).into(imageView)
                },
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "播放",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SceneStatusBadge(status: String, palette: LegadoMiuixPalette) {
    val (text, color) = when (status) {
        "pending" -> "等待中" to palette.secondaryText
        "image_generating" -> "生成图片中" to palette.accent
        "image_done" -> "图片完成" to palette.accent
        "video_generating" -> "生成视频中" to palette.accent
        "video_done" -> "完成" to palette.accent
        "failed" -> "失败" to palette.danger
        else -> status to palette.secondaryText
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Text(
            text = text,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

private fun playlistStatusText(status: String?): String {
    return when (status) {
        "pending" -> "等待中"
        "processing" -> "生成中"
        "done" -> "完成"
        "failed" -> "失败"
        else -> "未知"
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val seconds = totalSeconds % 60
    val minutes = totalSeconds / 60
    val hours = minutes / 60
    return when {
        hours > 0 -> "${hours}:${String.format("%02d", minutes % 60)}:${String.format("%02d", seconds)}"
        minutes > 0 -> "${minutes}:${String.format("%02d", seconds)}"
        else -> "${seconds}s"
    }
}
