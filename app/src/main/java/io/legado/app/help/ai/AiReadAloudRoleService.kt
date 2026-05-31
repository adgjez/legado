package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiReadAloudRoleCache
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.speech.SpeechVoiceAssigner
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
        val characterId: Long = 0L,
        val emotionName: String = "",
        val emotionTag: String = "",
        val confidence: Double
    )

    private data class CharacterCandidate(
        val name: String,
        val identity: String,
        val roleLevel: Int,
        val confidence: Double,
        val evidence: String
    )

    private data class RequestResult(
        val segments: List<Segment> = emptyList(),
        val candidates: List<CharacterCandidate> = emptyList()
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
        val oldCache = appDb.aiReadAloudRoleCacheDao.get(cacheKey)
        if (oldCache?.status == AiReadAloudRoleCache.STATUS_SUCCESS) return
        if (oldCache?.status == AiReadAloudRoleCache.STATUS_RUNNING &&
            System.currentTimeMillis() - oldCache.updatedAt < 5 * 60 * 1000L
        ) {
            return
        }
        if ((oldCache?.retryCount ?: 0) >= 3) return
        if (!runningCacheKeys.add(cacheKey)) return
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
                status = AiReadAloudRoleCache.STATUS_RUNNING,
                retryCount = oldCache?.retryCount ?: 0,
                characterHash = characterHash(currentBook.bookUrl),
                voiceHash = voiceHash(currentBook.bookUrl),
                createdAt = oldCache?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now
            )
        )
        try {
            val result = if (mode == AppConfig.AI_READ_ALOUD_ROLE_MODE_FULL) {
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
            val resolved = persistDetectedCharacters(currentBook.bookUrl, result.segments, result.candidates)
            val successAt = System.currentTimeMillis()
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
                    status = AiReadAloudRoleCache.STATUS_SUCCESS,
                    retryCount = oldCache?.retryCount ?: 0,
                    segmentsJson = resolved.first.toJsonArray().toString(),
                    createdCharacterIdsJson = JSONArray(resolved.second).toString(),
                    characterHash = characterHash(currentBook.bookUrl),
                    voiceHash = voiceHash(currentBook.bookUrl),
                    createdAt = oldCache?.createdAt?.takeIf { it > 0 } ?: successAt,
                    updatedAt = successAt
                )
            )
        } catch (throwable: Throwable) {
            val failedAt = System.currentTimeMillis()
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
                    status = AiReadAloudRoleCache.STATUS_FAILED,
                    retryCount = ((oldCache?.retryCount ?: 0) + 1).coerceAtMost(3),
                    lastError = (throwable.localizedMessage ?: throwable.javaClass.simpleName).take(400),
                    characterHash = characterHash(currentBook.bookUrl),
                    voiceHash = voiceHash(currentBook.bookUrl),
                    createdAt = oldCache?.createdAt?.takeIf { it > 0 } ?: failedAt,
                    updatedAt = failedAt
                )
            )
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
    ): RequestResult = coroutineScope {
        val semaphore = Semaphore(AppConfig.aiReadAloudRoleThreadCount)
        val results = paragraphs.indices
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
        RequestResult(
            segments = results
                .flatMap { it.segments }
                .distinctBy { "${it.paragraphIndex}:${it.start}:${it.end}:${it.roleType}:${it.characterName}" }
                .sortedWith(compareBy<Segment> { it.paragraphIndex }.thenBy { it.start }.thenBy { it.end }),
            candidates = results
                .flatMap { it.candidates }
                .distinctBy { it.name }
        )
    }

    private suspend fun requestSegments(
        book: Book,
        textChapter: TextChapter,
        paragraphs: List<String>,
        targetIndices: List<Int>,
        contextTitle: String,
        prompt: String,
        paragraphOffset: Int = 0
    ): RequestResult {
        val collectedSegments = mutableListOf<Segment>()
        val collectedCandidates = mutableListOf<CharacterCandidate>()
        val targetSet = targetIndices.toSet()
        val tool = AiResolvedTool(TOOL_RECORD_SEGMENTS, recordSegmentsDefinition()) { args ->
            val segments = parseSegments(args, targetSet, paragraphOffset, paragraphs)
            val candidates = parseCandidates(args)
            collectedSegments += segments
            collectedCandidates += candidates
            JSONObject().apply {
                put("ok", true)
                put("recorded", segments.size)
                put("newCharacters", candidates.size)
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
        if (collectedSegments.isEmpty()) {
            val fallback = parseFallbackResult(response, targetSet, paragraphOffset, paragraphs)
            collectedSegments += fallback.segments
            collectedCandidates += fallback.candidates
        }
        return RequestResult(collectedSegments, collectedCandidates)
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
            7. characterName 不确定时留空；已有角色请优先使用角色卡中的准确名称。
            8. 情绪明确时可填写 emotionName 和 emotionTag，例如 高兴 / [高兴]；不明确时留空。
            9. 如果发现明确新角色或稳定路人称谓，可在 newCharacters 中记录候选；不要把“我、你、他、众人、旁白”当成新角色。
            10. 如果工具不可用，最终只输出 JSON：{"segments":[...],"newCharacters":[...]}。

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

    private fun parseFallbackResult(
        response: String,
        targetSet: Set<Int>,
        paragraphOffset: Int,
        paragraphs: List<String>
    ): RequestResult {
        val jsonText = response.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val root = runCatching {
            JSONObject(jsonText)
        }.getOrNull() ?: return RequestResult()
        val array = runCatching {
            val root = JSONObject(jsonText)
            root.optJSONArray("segments")
        }.getOrNull()
        return RequestResult(
            segments = array?.let { parseSegmentsArray(it, targetSet, paragraphOffset, paragraphs) }.orEmpty(),
            candidates = parseCandidates(root)
        )
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
                characterId = item.optLong("characterId", 0L).coerceAtLeast(0L),
                emotionName = item.optString("emotionName").trim().take(40),
                emotionTag = item.optString("emotionTag").trim().take(40),
                confidence = item.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
            )
        }
        return result
    }

    private fun parseCandidates(args: JSONObject?): List<CharacterCandidate> {
        val array = args?.optJSONArray("newCharacters") ?: return emptyList()
        val result = mutableListOf<CharacterCandidate>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name").trim().take(80)
            if (name.isBlank()) continue
            result += CharacterCandidate(
                name = name,
                identity = item.optString("identity").trim().take(200),
                roleLevel = item.optInt("roleLevel", BookCharacter.ROLE_NORMAL)
                    .coerceIn(BookCharacter.ROLE_NORMAL, BookCharacter.ROLE_MAIN),
                confidence = item.optDouble("confidence", 0.0).coerceIn(0.0, 1.0),
                evidence = item.optString("evidence").trim().take(200)
            )
        }
        return result
    }

    private fun persistDetectedCharacters(
        bookUrl: String,
        segments: List<Segment>,
        candidates: List<CharacterCandidate>
    ): Pair<List<Segment>, List<Long>> {
        if (!AppConfig.aiReadAloudAutoCreateCharacters) {
            val characters = appDb.bookCharacterDao.characters(bookUrl)
            return resolveSegmentCharacters(segments, characters) to emptyList()
        }
        val now = System.currentTimeMillis()
        val httpTtsList = appDb.httpTTSDao.all
        val existing = appDb.bookCharacterDao.characters(bookUrl).toMutableList()
        val byName = existing.associateBy { it.name }.toMutableMap()
        val createdIds = mutableListOf<Long>()
        val candidateMap = candidates
            .filter { it.confidence >= 0.65 && isCreatableCharacterName(it.name) }
            .associateBy { it.name }
            .toMutableMap()
        segments.asSequence()
            .filter { it.roleType == "character" || it.roleType == "thought" }
            .filter { it.characterName.isNotBlank() && it.confidence >= 0.72 }
            .filter { isCreatableCharacterName(it.characterName) }
            .forEach { segment ->
                candidateMap.putIfAbsent(
                    segment.characterName,
                    CharacterCandidate(
                        name = segment.characterName,
                        identity = "",
                        roleLevel = BookCharacter.ROLE_NORMAL,
                        confidence = segment.confidence,
                        evidence = ""
                    )
                )
            }
        candidateMap.values
            .sortedByDescending { it.confidence }
            .take(20)
            .forEach { candidate ->
                val old = byName[candidate.name]
                if (old == null) {
                    val draft = BookCharacter(
                        bookUrl = bookUrl,
                        name = candidate.name,
                        identity = candidate.identity,
                        biography = candidate.evidence,
                        roleLevel = candidate.roleLevel,
                        sortOrder = (appDb.bookCharacterDao.maxCharacterOrder(bookUrl) ?: -1) + 1,
                        speechRouteJson = SpeechVoiceAssigner
                            .assignRoute(BookCharacter(bookUrl = bookUrl, name = candidate.name), httpTtsList)
                            .toJson(),
                        autoCreated = true,
                        source = "ai_read_aloud",
                        lastDetectedAt = now,
                        createdAt = now,
                        updatedAt = now
                    )
                    val id = appDb.bookCharacterDao.insertCharacter(draft)
                    val saved = draft.copy(id = id)
                    byName[saved.name] = saved
                    existing += saved
                    createdIds += id
                } else if (old.speechRouteJson.isBlank()) {
                    val route = SpeechVoiceAssigner.assignRoute(old, httpTtsList)
                    if (route.isConfigured) {
                        val updated = old.copy(
                            speechRouteJson = route.toJson(),
                            lastDetectedAt = now,
                            updatedAt = now
                        )
                        appDb.bookCharacterDao.updateCharacter(updated)
                        byName[updated.name] = updated
                    }
                }
            }
        return resolveSegmentCharacters(segments, byName.values.toList()) to createdIds
    }

    private fun resolveSegmentCharacters(
        segments: List<Segment>,
        characters: List<BookCharacter>
    ): List<Segment> {
        val byName = characters.associateBy { it.name }
        return segments.map { segment ->
            if (segment.characterId > 0 || segment.characterName.isBlank()) {
                segment
            } else {
                segment.copy(characterId = byName[segment.characterName]?.id ?: 0L)
            }
        }
    }

    private fun isCreatableCharacterName(name: String): Boolean {
        val value = name.trim()
        if (value.length !in 2..40) return false
        if (value.any { it == '\n' || it == '\r' || it == '\t' }) return false
        return value !in setOf("旁白", "作者", "读者", "我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "众人", "有人")
    }

    private fun characterHash(bookUrl: String): String {
        return MD5Utils.md5Encode(
            appDb.bookCharacterDao.characters(bookUrl)
                .joinToString("|") { "${it.id}:${it.name}:${it.updatedAt}:${it.speechRouteJson}" }
        )
    }

    private fun voiceHash(bookUrl: String): String {
        val characters = appDb.bookCharacterDao.characters(bookUrl)
            .joinToString("|") { "${it.id}:${it.speechRouteJson}" }
        val engines = appDb.httpTTSDao.all
            .joinToString("|") { "${it.id}:${it.speakersJson}:${it.emotionsJson}" }
        return MD5Utils.md5Encode("$characters\n$engines")
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
                                    put("characterId", intProp("Known character id if available, otherwise 0."))
                                    put("emotionName", stringProp("Optional emotion name, blank if unknown."))
                                    put("emotionTag", stringProp("Optional emotion tag, e.g. [高兴]."))
                                    put("confidence", numberProp("Confidence from 0 to 1."))
                                })
                                put("required", JSONArray().put("paragraphIndex").put("start").put("end").put("roleType"))
                                put("additionalProperties", false)
                            })
                        })
                        put("newCharacters", JSONObject().apply {
                            put("type", "array")
                            put("items", JSONObject().apply {
                                put("type", "object")
                                put("properties", JSONObject().apply {
                                    put("name", stringProp("New character name."))
                                    put("identity", stringProp("Short identity or role hint."))
                                    put("roleLevel", intProp("0 normal, 1 important, 2 main."))
                                    put("confidence", numberProp("Confidence from 0 to 1."))
                                    put("evidence", stringProp("Short evidence from current chapter."))
                                })
                                put("required", JSONArray().put("name"))
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
                    put("characterId", segment.characterId)
                    put("emotionName", segment.emotionName)
                    put("emotionTag", segment.emotionTag)
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
