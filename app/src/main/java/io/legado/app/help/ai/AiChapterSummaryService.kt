package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookAiChapterSummary
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.characterBookKey
import io.legado.app.help.character.BookCharacterIdentityMigrator
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import org.json.JSONObject

object AiChapterSummaryService {

    private const val CHUNK_LIMIT = 12_000
    private const val DIRECT_LIMIT = 18_000
    private val SUMMARY_TOOL_NAMES = setOf(
        "list_book_characters",
        "upsert_book_character",
        "list_speech_catalogs",
        "assign_character_speech_route",
        "batch_assign_character_speech_routes"
    )

    data class SummaryResult(
        val chapterIndex: Int,
        val summary: String,
        val cached: String = ""
    )

    data class BookSummaryResult(
        val bookName: String,
        val author: String,
        val totalChapters: Int,
        val summary: String,
        val mainCharacters: List<String>,
        val mainPlotPoints: List<String>,
        val worldSetting: String
    )

    data class SummaryInput(
        val book: Book,
        val chapter: BookChapter,
        val content: String
    ) {
        val bookUrl: String get() = book.bookUrl
        val chapterIndex: Int get() = chapter.index
        val chapterKey: String get() = chapter.url?.ifBlank { chapter.title }.orEmpty()
        val chapterTitle: String get() = chapter.title
        val contentHash: String get() = MD5Utils.md5Encode(content)
        val cacheKey: String
            get() = MD5Utils.md5Encode("chapter-summary|$bookUrl|$chapterIndex|$chapterKey|$contentHash")
    }

    suspend fun cached(input: SummaryInput): BookAiChapterSummary? = withContext(IO) {
        appDb.bookAiChapterSummaryDao.get(input.cacheKey)
            ?.takeIf { it.contentHash == input.contentHash && it.summary.isNotBlank() }
    }

    suspend fun summarize(
        input: SummaryInput,
        forceRefresh: Boolean,
        onPartial: (String) -> Unit,
        onStatus: (JSONObject) -> Unit
    ): BookAiChapterSummary {
        if (!forceRefresh) {
            cached(input)?.let { return it }
        }
        val content = input.content.trim()
        require(content.isNotBlank()) { "当前章节正文为空" }
        val summaryText = if (content.length <= DIRECT_LIMIT) {
            summarizeChunk(input, content, chunkIndex = 1, chunkCount = 1, onPartial, onStatus)
        } else {
            val chunks = splitContent(content)
            val chunkSummaries = chunks.mapIndexed { index, chunk ->
                onStatus(status("summary", "分析第 ${index + 1}/${chunks.size} 段"))
                summarizeChunk(input, chunk, index + 1, chunks.size, onPartial = {}, onStatus = onStatus)
            }
            combineSummaries(input, chunkSummaries, onPartial, onStatus)
        }.trim()
        val now = System.currentTimeMillis()
        val model = AppConfig.aiSummaryModelConfig
        val modelLabel = model?.let { currentModelLabel() }.orEmpty()
        val summary = BookAiChapterSummary(
            cacheKey = input.cacheKey,
            bookUrl = input.bookUrl,
            bookName = input.book.name,
            chapterIndex = input.chapterIndex,
            chapterKey = input.chapterKey,
            chapterTitle = input.chapterTitle,
            contentHash = input.contentHash,
            modelId = model?.id.orEmpty(),
            modelName = modelLabel,
            summary = summaryText,
            createdAt = now,
            updatedAt = now
        )
        withContext(IO) {
            appDb.bookAiChapterSummaryDao.upsert(summary)
        }
        return summary
    }

    private suspend fun summarizeChunk(
        input: SummaryInput,
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
            toolOverride = AiToolRegistry.resolveNativeTools(SUMMARY_TOOL_NAMES),
            modelConfigOverride = AppConfig.aiSummaryModelConfig
        )
    }

    private suspend fun combineSummaries(
        input: SummaryInput,
        chunkSummaries: List<String>,
        onPartial: (String) -> Unit,
        onStatus: (JSONObject) -> Unit
    ): String {
        val messages = listOf(
            AiChatMessage(
                role = AiChatMessage.Role.USER,
                content = """
                    你是小说章节总结助手。下面是同一章节按顺序得到的分段摘要，请合并成一份完整、紧凑、可读的章节总结。

                    要求：
                    1. 只基于分段摘要，不新增情节。
                    2. 输出 Markdown。
                    3. 包含“剧情概述”“人物变化”“伏笔/线索”三部分；没有内容的部分写“暂无明确信息”。
                    4. 不要输出工具调用过程。

                    书名：${input.book.name}
                    作者：${input.book.author}
                    章节：${input.chapterTitle}

                    分段摘要：
                    ${chunkSummaries.mapIndexed { index, summary -> "## 分段 ${index + 1}\n$summary" }.joinToString("\n\n")}
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

    private suspend fun buildChunkPrompt(
        input: SummaryInput,
        content: String,
        chunkIndex: Int,
        chunkCount: Int
    ): String {
        val existingCharacters = withContext(IO) {
            appDb.bookCharacterDao.characters(
                BookCharacterIdentityMigrator.migrate(input.book).ifBlank { input.book.characterBookKey() }
            )
        }
            .take(80)
            .joinToString("\n") { character ->
                "- ${character.name}: ${listOf(character.identity, character.skills, character.attributes, character.biography).filter { it.isNotBlank() }.joinToString("；").take(500)}"
            }
            .ifBlank { "暂无角色卡" }
        return """
            你是阅读页的 AI 章节总结助手，同时负责维护本书角色卡。

            你必须遵守：
            1. 先结合已有角色卡阅读本段正文。
            2. 如果正文中出现新的明确角色，请调用 upsert_book_character 新增角色卡。
            3. 如果正文明确说明已有角色的新技能、新属性、身份变化或生平变化，请调用 upsert_book_character 更新对应字段。
            4. 不要删除角色，不要根据猜测覆盖旧信息；不确定的信息写入总结即可，不写入角色卡。
            5. 如果角色没有配音且用户配置了发言人目录，可以读取配音目录并为角色分配发言人。
            6. 最终回答只输出当前${if (chunkCount > 1) "分段" else "章节"}摘要，不要解释工具调用。

            书籍：
            - bookUrl: ${input.bookUrl}
            - 书名: ${input.book.name}
            - 作者: ${input.book.author}
            - 章节序号: ${input.chapterIndex + 1}
            - 章节标题: ${input.chapterTitle}
            - 分段: $chunkIndex / $chunkCount

            已有角色卡：
            $existingCharacters

            输出格式：
            ## 剧情概述
            ## 人物变化
            ## 伏笔/线索

            当前正文：
            $content
        """.trimIndent()
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

    private fun currentModelLabel(): String {
        val model = AppConfig.aiSummaryModelConfig ?: return ""
        val providerName = AppConfig.aiProviderList.firstOrNull { it.id == model.providerId }
            ?.name
            ?.takeIf { it.isNotBlank() }
        return providerName?.let { "${model.modelId} - $it" } ?: model.modelId
    }

    private fun status(key: String, label: String): JSONObject {
        return JSONObject().apply {
            put("key", key)
            put("kind", "summary")
            put("label", label)
            put("success", true)
        }
    }

    // ════════════════════════════════════════════
    // ArcReel 增强 — 书籍级摘要
    // ════════════════════════════════════════════

    /**
     * 基于章节摘要生成书籍级摘要
     */
    suspend fun summarizeBook(
        bookName: String,
        author: String,
        chapterSummaries: List<SummaryResult>,
        onPartial: (String) -> Unit = {}
    ): BookSummaryResult {
        require(chapterSummaries.isNotEmpty()) { "章节摘要列表为空" }

        val summariesText = chapterSummaries
            .sortedBy { it.chapterIndex }
            .joinToString("\n\n") { "第${it.chapterIndex + 1}章: ${it.summary.take(500)}" }

        val messages = listOf(
            AiChatMessage(
                role = AiChatMessage.Role.USER,
                content = """
                    你是小说分析专家。请根据以下章节摘要，生成书籍级分析报告。

                    书名：$bookName
                    作者：$author
                    总章节数：${chapterSummaries.size}

                    章节摘要：
                    $summariesText

                    请用JSON格式输出（不要包含markdown代码块标记）：
                    {
                      "summary": "全书故事梗概，200-300字",
                      "mainCharacters": ["主角1", "主角2", "重要配角"],
                      "mainPlotPoints": ["关键情节1", "关键情节2", "关键情节3"],
                      "worldSetting": "世界观设定描述"
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
        return parseBookSummary(raw, bookName, author, chapterSummaries.size)
    }

    private fun parseBookSummary(raw: String, bookName: String, author: String, totalChapters: Int): BookSummaryResult {
        return try {
            val cleaned = raw.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val json = JSONObject(cleaned)
            BookSummaryResult(
                bookName = bookName,
                author = author,
                totalChapters = totalChapters,
                summary = json.optString("summary", ""),
                mainCharacters = parseStringArray(json.optJSONArray("mainCharacters")),
                mainPlotPoints = parseStringArray(json.optJSONArray("mainPlotPoints")),
                worldSetting = json.optString("worldSetting", "")
            )
        } catch (_: Exception) {
            BookSummaryResult(
                bookName = bookName,
                author = author,
                totalChapters = totalChapters,
                summary = raw.take(500),
                mainCharacters = emptyList(),
                mainPlotPoints = emptyList(),
                worldSetting = ""
            )
        }
    }

    private fun parseStringArray(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }
}
