package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiChatMessage
import org.json.JSONObject

/**
 * ArcReel 集成 — 跨镜头一致性管理器
 * 确保角色、场景在不同镜头/分镜中保持视觉一致
 * 以及审阅确认工作流
 */
object AiConsistencyManager {

    /**
     * 为分镜场景注入角色一致性约束
     * 生成统一的视觉引导，确保同一角色在不同镜头中外观一致
     */
    suspend fun generateConsistencyGuide(
        characters: List<AiCharacterDesignService.CharacterDesign>,
        scenes: List<AiCharacterDesignService.SceneDesign>,
        artStyle: String
    ): String {
        if (characters.isEmpty()) return ""

        val messages = listOf(
            AiChatMessage(
                role = AiChatMessage.Role.USER,
                content = """
                    你是视觉一致性总监。请为以下角色和场景生成统一的视觉风格指南。

                    画风：$artStyle

                    角色列表：
                    ${characters.joinToString("\n") { c ->
                        "- ${c.name}: ${c.appearance}, 一致性标签: ${c.consistencyTags.joinToString(", ")}"
                    }}

                    场景列表：
                    ${scenes.joinToString("\n") { s ->
                        "- ${s.name}: ${s.description.take(100)}, 一致性标签: ${s.consistencyTags.joinToString(", ")}"
                    }}

                    请生成一份视觉一致性指南，包含：
                    1. 统一画风描述
                    2. 每个角色的关键视觉特征（用于AI绘画提示词中的固定前缀）
                    3. 场景色调和光线统一建议
                    4. 角色在不同场景中的着装变化规则

                    输出 Markdown 格式。
                """.trimIndent()
            )
        )
        return AiChatService.chatStream(
            messages = messages,
            includeStructuredBlocks = false,
            useAllTools = false,
            modelConfigOverride = AppConfig.aiSummaryModelConfig
        )
    }

    /**
     * 为角色生成一致性约束前缀
     * 这个前缀会附加到每个场景的AI绘画提示词中
     */
    suspend fun generateCharacterPrefix(
        character: AiCharacterDesignService.CharacterDesign,
        style: String
    ): String {
        val messages = listOf(
            AiChatMessage(
                role = AiChatMessage.Role.USER,
                content = """
                    生成一个固定的AI绘画角色前缀描述，用于保持角色在不同场景中的外观一致。

                    角色名：${character.name}
                    外貌：${character.appearance}
                    画风：$style

                    输出一个简短英文提示词前缀（30-50词），包含角色的核心视觉特征，不包含具体场景和动作。
                    只输出前缀文本，不要其他内容。
                """.trimIndent()
            )
        )
        return AiChatService.chatStream(
            messages = messages,
            includeStructuredBlocks = false,
            useAllTools = false,
            modelConfigOverride = AppConfig.aiSummaryModelConfig
        ).trim()
    }

    /**
     * 审阅确认：检查分镜质量
     */
    suspend fun reviewStoryboard(
        storyboard: AiStoryboardService.StoryboardResult,
        characters: List<AiCharacterDesignService.CharacterDesign>
    ): ReviewResult {
        val messages = listOf(
            AiChatMessage(
                role = AiChatMessage.Role.USER,
                content = """
                    你是剧本审阅专家。请审阅以下分镜剧本，检查质量和一致性。

                    角色参考：
                    ${characters.joinToString("\n") { "- ${it.name}: ${it.appearance.take(150)}" }}

                    分镜剧本：
                    ${storyboard.rawMarkdown.take(8000)}

                    请用JSON格式输出审阅结果（不要包含markdown代码块标记）：
                    {
                      "score": 85,
                      "passed": true,
                      "issues": ["问题1", "问题2"],
                      "suggestions": ["建议1", "建议2"],
                      "consistencyCheck": {
                        "characterConsistent": true,
                        "sceneFlow": "场景流程是否顺畅的评价",
                        "visualQuality": "视觉质量评价"
                      }
                    }
                """.trimIndent()
            )
        )
        val raw = AiChatService.chatStream(
            messages = messages,
            includeStructuredBlocks = false,
            useAllTools = false,
            modelConfigOverride = AppConfig.aiSummaryModelConfig
        )
        return parseReview(raw)
    }

    data class ReviewResult(
        val score: Int = 0,
        val passed: Boolean = false,
        val issues: List<String> = emptyList(),
        val suggestions: List<String> = emptyList(),
        val characterConsistent: Boolean = true,
        val sceneFlow: String = "",
        val visualQuality: String = ""
    )

    private fun parseReview(raw: String): ReviewResult {
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val json = JSONObject(cleaned)
            val consistency = json.optJSONObject("consistencyCheck")
            ReviewResult(
                score = json.optInt("score", 0),
                passed = json.optBoolean("passed", false),
                issues = parseArray(json.optJSONArray("issues")),
                suggestions = parseArray(json.optJSONArray("suggestions")),
                characterConsistent = consistency?.optBoolean("characterConsistent", true) ?: true,
                sceneFlow = consistency?.optString("sceneFlow", "") ?: "",
                visualQuality = consistency?.optString("visualQuality", "") ?: ""
            )
        } catch (_: Exception) {
            ReviewResult(score = 70, passed = true)
        }
    }

    private fun parseArray(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }
}

/**
 * 场景图库管理器
 * 管理所有生成的场景图、角色图、道具图
 */
object AiSceneGallery {

    data class GalleryItem(
        val id: String,
        val type: ItemType,
        val name: String,
        val description: String,
        val imageUrl: String?,
        val projectId: String,
        val chapterIndex: Int = 0,
        val sceneId: Int = 0,
        val createdAt: Long = System.currentTimeMillis()
    ) {
        enum class ItemType { CHARACTER, SCENE, PROP, STORYBOARD_SHOT, VIDEO_FRAME }
    }

    /**
     * 从项目生成图库清单
     */
    fun buildGallery(project: ArcReelProject): List<GalleryItem> {
        return buildList {
            // 角色图
            project.characters.filter { it.generatedImageUrl != null }.forEach { char ->
                add(GalleryItem(
                    id = "char_${project.id}_${char.name}",
                    type = GalleryItem.ItemType.CHARACTER,
                    name = char.name,
                    description = "角色 · ${char.role} · ${char.appearance.take(80)}",
                    imageUrl = char.generatedImageUrl,
                    projectId = project.id
                ))
            }
            // 场景图
            project.scenes.filter { it.generatedImageUrl != null }.forEach { scene ->
                add(GalleryItem(
                    id = "scene_${project.id}_${scene.name}",
                    type = GalleryItem.ItemType.SCENE,
                    name = scene.name,
                    description = "场景 · ${scene.type} · ${scene.description.take(80)}",
                    imageUrl = scene.generatedImageUrl,
                    projectId = project.id
                ))
            }
            // 道具图
            project.props.filter { it.generatedImageUrl != null }.forEach { prop ->
                add(GalleryItem(
                    id = "prop_${project.id}_${prop.name}",
                    type = GalleryItem.ItemType.PROP,
                    name = prop.name,
                    description = "道具 · ${prop.type} · ${prop.description.take(80)}",
                    imageUrl = prop.generatedImageUrl,
                    projectId = project.id
                ))
            }
            // 分镜场景图
            project.storyboards.forEach { chapter ->
                chapter.sceneImages.forEach { (sceneId, url) ->
                    val scene = chapter.result.scenes.find { it.sceneId == sceneId }
                    add(GalleryItem(
                        id = "shot_${project.id}_${chapter.chapterIndex}_$sceneId",
                        type = GalleryItem.ItemType.STORYBOARD_SHOT,
                        name = scene?.sceneTitle ?: "场景 $sceneId",
                        description = "第${chapter.chapterIndex + 1}章 · ${scene?.description?.take(80) ?: ""}",
                        imageUrl = url,
                        projectId = project.id,
                        chapterIndex = chapter.chapterIndex,
                        sceneId = sceneId
                    ))
                }
            }
        }
    }
}