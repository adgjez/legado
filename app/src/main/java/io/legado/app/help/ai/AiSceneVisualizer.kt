package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.ui.main.ai.AiImageProviderConfig
import org.json.JSONObject

/**
 * ArcReel 集成 — AI 场景可视化服务
 * 在阅读时为当前章节内容生成场景插画、氛围描述和角色形象
 */
object AiSceneVisualizer {

    data class SceneContext(
        val bookName: String,
        val author: String,
        val chapterTitle: String,
        val paragraphText: String,
        val surroundingContext: String = ""
    )

    data class SceneVisualization(
        val sceneDescription: String,
        val atmosphere: String,
        val moodKeywords: List<String>,
        val imagePrompt: String,
        val imagePromptCN: String,
        val generatedImageUrl: String? = null
    )

    data class CharacterVisualization(
        val name: String,
        val appearance: String,
        val imagePrompt: String,
        val generatedImageUrl: String? = null
    )

    /**
     * 分析当前场景并生成可视化描述
     */
    suspend fun analyzeScene(
        context: SceneContext,
        onPartial: (String) -> Unit = {}
    ): SceneVisualization {
        val content = buildString {
            if (context.surroundingContext.isNotBlank()) {
                append("上下文：\n${context.surroundingContext.take(800)}\n\n")
            }
            append("当前段落：\n${context.paragraphText.take(3000)}")
        }

        val messages = listOf(
            AiChatMessage(
                role = AiChatMessage.Role.USER,
                content = """
                    你是小说场景分析专家。分析以下小说片段，提取场景视觉信息。

                    书名：${context.bookName}
                    作者：${context.author}
                    章节：${context.chapterTitle}

                    请用JSON格式输出（不要包含markdown代码块标记）：
                    {
                      "sceneDescription": "场景中文描述，50-100字",
                      "atmosphere": "氛围描述，20字以内",
                      "moodKeywords": ["关键词1", "关键词2", "关键词3"],
                      "imagePrompt": "英文AI绘画提示词，包含艺术风格、光线、颜色、构图，适合Midjourney/Stable Diffusion",
                      "imagePromptCN": "中文AI绘画提示词，适合国内AI绘画模型"
                    }

                    $content
                """.trimIndent()
            )
        )
        val raw = AiChatService.chatStream(
            messages = messages,
            onPartial = onPartial,
            includeStructuredBlocks = false,
            useAllTools = false,
            modelConfigOverride = AppConfig.aiSummaryModelConfig
        )
        return parseSceneJson(raw)
    }

    /**
     * 为场景生成AI插画
     */
    suspend fun generateSceneImage(
        visualization: SceneVisualization,
        provider: AiImageProviderConfig? = null
    ): String? {
        return try {
            val prompt = visualization.imagePromptCN.ifBlank { visualization.imagePrompt }
            if (prompt.isBlank()) return null
            AiImageService.generate(prompt, provider)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 生成角色形象可视化
     */
    suspend fun visualizeCharacter(
        name: String,
        description: String,
        context: String = "",
        onPartial: (String) -> Unit = {}
    ): CharacterVisualization {
        val messages = listOf(
            AiChatMessage(
                role = AiChatMessage.Role.USER,
                content = """
                    你是角色形象设计师。根据以下角色信息，生成角色可视化描述。

                    角色名：$name
                    角色描述：$description
                    ${if (context.isNotBlank()) "上下文：$context" else ""}

                    请用JSON格式输出（不要包含markdown代码块标记）：
                    {
                      "appearance": "角色外貌详细描述，80-120字",
                      "imagePrompt": "英文AI绘画提示词，包含角色特征、服装、风格、光线，适合生成角色立绘"
                    }
                """.trimIndent()
            )
        )
        val raw = AiChatService.chatStream(
            messages = messages,
            onPartial = onPartial,
            includeStructuredBlocks = false,
            useAllTools = false,
            modelConfigOverride = AppConfig.aiSummaryModelConfig
        )
        return parseCharacterJson(raw, name)
    }

    /**
     * 为角色生成形象图
     */
    suspend fun generateCharacterImage(
        visualization: CharacterVisualization,
        provider: AiImageProviderConfig? = null
    ): String? {
        return try {
            if (visualization.imagePrompt.isBlank()) return null
            AiImageService.generate(visualization.imagePrompt, provider)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 批量生成场景插画（用于分镜/剧本的每个场景）
     */
    suspend fun generateSceneImages(
        scenes: List<AiStoryboardService.SceneCard>,
        provider: AiImageProviderConfig? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<Pair<Int, String?>> {
        return scenes.mapIndexed { index, scene ->
            onProgress(index + 1, scenes.size)
            val imageUrl = try {
                val prompt = scene.visualPrompt.ifBlank { scene.description }
                if (prompt.isBlank()) null
                else AiImageService.generate(prompt, provider)
            } catch (_: Exception) {
                null
            }
            scene.sceneId to imageUrl
        }
    }

    private fun parseSceneJson(raw: String): SceneVisualization {
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val json = JSONObject(cleaned)
            SceneVisualization(
                sceneDescription = json.optString("sceneDescription", ""),
                atmosphere = json.optString("atmosphere", ""),
                moodKeywords = json.optJSONArray("moodKeywords")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                }.orEmpty(),
                imagePrompt = json.optString("imagePrompt", ""),
                imagePromptCN = json.optString("imagePromptCN", "")
            )
        } catch (_: Exception) {
            SceneVisualization(
                sceneDescription = raw.take(200),
                atmosphere = "",
                moodKeywords = emptyList(),
                imagePrompt = "",
                imagePromptCN = ""
            )
        }
    }

    private fun parseCharacterJson(raw: String, name: String): CharacterVisualization {
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val json = JSONObject(cleaned)
            CharacterVisualization(
                name = name,
                appearance = json.optString("appearance", ""),
                imagePrompt = json.optString("imagePrompt", "")
            )
        } catch (_: Exception) {
            CharacterVisualization(
                name = name,
                appearance = raw.take(200),
                imagePrompt = ""
            )
        }
    }
}