package io.legado.app.ui.main.ai.arcreel

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.legado.app.help.ai.*
import io.legado.app.ui.main.ai.compose.aiComposeStyle
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * ArcReel 活动 — 宿主Activity，承载完整的ArcReel管道UI
 * 从小说 → 角色/场景/道具设计 → 剧本 → 分镜 → 视频
 *
 * 支持两种启动方式：
 * 1. 从阅读页启动（带书籍和章节内容）
 * 2. 从AI聊天页启动（无内容，显示项目列表）
 */
class ArcReelActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bookName = intent.getStringExtra("bookName") ?: ""
        val author = intent.getStringExtra("author") ?: ""
        val bookUrl = intent.getStringExtra("bookUrl") ?: ""
        val content = intent.getStringExtra("content") ?: ""
        val chapterContentsJson = intent.getStringExtra("chapterContentsJson")
        val chapterContents: List<Pair<Int, String>>? = chapterContentsJson?.let { parseChapterContents(it) }
        val hasContent = bookName.isNotBlank() || content.isNotBlank()

        val activity = this

        setContent {
            val style = aiComposeStyle(this)
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = style.colors.pageBackground
            ) {
                if (hasContent) {
                    ArcReelActivityContent(
                        bookName = bookName.ifBlank { "未命名项目" },
                        author = author,
                        bookUrl = bookUrl,
                        content = content,
                        chapterContents = chapterContents,
                        onFinish = { activity.finish() }
                    )
                } else {
                    ArcReelEntryPoint(onFinish = { activity.finish() })
                }
            }
        }
    }
}

/**
 * 从AI聊天页启动时的入口：显示项目列表
 */
@Composable
private fun ArcReelEntryPoint(onFinish: () -> Unit) {
    var showProjectList by remember { mutableStateOf(true) }
    var loadedProject by remember { mutableStateOf<ArcReelProject?>(null) }

    if (showProjectList) {
        ArcReelProjectListScreen(
            onBack = onFinish,
            onOpenProject = { project ->
                loadedProject = project
                showProjectList = false
            },
            onNewProject = {
                loadedProject = ArcReelProject(name = "新项目")
                showProjectList = false
            }
        )
    } else {
        loadedProject?.let { project ->
            ArcReelActivityContent(
                bookName = project.bookName,
                author = project.author,
                bookUrl = project.bookUrl,
                content = "",
                chapterContents = null,
                initialProject = project,
                onFinish = {
                    showProjectList = true
                    loadedProject = null
                }
            )
        }
    }
}

@Composable
private fun ArcReelActivityContent(
    bookName: String,
    author: String,
    bookUrl: String,
    content: String,
    chapterContents: List<Pair<Int, String>>?,
    initialProject: ArcReelProject? = null,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var project by remember {
        mutableStateOf(
            initialProject ?: ArcReelProject(
                name = "$bookName - ArcReel",
                bookName = bookName,
                author = author,
                bookUrl = bookUrl
            )
        )
    }
    var currentScreen by remember { mutableStateOf<ArcReelScreen>(ArcReelScreen.MAIN) }
    var selectedChapter by remember { mutableStateOf<ChapterStoryboard?>(null) }
    var generateVideos by remember { mutableStateOf(false) }

    when (currentScreen) {
        ArcReelScreen.MAIN -> {
            ArcReelMainScreen(
                project = project,
                onBack = onFinish,
                onStartPipeline = { proj, generateVids ->
                    generateVideos = generateVids
                    val contents = chapterContents ?: listOf(0 to content)
                    try {
                        val result = ArcReelPipeline.executeFullPipeline(
                            project = proj,
                            chapterContents = contents,
                            contentText = content,
                            generateImages = true,
                            generateVideos = generateVids
                        )
                        // Auto-save after pipeline completes
                        ArcReelProjectRepository.saveProject(result)
                        result
                    } catch (e: Exception) {
                        val msg = e.message ?: "未知错误"
                        Toast.makeText(context, "管道执行出错：$msg", Toast.LENGTH_LONG).show()
                        proj
                    }
                },
                onSaveProject = { proj ->
                    scope.launch {
                        ArcReelProjectRepository.saveProject(proj)
                            .onSuccess { Toast.makeText(context, "项目已保存", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, "保存失败：${it.message}", Toast.LENGTH_SHORT).show() }
                    }
                },
                onViewStoryboard = { chapter ->
                    selectedChapter = chapter
                    currentScreen = ArcReelScreen.STORYBOARD
                },
                onViewCharacters = {
                    currentScreen = ArcReelScreen.DESIGN
                },
                onViewGallery = {
                    currentScreen = ArcReelScreen.GALLERY
                }
            )
        }
        ArcReelScreen.STORYBOARD -> {
            selectedChapter?.let { chapter ->
                ArcReelStoryboardScreen(
                    chapter = chapter,
                    onBack = { currentScreen = ArcReelScreen.MAIN },
                    onGenerateSceneImage = { sceneId ->
                        val scene = chapter.result.scenes.find { it.sceneId == sceneId }
                        scene?.let {
                            try {
                                AiSceneVisualizer.generateSceneImage(
                                    AiSceneVisualizer.SceneVisualization(
                                        sceneDescription = it.description,
                                        atmosphere = "",
                                        moodKeywords = emptyList(),
                                        imagePrompt = it.visualPrompt,
                                        imagePromptCN = it.visualPrompt
                                    )
                                )
                            } catch (e: Exception) {
                                Toast.makeText(context, "图片生成失败：${e.message}", Toast.LENGTH_SHORT).show()
                                null
                            }
                        }
                    }
                )
            }
        }
        ArcReelScreen.DESIGN -> {
            ArcReelDesignWorkshopScreen(
                project = project,
                onBack = { currentScreen = ArcReelScreen.MAIN },
                onGenerateCharacterImage = { name ->
                    val char = project.characters.find { it.name == name }
                    char?.let {
                        try {
                            val vis = AiSceneVisualizer.visualizeCharacter(
                                name = it.name,
                                description = it.appearance,
                                context = "${it.identity} · ${it.personality}"
                            )
                            AiSceneVisualizer.generateCharacterImage(vis)
                        } catch (e: Exception) {
                            Toast.makeText(context, "图片生成失败：${e.message}", Toast.LENGTH_SHORT).show()
                            null
                        }
                    }
                },
                onGenerateSceneImage = { name ->
                    val scene = project.scenes.find { it.name == name }
                    scene?.let {
                        try {
                            AiImageService.generate(it.imagePromptCN.ifBlank { it.imagePrompt })
                        } catch (e: Exception) {
                            Toast.makeText(context, "图片生成失败：${e.message}", Toast.LENGTH_SHORT).show()
                            null
                        }
                    }
                }
            )
        }
        ArcReelScreen.GALLERY -> {
            val galleryItems = remember(project) { AiSceneGallery.buildGallery(project) }
            ArcReelGalleryScreen(
                items = galleryItems,
                onBack = { currentScreen = ArcReelScreen.MAIN }
            )
        }
    }
}

private enum class ArcReelScreen { MAIN, STORYBOARD, DESIGN, GALLERY }

private fun parseChapterContents(json: String): List<Pair<Int, String>> {
    return try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            obj.getInt("index") to obj.getString("content")
        }
    } catch (_: Exception) { emptyList() }
}