package io.legado.app.ui.main.ai.creation

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import io.legado.app.ui.video.VideoPlayerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

// ── 数据模型 ──

private enum class CreationTab { IMAGE, VIDEO }

private enum class ImageMode { TEXT_TO_IMAGE, IMAGE_TO_IMAGE }

private enum class VideoMode { TEXT_TO_VIDEO, IMAGE_TO_VIDEO }

private enum class VideoResolution(val label: String, val baseHeight: Int) {
    SD("SD", 720),
    HD("HD", 1080)
}

private data class ImageSize(val label: String, val value: String)
private data class VideoPreset(val label: String, val numFrames: Int)
private data class VideoAspectRatio(val label: String, val ratioW: Int, val ratioH: Int)

// ── 预设 ──

private val imageSizes = listOf(
    ImageSize("1:1", "1024x1024"),
    ImageSize("4:3", "1024x768"),
    ImageSize("3:4", "768x1024"),
    ImageSize("16:9", "1360x768"),
    ImageSize("9:16", "768x1360"),
    ImageSize("3:2", "1152x768"),
    ImageSize("2:3", "768x1152"),
    ImageSize("21:9", "1792x768"),
    ImageSize("自定义", ""),
)

private val videoPresets = listOf(
    VideoPreset("3秒", 81),
    VideoPreset("5秒", 121),
    VideoPreset("10秒", 241),
    VideoPreset("15秒", 361),
)

private val videoAspectRatios = listOf(
    VideoAspectRatio("9:16", 9, 16),
    VideoAspectRatio("16:9", 16, 9),
    VideoAspectRatio("1:1", 1, 1),
    VideoAspectRatio("4:3", 4, 3),
    VideoAspectRatio("3:4", 3, 4),
    VideoAspectRatio("3:2", 3, 2),
    VideoAspectRatio("2:3", 2, 3),
    VideoAspectRatio("21:9", 21, 9),
)

// ── 图片压缩 ──

/** 将本地图片压缩为 JPEG (base64 data URI)，最大边长 1024，质量 80% */
private fun compressImageToBase64(context: android.content.Context, uri: Uri, maxDim: Int = 1024): String {
    // 先读取尺寸
    val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, boundsOptions)
    }
    val scaleFactor = maxOf(
        (boundsOptions.outWidth + maxDim - 1) / maxDim,
        (boundsOptions.outHeight + maxDim - 1) / maxDim,
        1
    )
    val decodeOpts = BitmapFactory.Options().apply { inSampleSize = scaleFactor }
    val bitmap = context.contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, decodeOpts)
    } ?: throw AiCreationException("无法读取图片文件")
    val outputStream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
    bitmap.recycle()
    val b64 = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    return "data:image/jpeg;base64,$b64"
}

// ── 持久化生成状态（离开页面不中断） ──

private val generationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

private class ImageGenState {
    var generating by mutableStateOf(false)
    var resultUrl by mutableStateOf<String?>(null)
    var errorMsg by mutableStateOf<String?>(null)
    var progressText by mutableStateOf("")
}

private class VideoGenState {
    var generating by mutableStateOf(false)
    var resultUrl by mutableStateOf<String?>(null)
    var errorMsg by mutableStateOf<String?>(null)
    var progressText by mutableStateOf("")
    var videoProgress by mutableFloatStateOf(0f)
    var cooldownUntil by mutableStateOf(0L)
    var cooldownSeconds by mutableIntStateOf(0)
}

private val imageState = ImageGenState()
private val videoState = VideoGenState()

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
    var prompt by remember { mutableStateOf("") }
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
            CreationTab.IMAGE -> ImageCreationPanel(style, prompt, { prompt = it })
            CreationTab.VIDEO -> VideoCreationPanel(style, prompt, { prompt = it })
        }
    }
}

// ── 图片生成面板 ──

@Composable
private fun ImageCreationPanel(style: AiComposeStyle, prompt: String, onPromptChange: (String) -> Unit) {
    var mode by remember { mutableStateOf(ImageMode.TEXT_TO_IMAGE) }
    var selectedSize by remember { mutableStateOf(imageSizes[0]) }
    var customWidth by remember { mutableStateOf("1024") }
    var customHeight by remember { mutableStateOf("768") }
    var is4K by remember { mutableStateOf(false) }
    fun currentSizeValue(): String = if (is4K && selectedSize.value.isNotBlank()) {
        val parts = selectedSize.value.split("x").map { it.toIntOrNull() ?: 1024 }
        "${parts[0] * 2}x${parts[1] * 2}"
    } else {
        selectedSize.value.takeIf { it.isNotBlank() } ?: "${customWidth}x${customHeight}"
    }
    var imageUri by remember { mutableStateOf<Uri?>(null) }

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
                    onClick = { mode = m; imageState.resultUrl = null; imageState.errorMsg = null },
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
                onValueChange = onPromptChange,
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
        if (selectedSize.value.isEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("宽", color = style.colors.secondaryText, fontSize = 13.sp)
                BasicTextField(
                    value = customWidth,
                    onValueChange = { v -> customWidth = v.filter { it.isDigit() } },
                    singleLine = true,
                    textStyle = TextStyle(color = style.colors.primaryText, fontSize = 13.sp, textAlign = TextAlign.Center),
                    modifier = Modifier
                        .width(72.dp)
                        .background(style.colors.cardSurface, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
                Text("×", color = style.colors.secondaryText, fontSize = 13.sp)
                Text("高", color = style.colors.secondaryText, fontSize = 13.sp)
                BasicTextField(
                    value = customHeight,
                    onValueChange = { v -> customHeight = v.filter { it.isDigit() } },
                    singleLine = true,
                    textStyle = TextStyle(color = style.colors.primaryText, fontSize = 13.sp, textAlign = TextAlign.Center),
                    modifier = Modifier
                        .width(72.dp)
                        .background(style.colors.cardSurface, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // 4K 切换
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "4K 超清",
                color = if (is4K) style.colors.accent else style.colors.secondaryText,
                fontSize = 13.sp,
                fontWeight = if (is4K) FontWeight.SemiBold else FontWeight.Normal
            )
            Spacer(Modifier.weight(1f))
            Switch(
                checked = is4K,
                onCheckedChange = { is4K = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = style.colors.accent,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = style.colors.secondaryText.copy(alpha = 0.3f)
                )
            )
        }
        Spacer(Modifier.height(20.dp))

        // 生成按钮
        Button(
            onClick = {
                imageState.generating = true
                imageState.errorMsg = null
                imageState.resultUrl = null
                generationScope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            if (mode == ImageMode.TEXT_TO_IMAGE) {
                                AiCreationService.textToImage(prompt, currentSizeValue())
                            } else {
                                val uri = imageUri
                                    ?: throw AiCreationException("请先选择图片")
                                val dataUri = compressImageToBase64(context, uri)
                                AiCreationService.imageToImage(prompt, dataUri, currentSizeValue())
                            }
                        }
                        imageState.resultUrl = result.url
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
                        imageState.errorMsg = e.message
                    } finally {
                        imageState.generating = false
                    }
                }
            },
            enabled = prompt.isNotBlank() && !imageState.generating &&
                (mode == ImageMode.TEXT_TO_IMAGE || imageUri != null),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = style.colors.accent),
            shape = RoundedCornerShape(style.metrics.chipRadius)
        ) {
            if (imageState.generating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                if (imageState.generating) "生成中..." else "生成图片",
                color = Color.White,
                fontSize = 15.sp
            )
        }
        Spacer(Modifier.height(16.dp))

        // 进度条
        if (imageState.generating) {
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
                imageState.progressText.ifBlank { "生成中..." },
                color = style.colors.secondaryText,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
        }

        // 结果
        imageState.errorMsg?.let { msg ->
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
        imageState.resultUrl?.let { url ->
            var showFull by remember { mutableStateOf(false) }
            Surface(
                shape = RoundedCornerShape(style.metrics.cardRadius),
                color = style.colors.cardSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    // 图片 - 自适应
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(url)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showFull = true }
                    )
                    // 操作栏
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showFull = true }) {
                            Text("查看大图", fontSize = 12.sp, color = style.colors.accent)
                        }
                        TextButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse(url)
                            }
                            context.startActivity(intent)
                        }) {
                            Text("下载", fontSize = 12.sp, color = style.colors.accent)
                        }
                    }
                }
            }
            // 大图弹窗（支持缩放）
            if (showFull) {
                Dialog(onDismissRequest = { showFull = false }) {
                    var scale by remember { mutableFloatStateOf(1f) }
                    var offset by remember { mutableStateOf(Offset.Zero) }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Black,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("查看大图", color = Color.White, fontSize = 14.sp)
                                TextButton(onClick = { showFull = false }) {
                                    Text("关闭", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                                }
                            }
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(url).crossfade(true).build(),
                                contentDescription = null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .pointerInput(Unit) {
                                        detectTransformGestures { _, pan, zoom, _ ->
                                            scale = (scale * zoom).coerceIn(1f, 5f)
                                            offset = if (scale <= 1f) Offset.Zero else offset + pan
                                        }
                                    }
                                    .graphicsLayer {
                                        scaleX = scale; scaleY = scale
                                        translationX = offset.x; translationY = offset.y
                                    }
                            )
                            Spacer(Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.End
                            ) {
                                if (scale > 1f) {
                                    TextButton(onClick = { scale = 1f; offset = Offset.Zero }) {
                                        Text("复位", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                                    }
                                }
                                TextButton(onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW).apply { data = Uri.parse(url) }
                                    context.startActivity(intent)
                                }) {
                                    Text("下载图片", color = style.colors.accent, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        // 历史记录
        ImageHistorySection(style)
        Spacer(Modifier.height(24.dp))
    }
}

// ── 视频生成面板 ──

@Composable
private fun VideoCreationPanel(style: AiComposeStyle, prompt: String, onPromptChange: (String) -> Unit) {
    var mode by remember { mutableStateOf(VideoMode.TEXT_TO_VIDEO) }
    var selectedPreset by remember { mutableStateOf(videoPresets[0]) }
    var selectedResolution by remember { mutableStateOf(VideoResolution.SD) }
    var selectedAspectRatio by remember { mutableStateOf(videoAspectRatios[1]) } // 默认16:9
    var imageUri by remember { mutableStateOf<Uri?>(null) }

    val context = LocalContext.current

    // 冷却计时器：每秒更新倒计时
    LaunchedEffect(videoState.cooldownUntil) {
        while (videoState.cooldownUntil > 0) {
            val remaining = ((videoState.cooldownUntil - System.currentTimeMillis()) / 1000).toInt()
            if (remaining <= 0) break
            videoState.cooldownSeconds = remaining
            kotlinx.coroutines.delay(1000)
        }
        videoState.cooldownSeconds = 0
    }

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
                    onClick = { mode = m; videoState.resultUrl = null; videoState.errorMsg = null },
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

        // 分辨率
        SectionLabel("分辨率", style)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VideoResolution.entries.forEach { res ->
                val sel = selectedResolution == res
                Surface(
                    onClick = { selectedResolution = res },
                    shape = RoundedCornerShape(style.metrics.chipRadius),
                    color = if (sel) style.colors.accent.copy(alpha = 0.14f)
                    else style.colors.cardSurface
                ) {
                    Text(
                        text = res.label,
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
                onValueChange = onPromptChange,
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
        Spacer(Modifier.height(12.dp))

        // 比例
        SectionLabel("比例", style)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(videoAspectRatios) { ratio ->
                Surface(
                    onClick = { selectedAspectRatio = ratio },
                    shape = RoundedCornerShape(style.metrics.chipRadius),
                    color = if (ratio == selectedAspectRatio) style.colors.accent.copy(alpha = 0.14f)
                    else style.colors.cardSurface
                ) {
                    Text(
                        text = ratio.label,
                        color = if (ratio == selectedAspectRatio) style.colors.accent else style.colors.secondaryText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                videoState.generating = true
                videoState.errorMsg = null
                videoState.resultUrl = null
                videoState.progressText = "创建视频任务..."
                videoState.videoProgress = 0f
                generationScope.launch {
                    try {
                        val onProgress: (AiCreationService.GenerationProgress) -> Unit = { p ->
                            videoState.progressText = "${p.statusText} ${p.percent}% (${p.elapsedSeconds}秒)"
                            videoState.videoProgress = p.percent / 100f
                        }
                        val videoW = (selectedResolution.baseHeight * selectedAspectRatio.ratioW / selectedAspectRatio.ratioH)
                            val videoH = selectedResolution.baseHeight
                            val result = withContext(Dispatchers.IO) {
                            if (mode == VideoMode.TEXT_TO_VIDEO) {
                                AiCreationService.textToVideo(
                                    prompt,
                                    numFrames = selectedPreset.numFrames,
                                    width = videoW,
                                    height = videoH,
                                    onProgress = onProgress
                                )
                            } else {
                                val uri = imageUri
                                    ?: throw AiCreationException("请先选择图片")
                                val dataUri = compressImageToBase64(context, uri)
                                AiCreationService.imageToVideo(
                                    prompt,
                                    dataUri,
                                    numFrames = selectedPreset.numFrames,
                                    width = videoW,
                                    height = videoH,
                                    onProgress = onProgress
                                )
                            }
                        }
                        videoState.resultUrl = result.videoUrl
                        videoState.progressText = ""
                        videoState.videoProgress = 1f
                        AiCreationHistory.addVideo(
                            AiCreationHistory.VideoRecord(
                                url = result.videoUrl,
                                prompt = prompt,
                                mode = if (mode == VideoMode.TEXT_TO_VIDEO) "text-to-video" else "image-to-video"
                            )
                        )
                    } catch (e: Exception) {
                        videoState.errorMsg = e.message
                        videoState.progressText = ""
                    } finally {
                        videoState.generating = false
                        videoState.cooldownUntil = System.currentTimeMillis() + 65_000
                    }
                }
            },
            enabled = prompt.isNotBlank() && !videoState.generating &&
                (mode == VideoMode.TEXT_TO_VIDEO || imageUri != null) &&
                System.currentTimeMillis() > videoState.cooldownUntil,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = style.colors.accent),
            shape = RoundedCornerShape(style.metrics.chipRadius)
        ) {
            if (videoState.generating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                when {
                    videoState.generating -> "生成中..."
                    videoState.cooldownSeconds > 0 -> "冷却中 (${videoState.cooldownSeconds}s)"
                    else -> "生成视频"
                },
                color = Color.White,
                fontSize = 15.sp
            )
        }
        Spacer(Modifier.height(8.dp))

        if (videoState.generating) {
            LinearProgressIndicator(
                progress = { videoState.videoProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = style.colors.accent,
                trackColor = style.colors.accent.copy(alpha = 0.12f),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                videoState.progressText.ifBlank { "生成中..." },
                color = style.colors.secondaryText,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(8.dp))
        }

        videoState.errorMsg?.let { msg ->
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
        videoState.resultUrl?.let { url ->
            var cachedPath by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(url) {
                cachedPath = withContext(Dispatchers.IO) { cacheVideo(url) }
            }
            Surface(
                shape = RoundedCornerShape(style.metrics.cardRadius),
                color = style.colors.cardSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("视频已生成", color = style.colors.accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    // 自适应播放器
                    AndroidView(
                        factory = { ctx ->
                            VideoPlayer(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                                )
                                setUp(cachedPath ?: url, false, null, "")
                                startPlayLogic()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 360.dp)
                            .clip(RoundedCornerShape(style.metrics.chipRadius))
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                                    putExtra("videoUrl", url)
                                    putExtra("videoTitle", prompt.take(30))
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = style.colors.accent),
                            shape = RoundedCornerShape(style.metrics.chipRadius)
                        ) { Text("内置播放器", fontSize = 13.sp) }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(url), "video/*")
                                })
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = style.colors.accent),
                            shape = RoundedCornerShape(style.metrics.chipRadius)
                        ) { Text("系统播放器", fontSize = 13.sp) }
                    }
                    if (cachedPath != null) {
                        Text("已缓存", color = style.colors.secondaryText, fontSize = 11.sp)
                    }
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
    val clipboard = LocalClipboardManager.current
    var images by remember { mutableStateOf(AiCreationHistory.getImages()) }
    val refresh = { images = AiCreationHistory.getImages() }
    if (images.isEmpty()) return
    Spacer(Modifier.height(24.dp))
    SectionLabel("历史图片", style)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEach { record ->
            var showFull by remember { mutableStateOf(false) }
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
                            clipboard.setText(AnnotatedString(record.prompt))
                            Toast.makeText(context, "已复制提示词", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.size(32.dp)) {
                            Text("📋", fontSize = 14.sp)
                        }
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
                            .heightIn(max = 300.dp)
                            .clip(RoundedCornerShape(style.metrics.chipRadius))
                            .clickable { showFull = true }
                    )
                    // 缩放弹窗
                    if (showFull) {
                        var scale by remember { mutableFloatStateOf(1f) }
                        var offset by remember { mutableStateOf(Offset.Zero) }
                        Dialog(onDismissRequest = { showFull = false }) {
                            Surface(shape = RoundedCornerShape(12.dp), color = Color.Black, modifier = Modifier.fillMaxWidth()) {
                                Column {
                                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text("查看大图", color = Color.White, fontSize = 14.sp)
                                        TextButton(onClick = { showFull = false }) {
                                            Text("关闭", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                                        }
                                    }
                                    AsyncImage(
                                        model = ImageRequest.Builder(context).data(record.url).crossfade(true).build(),
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .pointerInput(Unit) {
                                                detectTransformGestures { _, pan, zoom, _ ->
                                                    scale = (scale * zoom).coerceIn(1f, 5f)
                                                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                                                }
                                            }
                                            .graphicsLayer {
                                                scaleX = scale; scaleY = scale
                                                translationX = offset.x; translationY = offset.y
                                            }
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                                        if (scale > 1f) TextButton(onClick = { scale = 1f; offset = Offset.Zero }) { Text("复位", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoHistorySection(style: AiComposeStyle) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
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
                            clipboard.setText(AnnotatedString(record.prompt))
                            Toast.makeText(context, "已复制提示词", Toast.LENGTH_SHORT).show()
                        }, modifier = Modifier.size(32.dp)) {
                            Text("📋", fontSize = 14.sp)
                        }
                        IconButton(onClick = {
                            AiCreationHistory.deleteVideo(record.url)
                            refresh()
                        }, modifier = Modifier.size(32.dp)) {
                            Text("×", color = style.colors.danger, fontSize = 16.sp)
                        }
                    }
                    // 视频预览缩略图
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .clip(RoundedCornerShape(style.metrics.chipRadius))
                            .background(style.colors.cardSurface.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(record.url).crossfade(true).build(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize()
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.Black.copy(alpha = 0.5f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Text("▶", color = Color.White, fontSize = 20.sp, modifier = Modifier.wrapContentSize(), textAlign = TextAlign.Center)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                val intent = Intent(context, VideoPlayerActivity::class.java).apply {
                                    putExtra("videoUrl", record.url)
                                    putExtra("videoTitle", record.prompt.take(30))
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors(contentColor = style.colors.accent),
                            shape = RoundedCornerShape(style.metrics.chipRadius)
                        ) { Text("内置播放", fontSize = 13.sp) }
                        TextButton(
                            onClick = {
                                context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(Uri.parse(record.url), "video/*")
                                })
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.textButtonColors(contentColor = style.colors.accent),
                            shape = RoundedCornerShape(style.metrics.chipRadius)
                        ) { Text("系统播放", fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}

// ── 视频缓存 ──

private fun cacheVideo(url: String): String? {
    return try {
        val dir = File(appCtx.cacheDir, "ai_video")
        dir.mkdirs()
        val name = "video_${url.hashCode()}.mp4"
        val file = File(dir, name)
        if (file.exists()) return file.absolutePath
        URL(url).openStream().use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        file.absolutePath
    } catch (_: Exception) { null }
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