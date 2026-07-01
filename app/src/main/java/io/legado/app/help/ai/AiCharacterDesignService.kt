package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.ui.main.ai.AiImageProviderConfig
import org.json.JSONArray
import org.json.JSONObject

/**
 * ArcReel 集成 — AI 角色/场景/道具设计系统
 * 从小说中自动提取和设计角色、场景、道具，支持一致性维护
 */
object AiCharacterDesignService {

    data class DesignInput(
        val bookName: String,
        val author: String,
        val content: String,
        val genre: String = ""
    )

    data class CharacterDesign(
        val id: String,
        val name: String,
        val role: String,           // 主角/配角/反派/路人
        val gender: String,
        val age: String,
        val appearance: String,     // 外貌描述
        val personality: String,    // 性格
        val identity: String,       // 身份
        val skills: String,         // 能力/技能
        val biography: String,      // 背景故事
        val relationships: List<RelationShip>,
        val imagePrompt: String,    // AI绘画提示词
        val imagePromptCN: String,  // 中文绘画提示词
        val generatedImageUrl: String? = null,
        val consistencyTags: List<String> = emptyList() // 跨镜头一致性标签
    )

    data class RelationShip(
        val targetName: String,
        val relation: String
    )

    data class SceneDesign(
        val id: String,
        val name: String,
        val type: String,           // 室内/室外/城市/自然/幻想
        val description: String,
        val atmosphere: String,
        val keyElements: List<String>,
        val imagePrompt: String,
        val imagePromptCN: String,
        val generatedImageUrl: String? = null,
        val consistencyTags: List<String> = emptyList()
    )

    data class PropDesign(
        val id: String,
        val name: String,
        val type: String,           // 武器/法宝/道具/物品
        val description: String,
        val significance: String,   // 在故事中的重要性
        val imagePrompt: String,
        val imagePromptCN: String,
        val generatedImageUrl: String? = null
    )

    data class DesignResult(
        val characters: List<CharacterDesign>,
        val scenes: List<SceneDesign>,
        val props: List<PropDesign>,
        val worldStyle: String,     // 世界观风格
        val artStyle: String        // 推荐画风
    )

    /**
     * 从小说内容中提取并设计所有角色
     */
    suspend fun extractCharacters(
        input: DesignInput,
        onPartial: (String) -> Unit = {}
    ): List<CharacterDesign> {
        val content = input.content.take(12_000)
        val messages = listOf(
            AiChatMessage(
                role = AiChatMessage.Role.USER,
                content = """
                    你是小说角色设计师。从以下小说内容中提取所有角色，并为每个角色设计详细档案。

                    书名：${input.bookName}
                    作者：${input.author}
                    ${if (input.genre.isNotBlank()) "类型：${input.genre}" else ""}

                    要求：
                    1. 提取所有有名有姓的角色，包括主角、配角、反派
                    2. 每个角色给出详细的外貌、性格、身份、能力描述
                    3. 标注角色之间的关系
                    4. 为每个角色生成英文和中文AI绘画提示词（适合生成角色立绘）
                    5. 为每个角色生成一致性标签（用于跨镜头保持角色一致）

                    请用JSON格式输出（不要包含markdown代码块标记）：
                    {
                      "characters": [
                        {
                          "name": "角色名",
                          "role": "主角/配角/反派/路人",
                          "gender": "男/女/未知",
                          "age": "年龄或年龄段",
                          "appearance": "外貌详细描述",
                          "personality": "性格描述",
                          "identity": "身份/职业",
                          "skills": "能力/技能",
                          "biography": "背景故事",
                          "relationships": [{"targetName": "关联角色名", "relation": "关系描述"}],
                          "imagePrompt": "英文AI绘画提示词",
                          "imagePromptCN": "中文AI绘画提示词",
                          "consistencyTags": ["标签1", "标签2"]
                        }
                      ],
                      "worldStyle": "世界观风格描述",
                      "artStyle": "推荐画风"
                    }

                    小说内容：
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
        return parseCharacters(raw)
    }

    /**
     * 从小说内容中提取场景设计
     */
    suspend fun extractScenes(
        input: DesignInput,
        onPartial: (String) -> Unit = {}
    ): List<SceneDesign> {
        val content = input.content.take(12_000)
        val messages = listOf(
            AiChatMessage(
                role = AiChatMessage.Role.USER,
                content = """
                    你是小说场景设计师。从以下小说内容中提取所有重要场景，并为每个场景设计视觉方案。

                    书名：${input.bookName}
                    作者：${input.author}

                    要求：
                    1. 提取所有重要场景地点
                    2. 每个场景给出详细描述、氛围、关键元素
                    3. 为每个场景生成英文和中文AI绘画提示词
                    4. 为每个场景生成一致性标签

                    请用JSON格式输出（不要包含markdown代码块标记）：
                    {
                      "scenes": [
                        {
                          "name": "场景名称",
                          "type": "室内/室外/城市/自然/幻想",
                          "description": "场景详细描述",
                          "atmosphere": "氛围描述",
                          "keyElements": ["关键元素1", "关键元素2"],
                          "imagePrompt": "英文AI绘画提示词",
                          "imagePromptCN": "中文AI绘画提示词",
                          "consistencyTags": ["标签1", "标签2"]
                        }
                      ]
                    }

                    小说内容：
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
        return parseScenes(raw)
    }

    /**
     * 从小说内容中提取道具设计
     */
    suspend fun extractProps(
        input: DesignInput,
        onPartial: (String) -> Unit = {}
    ): List<PropDesign> {
        val content = input.content.take(12_000)
        val messages = listOf(
            AiChatMessage(
                role = AiChatMessage.Role.USER,
                content = """
                    你是小说道具设计师。从以下小说内容中提取所有重要道具/物品，并为每个道具设计视觉方案。

                    书名：${input.bookName}
                    作者：${input.author}

                    要求：
                    1. 提取所有重要道具（武器、法宝、关键物品等）
                    2. 每个道具给出详细描述和在故事中的重要性
                    3. 为每个道具生成英文和中文AI绘画提示词

                    请用JSON格式输出（不要包含markdown代码块标记）：
                    {
                      "props": [
                        {
                          "name": "道具名称",
                          "type": "武器/法宝/道具/物品",
                          "description": "道具详细描述",
                          "significance": "在故事中的重要性",
                          "imagePrompt": "英文AI绘画提示词",
                          "imagePromptCN": "中文AI绘画提示词"
                        }
                      ]
                    }

                    小说内容：
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
        return parseProps(raw)
    }

    /**
     * 全量设计：角色 + 场景 + 道具
     */
    suspend fun designAll(
        input: DesignInput,
        onProgress: (String) -> Unit = {}
    ): DesignResult {
        onProgress("正在提取角色...")
        val characters = extractCharacters(input)

        onProgress("正在提取场景...")
        val scenes = extractScenes(input)

        onProgress("正在提取道具...")
        val props = extractProps(input)

        val worldStyle = extractWorldStyle(input)
        val artStyle = inferArtStyle(input)

        return DesignResult(
            characters = characters,
            scenes = scenes,
            props = props,
            worldStyle = worldStyle,
            artStyle = artStyle
        )
    }

    /**
     * 为角色生成AI形象图
     */
    suspend fun generateCharacterImages(
        characters: List<CharacterDesign>,
        provider: AiImageProviderConfig? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<CharacterDesign> {
        return characters.mapIndexed { index, char ->
            onProgress(index + 1, characters.size)
            val url = try {
                val prompt = char.imagePromptCN.ifBlank { char.imagePrompt }
                if (prompt.isNotBlank()) AiImageService.generate(prompt, provider) else null
            } catch (_: Exception) { null }
            char.copy(generatedImageUrl = url)
        }
    }

    /**
     * 为场景生成AI概念图
     */
    suspend fun generateSceneImages(
        scenes: List<SceneDesign>,
        provider: AiImageProviderConfig? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<SceneDesign> {
        return scenes.mapIndexed { index, scene ->
            onProgress(index + 1, scenes.size)
            val url = try {
                val prompt = scene.imagePromptCN.ifBlank { scene.imagePrompt }
                if (prompt.isNotBlank()) AiImageService.generate(prompt, provider) else null
            } catch (_: Exception) { null }
            scene.copy(generatedImageUrl = url)
        }
    }

    /**
     * 合并角色到已有角色库（用于跨章节一致性）
     */
    fun mergeCharacters(
        existing: List<CharacterDesign>,
        newDesigns: List<CharacterDesign>
    ): List<CharacterDesign> {
        val merged = existing.toMutableList()
        newDesigns.forEach { newChar ->
            val existingIndex = merged.indexOfFirst {
                it.name.equals(newChar.name, ignoreCase = true)
            }
            if (existingIndex >= 0) {
                val existing = merged[existingIndex]
                merged[existingIndex] = existing.copy(
                    appearance = if (newChar.appearance.isNotBlank()) newChar.appearance else existing.appearance,
                    personality = if (newChar.personality.isNotBlank()) newChar.personality else existing.personality,
                    skills = if (newChar.skills.isNotBlank()) newChar.skills else existing.skills,
                    biography = if (newChar.biography.isNotBlank()) newChar.biography else existing.biography,
                    relationships = (existing.relationships + newChar.relationships).distinct(),
                    consistencyTags = (existing.consistencyTags + newChar.consistencyTags).distinct()
                )
            } else {
                merged.add(newChar)
            }
        }
        return merged
    }

    private suspend fun extractWorldStyle(input: DesignInput): String {
        return try {
            val messages = listOf(
                AiChatMessage(
                    role = AiChatMessage.Role.USER,
                    content = """
                        分析以下小说的世界观风格，用一句话描述（如：东方玄幻、西方奇幻、都市异能、科幻未来等）。

                        书名：${input.bookName}
                        内容：${input.content.take(2000)}

                        只输出风格描述，不要其他内容。
                    """.trimIndent()
                )
            )
            AiChatService.chatStream(
                messages = messages,
                includeStructuredBlocks = false,
                useAllTools = false,
                modelConfigOverride = AppConfig.aiSummaryModelConfig
            ).trim().take(100)
        } catch (_: Exception) { "" }
    }

    private suspend fun inferArtStyle(input: DesignInput): String {
        return try {
            val messages = listOf(
                AiChatMessage(
                    role = AiChatMessage.Role.USER,
                    content = """
                        根据以下小说类型，推荐最适合的AI绘画画风（如：国风水墨、日系动漫、写实油画、赛博朋克等）。

                        书名：${input.bookName}
                        世界观：${input.content.take(1000)}

                        只输出画风推荐，不要其他内容。
                    """.trimIndent()
                )
            )
            AiChatService.chatStream(
                messages = messages,
                includeStructuredBlocks = false,
                useAllTools = false,
                modelConfigOverride = AppConfig.aiSummaryModelConfig
            ).trim().take(100)
        } catch (_: Exception) { "写实" }
    }

    private fun parseCharacters(raw: String): List<CharacterDesign> {
        return try {
            val cleaned = cleanJson(raw)
            val json = JSONObject(cleaned)
            val arr = json.optJSONArray("characters") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                CharacterDesign(
                    id = obj.optString("name", "char_$i"),
                    name = obj.optString("name", ""),
                    role = obj.optString("role", ""),
                    gender = obj.optString("gender", ""),
                    age = obj.optString("age", ""),
                    appearance = obj.optString("appearance", ""),
                    personality = obj.optString("personality", ""),
                    identity = obj.optString("identity", ""),
                    skills = obj.optString("skills", ""),
                    biography = obj.optString("biography", ""),
                    relationships = parseRelationships(obj.optJSONArray("relationships")),
                    imagePrompt = obj.optString("imagePrompt", ""),
                    imagePromptCN = obj.optString("imagePromptCN", ""),
                    consistencyTags = parseStringArray(obj.optJSONArray("consistencyTags"))
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseScenes(raw: String): List<SceneDesign> {
        return try {
            val cleaned = cleanJson(raw)
            val json = JSONObject(cleaned)
            val arr = json.optJSONArray("scenes") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                SceneDesign(
                    id = obj.optString("name", "scene_$i"),
                    name = obj.optString("name", ""),
                    type = obj.optString("type", ""),
                    description = obj.optString("description", ""),
                    atmosphere = obj.optString("atmosphere", ""),
                    keyElements = parseStringArray(obj.optJSONArray("keyElements")),
                    imagePrompt = obj.optString("imagePrompt", ""),
                    imagePromptCN = obj.optString("imagePromptCN", ""),
                    consistencyTags = parseStringArray(obj.optJSONArray("consistencyTags"))
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseProps(raw: String): List<PropDesign> {
        return try {
            val cleaned = cleanJson(raw)
            val json = JSONObject(cleaned)
            val arr = json.optJSONArray("props") ?: return emptyList()
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                PropDesign(
                    id = obj.optString("name", "prop_$i"),
                    name = obj.optString("name", ""),
                    type = obj.optString("type", ""),
                    description = obj.optString("description", ""),
                    significance = obj.optString("significance", ""),
                    imagePrompt = obj.optString("imagePrompt", ""),
                    imagePromptCN = obj.optString("imagePromptCN", "")
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun parseRelationships(arr: JSONArray?): List<RelationShip> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            RelationShip(
                targetName = obj.optString("targetName", ""),
                relation = obj.optString("relation", "")
            )
        }
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun cleanJson(raw: String): String {
        return raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }
}