package io.legado.app.ui.main.ai.arcreel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import io.legado.app.help.ai.*
import io.legado.app.ui.main.ai.compose.aiComposeStyle
import org.json.JSONArray
import org.json.JSONObject

/**
 * ArcReel 活动 — 宿主Activity，承载完整的ArcReel管道UI
 * 从小说 → 角色/场景/道具设计 → 剧本 → 分镜 → 视频
 */
class ArcReelActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bookName = intent.getStringExtra("bookName") ?: "未命名项目"
        val author = intent.getStringExtra("author") ?: ""
        val bookUrl = intent.getStringExtra("bookUrl") ?: ""
        val content = intent.getStringExtra("content") ?: ""
        val chapterContentsJson = intent.getStringExtra("chapterContentsJson")
        val chapterContents: List<Pair<Int, String>>? = chapterContentsJson?.let { parseChapterContents(it) }

        val activity = this

        setContent {
            val style = aiComposeStyle(this)
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = style.colors.pageBackground
            ) {
                ArcReelActivityContent(
                    bookName = bookName,
                    author = author,
                    bookUrl = bookUrl,
                    content = content,
                    chapterContents = chapterContents,
                    onFinish = { activity.finish() }
                )
            }
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
    onFinish: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var project by remember {
        mutableStateOf(
            ArcReelProject(
                name = "$bookName - ArcReel",
                bookName = bookName,
                author = author,
                bookUrl = bookUrl
            )
        )
    }
    var currentScreen by remember { mutableStateOf<ArcReelScreen>(ArcReelScreen.MAIN) }
    var selectedChapter by remember { mutableStateOf<ChapterStoryboard?>(null) }

    when (currentScreen) {
        ArcReelScreen.MAIN -> {
            ArcReelMainScreen(
                project = project,
                onBack = onFinish,
                onStartPipeline = { proj ->
                    val contents = chapterContents ?: listOf(0 to content)
                    ArcReelPipeline.executeFullPipeline(
                        project = proj,
                        chapterContents = contents,
                        contentText = content,
                        generateImages = true,
                        generateVideos = false
                    ).also { project = it }
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
                            } catch (_: Exception) { null }
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
                        } catch (_: Exception) { null }
                    }
                },
                onGenerateSceneImage = { name ->
                    val scene = project.scenes.find { it.name == name }
                    scene?.let {
                        try {
                            AiImageService.generate(it.imagePromptCN.ifBlank { it.imagePrompt })
                        } catch (_: Exception) { null }
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

private fun encodeChapterContents(chapters: List<Pair<Int, String>>): String {
    return JSONArray().apply {
        chapters.forEach { (index, content) ->
            put(JSONObject().apply {
                put("index", index)
                put("content", content)
            })
        }
    }.toString()
}