package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiReadAloudRoleCache
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object AiReadAloudRoleService {

    private const val TOOL_RECORD_SEGMENTS = "record_read_aloud_role_segments"
    private const val TARGET_GROUP_SIZE = 12
    private val runningCacheKeys = ConcurrentHashMap.newKeySet<String>()

    data class Segment(
        val paragraphIndex: Int,
        val start: Int,
        val end: Int,
        val roleType: String,
        val characterName: String,
        val confidence: Double
    )

    suspend fun ensureCache(
        book: Book?,
        textChapter: TextChapter?,
        paragraphs: List<String>
    ) {
        if (!AppConfig.aiReadAloudRoleEnabled) return
        val modelConfig = AppConfig.aiReadAloudRoleModelConfig ?: return
        if (AppConfig.aiProviderForModel(modelConfig)?.baseUrl.isNullOrBlank()) return
        val currentBook = book ?: return
        val currentChapter = textChapter ?: return
        val cleanParagraphs = paragraphs.map { it.trimEnd() }.filter { it.isNotBlank() }
        if (cleanParagraphs.isEmpty()) return
        val mode = AppConfig.aiReadAloudRoleMode
        val prompt = AppConfig.aiReadAloudRolePrompt.trim()
        val contentHash = MD5Utils.md5Encode(cleanParagraphs.joinToString("\n"))
        val promptHash = MD5Utils.md5Encode(prompt)
        val contextParagraphs = AppConfig.aiReadAloudRoleContextParagraphs
        val cacheKey = MD5Utils.md5Encode(
            "read-aloud-role|${currentBook.bookUrl}|${currentChapter.chapter.index}|${currentChapter.chapter.url}|$contentHash|$mode|$contextParagraphs|$promptHash|${modelConfig.id}"
        )
        if (appDb.aiReadAloudRoleCacheDao.get(cacheKey) != null) return
        if (!runningCacheKeys.add(cacheKey)) return
        try {
            val segments = if (mode == AppConfig.AI_READ_ALOUD_ROLE_MODE_FULL) {
                requestSegments(
                    book = currentBook,
                    textChapter = currentChapter,
                    paragraphs = cleanParagraphs,
                    targetIndices = cleanParagraphs.indices.toList(),
                    contextTitle = "全文工具模式",
                    prompt = prompt
                )
            } else {
                requestChunkedSegments(
                    book = currentBook,
                    textChapter = currentChapter,
                    paragraphs = cleanParagraphs,
                    contextParagraphs = contextParagraphs,
                    prompt = prompt
                )
            }
            val now = System.currentTimeMillis()
            appDb.aiReadAloudRoleCacheDao.upsert(
                AiReadAloudRoleCache(
                    cacheKey = cacheKey,
                    bookUrl = currentBook.bookUrl,
                    chapterKey = currentChapter.chapter.url?.ifBlank { currentChapter.chapter.title }.orEmpty(),
                    chapterIndex = currentChapter.chapter.index,
                    chapterTitle = currentChapter.chapter.title,
                    contentHash = contentHash,
                    mode = mode,
                    paragraphCount = cleanParagraphs.size,
                    segmentsJson = segments.toJsonArray().toString(),
                    createdAt = now,
                    updatedAt = now
                )
            )
        } catch (throwable: Throwable) {
            AppLog.put("AI分角色标注失败\n${throwable.localizedMessage ?: throwable.javaClass.simpleName}", throwable)
        } finally {
            runningCacheKeys.remove(cacheKey)
        }
    }

    private suspend fun requestChunkedSegments(
        book: Book,
        textChapter: TextChapter,
        paragraphs: List<String>,
        contextParagraphs: Int,
        prompt: String
    ): List<Segment> = coroutineScope {
        val semaphore = Semaphore(AppConfig.aiReadAloudRoleThreadCount)
        paragraphs.indices
            .chunked(TARGET_GROUP_SIZE)
            .map { targetIndices ->
                async {
                    semaphore.withPermit {
                        val start = (targetIndices.first() - contextParagraphs).coerceAtLeast(0)
                        val end = (targetIndices.last() + contextParagraphs).coerceAtMost(paragraphs.lastIndex)
                        requestSegments(
                            book = book,
                            textChapter = textChapter,
                            paragraphs = paragraphs.subList(start, end + 1),
                            targetIndices = targetIndices,
                            contextTitle = "分线程上下文模式，当前任务只记录目标段落 ${targetIndices.first() + 1}-${targetIndices.last() + 1}",
                            prompt = prompt,
                            paragraphOffset = start
                        )
                    }
                }
            }
            .awaitAll()
            .flatten()
            .distinctBy { "${it.paragraphIndex}:${it.start}:${it.end}:${it.roleType}:${it.characterName}" }
            .sortedWith(compareBy<Segment> { it.paragraphIndex }.thenBy { it.start }.thenBy { it.end })
    }

    private suspend fun requestSegments(
        book: Book,
        textChapter: TextChapter,
        paragraphs: List<String>,
        targetIndices: List<Int>,
        contextTitle: String,
        prompt: String,
        paragraphOffset: Int = 0
    ): List<Segment> {
        val collected = mutableListOf<Segment>()
        val targetSet = targetIndices.toSet()
        val tool = AiResolvedTool(TOOL_RECORD_SEGMENTS, recordSegmentsDefinition()) { args ->
            val segments = parseSegments(args, targetSet, paragraphOffset, paragraphs)
            collected += segments
            JSONObject().apply {
                put("ok", true)
                put("recorded", segments.size)
            }.toString()
        }
        val response = AiChatService.chatStream(
            messages = listOf(
                AiChatMessage(
                    role = AiChatMessage.Role.USER,
                    content = buildPrompt(book, textChapter, paragraphs, targetIndices, contextTitle, prompt, paragraphOffset)
                )
            ),
            onPartial = {},
            includeStructuredBlocks = false,
            useAllTools = false,
            extraTools = listOf(tool),
            modelConfigOverride = AppConfig.aiReadAloudRoleModelConfig
        )
        if (collected.isEmpty()) {
            collected += parseFallbackSegments(response, targetSet, paragraphOffset, paragraphs)
        }
        return collected
    }

    private fun buildPrompt(
        book: Book,
        textChapter: TextChapter,
        paragraphs: List<String>,
        targetIndices: List<Int>,
        contextTitle: String,
        prompt: String,
        paragraphOffset: Int
    ): String {
        val characters = appDb.bookCharacterDao.characters(book.bookUrl)
            .joinToString("\n") { "- ${it.name}：${listOf(it.identity, it.skills, it.attributes).filter { value -> value.isNotBlank() }.joinToString("；")}" }
            .ifBlank { "暂无角色卡" }
        val indexed = paragraphs.mapIndexed { index, text ->
            val absolute = paragraphOffset + index
            val mark = if (absolute in targetIndices) "[TARGET]" else "[CONTEXT]"
            "$mark 段落${absolute + 1}: $text"
        }.joinToString("\n")
        return """
            你是小说朗读分角色标注器。请为目标段落逐字逐句标注旁白、角色台词、心理活动或其他需要区分朗读身份的片段。

            规则：
            1. 必须调用工具 $TOOL_RECORD_SEGMENTS 记录结果。
            2. 只记录 [TARGET] 段落，不要记录 [CONTEXT] 段落。
            3. paragraphIndex 使用从 0 开始的绝对段落索引。
            4. start/end 使用 Kotlin 字符串下标，start 包含，end 不包含。
            5. 一个段落可以有多个片段，例如“我说‘你好’”应拆成旁白和角色台词。
            6. roleType 只能使用 narrator、character、thought、other。
            7. characterName 不确定时留空；不要猜测不存在的角色。
            8. 如果工具不可用，最终只输出 JSON：{"segments":[...]}。

            书籍：${book.name}
            作者：${book.author}
            章节：${textChapter.chapter.title}
            模式：$contextTitle
            目标段落：${targetIndices.joinToString { (it + 1).toString() }}
            已有角色卡：
            $characters

            额外提示：
            ${prompt.ifBlank { "无" }}

            段落：
            $indexed
        """.trimIndent()
    }

    private fun parseSegments(
        args: JSONObject?,
        targetSet: Set<Int>,
        paragraphOffset: Int,
        paragraphs: List<String>
    ): List<Segment> {
        val array = args?.optJSONArray("segments") ?: return emptyList()
        return parseSegmentsArray(array, targetSet, paragraphOffset, paragraphs)
    }

    private fun parseFallbackSegments(
        response: String,
        targetSet: Set<Int>,
        paragraphOffset: Int,
        paragraphs: List<String>
    ): List<Segment> {
        val jsonText = response.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val array = runCatching {
            val root = JSONObject(jsonText)
            root.optJSONArray("segments")
        }.getOrNull() ?: return emptyList()
        return parseSegmentsArray(array, targetSet, paragraphOffset, paragraphs)
    }

    private fun parseSegmentsArray(
        array: JSONArray,
        targetSet: Set<Int>,
        paragraphOffset: Int,
        paragraphs: List<String>
    ): List<Segment> {
        val result = mutableListOf<Segment>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val paragraphIndex = item.optInt("paragraphIndex", -1)
            if (paragraphIndex !in targetSet) continue
            val localIndex = paragraphIndex - paragraphOffset
            val text = paragraphs.getOrNull(localIndex) ?: continue
            val start = item.optInt("start", -1).coerceAtLeast(0)
            val end = item.optInt("end", -1).coerceAtMost(text.length)
            if (start >= end) continue
            val roleType = item.optString("roleType").trim()
                .takeIf { it in setOf("narrator", "character", "thought", "other") }
                ?: "other"
            result += Segment(
                paragraphIndex = paragraphIndex,
                start = start,
                end = end,
                roleType = roleType,
                characterName = item.optString("characterName").trim().take(80),
                confidence = item.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
            )
        }
        return result
    }

    private fun recordSegmentsDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_RECORD_SEGMENTS)
                put("description", "记录朗读分角色片段标注。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("segments", JSONObject().apply {
                            put("type", "array")
                            put("items", JSONObject().apply {
                                put("type", "object")
                                put("properties", JSONObject().apply {
                                    put("paragraphIndex", intProp("0-based absolute paragraph index."))
                                    put("start", intProp("Inclusive start offset in the paragraph."))
                                    put("end", intProp("Exclusive end offset in the paragraph."))
                                    put("roleType", stringProp("narrator, character, thought, or other."))
                                    put("characterName", stringProp("Character name, blank if unknown."))
                                    put("confidence", numberProp("Confidence from 0 to 1."))
                                })
                                put("required", JSONArray().put("paragraphIndex").put("start").put("end").put("roleType"))
                                put("additionalProperties", false)
                            })
                        })
                    })
                    put("required", JSONArray().put("segments"))
                    put("additionalProperties", false)
                })
            })
        }
    }

    private fun List<Segment>.toJsonArray(): JSONArray {
        return JSONArray().also { array ->
            forEach { segment ->
                array.put(JSONObject().apply {
                    put("paragraphIndex", segment.paragraphIndex)
                    put("start", segment.start)
                    put("end", segment.end)
                    put("roleType", segment.roleType)
                    put("characterName", segment.characterName)
                    put("confidence", segment.confidence)
                })
            }
        }
    }

    private fun stringProp(description: String) = JSONObject().apply {
        put("type", "string")
        put("description", description)
    }

    private fun intProp(description: String) = JSONObject().apply {
        put("type", "integer")
        put("description", description)
    }

    private fun numberProp(description: String) = JSONObject().apply {
        put("type", "number")
        put("description", description)
    }
}
