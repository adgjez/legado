package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiChatMessage
import org.json.JSONObject

/**
 * ArcReel 集成 — AI 剧本/分镜生成服务
 * 从小说章节内容生成剧本、分镜描述和场景卡片
 */
object AiStoryboardService {

    private const val DIRECT_LIMIT = 16_000
    private const val CHUNK_LIMIT = 10_000

    data class StoryboardInput(
        val bookName: String,
        val author: String,
        val chapterTitle: String,
        val content: String,
        val existingCharacters: String = ""
    )

    data class StoryboardResult(
        val title: String,
        val summary: String,
        val scenes: List<SceneCard>,
        val rawMarkdown: String
    )

    data class SceneCard(
        val sceneId: Int,
        val sceneTitle: String,
        val location: String,
        val timeOfDay: String,
        val characters: List<String>,
        val description: String,
        val visualPrompt: String,
        val dialogue: String = ""
    )

    /**
     * 从章节内容生成完整剧本/分镜
     */
    suspend fun generateStoryboard(
        input: StoryboardInput,
        onPartial: (String) -> Unit = {},
        onStatus: (JSONObject) -> Unit = {}
    ): StoryboardResult {
        val content = input.content.trim()
        require(content.isNotBlank()) { "章节内容为空" }

        val rawMarkdown = if (content.length <= DIRECT_LIMIT) {
            generateChunk(input, content, 1, 1, onPartial, onStatus)
        } else {
            val chunks = splitContent(content)
            val chunkResults = chunks.mapIndexed { index, chunk ->
                onStatus(status("storyboard", "分析第 ${index + 1}/${chunks.size} 段"))
                generateChunk(input, chunk, index + 1, chunks.size, onPartial = {}, onStatus)
            }
            mergeChunks(input, chunkResults, onPartial, onStatus)
        }.trim()

        return parseStoryboardResult(rawMarkdown, input)
    }

    /**
     * 生成单段分镜
     */
    private suspend fun generateChunk(
        input: StoryboardInput,
        content: String,
        chunkIndex: Int,
        chunkCount: Int,
        onPartial: (String) -> Unit,
        onStatus: (JSONObject) -> Unit
    ): String {
        val messages = listOf(
            AiChatMessage(
                role = AiChatMessage.Role.USER,
                content = buildChunkPrompt(input, content, chunkIndex, chunkCount)
            )
        )
        return AiChatService.chatStream(
            messages = messages,
            onPartial = onPartial,
            onStatus = onStatus,
            includeStructuredBlocks = false,
            useAllTools = false,
            modelConfigOverride = AppConfig.aiSummaryModelConfig
        )
    }

    /**
     * 合并多个分段的剧本
     */
    private suspend fun mergeChunks(
        input: StoryboardInput,
        chunkResults: List<String>,
        onPartial: (String) -> Unit,
        onStatus: (JSONObject) -> Unit
    ): String {
        val messages = listOf(
            AiChatMessage(
                role = AiChatMessage.Role.USER,
                content = """
                    你是专业剧本/分镜编辑。请将以下分段剧本合并为一份完整、连贯的剧本分镜。

                    要求：
                    1. 保持场景编号连续性（从1开始重新编号）
                    2. 合并重复场景，保持场景之间的逻辑连贯
                    3. 输出格式与分段剧本一致
                    4. 不要遗漏任何场景

                    书名：${input.bookName}
                    章节：${input.chapterTitle}

                    分段剧本：
                    ${chunkResults.joinToString("\n\n---\n\n")}
                """.trimIndent()
            )
        )
        return AiChatService.chatStream(
            messages = messages,
            onPartial = onPartial,
            onStatus = onStatus,
            includeStructuredBlocks = false,
            useAllTools = false,
            modelConfigOverride = AppConfig.aiSummaryModelConfig
        )
    }

    private fun buildChunkPrompt(
        input: StoryboardInput,
        content: String,
        chunkIndex: Int,
        chunkCount: Int
    ): String = """
        你是专业影视剧本/分镜师。请将以下小说${if (chunkCount > 1) "分段" else "内容"}转化为视觉化剧本分镜。

        输出格式（Markdown）：

        ## 剧本摘要
        （50字以内概括）

        ## 场景列表

        ### 场景 N：场景标题
        - **地点**：具体场景位置
        - **时间**：白天/夜晚/黄昏等
        - **角色**：出场角色列表
        - **画面描述**：详细视觉描述（用于AI生图）
        - **AI绘画提示词**：英文，适合AI绘画模型，包含风格、光线、构图、角色特征
        - **对话/动作**：该场景的关键对话或动作

        书籍信息：
        - 书名：${input.bookName}
        - 作者：${input.author}
        - 章节：${input.chapterTitle}
        - 分段：$chunkIndex / $chunkCount

        ${if (input.existingCharacters.isNotBlank()) "已有角色：\n${input.existingCharacters}" else ""}

        当前正文：
        $content
    """.trimIndent()

    /**
     * 解析生成的Markdown为结构化剧本数据
     */
    private fun parseStoryboardResult(
        markdown: String,
        input: StoryboardInput
    ): StoryboardResult {
        val summary = Regex("## 剧本摘要[\\s\\S]*?(?=## 场景列表|$)")
            .find(markdown)?.value
            ?.removePrefix("## 剧本摘要")
            ?.trim()
            .orEmpty()

        val scenesBlock = Regex("## 场景列表([\\s\\S]*)")
            .find(markdown)?.groupValues?.get(1)?.trim().orEmpty()

        val scenes = parseScenes(scenesBlock)

        return StoryboardResult(
            title = "${input.bookName} - ${input.chapterTitle}",
            summary = summary.ifBlank { "剧本已生成" },
            scenes = scenes,
            rawMarkdown = markdown
        )
    }

    private fun parseScenes(block: String): List<SceneCard> {
        val sceneRegex = Regex(
            "###\\s*场景\\s*(\\d+)[：:]\\s*(.+?)\\n([\\s\\S]*?)(?=###\\s*场景|$)"
        )
        return sceneRegex.findAll(block).mapNotNull { match ->
            val id = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val title = match.groupValues[2].trim()
            val body = match.groupValues[3]

            SceneCard(
                sceneId = id,
                sceneTitle = title,
                location = extractField(body, "地点"),
                timeOfDay = extractField(body, "时间"),
                characters = extractList(body, "角色"),
                description = extractField(body, "画面描述"),
                visualPrompt = extractField(body, "AI绘画提示词"),
                dialogue = extractField(body, "对话/动作")
            )
        }.toList()
    }

    private fun extractField(body: String, field: String): String {
        return Regex("-\\s*\\*\\*$field\\*\\*[：:]\\s*(.+)")
            .find(body)?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun extractList(body: String, field: String): List<String> {
        return Regex("-\\s*\\*\\*$field\\*\\*[：:]\\s*(.+)")
            .find(body)?.groupValues?.get(1)
            ?.split("、", "，", ",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            .orEmpty()
    }

    private fun splitContent(content: String): List<String> {
        val chunks = mutableListOf<String>()
        val builder = StringBuilder()
        content.lineSequence().forEach { line ->
            val text = line.trimEnd()
            if (builder.length + text.length + 1 > CHUNK_LIMIT && builder.isNotBlank()) {
                chunks += builder.toString().trim()
                builder.clear()
            }
            builder.append(text).append('\n')
        }
        if (builder.isNotBlank()) chunks += builder.toString().trim()
        return chunks.ifEmpty { listOf(content.take(CHUNK_LIMIT)) }
    }

    private fun status(key: String, label: String) = JSONObject().apply {
        put("key", key)
        put("kind", "storyboard")
        put("label", label)
        put("success", true)
    }
}