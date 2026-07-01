package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiImageProviderConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * ArcReel 集成 — 项目管理与全流程管道
 * 小说 → 角色/场景/道具设计 → 剧本 → 分镜 → 视频
 *
 * 核心管道状态机，驱动整个 AI 创作流程
 */
data class ArcReelProject(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val bookName: String = "",
    val author: String = "",
    val bookUrl: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // 阶段产物
    val characters: List<AiCharacterDesignService.CharacterDesign> = emptyList(),
    val scenes: List<AiCharacterDesignService.SceneDesign> = emptyList(),
    val props: List<AiCharacterDesignService.PropDesign> = emptyList(),
    val worldStyle: String = "",
    val artStyle: String = "",
    val storyboard: AiStoryboardService.StoryboardResult? = null,
    val storyboards: List<ChapterStoryboard> = emptyList(), // 多章节分镜
    val sceneImages: Map<Int, String> = emptyMap(),         // sceneId → imageUrl
    val characterImages: Map<String, String> = emptyMap(),  // characterName → imageUrl
    val videos: List<VideoOutput> = emptyList(),
    val status: ProjectStatus = ProjectStatus.DRAFT,
    val currentPhase: PipelinePhase = PipelinePhase.IDLE,
    val phaseProgress: Float = 0f,  // 0..1
    val phaseMessage: String = ""
) {
    enum class ProjectStatus { DRAFT, DESIGNING, STORYBOARDING, GENERATING, REVIEWING, COMPLETED }
    enum class PipelinePhase {
        IDLE,
        EXTRACTING_CHARACTERS,
        EXTRACTING_SCENES,
        EXTRACTING_PROPS,
        GENERATING_CHARACTER_IMAGES,
        GENERATING_SCENE_IMAGES,
        GENERATING_STORYBOARD,
        GENERATING_SCENE_SHOTS,
        GENERATING_VIDEO,
        COMPLETED
    }
}

data class ChapterStoryboard(
    val chapterIndex: Int,
    val chapterTitle: String,
    val result: AiStoryboardService.StoryboardResult,
    val sceneImages: Map<Int, String> = emptyMap()
)

data class VideoOutput(
    val id: String = UUID.randomUUID().toString(),
    val sceneId: Int,
    val sceneTitle: String,
    val videoUrl: String,
    val localPath: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class PipelineState(
    val phase: ArcReelProject.PipelinePhase = ArcReelProject.PipelinePhase.IDLE,
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null
)

object ArcReelPipeline {

    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state

    /**
     * 执行完整管道：小说 → 角色 → 场景 → 分镜 → 视频
     */
    suspend fun executeFullPipeline(
        project: ArcReelProject,
        chapterContents: List<Pair<Int, String>>, // (chapterIndex, content)
        contentText: String,
        imageProvider: AiImageProviderConfig? = null,
        generateImages: Boolean = true,
        generateVideos: Boolean = false
    ): ArcReelProject {
        var current = project.copy(status = ArcReelProject.ProjectStatus.DESIGNING)
        val designInput = AiCharacterDesignService.DesignInput(
            bookName = project.bookName,
            author = project.author,
            content = contentText
        )

        // Phase 1: 角色设计
        updatePhase(ArcReelProject.PipelinePhase.EXTRACTING_CHARACTERS, 0f, "正在分析角色...")
        saveProgress(project.id, ArcReelProject.PipelinePhase.EXTRACTING_CHARACTERS, 0f, "正在分析角色...")
        val characters = AiCharacterDesignService.extractCharacters(designInput)
        updatePhase(ArcReelProject.PipelinePhase.EXTRACTING_CHARACTERS, 1f, "已提取 ${characters.size} 个角色")
        saveProgress(project.id, ArcReelProject.PipelinePhase.EXTRACTING_CHARACTERS, 1f, "已提取 ${characters.size} 个角色")

        // Phase 2: 场景设计
        updatePhase(ArcReelProject.PipelinePhase.EXTRACTING_SCENES, 0f, "正在分析场景...")
        saveProgress(project.id, ArcReelProject.PipelinePhase.EXTRACTING_SCENES, 0f, "正在分析场景...")
        val scenes = AiCharacterDesignService.extractScenes(designInput)
        updatePhase(ArcReelProject.PipelinePhase.EXTRACTING_SCENES, 1f, "已提取 ${scenes.size} 个场景")
        saveProgress(project.id, ArcReelProject.PipelinePhase.EXTRACTING_SCENES, 1f, "已提取 ${scenes.size} 个场景")

        // Phase 3: 道具设计
        updatePhase(ArcReelProject.PipelinePhase.EXTRACTING_PROPS, 0f, "正在分析道具...")
        saveProgress(project.id, ArcReelProject.PipelinePhase.EXTRACTING_PROPS, 0f, "正在分析道具...")
        val props = AiCharacterDesignService.extractProps(designInput)
        updatePhase(ArcReelProject.PipelinePhase.EXTRACTING_PROPS, 1f, "已提取 ${props.size} 个道具")
        saveProgress(project.id, ArcReelProject.PipelinePhase.EXTRACTING_PROPS, 1f, "已提取 ${props.size} 个道具")

        current = current.copy(
            characters = characters,
            scenes = scenes,
            props = props,
            worldStyle = designInput.let { extractWorldStyle(it) },
            artStyle = designInput.let { inferArtStyle(it) }
        )

        // Phase 4: 生成角色形象图（使用增强版批量生成）
        if (generateImages && characters.isNotEmpty()) {
            updatePhase(ArcReelProject.PipelinePhase.GENERATING_CHARACTER_IMAGES, 0f, "正在生成角色形象图...")
            val prompts = characters.map { it.imagePromptCN.ifBlank { it.imagePrompt } }
            val batchResults = AiImageService.generateBatch(
                prompts = prompts,
                provider = imageProvider,
                maxConcurrent = 3,
                onProgress = { done, total, _ ->
                    updatePhase(
                        ArcReelProject.PipelinePhase.GENERATING_CHARACTER_IMAGES,
                        done.toFloat() / total.coerceAtLeast(1),
                        "角色形象图 $done/$total"
                    )
                }
            )
            val charWithImages = characters.mapIndexed { index, char ->
                val url = batchResults.getOrNull(index)?.imageUrl
                char.copy(generatedImageUrl = url)
            }
            val charImages = charWithImages.filter { it.generatedImageUrl != null }
                .associate { it.name to it.generatedImageUrl!! }
            current = current.copy(
                characters = charWithImages,
                characterImages = current.characterImages + charImages
            )
        }

        // Phase 5: 生成场景概念图（使用增强版批量生成）
        if (generateImages && scenes.isNotEmpty()) {
            updatePhase(ArcReelProject.PipelinePhase.GENERATING_SCENE_IMAGES, 0f, "正在生成场景概念图...")
            val prompts = scenes.map { it.imagePromptCN.ifBlank { it.imagePrompt } }
            val batchResults = AiImageService.generateBatch(
                prompts = prompts,
                provider = imageProvider,
                maxConcurrent = 3,
                onProgress = { done, total, _ ->
                    updatePhase(
                        ArcReelProject.PipelinePhase.GENERATING_SCENE_IMAGES,
                        done.toFloat() / total.coerceAtLeast(1),
                        "场景概念图 $done/$total"
                    )
                }
            )
            val scenesWithImages = scenes.mapIndexed { index, scene ->
                val url = batchResults.getOrNull(index)?.imageUrl
                scene.copy(generatedImageUrl = url)
            }
            current = current.copy(scenes = scenesWithImages)
        }

        // Phase 6: 生成分镜（每个章节）
        current = current.copy(status = ArcReelProject.ProjectStatus.STORYBOARDING)
        updatePhase(ArcReelProject.PipelinePhase.GENERATING_STORYBOARD, 0f, "正在生成分镜...")
        val storyboards = mutableListOf<ChapterStoryboard>()
        val existingChars = current.characters.joinToString("\n") { c ->
            "- ${c.name}(${c.role}): ${c.appearance.take(100)}"
        }
        chapterContents.forEachIndexed { index, (chapterIdx, content) ->
            updatePhase(
                ArcReelProject.PipelinePhase.GENERATING_STORYBOARD,
                (index + 1).toFloat() / chapterContents.size,
                "分镜 ${index + 1}/${chapterContents.size}"
            )
            val input = AiStoryboardService.StoryboardInput(
                bookName = project.bookName,
                author = project.author,
                chapterTitle = "第${chapterIdx + 1}章",
                content = content,
                existingCharacters = existingChars
            )
            val result = AiStoryboardService.generateStoryboard(input)

            // 为分镜场景生成插画（使用批量生成）
            var sceneImages = emptyMap<Int, String>()
            if (generateImages && result.scenes.isNotEmpty()) {
                val prompts = result.scenes.map { it.visualPrompt.ifBlank { it.description } }
                val batchResults = AiImageService.generateBatch(
                    prompts = prompts,
                    provider = imageProvider,
                    maxConcurrent = 3,
                    onProgress = { done, total, _ ->
                        updatePhase(
                            ArcReelProject.PipelinePhase.GENERATING_SCENE_SHOTS,
                            done.toFloat() / total.coerceAtLeast(1),
                            "场景插画 $done/$total"
                        )
                    }
                )
                sceneImages = batchResults
                    .filter { it.imageUrl != null }
                    .associate { Pair(result.scenes.getOrNull(it.index)?.sceneId ?: it.index, it.imageUrl!!) }
            }
            storyboards.add(ChapterStoryboard(chapterIdx, "第${chapterIdx + 1}章", result, sceneImages))
        }

        // Phase 7: 视频生成（可选，使用增强版进度追踪）
        val videos = mutableListOf<VideoOutput>()
        if (generateVideos) {
            updatePhase(ArcReelProject.PipelinePhase.GENERATING_VIDEO, 0f, "正在生成视频...")
            storyboards.firstOrNull()?.result?.scenes?.firstOrNull()?.let { scene ->
                try {
                    val prompt = scene.visualPrompt.ifBlank { scene.description }
                    if (prompt.isNotBlank() && AiCreationService.hasApiKey()) {
                        val result = AiCreationService.textToVideoWithProgress(prompt = prompt)
                        // 监控进度
                        AiCreationService.progress.collect { p ->
                            updatePhase(
                                ArcReelProject.PipelinePhase.GENERATING_VIDEO,
                                p.percent / 100f,
                                "视频生成 ${p.percent}% ${p.statusText}"
                            )
                        }
                        videos.add(VideoOutput(
                            sceneId = scene.sceneId,
                            sceneTitle = scene.sceneTitle,
                            videoUrl = result.videoUrl,
                            localPath = result.localPath
                        ))
                    }
                } catch (_: Exception) { }
            }
        }

        updatePhase(ArcReelProject.PipelinePhase.COMPLETED, 1f, "完成")
        saveProgress(project.id, ArcReelProject.PipelinePhase.COMPLETED, 1f, "完成")
        return current.copy(
            storyboards = storyboards,
            storyboard = storyboards.firstOrNull()?.result,
            videos = videos,
            status = ArcReelProject.ProjectStatus.COMPLETED,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * 获取管道成本预估
     */
    fun estimateCost(project: ArcReelProject): JSONObject {
        return AiModelManager.estimatePipelineCost(
            characterCount = project.characters.size.coerceAtLeast(3),
            sceneCount = project.scenes.size.coerceAtLeast(3),
            chapterCount = project.storyboards.size.coerceAtLeast(1),
            generateImages = true,
            generateVideos = project.videos.isNotEmpty()
        )
    }

    /**
     * 增量更新：为已有项目处理新章节
     */
    suspend fun processNewChapter(
        project: ArcReelProject,
        chapterIndex: Int,
        chapterTitle: String,
        content: String,
        imageProvider: AiImageProviderConfig? = null,
        generateImages: Boolean = true
    ): ArcReelProject {
        val existingChars = project.characters.joinToString("\n") { c ->
            "- ${c.name}(${c.role}): ${c.appearance.take(100)}"
        }
        updatePhase(ArcReelProject.PipelinePhase.GENERATING_STORYBOARD, 0f, "正在生成分镜...")
        val input = AiStoryboardService.StoryboardInput(
            bookName = project.bookName,
            author = project.author,
            chapterTitle = chapterTitle,
            content = content,
            existingCharacters = existingChars
        )
        val result = AiStoryboardService.generateStoryboard(input)

        var sceneImages = emptyMap<Int, String>()
        if (generateImages && result.scenes.isNotEmpty()) {
            updatePhase(ArcReelProject.PipelinePhase.GENERATING_SCENE_SHOTS, 0f, "正在生成场景插画...")
            val prompts = result.scenes.map { it.visualPrompt.ifBlank { it.description } }
            val batchResults = AiImageService.generateBatch(
                prompts = prompts,
                provider = imageProvider,
                maxConcurrent = 3
            )
            sceneImages = batchResults
                .filter { it.imageUrl != null }
                .associate { Pair(result.scenes.getOrNull(it.index)?.sceneId ?: it.index, it.imageUrl!!) }
        }

        val newStoryboard = ChapterStoryboard(chapterIndex, chapterTitle, result, sceneImages)
        updatePhase(ArcReelProject.PipelinePhase.COMPLETED, 1f, "完成")

        return project.copy(
            storyboards = project.storyboards + newStoryboard,
            updatedAt = System.currentTimeMillis()
        )
    }

    /**
     * 获取项目进度的可序列化快照
     */
    fun projectProgressJson(project: ArcReelProject): JSONObject {
        return JSONObject().apply {
            put("id", project.id)
            put("name", project.name)
            put("status", project.status.name)
            put("phase", project.currentPhase.name)
            put("phaseProgress", project.phaseProgress.toDouble())
            put("phaseMessage", project.phaseMessage)
            put("characterCount", project.characters.size)
            put("sceneCount", project.scenes.size)
            put("propCount", project.props.size)
            put("storyboardCount", project.storyboards.size)
            put("videoCount", project.videos.size)
            put("characterImageCount", project.characterImages.size)
            put("sceneImageCount", project.sceneImages.size)
        }
    }

    private fun updatePhase(phase: ArcReelProject.PipelinePhase, progress: Float, message: String) {
        _state.value = PipelineState(phase, progress, message)
    }

    private suspend fun saveProgress(projectId: String, phase: ArcReelProject.PipelinePhase, progress: Float, message: String) {
        ArcReelProjectRepository.savePipelineProgress(projectId, phase, progress, message)
    }

    private suspend fun extractWorldStyle(input: AiCharacterDesignService.DesignInput): String {
        return try {
            val messages = listOf(
                io.legado.app.ui.main.ai.AiChatMessage(
                    role = io.legado.app.ui.main.ai.AiChatMessage.Role.USER,
                    content = "分析小说世界观风格，一句话描述：\n书名：${input.bookName}\n内容：${input.content.take(2000)}"
                )
            )
            AiChatService.chatStream(
                messages = messages,
                onPartial = {},
                includeStructuredBlocks = false,
                useAllTools = false,
                modelConfigOverride = AppConfig.aiSummaryModelConfig
            ).trim().take(100)
        } catch (_: Exception) { "" }
    }

    private suspend fun inferArtStyle(input: AiCharacterDesignService.DesignInput): String {
        return try {
            val messages = listOf(
                io.legado.app.ui.main.ai.AiChatMessage(
                    role = io.legado.app.ui.main.ai.AiChatMessage.Role.USER,
                    content = "推荐AI绘画画风：\n书名：${input.bookName}\n内容：${input.content.take(1000)}"
                )
            )
            AiChatService.chatStream(
                messages = messages,
                onPartial = {},
                includeStructuredBlocks = false,
                useAllTools = false,
                modelConfigOverride = AppConfig.aiSummaryModelConfig
            ).trim().take(100)
        } catch (_: Exception) { "写实" }
    }
}