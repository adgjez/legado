package io.legado.app.ui.main.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.data.appDb
import io.legado.app.help.ai.AiImageGalleryManager
import io.legado.app.help.ai.AiVideoGalleryManager
import io.legado.app.help.glide.ImageLoader
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 统一的 AI 二创条目：图片或视频，按创建时间倒序排列。
 */
private data class AiCreationItem(
    val id: String,
    val isVideo: Boolean,
    val thumbnailPath: String,
    val name: String,
    val createdAt: Long,
    val uri: String
)

/**
 * 在书籍详情页展示的横向 AI 二创内容条。
 *
 * 加载 [bookKey] 关联的 AI 图片与视频，按创建时间倒序横向滚动展示。
 * - 图片直接显示缩略图；
 * - 视频缩略图上叠加播放图标；
 * - 无内容时展示占位文案“暂无AI二创内容”；
 * - 点击条目通过 [onItemClick] 回调对应的资源 URI（ai-image://id 或 ai-video://id）。
 */
@Composable
fun AiBookCreationStrip(bookKey: String, onItemClick: (String) -> Unit) {
    val palette = rememberAppDialogStyle().toMiuixPalette()
    var items by remember { mutableStateOf<List<AiCreationItem>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(bookKey) {
        loaded = false
        if (bookKey.isBlank()) {
            items = emptyList()
            loaded = true
            return@LaunchedEffect
        }
        val data = withContext(Dispatchers.IO) {
            val images = appDb.aiGeneratedImageDao.byBook(bookKey)
            val videos = appDb.aiGeneratedVideoDao.byBook(bookKey)
            val imageItems = images.map { image ->
                AiCreationItem(
                    id = image.id,
                    isVideo = false,
                    thumbnailPath = image.localPath,
                    name = image.name,
                    createdAt = image.createdAt,
                    uri = AiImageGalleryManager.imageUri(image.id)
                )
            }
            val videoItems = videos.map { video ->
                AiCreationItem(
                    id = video.id,
                    isVideo = true,
                    // 视频优先使用缩略图，缺失时回退到视频文件本身（Glide 可抽取首帧）
                    thumbnailPath = video.thumbnailPath.ifBlank { video.localPath },
                    name = video.name,
                    createdAt = video.createdAt,
                    uri = AiVideoGalleryManager.videoUri(video.id)
                )
            }
            (imageItems + videoItems).sortedByDescending { it.createdAt }
        }
        items = data
        loaded = true
    }

    when {
        items.isNotEmpty() -> {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(items, key = { it.id }) { item ->
                    CreationThumb(item, palette, onItemClick)
                }
            }
        }

        loaded -> {
            Text(
                text = "暂无AI二创内容",
                color = palette.secondaryText,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 20.dp)
            )
        }

        else -> {
            // 首次加载中，暂不展示内容，避免占位文案闪烁
        }
    }
}

@Composable
private fun CreationThumb(
    item: AiCreationItem,
    palette: LegadoMiuixPalette,
    onItemClick: (String) -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier
            .size(width = 96.dp, height = 128.dp)
            .clickable { onItemClick(item.uri) },
        color = palette.surfaceVariant,
        contentColor = palette.primaryText,
        cornerRadius = 12.dp
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (item.thumbnailPath.isNotBlank()) {
                AndroidView(
                    factory = { ctx ->
                        android.widget.ImageView(ctx).apply {
                            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                            ImageLoader.load(ctx, item.thumbnailPath).into(this)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (item.isVideo) {
                Surface(
                    color = Color.Black.copy(alpha = 0.45f),
                    shape = CircleShape,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(32.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}
