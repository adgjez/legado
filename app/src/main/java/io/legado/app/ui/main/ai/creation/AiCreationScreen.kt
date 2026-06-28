package io.legado.app.ui.main.ai.creation

import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import coil.request.ImageRequest
import io.legado.app.help.ai.AiCreationException
import io.legado.app.help.ai.AiCreationHistory
import io.legado.app.help.ai.AiCreationService
import io.legado.app.help.gsyVideo.VideoPlayer
import io.legado.app.ui.main.ai.compose.AiComposeStyle
import io.legado.app.ui.main.ai.compose.aiComposeStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ── 数据模型 ──

private enum class CreationTab { IMAGE, VIDEO }

private enum class ImageMode { TEXT_TO_IMAGE, IMAGE_TO_IMAGE }

private enum class VideoMode { TEXT_TO_VIDEO, IMAGE_TO_VIDEO }

private data class ImageSize(val label: String, val value: String)
private data class VideoPreset(val label: String, val numFrames: Int)

// ── 预设 ──

private val imageSizes = listOf(
    ImageSize("1:1 方图", "1024x1024"),
    ImageSize("4:3 横图", "1024x768"),
    ImageSize("3:4 竖图", "768x1024"),
    ImageSize("16:9 宽屏", "1152x768"),
)

private val videoPresets = listOf(
    VideoPreset("3秒", 81),
    VideoPreset("5秒", 121),
    VideoPreset("10秒", 241),
    VideoPreset("18秒", 441),
)

// ── 主入口 ──

@Composable
fun AiCreationRoute(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var hasKey by remember { mutableStateOf(false) }
    val style = aiComposeStyle(context)

    LaunchedEffect(Unit) {
        AiCreationService.loadApiKey()
        hasKey = AiCreationService.hasApiKey()
    }

    if (!hasKey) {
        ApiKeySetupDialog(
            style = style,
            onSet = { key ->
                AiCreationService.setApiKey(key)
                hasKey = true
            },
            onDismiss = onBack
        )
    } else {
        AiCreationScreen(style = style, onBack = onBack)
    }
}

// ── API Key 设置对话框 ──

@Composable
private fun ApiKeySetupDialog(
    style: AiComposeStyle,
    onSet: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var key by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(style.metrics.cardRadius),
            color = style.colors.composerSurface
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "设置 Agnes AI API Key",
                    color = style.colors.primaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "请输入您的 Agnes AI API Key 以使用图片和视频生成功能",
                    color = style.colors.secondaryText,
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(16.dp))
                Surface(
                    shape = RoundedCornerShape(style.metrics.chipRadius),
                    color = style.colors.cardSurface
                ) {
                    BasicTextField(
                        value = key,
                        onValueChange = { key = it },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = style.colors.primaryText,
                            fontSize = 14.sp
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        decorationBox = { inner ->
                            if (key.isBlank()) {
                                Text(
                                    "sk-...",
                                    color = style.colors.secondaryText.copy(alpha = 0.5f),
                                    fontSize = 14.sp
                                )
                            }
                            inner()
                        }
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("取消", color = style.colors.secondaryText)
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            key.trim().takeIf { it.isNotBlank() }?.let(onSet)
                        },
                        enabled = key.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = style.colors.accent)
                    ) {
                        Text("确认")
                    }
                }
            }
        }
    }
}

// ── 主界面 ──

@Composable
private fun AiCreationScreen(
    style: AiComposeStyle,
    onBack: () -> Unit
) {
    var tab by remember { mutableStateOf(CreationTab.IMAGE) }
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
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "AI 创造平台",
                color = style.colors.primaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            Text(
                "返回",
                color = style.colors.accent,
                fontSize = 14.sp,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        // Tab 切换
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CreationTab.entries.forEach { t ->
                val selected = tab == t
                Surface(
                    onClick = { tab = t },
                    shape = RoundedCornerShape(style.metrics.chipRadius),
                    color = if (selected) style.colors.accent.copy(alpha = 0.14f)
                    else style.colors.cardSurface,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (t == CreationTab.IMAGE) "图片生成" else "视频生成",
                        color = if (selected) style.colors.accent else style.colors.secondaryText,
                        fontSize = 15.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        // 内容区
        when (tab) {
            CreationTab.IMAGE -> ImageCreationPanel(style)
            CreationTab.VIDEO -> VideoCreationPanel(style)
        }
    }
}

// ── 图片生成面板 ──

@Composable
private fun ImageCreationPanel(style: AiComposeStyle) {
    var mode by remember { mutableStateOf(ImageMode.TEXT_TO_IMAGE) }
    var prompt by remember { mutableStateOf("") }
    var selectedSize by remember { mutableStateOf(imageSizes[0]) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var generating by remember { mutableStateOf(false) }
    var resultUrl by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var progressText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> imageUri = uri }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        // 模式切换
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ImageMode.entries.forEach { m ->
                val sel = mode == m
                Surface(
                    onClick = { mode = m; resultUrl = null; errorMsg = null },
                    shape = RoundedCornerShape(style.metrics.chipRadius),
                    color = if (sel) style.colors.accent.copy(alpha = 0.14f)
                    else style.colors.cardSurface
                ) {
                    Text(
                        text = if (m == ImageMode.TEXT_TO_IMAGE) "文生图" else "图生图",
                        color = if (sel) style.colors.accent else style.colors.secondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // 图生图：图片选择
        if (mode == ImageMode.IMAGE_TO_IMAGE) {
            Surface(
                onClick = { imagePicker.launch("image/*") },
                shape = RoundedCornerShape(style.metrics.cardRadius),
                color = style.colors.cardSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (imageUri != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imageUri)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "+",
                                color = style.colors.accent,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Light
                            )
                            Text(
                                "点击选择图片",
                                color = style.colors.secondaryText,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // 提示词
        SectionLabel("提示词", style)
        Surface(
            shape = RoundedCornerShape(style.metrics.chipRadius),
            color = style.colors.cardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 140.dp)
        ) {
            BasicTextField(
                value = prompt,
                onValueChange = { prompt = it },
                textStyle = TextStyle(
                    color = style.colors.primaryText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                decorationBox = { inner ->
                    if (prompt.isBlank()) {
                        Text(
                            "描述你想要的画面...",
                            color = style.colors.secondaryText.copy(alpha = 0.5f),
                            fontSize = 14.sp
                        )
                    }
                    inner()
                }
            )
        }
        Spacer(Modifier.height(12.dp))

        // 尺寸选择
        SectionLabel("尺寸", style)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(imageSizes) { size ->
                Surface(
                    onClick = { selectedSize = size },
                    shape = RoundedCornerShape(style.metrics.chipRadius),
                    color = if (size == selectedSize) style.colors.accent.copy(alpha = 0.14f)
                    else style.colors.cardSurface
                ) {
                    Text(
                        text = size.label,
                        color = if (size == selectedSize) style.colors.accent else style.colors.secondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        // 生成按钮
        Button(
            onClick = {
                generating = true
                errorMsg = null
                resultUrl = null
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            if (mode == ImageMode.TEXT_TO_IMAGE) {
                                AiCreationService.textToImage(prompt, selectedSize.value)
                            } else {
                                val uri = imageUri
                                    ?: throw AiCreationException("请先选择图片")
                                val dataUri = context.contentResolver.run {
                                    val inputStream = openInputStream(uri) ?: throw AiCreationException("无法读取图片文件")
                                    val bytes = inputStream.use { it.readBytes() }
                                    val mimeType = getType(uri) ?: "image/png"
                                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                    "data:$mimeType;base64,$b64"
                                }
                                AiCreationService.imageToImage(prompt, dataUri, selectedSize.value)
                            }
                        }
                        resultUrl = result.url
                        result.url?.let { url ->
                            AiCreationHistory.addImage(
                                AiCreationHistory.ImageRecord(
                                    url = url,
                                    prompt = prompt,
                                    mode = if (mode == ImageMode.TEXT_TO_IMAGE) "text-to-image" else "image-to-image"
                                )
                            )
                        }
                    } catch (e: Exception) {
                        errorMsg = e.message
                    } finally {
                        generating = false
                    }
                }
            },
            enabled = prompt.isNotBlank() && !generating &&
                (mode == ImageMode.TEXT_TO_IMAGE || imageUri != null),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = style.colors.accent),
            shape = RoundedCornerShape(style.metrics.chipRadius)
        ) {
            if (generating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (generating) "生成中..." else "生成图片",
                color = Color.White,
                fontSize = 15.sp
            )
        }
        Spacer(Modifier.height(16.dp))

        // 进度条
        if (generating) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = style.colors.accent,
                trackColor = style.colors.accent.copy(alpha = 0.12f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                progressText.ifBlank { "生成中..." },
                color = style.colors.secondaryText,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
        }

        // 结果
        errorMsg?.let { msg ->
            Surface(
                shape = RoundedCornerShape(style.metrics.cardRadius),
                color = style.colors.danger.copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    msg,
                    color = style.colors.danger,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        resultUrl?.let { url ->
            Surface(
                shape = RoundedCornerShape(style.metrics.cardRadius),
                color = style.colors.cardSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(url)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(
                            selectedSize.value.let { s ->
                                val parts = s.split("x").map { it.toFloatOrNull() ?: 1f }
                                parts[0] / parts[1]
                            }
                        )
                )
            }
        }
        // 历史记录
        ImageHistorySection(style)
        Spacer(Modifier.height(24.dp))
    }
}

// ── 视频生成面板 ──

@Composable
private fun VideoCreationPanel(style: AiComposeStyle) {
    var mode by remember { mutableStateOf(VideoMode.TEXT_TO_VIDEO) }
    var prompt by remember { mutableStateOf("") }
    var selectedPreset by remember { mutableStateOf(videoPresets[0]) }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var generating by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    var videoProgress by remember { mutableFloatStateOf(0f) }
    var resultUrl by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> imageUri = uri }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoMode.entries.forEach { m ->
                val sel = mode == m
                Surface(
                    onClick = { mode = m; resultUrl = null; errorMsg = null },
                    shape = RoundedCornerShape(style.metrics.chipRadius),
                    color = if (sel) style.colors.accent.copy(alpha = 0.14f)
                    else style.colors.cardSurface
                ) {
                    Text(
                        text = if (m == VideoMode.TEXT_TO_VIDEO) "文生视频" else "图生视频",
                        color = if (sel) style.colors.accent else style.colors.secondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (mode == VideoMode.IMAGE_TO_VIDEO) {
            Surface(
                onClick = { imagePicker.launch("image/*") },
                shape = RoundedCornerShape(style.metrics.cardRadius),
                color = style.colors.cardSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (imageUri != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(imageUri)
                                .crossfade(true)
                                .build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "+",
                                color = style.colors.accent,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Light
                            )
                            Text(
                                "点击选择图片",
                                color = style.colors.secondaryText,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        SectionLabel("提示词", style)
        Surface(
            shape = RoundedCornerShape(style.metrics.chipRadius),
            color = style.colors.cardSurface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp, max = 140.dp)
        ) {
            BasicTextField(
                value = prompt,
                onValueChange = { prompt = it },
                textStyle = TextStyle(
                    color = style.colors.primaryText,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                decorationBox = { inner ->
                    if (prompt.isBlank()) {
                        Text(
                            "描述你想要的视频画面...",
                            color = style.colors.secondaryText.copy(alpha = 0.5f),
                            fontSize = 14.sp
                        )
                    }
                    inner()
                }
            )
        }
        Spacer(Modifier.height(12.dp))

        SectionLabel("时长", style)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(videoPresets) { preset ->
                Surface(
                    onClick = { selectedPreset = preset },
                    shape = RoundedCornerShape(style.metrics.chipRadius),
                    color = if (preset == selectedPreset) style.colors.accent.copy(alpha = 0.14f)
                    else style.colors.cardSurface
                ) {
                    Text(
                        text = preset.label,
                        color = if (preset == selectedPreset) style.colors.accent else style.colors.secondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                generating = true
                errorMsg = null
                resultUrl = null
                progressText = "创建视频任务..."
                videoProgress = 0f
                scope.launch {
                    try {
                        val onProgress: (AiCreationService.GenerationProgress) -> Unit = { p ->
                            progressText = "${p.statusText} ${p.percent}% (${p.elapsedSeconds}秒)"
                            videoProgress = p.percent / 100f
                        }
                        val result = withContext(Dispatchers.IO) {
                            if (mode == VideoMode.TEXT_TO_VIDEO) {
                                AiCreationService.textToVideo(
                                    prompt,
                                    numFrames = selectedPreset.numFrames,
                                    onProgress = onProgress
                                )
                            } else {
                                val uri = imageUri
                                    ?: throw AiCreationException("请先选择图片")
                                val dataUri = context.contentResolver.run {
                                    val inputStream = openInputStream(uri) ?: throw AiCreationException("无法读取图片文件")
                                    val bytes = inputStream.use { it.readBytes() }
                                    val mimeType = getType(uri) ?: "image/png"
                                    val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                                    "data:$mimeType;base64,$b64"
                                }
                                AiCreationService.imageToVideo(
                                    prompt,
                                    dataUri,
                                    numFrames = selectedPreset.numFrames,
                                    onProgress = onProgress
                                )
                            }
                        }
                        resultUrl = result.videoUrl
                        progressText = ""
                        videoProgress = 1f
                        AiCreationHistory.addVideo(
                            AiCreationHistory.VideoRecord(
                                url = result.videoUrl,
                                prompt = prompt,
                                mode = if (mode == VideoMode.TEXT_TO_VIDEO) "text-to-video" else "image-to-video"
                            )
                        )
                    } catch (e: Exception) {
                        errorMsg = e.message
                        progressText = ""
                    } finally {
                        generating = false
                    }
                }
            },
            enabled = prompt.isNotBlank() && !generating &&
                (mode == VideoMode.TEXT_TO_VIDEO || imageUri != null),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = style.colors.accent),
            shape = RoundedCornerShape(style.metrics.chipRadius)
        ) {
            if (generating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (generating) "生成中..." else "生成视频",
                color = Color.White,
                fontSize = 15.sp
            )
        }
        Spacer(Modifier.height(8.dp))

        if (generating) {
            LinearProgressIndicator(
                progress = { videoProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = style.colors.accent,
                trackColor = style.colors.accent.copy(alpha = 0.12f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                progressText.ifBlank { "生成中..." },
                color = style.colors.secondaryText,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
        }

        errorMsg?.let { msg ->
            Surface(
                shape = RoundedCornerShape(style.metrics.cardRadius),
                color = style.colors.danger.copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    msg,
                    color = style.colors.danger,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        resultUrl?.let { url ->
            Surface(
                shape = RoundedCornerShape(style.metrics.cardRadius),
                color = style.colors.cardSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("视频已生成", color = style.colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    // 内嵌视频播放器
                    AndroidView(
                        factory = { ctx ->
                            VideoPlayer(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    (ctx.resources.displayMetrics.widthPixels * 9 / 16)
                                )
                                setUp(url, false, null, "")
                                startPlayLogic()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(style.metrics.chipRadius))
                    )
                    Spacer(Modifier.height(8.dp))
                    // 系统播放器按钮
                    OutlinedButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(url), "video/*")
                            })
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = style.colors.accent),
                        shape = RoundedCornerShape(style.metrics.chipRadius)
                    ) {
                        Text("用系统播放器打开", fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(url, color = style.colors.secondaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // 历史记录
        VideoHistorySection(style)
        Spacer(Modifier.height(24.dp))
    }
}

// ── 历史记录 ──

@Composable
private fun ImageHistorySection(style: AiComposeStyle) {
    val context = LocalContext.current
    var images by remember { mutableStateOf(AiCreationHistory.getImages()) }
    val refresh = { images = AiCreationHistory.getImages() }
    if (images.isEmpty()) return
    Spacer(Modifier.height(24.dp))
    SectionLabel("历史图片", style)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEach { record ->
            Surface(
                shape = RoundedCornerShape(style.metrics.cardRadius),
                color = style.colors.cardSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            record.prompt.take(40).let { if (record.prompt.length > 40) "$it..." else it },
                            color = style.colors.primaryText,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            AiCreationHistory.deleteImage(record.url)
                            refresh()
                        }, modifier = Modifier.size(32.dp)) {
                            Text("×", color = style.colors.danger, fontSize = 16.sp)
                        }
                    }
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(record.url).crossfade(true).build(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(style.metrics.chipRadius))
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoHistorySection(style: AiComposeStyle) {
    val context = LocalContext.current
    var videos by remember { mutableStateOf(AiCreationHistory.getVideos()) }
    val refresh = { videos = AiCreationHistory.getVideos() }
    if (videos.isEmpty()) return
    Spacer(Modifier.height(24.dp))
    SectionLabel("历史视频", style)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        videos.forEach { record ->
            Surface(
                shape = RoundedCornerShape(style.metrics.cardRadius),
                color = style.colors.cardSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            record.prompt.take(40).let { if (record.prompt.length > 40) "$it..." else it },
                            color = style.colors.primaryText,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            AiCreationHistory.deleteVideo(record.url)
                            refresh()
                        }, modifier = Modifier.size(32.dp)) {
                            Text("×", color = style.colors.danger, fontSize = 16.sp)
                        }
                    }
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(record.url), "video/*")
                            })
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(contentColor = style.colors.accent),
                        shape = RoundedCornerShape(style.metrics.chipRadius)
                    ) {
                        Text("播放视频", fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, style: AiComposeStyle) {
    Text(
        text = text,
        color = style.colors.secondaryText,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}