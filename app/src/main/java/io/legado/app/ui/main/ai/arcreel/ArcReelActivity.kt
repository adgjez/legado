package io.legado.app.ui.main.ai.arcreel

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * ArcReel 入口 Activity — 智能路由
 *
 * 根据环境安装状态：
 * 1. 未安装 → 显示安装引导
 * 2. 已安装，服务未运行 → 启动服务后跳转 WebView
 * 3. 服务运行中 → 直接跳转 WebView
 */
class ArcReelActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ArcReelEntryScreen(
                onFinish = { finish() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArcReelEntryScreen(onFinish: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var isChecking by remember { mutableStateOf(true) }
    var showSetup by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("检查 ArcReel 环境...") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var retryTrigger by remember { mutableStateOf(0) }

    // 检查环境状态
    LaunchedEffect(retryTrigger) {
        isChecking = true
        errorMessage = null
        withContext(Dispatchers.IO) {
            try {
                // 检查环境是否已安装
                if (!ArcReelEnvironment.isInstalled(context)) {
                    showSetup = true
                    isChecking = false
                    return@withContext
                }

                // 检查 ArcReel 服务是否已安装
                if (!ArcReelServiceController.isInstalled(context)) {
                    showSetup = true
                    isChecking = false
                    return@withContext
                }

                message = "正在启动 ArcReel 服务..."
                // 启动服务
                val startResult = ArcReelServiceController.start(context)
                if (startResult.isSuccess) {
                    // 跳转 WebView（需要在主线程）
                    withContext(Dispatchers.Main) {
                        context.startActivity(
                            Intent(context, ArcReelWebViewActivity::class.java)
                        )
                    }
                } else {
                    errorMessage = "服务启动失败: ${startResult.exceptionOrNull()?.message ?: "未知错误"}"
                }
            } catch (e: Exception) {
                errorMessage = "启动失败: ${e.message ?: "未知错误"}"
            } finally {
                isChecking = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F23))
    ) {
        TopAppBar(
            title = { Text("ArcReel", fontSize = 18.sp, fontWeight = FontWeight.Medium) },
            navigationIcon = {
                IconButton(onClick = onFinish) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White
            )
        )

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                showSetup -> {
                    ArcReelSetupScreen(
                        onBack = onFinish,
                        onComplete = {
                            context.startActivity(
                                Intent(context, ArcReelWebViewActivity::class.java)
                            )
                        }
                    )
                }

                isChecking -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF6C63FF))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(message, color = Color.White, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "首次启动需要下载约 30MB 数据",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 13.sp
                        )
                    }
                }

                errorMessage != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            "启动失败",
                            color = Color(0xFFFF6B6B),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            errorMessage!!,
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = { retryTrigger++ },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6C63FF)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("重试")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onFinish) {
                            Text("返回", color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}