package io.legado.app.ui.main.ai.arcreel

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.legado.app.help.ai.ArcReelEnvironment
import io.legado.app.help.ai.ArcReelServiceController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ArcReel WebView Activity — 展示 ArcReel Web UI
 *
 * 在 proot Ubuntu 环境中启动 ArcReel FastAPI 服务，
 * 然后通过 WebView 加载 http://localhost:1241
 */
class ArcReelWebViewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ArcReelWebViewContent(
                onBack = { finish() }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Activity 销毁时保持服务运行（用户可能切到后台继续用）
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArcReelWebViewContent(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var loadingProgress by remember { mutableStateOf(0) }
    var pageTitle by remember { mutableStateOf("ArcReel") }

    // 启动服务
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                // 确保环境已安装
                if (!ArcReelEnvironment.isInstalled(context)) {
                    errorMessage = "ArcReel 环境尚未安装，请先完成设置"
                    isLoading = false
                    return@withContext
                }

                // 确保 ArcReel 已安装
                if (!ArcReelServiceController.isInstalled(context)) {
                    errorMessage = "ArcReel 服务尚未安装，请先完成设置"
                    isLoading = false
                    return@withContext
                }

                // 启动服务
                ArcReelServiceController.start(context).onFailure {
                    errorMessage = "服务启动失败: ${it.message}"
                    isLoading = false
                }
            } catch (e: Exception) {
                errorMessage = "启动失败: ${e.message}"
                isLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏
        TopAppBar(
            title = { Text(pageTitle, fontSize = 18.sp, fontWeight = FontWeight.Medium) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                IconButton(onClick = {
                    webView?.reload()
                }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFF1A1A2E),
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White,
                actionIconContentColor = Color.White
            )
        )

        // 加载进度条
        if (loadingProgress in 1..99) {
            LinearProgressIndicator(
                progress = { loadingProgress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFF6C63FF)
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
            // WebView
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            setSupportZoom(true)
                            builtInZoomControls = true
                            displayZoomControls = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                            mixedContentMode =
                                android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageCommitVisible(
                                view: WebView,
                                url: String?
                            ) {
                                super.onPageCommitVisible(view, url)
                                isLoading = false
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest?,
                                error: android.webkit.WebResourceError?
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request?.isForMainFrame == true) {
                                    errorMessage = "无法连接到 ArcReel 服务"
                                    isLoading = false
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(
                                view: WebView,
                                newProgress: Int
                            ) {
                                loadingProgress = newProgress
                            }

                            override fun onReceivedTitle(
                                view: WebView,
                                title: String?
                            ) {
                                if (!title.isNullOrBlank() && title != "about:blank") {
                                    pageTitle = title
                                }
                            }
                        }
                        webView = this
                        loadUrl("http://localhost:1241")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // 加载中遮罩
            if (isLoading && errorMessage == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1A1A2E).copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF6C63FF))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "正在启动 ArcReel 服务...",
                            color = Color.White,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "首次启动可能需要较长时间",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            // 错误遮罩
            if (errorMessage != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF1A1A2E).copy(alpha = 0.95f)),
                    contentAlignment = Alignment.Center
                ) {
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
                            onClick = {
                                errorMessage = null
                                isLoading = true
                                scope.launch {
                                    webView?.loadUrl("http://localhost:1241")
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6C63FF)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("重试")
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onBack) {
                            Text("返回", color = Color.White.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }
    }
}