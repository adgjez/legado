package io.legado.app.ui.main.ai.pipeline

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.legado.app.help.ai.pipeline.*
import kotlinx.coroutines.launch
import java.io.File

/**
 * 视频流水线配置与启动界面
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPipelineScreen(
    novelText: String = "",
    novelTitle: String = "",
    chapterName: String = "",
    episode: Int = 1,
    onDismiss: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phase by remember { mutableStateOf<PipelinePhase?>(null) }
    var progressPercent by remember { mutableIntStateOf(0) }
    var progressMessage by remember { mutableStateOf("") }
    var isRunning by remember { mutableStateOf(false) }
    var resultPath by remember { mutableStateOf<String?>(null) }

    // 配置参数
    var sceneDuration by remember { mutableIntStateOf(5) }
    var imageWidth by remember { mutableIntStateOf(1024) }
    var imageHeight by remember { mutableIntStateOf(768) }
    var videoWidth by remember { mutableIntStateOf(1152) }
    var videoHeight by remember { mutableIntStateOf(768) }
    var maxConcurrent by remember { mutableIntStateOf(1) }
    var skipStoryboards by remember { mutableStateOf(false) }
    var onlyStoryboards by remember { mutableStateOf(false) }

    val config = remember(sceneDuration, imageWidth, imageHeight, videoWidth, videoHeight, maxConcurrent) {
        PipelineConfig(
            sceneDurationSeconds = sceneDuration,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            maxConcurrentGenerations = maxConcurrent
        )
    }

    val engine = remember { VideoPipelineEngine(config) }

    val outputDir = File(context.cacheDir, "ai_pipeline").also { it.mkdirs() }

    fun startPipeline() {
        if (novelText.isBlank()) {
            Toast.makeText(context, "没有可用的章节文本", Toast.LENGTH_SHORT).show()
            return
        }
        isRunning = true
        phase = PipelinePhase.GENERATING_SCRIPT
        resultPath = null

        scope.launch {
            try {
                val result = engine.execute(
                    novelText = novelText,
                    novelTitle = novelTitle,
                    chapterName = chapterName,
                    episode = episode,
                    outputDir = outputDir,
                    onProgress = { p ->
                        phase = p.phase
                        progressPercent = p.percent
                        progressMessage = p.message
                    }
                )
                resultPath = result
            } catch (e: Exception) {
                phase = PipelinePhase.FAILED
                progressMessage = e.message ?: "未知错误"
            } finally {
                isRunning = false
            }
        }
    }

    fun startStoryboardsOnly() {
        if (novelText.isBlank()) {
            Toast.makeText(context, "没有可用的章节文本", Toast.LENGTH_SHORT).show()
            return
        }
        isRunning = true
        phase = PipelinePhase.GENERATING_SCRIPT
        resultPath = null

        scope.launch {
            try {
                engine.generateStoryboardsOnly(
                    novelText = novelText,
                    novelTitle = novelTitle,
                    chapterName = chapterName,
                    episode = episode,
                    outputDir = outputDir,
                    onProgress = { p ->
                        phase = p.phase
                        progressPercent = p.percent
                        progressMessage = p.message
                    }
                )
            } catch (e: Exception) {
                phase = PipelinePhase.FAILED
                progressMessage = e.message ?: "未知错误"
            } finally {
                isRunning = false
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 标题
            Text(
                text = "小说→视频 流水线",
                style = MaterialTheme.typography.headlineMedium
            )

            Text(
                text = "《$novelTitle》$chapterName · 第${episode}集",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // 配置区域
            Text("参数配置", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = sceneDuration.toString(),
                    onValueChange = { sceneDuration = it.toIntOrNull() ?: 5 },
                    label = { Text("场景时长(秒)") },
                    modifier = Modifier.weight(1f),
                    enabled = !isRunning,
                    singleLine = true
                )
                OutlinedTextField(
                    value = maxConcurrent.toString(),
                    onValueChange = { maxConcurrent = it.toIntOrNull()?.coerceIn(1, 3) ?: 1 },
                    label = { Text("并发数") },
                    modifier = Modifier.weight(1f),
                    enabled = !isRunning,
                    singleLine = true
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = "$imageWidth×$imageHeight",
                    onValueChange = {
                        val parts = it.split("×", "x", "X")
                        if (parts.size == 2) {
                            imageWidth = parts[0].toIntOrNull() ?: imageWidth
                            imageHeight = parts[1].toIntOrNull() ?: imageHeight
                        }
                    },
                    label = { Text("图片分辨率") },
                    modifier = Modifier.weight(1f),
                    enabled = !isRunning,
                    singleLine = true
                )
                OutlinedTextField(
                    value = "$videoWidth×$videoHeight",
                    onValueChange = {
                        val parts = it.split("×", "x", "X")
                        if (parts.size == 2) {
                            videoWidth = parts[0].toIntOrNull() ?: videoWidth
                            videoHeight = parts[1].toIntOrNull() ?: videoHeight
                        }
                    },
                    label = { Text("视频分辨率") },
                    modifier = Modifier.weight(1f),
                    enabled = !isRunning,
                    singleLine = true
                )
            }

            HorizontalDivider()

            // 进度区域
            if (phase != null) {
                Text(
                    text = when (phase) {
                        PipelinePhase.GENERATING_SCRIPT -> "📝 生成剧本"
                        PipelinePhase.GENERATING_STORYBOARDS -> "🎨 生成分镜图"
                        PipelinePhase.GENERATING_VIDEOS -> "🎬 生成视频"
                        PipelinePhase.COMPOSING_VIDEO -> "🎞️ 合成视频"
                        PipelinePhase.COMPLETED -> "✅ 完成"
                        PipelinePhase.FAILED -> "❌ 失败"
                    },
                    style = MaterialTheme.typography.titleMedium
                )

                LinearProgressIndicator(
                    progress = { progressPercent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = progressMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 结果
            if (resultPath != null) {
                HorizontalDivider()
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("视频已生成", style = MaterialTheme.typography.titleSmall)
                        Text(resultPath!!, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { startPipeline() },
                    enabled = !isRunning && novelText.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🚀 完整流水线")
                }

                OutlinedButton(
                    onClick = { startStoryboardsOnly() },
                    enabled = !isRunning && novelText.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("🎨 仅分镜图")
                }
            }

            if (isRunning) {
                OutlinedButton(
                    onClick = {
                        // TODO: 取消流水线
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("取消")
                }
            }

            // 关闭
            if (!isRunning) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("关闭")
                }
            }
        }
    }
}
