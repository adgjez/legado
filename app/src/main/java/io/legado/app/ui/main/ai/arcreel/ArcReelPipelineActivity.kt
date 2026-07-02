package io.legado.app.ui.main.ai.arcreel

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
import io.legado.app.help.ai.ArcReelPipeline
import io.legado.app.help.ai.ArcReelProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ArcReel AI 管道 Activity — 角色设计、分镜、视频生成
 */
class ArcReelPipelineActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bookName = intent.getStringExtra("bookName") ?: ""
        val author = intent.getStringExtra("author") ?: ""
        val content = intent.getStringExtra("content") ?: ""

        setContent {
            ArcReelPipelineScreen(
                bookName = bookName,
                author = author,
                content = content,
                onBack = { finish() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArcReelPipelineScreen(
    bookName: String,
    author: String,
    content: String,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf<ArcReelProject?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val p = ArcReelProject(
                    name = bookName.ifBlank { "新项目" },
                    bookName = bookName,
                    author = author
                )
                project = p
                isLoading = false
            } catch (e: Exception) {
                errorMessage = e.message ?: "创建项目失败"
                isLoading = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("AI 管道", fontSize = 18.sp, fontWeight = FontWeight.Medium) },
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

        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F0F23)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color(0xFF6C63FF))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("创建项目...", color = Color.White, fontSize = 16.sp)
                    }
                }
            }

            errorMessage != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF0F0F23)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text("创建失败", color = Color(0xFFFF6B6B), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(errorMessage!!, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(onClick = onBack, shape = RoundedCornerShape(8.dp)) {
                            Text("返回")
                        }
                    }
                }
            }

            project != null -> {
                val p = project!!
                ArcReelMainScreen(
                    project = p,
                    onBack = onBack,
                    onStartPipeline = { prj ->
                        scope.launch(Dispatchers.IO) {
                            ArcReelPipeline.executeFullPipeline(
                                project = prj,
                                chapterContents = listOf(0 to content),
                                contentText = content
                            )
                        }
                        prj
                    },
                    onViewStoryboard = { /* TODO: 查看分镜详情 */ },
                    onViewCharacters = { /* TODO: 查看角色设计 */ },
                    onViewGallery = { /* TODO: 查看画廊 */ }
                )
            }
        }
    }
}