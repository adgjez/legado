package io.legado.app.ui.main.ai.arcreel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.legado.app.help.ai.*
import io.legado.app.ui.main.ai.compose.AiComposeStyle
import io.legado.app.ui.main.ai.compose.aiComposeStyle
import kotlinx.coroutines.launch

/**
 * ArcReel 分镜查看器 — 展示完整的剧本分镜，包含场景卡片和插画
 */
@Composable
fun ArcReelStoryboardScreen(
    chapter: ChapterStoryboard,
    onBack: () -> Unit,
    onGenerateSceneImage: (suspend (Int) -> String?)? = null,
    onGenerateVideo: (suspend (Int) -> Unit)? = null
) {
    val context = LocalContext.current
    val style = aiComposeStyle(context)
    var selectedSceneIndex by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var sceneImages by remember { mutableStateOf(chapter.sceneImages) }
    var generatingScene by remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(style.colors.pageBackground)
            .statusBarsPadding()
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Text("←", fontSize = 20.sp, color = style.colors.primaryText)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    chapter.chapterTitle,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = style.colors.primaryText
                )
                Text(
                    "${chapter.result.scenes.size} 个场景",
                    fontSize = 13.sp,
                    color = style.colors.secondaryText
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 剧本摘要
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(style.metrics.cardRadius),
                    colors = CardDefaults.cardColors(containerColor = style.colors.accent.copy(alpha = 0.08f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("剧本摘要", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = style.colors.accent)
                        Spacer(Modifier.height(4.dp))
                        Text(chapter.result.summary, fontSize = 14.sp, color = style.colors.primaryText)
                    }
                }
            }

            // 场景列表
            itemsIndexed(chapter.result.scenes) { index, scene ->
                SceneDetailCard(
                    scene = scene,
                    sceneImageUrl = sceneImages[scene.sceneId],
                    isGenerating = generatingScene == scene.sceneId,
                    style = style,
                    onGenerateImage = {
                        if (onGenerateSceneImage != null) {
                            generatingScene = scene.sceneId
                            scope.launch {
                                val url = onGenerateSceneImage(scene.sceneId)
                                if (url != null) {
                                    sceneImages = sceneImages + (scene.sceneId to url)
                                }
                                generatingScene = null
                            }
                        }
                    },
                    onGenerateVideo = {
                        onGenerateVideo?.let { fn ->
                            scope.launch { fn(scene.sceneId) }
                        }
                    }
                )
            }

            // 底部间距
            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

@Composable
private fun SceneDetailCard(
    scene: AiStoryboardService.SceneCard,
    sceneImageUrl: String?,
    isGenerating: Boolean,
    style: AiComposeStyle,
    onGenerateImage: () -> Unit,
    onGenerateVideo: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.metrics.cardRadius),
        colors = CardDefaults.cardColors(containerColor = style.colors.cardSurface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // 场景标题
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = style.colors.accent
                    ) {
                        Text(
                            " ${scene.sceneId} ",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        scene.sceneTitle,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = style.colors.primaryText
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 场景插画
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(style.colors.stroke),
                contentAlignment = Alignment.Center
            ) {
                if (sceneImageUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(sceneImageUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = scene.sceneTitle,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎬", fontSize = 32.sp)
                        Spacer(Modifier.height(8.dp))
                        if (isGenerating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = style.colors.accent,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("正在生成插画...", fontSize = 12.sp, color = style.colors.secondaryText)
                        } else {
                            TextButton(
                                onClick = onGenerateImage,
                                colors = ButtonDefaults.textButtonColors(contentColor = style.colors.accent)
                            ) {
                                Text("生成场景插画")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 场景信息
            if (scene.location.isNotBlank()) {
                InfoRow("地点", scene.location, style)
            }
            if (scene.timeOfDay.isNotBlank()) {
                InfoRow("时间", scene.timeOfDay, style)
            }
            if (scene.characters.isNotEmpty()) {
                InfoRow("角色", scene.characters.joinToString("、"), style)
            }
            if (scene.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("画面描述", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = style.colors.accent)
                Text(scene.description, fontSize = 13.sp, color = style.colors.primaryText)
            }
            if (scene.dialogue.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("对话/动作", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = style.colors.accent)
                Text(scene.dialogue, fontSize = 13.sp, color = style.colors.secondaryText)
            }
            if (scene.visualPrompt.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("AI绘画提示词", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = style.colors.accent)
                Text(
                    scene.visualPrompt,
                    fontSize = 12.sp,
                    color = style.colors.secondaryText,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 生成视频按钮
            if (onGenerateVideo != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onGenerateVideo,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(style.metrics.chipRadius),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = style.colors.accent)
                ) {
                    Text("🎥 生成视频")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, style: AiComposeStyle) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            "$label：",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = style.colors.accent
        )
        Text(
            value,
            fontSize = 13.sp,
            color = style.colors.primaryText
        )
    }
}

/**
 * 场景图库页面
 */
@Composable
fun ArcReelGalleryScreen(
    items: List<AiSceneGallery.GalleryItem>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val style = aiComposeStyle(context)
    var selectedType by remember { mutableStateOf<AiSceneGallery.GalleryItem.ItemType?>(null) }

    val filteredItems = if (selectedType != null) {
        items.filter { it.type == selectedType }
    } else items

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(style.colors.pageBackground)
            .statusBarsPadding()
    ) {
        // 顶部栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Text("←", fontSize = 20.sp, color = style.colors.primaryText)
            }
            Text("场景图库", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = style.colors.primaryText)
        }

        // 分类筛选
        LazyRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = selectedType == null,
                    onClick = { selectedType = null },
                    label = { Text("全部") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = style.colors.accent,
                        selectedLabelColor = Color.White
                    )
                )
            }
            items(AiSceneGallery.GalleryItem.ItemType.entries) { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { selectedType = if (selectedType == type) null else type },
                    label = { Text(typeLabel(type)) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = style.colors.accent,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 图片网格
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(filteredItems.chunked(2)) { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowItems.forEach { item ->
                        Card(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(style.metrics.cardRadius),
                            colors = CardDefaults.cardColors(containerColor = style.colors.cardSurface)
                        ) {
                            Column {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp)
                                        .clip(RoundedCornerShape(topStart = style.metrics.cardRadius, topEnd = style.metrics.cardRadius))
                                        .background(style.colors.stroke),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (item.imageUrl != null) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current)
                                                .data(item.imageUrl)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = item.name,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Text("无图片", fontSize = 13.sp, color = style.colors.secondaryText)
                                    }
                                }
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = style.colors.primaryText, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.description.take(50), fontSize = 11.sp, color = style.colors.secondaryText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(typeLabel(item.type), fontSize = 10.sp, color = style.colors.accent)
                                }
                            }
                        }
                    }
                    // 补齐单数
                    if (rowItems.size < 2) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

private fun typeLabel(type: AiSceneGallery.GalleryItem.ItemType): String = when (type) {
    AiSceneGallery.GalleryItem.ItemType.CHARACTER -> "角色"
    AiSceneGallery.GalleryItem.ItemType.SCENE -> "场景"
    AiSceneGallery.GalleryItem.ItemType.PROP -> "道具"
    AiSceneGallery.GalleryItem.ItemType.STORYBOARD_SHOT -> "分镜"
    AiSceneGallery.GalleryItem.ItemType.VIDEO_FRAME -> "视频"
}