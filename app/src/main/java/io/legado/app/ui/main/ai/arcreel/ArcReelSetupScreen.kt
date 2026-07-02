package io.legado.app.ui.main.ai.arcreel

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.help.ai.ArcReelEnvironment
import io.legado.app.help.ai.ArcReelServiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ArcReel 首次安装引导界面
 *
 * 步骤：
 * 1. 下载并安装 proot + Ubuntu rootfs
 * 2. 安装 Python/git 依赖
 * 3. 克隆/更新 ArcReel 仓库
 * 4. 启动服务
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArcReelSetupScreen(
    onBack: () -> Unit,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    enum class SetupStep {
        CHECKING,           // 检查环境
        INSTALLING_ENV,     // 安装 Ubuntu 环境
        INSTALLING_ARCREEL, // 安装 ArcReel
        STARTING,           // 启动服务
        COMPLETED,          // 完成
        ERROR               // 错误
    }

    data class SetupStepInfo(
        val label: String,
        val isCompleted: Boolean = false,
        val isActive: Boolean = false,
        val isError: Boolean = false
    )

    var currentStep by remember { mutableStateOf(SetupStep.CHECKING) }
    var progress by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf("检查环境...") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var stepDetails by remember { mutableStateOf(listOf<SetupStepInfo>()) }
    var retryTrigger by remember { mutableStateOf(0) }  // 递增触发重试

    fun updateSteps(stepIndex: Int, isCompleted: Boolean = false, isActive: Boolean = false, isError: Boolean = false) {
        val labels = listOf(
            "下载 Ubuntu 环境 (~200MB)",
            "安装 Python 和依赖",
            "克隆 ArcReel 仓库",
            "安装 Python 依赖",
            "启动 ArcReel 服务"
        )
        stepDetails = labels.mapIndexed { index, label ->
            SetupStepInfo(
                label = label,
                isCompleted = index < stepIndex || (index == stepIndex && isCompleted),
                isActive = index == stepIndex && isActive,
                isError = index == stepIndex && isError
            )
        }
    }

    // 自动开始安装（retryTrigger 变化时重新触发）
    LaunchedEffect(retryTrigger) {
        withContext(Dispatchers.IO) {
            try {
                // Step 1: 安装 Ubuntu 环境
                currentStep = SetupStep.INSTALLING_ENV
                updateSteps(0, isActive = true)
                message = "正在下载 Ubuntu 环境..."

                val envResult = ArcReelEnvironment.install(context)
                if (envResult.isFailure) {
                    errorMessage = envResult.exceptionOrNull()?.message ?: "环境安装失败"
                    currentStep = SetupStep.ERROR
                    updateSteps(0, isError = true)
                    return@withContext
                }
                updateSteps(0, isCompleted = true)

                // Step 2: 安装 ArcReel
                currentStep = SetupStep.INSTALLING_ARCREEL
                updateSteps(1, isActive = true)
                message = "正在安装 Python 依赖..."

                val installResult = ArcReelServiceController.install(context)
                if (installResult.isFailure) {
                    errorMessage = installResult.exceptionOrNull()?.message ?: "ArcReel 安装失败"
                    currentStep = SetupStep.ERROR
                    updateSteps(1, isError = true)
                    return@withContext
                }
                updateSteps(1, isCompleted = true)
                updateSteps(2, isCompleted = true)
                updateSteps(3, isCompleted = true)

                // Step 3: 启动服务
                currentStep = SetupStep.STARTING
                updateSteps(4, isActive = true)
                message = "正在启动 ArcReel 服务..."

                val startResult = ArcReelServiceController.start(context)
                if (startResult.isFailure) {
                    errorMessage = startResult.exceptionOrNull()?.message ?: "服务启动失败"
                    currentStep = SetupStep.ERROR
                    updateSteps(4, isError = true)
                    return@withContext
                }
                updateSteps(4, isCompleted = true)

                currentStep = SetupStep.COMPLETED
                message = "安装完成！"
                progress = 1f
            } catch (e: Exception) {
                errorMessage = e.message ?: "未知错误"
                currentStep = SetupStep.ERROR
            }
        }
    }

    // 监听环境状态
    val envState by ArcReelEnvironment.state.collectAsState()
    val serviceState by ArcReelServiceController.state.collectAsState()

    LaunchedEffect(envState, serviceState) {
        progress = when (currentStep) {
            SetupStep.INSTALLING_ENV -> envState.progress * 0.5f
            SetupStep.INSTALLING_ARCREEL -> 0.5f + serviceState.progress * 0.4f
            SetupStep.STARTING -> 0.9f + serviceState.progress * 0.1f
            SetupStep.COMPLETED -> 1f
            else -> progress
        }
        if (envState.message.isNotBlank()) message = envState.message
        if (serviceState.message.isNotBlank()) message = serviceState.message
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F23))
    ) {
        // 顶部栏
        TopAppBar(
            title = { Text("ArcReel 环境安装", fontSize = 18.sp, fontWeight = FontWeight.Medium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // 状态图标
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(
                        when (currentStep) {
                            SetupStep.ERROR -> Color(0xFFFF6B6B).copy(alpha = 0.15f)
                            SetupStep.COMPLETED -> Color(0xFF4CAF50).copy(alpha = 0.15f)
                            else -> Color(0xFF6C63FF).copy(alpha = 0.15f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when (currentStep) {
                        SetupStep.ERROR -> Icons.Default.Warning
                        SetupStep.COMPLETED -> Icons.Default.CheckCircle
                        else -> Icons.Default.Refresh
                    },
                    contentDescription = null,
                    tint = when (currentStep) {
                        SetupStep.ERROR -> Color(0xFFFF6B6B)
                        SetupStep.COMPLETED -> Color(0xFF4CAF50)
                        else -> Color(0xFF6C63FF)
                    },
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 标题
            Text(
                text = when (currentStep) {
                    SetupStep.CHECKING -> "正在检查环境"
                    SetupStep.INSTALLING_ENV -> "安装 Ubuntu 环境"
                    SetupStep.INSTALLING_ARCREEL -> "安装 ArcReel"
                    SetupStep.STARTING -> "启动服务"
                    SetupStep.COMPLETED -> "安装完成"
                    SetupStep.ERROR -> "安装失败"
                },
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = message,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 进度条
            if (currentStep != SetupStep.COMPLETED && currentStep != SetupStep.ERROR) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF6C63FF),
                    trackColor = Color(0xFF6C63FF).copy(alpha = 0.2f)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "${(progress * 100).toInt()}%",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 步骤列表
            if (stepDetails.isNotEmpty()) {
                stepDetails.forEach { step ->
                    StepItem(
                        label = step.label,
                        isCompleted = step.isCompleted,
                        isActive = step.isActive,
                        isError = step.isError
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // 操作按钮
            AnimatedVisibility(
                visible = currentStep == SetupStep.COMPLETED,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Button(
                    onClick = onComplete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6C63FF)
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("进入 ArcReel", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }

            AnimatedVisibility(
                visible = currentStep == SetupStep.ERROR,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    errorMessage?.let { error ->
                        Text(
                            text = error,
                            color = Color(0xFFFF6B6B),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = onBack,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Text("返回")
                        }
                        Button(
                            onClick = {
                                retryTrigger++
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6C63FF)
                            )
                        ) {
                            Text("重试")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepItem(
    label: String,
    isCompleted: Boolean,
    isActive: Boolean,
    isError: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    isError -> Color(0xFFFF6B6B).copy(alpha = 0.1f)
                    isActive -> Color(0xFF6C63FF).copy(alpha = 0.1f)
                    isCompleted -> Color(0xFF4CAF50).copy(alpha = 0.08f)
                    else -> Color.White.copy(alpha = 0.05f)
                }
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(
                    when {
                        isError -> Color(0xFFFF6B6B)
                        isCompleted -> Color(0xFF4CAF50)
                        isActive -> Color(0xFF6C63FF)
                        else -> Color.White.copy(alpha = 0.2f)
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            when {
                isError -> Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                isCompleted -> Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
                isActive -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                else -> Icon(
                    Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Text(
            text = label,
            color = when {
                isError -> Color(0xFFFF6B6B)
                isCompleted -> Color(0xFF4CAF50)
                isActive -> Color.White
                else -> Color.White.copy(alpha = 0.5f)
            },
            fontSize = 14.sp,
            fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
        )
    }
}