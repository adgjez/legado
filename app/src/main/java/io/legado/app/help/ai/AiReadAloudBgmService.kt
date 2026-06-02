package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.ReadAloudBgmAssignmentCache
import io.legado.app.data.entities.ReadAloudBgmGroup
import io.legado.app.data.entities.ReadAloudBgmTrack
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.read.page.entities.ReadAloudCue
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.utils.MD5Utils
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object AiReadAloudBgmService {

    const val TOOL_ASSIGN_BGM_RANGES = "assign_read_aloud_bgm_ranges"
    private const val SCHEMA_VERSION = 1

    data class Assignment(
        val fromCueIndex: Int,
        val toCueIndex: Int,
        val trackId: Long,
        val volume: Float = 0.22f,
        val fadeInMs: Int = 1800,
        val fadeOutMs: Int = 1800,
        val reason: String = "",
        val confidence: Double = 0.0
    )

    data class ResolvedAssignment(
        val assignment: Assignment,
        val track: ReadAloudBgmTrack
    )

    data class EnsureResult(
        val status: String,
        val assignmentCount: Int = 0,
        val message: String = "",
        val error: String = "",
        val cacheKey: String = ""
    )

    private data class CatalogSnapshot(
        val groups: List<ReadAloudBgmGroup>,
        val tracks: List<ReadAloudBgmTrack>,
        val hash: String
    )

    private data class BgmCacheKey(
        val cacheKey: String,
        val contentHash: String,
        val catalogHash: String,
        val modelId: String,
        val promptCacheKey: String
    )

    suspend fun ensureChapterAssignments(
        book: Book?,
        textChapter: TextChapter?,
        cues: List<ReadAloudCue>
    ): EnsureResult = withContext(IO) {
        if (!AppConfig.aiReadAloudBgmEnabled) {
            return@withContext EnsureResult(ReadAloudBgmAssignmentCache.STATUS_FAILED, message = "智能配乐未开启")
        }
        val modelConfig = AppConfig.aiReadAloudRoleModelConfig
            ?: return@withContext EnsureResult(ReadAloudBgmAssignmentCache.STATUS_FAILED, message = "未配置多角色模型")
        if (AppConfig.aiProviderForModel(modelConfig)?.baseUrl.isNullOrBlank()) {
            return@withContext EnsureResult(ReadAloudBgmAssignmentCache.STATUS_FAILED, message = "模型供应商不可用")
        }
        val currentBook = book ?: return@withContext EnsureResult(ReadAloudBgmAssignmentCache.STATUS_FAILED, message = "书籍为空")
        val currentChapter = textChapter
            ?: return@withContext EnsureResult(ReadAloudBgmAssignmentCache.STATUS_FAILED, message = "章节为空")
        val cleanCues = cues.filter { it.text.isNotBlank() }
        if (cleanCues.isEmpty()) {
            return@withContext EnsureResult(ReadAloudBgmAssignmentCache.STATUS_FAILED, message = "当前章节无可配乐文本")
        }
        val catalog = catalogSnapshot()
        if (catalog.tracks.isEmpty()) {
            return@withContext EnsureResult(ReadAloudBgmAssignmentCache.STATUS_FAILED, message = "暂无可用配乐")
        }
        val key = buildCacheKey(currentBook, currentChapter, cleanCues, catalog.hash, modelConfig.id)
        appDb.readAloudBgmDao.latestAssignmentCache(
            currentBook.bookUrl,
            currentChapter.chapter.index,
            key.contentHash,
            key.catalogHash
        )?.takeIf { it.assignmentsJson.isNotBlank() }?.let { cache ->
            return@withContext EnsureResult(
                status = ReadAloudBgmAssignmentCache.STATUS_SUCCESS,
                assignmentCount = assignmentsFromJson(cache.assignmentsJson).size,
                message = "当前章节配乐已缓存",
                cacheKey = cache.cacheKey
            )
        }
        val old = appDb.readAloudBgmDao.assignmentCache(key.cacheKey)
        if (old?.status == ReadAloudBgmAssignmentCache.STATUS_RUNNING) {
            return@withContext EnsureResult(
                status = ReadAloudBgmAssignmentCache.STATUS_RUNNING,
                message = "智能配乐分析中",
                cacheKey = key.cacheKey
            )
        }
        val now = System.currentTimeMillis()
        appDb.readAloudBgmDao.upsertAssignmentCache(
            ReadAloudBgmAssignmentCache(
                cacheKey = key.cacheKey,
                bookUrl = currentBook.bookUrl,
                chapterKey = currentChapter.chapter.url?.ifBlank { currentChapter.chapter.title }.orEmpty(),
                chapterIndex = currentChapter.chapter.index,
                chapterTitle = currentChapter.chapter.title,
                contentHash = key.contentHash,
                modelId = key.modelId,
                catalogHash = key.catalogHash,
                status = ReadAloudBgmAssignmentCache.STATUS_RUNNING,
                createdAt = old?.createdAt?.takeIf { it > 0 } ?: now,
                updatedAt = now
            )
        )
        try {
            val response = AiChatService.requestSingleToolCall(
                messages = listOf(
                    AiChatMessage(
                        role = AiChatMessage.Role.USER,
                        content = buildAssignmentPrompt(currentBook, currentChapter, cleanCues, catalog)
                    )
                ),
                tool = AiResolvedTool(TOOL_ASSIGN_BGM_RANGES, assignmentToolDefinition()) {
                    JSONObject().put("ok", true).toString()
                },
                modelConfigOverride = modelConfig,
                promptCacheKeyOverride = key.promptCacheKey
            )
            val assignments = when {
                response.hasToolCall -> parseAssignments(JSONObject(response.arguments), cleanCues.size, catalog.tracks)
                else -> parseAssignmentsFromContent(response.content, cleanCues.size, catalog.tracks)
            }
            val json = assignmentsToJson(assignments, key.contentHash, key.catalogHash)
            appDb.readAloudBgmDao.upsertAssignmentCache(
                ReadAloudBgmAssignmentCache(
                    cacheKey = key.cacheKey,
                    bookUrl = currentBook.bookUrl,
                    chapterKey = currentChapter.chapter.url?.ifBlank { currentChapter.chapter.title }.orEmpty(),
                    chapterIndex = currentChapter.chapter.index,
                    chapterTitle = currentChapter.chapter.title,
                    contentHash = key.contentHash,
                    modelId = key.modelId,
                    catalogHash = key.catalogHash,
                    assignmentsJson = json,
                    status = ReadAloudBgmAssignmentCache.STATUS_SUCCESS,
                    createdAt = old?.createdAt?.takeIf { it > 0 } ?: now,
                    updatedAt = System.currentTimeMillis()
                )
            )
            EnsureResult(
                status = ReadAloudBgmAssignmentCache.STATUS_SUCCESS,
                assignmentCount = assignments.size,
                message = "智能配乐已缓存",
                cacheKey = key.cacheKey
            )
        } catch (throwable: Throwable) {
            val error = throwable.localizedMessage ?: throwable.javaClass.simpleName
            appDb.readAloudBgmDao.upsertAssignmentCache(
                ReadAloudBgmAssignmentCache(
                    cacheKey = key.cacheKey,
                    bookUrl = currentBook.bookUrl,
                    chapterKey = currentChapter.chapter.url?.ifBlank { currentChapter.chapter.title }.orEmpty(),
                    chapterIndex = currentChapter.chapter.index,
                    chapterTitle = currentChapter.chapter.title,
                    contentHash = key.contentHash,
                    modelId = key.modelId,
                    catalogHash = key.catalogHash,
                    assignmentsJson = old?.assignmentsJson.orEmpty(),
                    status = ReadAloudBgmAssignmentCache.STATUS_FAILED,
                    lastError = error,
                    createdAt = old?.createdAt?.takeIf { it > 0 } ?: now,
                    updatedAt = System.currentTimeMillis()
                )
            )
            EnsureResult(
                status = ReadAloudBgmAssignmentCache.STATUS_FAILED,
                error = error,
                cacheKey = key.cacheKey
            )
        }
    }

    fun catalogJson(): JSONObject {
        val snapshot = catalogSnapshot()
        return JSONObject().apply {
            put("ok", true)
            put("catalogHash", snapshot.hash)
            put("groups", JSONArray().apply {
                snapshot.groups.forEach { group ->
                    put(JSONObject().apply {
                        put("groupId", group.id)
                        put("name", group.displayName())
                    })
                }
            })
            put("tracks", JSONArray().apply {
                snapshot.tracks.forEach { track ->
                    put(trackJson(track, snapshot.groups))
                }
            })
        }
    }

    fun assignmentToolDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_ASSIGN_BGM_RANGES)
                put("description", "给朗读章节按 cue 范围选择背景音乐。只返回需要播放配乐的范围，旁白无配乐时不用返回。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("assignments", JSONObject().apply {
                            put("type", "array")
                            put("items", JSONObject().apply {
                                put("type", "object")
                                put("properties", JSONObject().apply {
                                    put("fromCueIndex", intProp("0-based cue index, inclusive."))
                                    put("toCueIndex", intProp("0-based cue index, inclusive."))
                                    put("trackId", intProp("music track id from catalog."))
                                    put("volume", numberProp("0.0..1.0, default 0.22."))
                                    put("fadeInMs", intProp("fade-in milliseconds, default 1800."))
                                    put("fadeOutMs", intProp("fade-out milliseconds, default 1800."))
                                    put("reason", stringProp("short reason."))
                                    put("confidence", numberProp("0.0..1.0."))
                                })
                                put("required", JSONArray().put("fromCueIndex").put("toCueIndex").put("trackId"))
                                put("additionalProperties", false)
                            })
                        })
                        put("bookUrl", stringProp("optional, used only when persisting from general AI tools."))
                        put("chapterIndex", intProp("optional, 0-based chapter index."))
                        put("contentHash", stringProp("optional content hash."))
                        put("catalogHash", stringProp("optional catalog hash."))
                    })
                    put("required", JSONArray().put("assignments"))
                    put("additionalProperties", false)
                })
            })
        }
    }

    fun toolAssign(args: JSONObject?): String {
        val snapshot = catalogSnapshot()
        val assignments = parseAssignments(args ?: JSONObject(), args?.optInt("cueCount", 10_000) ?: 10_000, snapshot.tracks)
        val bookUrl = args?.optString("bookUrl").orEmpty()
        val chapterIndex = args?.optInt("chapterIndex", -1) ?: -1
        val contentHash = args?.optString("contentHash").orEmpty()
        val catalogHash = args?.optString("catalogHash").orEmpty().ifBlank { snapshot.hash }
        val persisted = if (bookUrl.isNotBlank() && chapterIndex >= 0 && contentHash.isNotBlank()) {
            val now = System.currentTimeMillis()
            val modelId = AppConfig.aiReadAloudRoleModelConfig?.id.orEmpty()
            val cacheKey = MD5Utils.md5Encode("read-aloud-bgm-tool|$bookUrl|$chapterIndex|$contentHash|$catalogHash|$modelId")
            appDb.readAloudBgmDao.upsertAssignmentCache(
                ReadAloudBgmAssignmentCache(
                    cacheKey = cacheKey,
                    bookUrl = bookUrl,
                    chapterIndex = chapterIndex,
                    contentHash = contentHash,
                    catalogHash = catalogHash,
                    modelId = modelId,
                    assignmentsJson = assignmentsToJson(assignments, contentHash, catalogHash),
                    status = ReadAloudBgmAssignmentCache.STATUS_SUCCESS,
                    createdAt = now,
                    updatedAt = now
                )
            )
            true
        } else {
            false
        }
        return JSONObject().apply {
            put("ok", true)
            put("persisted", persisted)
            put("assignmentCount", assignments.size)
            put("assignments", JSONArray().apply {
                assignments.forEach { put(it.toJson()) }
            })
        }.toString()
    }

    fun cachedAssignmentForCue(
        bookUrl: String?,
        chapterIndex: Int,
        cueIndex: Int
    ): ResolvedAssignment? {
        if (bookUrl.isNullOrBlank() || chapterIndex < 0 || cueIndex < 0) return null
        val cache = appDb.readAloudBgmDao.latestAssignmentCacheByChapter(bookUrl, chapterIndex)
            ?: return null
        val assignment = assignmentsFromJson(cache.assignmentsJson)
            .firstOrNull { cueIndex in it.fromCueIndex..it.toCueIndex }
            ?: return null
        val track = appDb.readAloudBgmDao.track(assignment.trackId)
            ?.takeIf { it.enabled && it.filePath.isNotBlank() && File(it.filePath).exists() }
            ?: return null
        return ResolvedAssignment(assignment, track)
    }

    private fun buildAssignmentPrompt(
        book: Book,
        chapter: TextChapter,
        cues: List<ReadAloudCue>,
        catalog: CatalogSnapshot
    ): String {
        val catalogText = catalog.tracks.joinToString("\n") { track ->
            val group = catalog.groups.firstOrNull { it.id == track.groupId }?.displayName() ?: "默认分组"
            val tags = track.tags.ifBlank { "-" }
            "${track.id}|${track.displayName()}|$group|$tags|${track.durationMs / 1000}s"
        }
        val cueText = cues.mapIndexed { index, cue ->
            "C$index|pos=${cue.chapterPosition}|${cue.text.compactForPrompt(180)}"
        }.joinToString("\n")
        return """
            任务：为小说朗读选择背景音乐范围。必须调用 $TOOL_ASSIGN_BGM_RANGES。
            规则：只给需要配乐的连续 cue 范围；安静阅读、普通对话可以不配乐。不要逐句都换音乐，优先减少切换。一个范围用 fromCueIndex/toCueIndex，均为 0-based 且包含边界。trackId 必须来自曲库。音量建议 0.12-0.28，避免盖过朗读。开头和末尾都要给淡入淡出毫秒数。
            书：${book.name} / ${book.author}
            章：${chapter.chapter.title}

            曲库(trackId|名称|分组|标签|时长)：
            $catalogText

            章节 cue：
            $cueText
        """.trimIndent()
    }

    private fun buildCacheKey(
        book: Book,
        chapter: TextChapter,
        cues: List<ReadAloudCue>,
        catalogHash: String,
        modelId: String
    ): BgmCacheKey {
        val contentHash = MD5Utils.md5Encode(
            cues.joinToString("\n") { "${it.chapterPosition}|${it.text}" }
        )
        val cacheKey = MD5Utils.md5Encode(
            "read-aloud-bgm|${book.bookUrl}|${chapter.chapter.index}|${chapter.chapter.url}|$contentHash|$catalogHash|$modelId|$SCHEMA_VERSION"
        )
        val promptCacheKey = MD5Utils.md5Encode(
            "read-aloud-bgm-prompt|${book.bookUrl}|$catalogHash|$modelId|$SCHEMA_VERSION"
        )
        return BgmCacheKey(
            cacheKey = cacheKey,
            contentHash = contentHash,
            catalogHash = catalogHash,
            modelId = modelId,
            promptCacheKey = "read_aloud_bgm_$promptCacheKey"
        )
    }

    private fun catalogSnapshot(): CatalogSnapshot {
        val groups = appDb.readAloudBgmDao.groups()
        val tracks = appDb.readAloudBgmDao.enabledTracksByType(ReadAloudBgmTrack.TYPE_BGM)
        val hash = MD5Utils.md5Encode(
            tracks.joinToString("|") {
                "${it.id}:${it.groupId}:${it.displayName()}:${it.tags}:${it.checksum}:${it.enabled}:${it.defaultVolume}:${it.updatedAt}"
            }
        )
        return CatalogSnapshot(groups, tracks, hash)
    }

    private fun parseAssignmentsFromContent(
        content: String,
        cueCount: Int,
        tracks: List<ReadAloudBgmTrack>
    ): List<Assignment> {
        return runCatching {
            parseAssignments(JSONObject(content), cueCount, tracks)
        }.getOrDefault(emptyList())
    }

    private fun parseAssignments(
        args: JSONObject,
        cueCount: Int,
        tracks: List<ReadAloudBgmTrack>
    ): List<Assignment> {
        val validTrackIds = tracks.mapTo(hashSetOf()) { it.id }
        val array = args.optJSONArray("assignments")
            ?: args.optJSONArray("ranges")
            ?: JSONArray()
        val maxCueIndex = (cueCount - 1).coerceAtLeast(0)
        val result = mutableListOf<Assignment>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val trackId = item.optLong("trackId", 0L)
            if (trackId !in validTrackIds) continue
            val from = item.firstInt("fromCueIndex", "cueFrom", "startCueIndex", "from", "fromParagraph")
                ?.let { normalizeCueIndex(it, item.has("fromParagraph")) }
                ?.coerceIn(0, maxCueIndex)
                ?: continue
            val to = item.firstInt("toCueIndex", "cueTo", "endCueIndex", "to", "toParagraph")
                ?.let { normalizeCueIndex(it, item.has("toParagraph")) }
                ?.coerceIn(from, maxCueIndex)
                ?: from
            result += Assignment(
                fromCueIndex = from,
                toCueIndex = to,
                trackId = trackId,
                volume = item.optDouble("volume", 0.22).toFloat().coerceIn(0.0f, 0.8f),
                fadeInMs = item.optInt("fadeInMs", 1800).coerceIn(0, 12_000),
                fadeOutMs = item.optInt("fadeOutMs", 1800).coerceIn(0, 12_000),
                reason = item.optString("reason").trim().take(120),
                confidence = item.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
            )
        }
        return result
            .sortedWith(compareBy<Assignment> { it.fromCueIndex }.thenBy { it.toCueIndex })
            .fold(mutableListOf()) { acc, assignment ->
                val last = acc.lastOrNull()
                if (last != null && assignment.fromCueIndex <= last.toCueIndex) {
                    if (assignment.confidence > last.confidence) {
                        acc[acc.lastIndex] = assignment.copy(fromCueIndex = last.fromCueIndex)
                    }
                } else {
                    acc += assignment
                }
                acc
            }
    }

    private fun assignmentsFromJson(json: String): List<Assignment> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val root = JSONObject(json)
            val array = root.optJSONArray("assignments") ?: JSONArray()
            val result = mutableListOf<Assignment>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val from = item.optInt("fromCueIndex", -1)
                val to = item.optInt("toCueIndex", from)
                val trackId = item.optLong("trackId", 0L)
                if (from < 0 || to < from || trackId <= 0L) continue
                result += Assignment(
                    fromCueIndex = from,
                    toCueIndex = to,
                    trackId = trackId,
                    volume = item.optDouble("volume", 0.22).toFloat().coerceIn(0.0f, 0.8f),
                    fadeInMs = item.optInt("fadeInMs", 1800).coerceIn(0, 12_000),
                    fadeOutMs = item.optInt("fadeOutMs", 1800).coerceIn(0, 12_000),
                    reason = item.optString("reason").trim().take(120),
                    confidence = item.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
                )
            }
            result
        }.getOrDefault(emptyList())
    }

    private fun assignmentsToJson(
        assignments: List<Assignment>,
        contentHash: String,
        catalogHash: String
    ): String {
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("contentHash", contentHash)
            put("catalogHash", catalogHash)
            put("assignments", JSONArray().apply {
                assignments.forEach { put(it.toJson()) }
            })
        }.toString()
    }

    private fun Assignment.toJson(): JSONObject {
        return JSONObject().apply {
            put("fromCueIndex", fromCueIndex)
            put("toCueIndex", toCueIndex)
            put("trackId", trackId)
            put("volume", volume.toDouble())
            put("fadeInMs", fadeInMs)
            put("fadeOutMs", fadeOutMs)
            put("reason", reason)
            put("confidence", confidence)
        }
    }

    private fun trackJson(track: ReadAloudBgmTrack, groups: List<ReadAloudBgmGroup>): JSONObject {
        return JSONObject().apply {
            put("trackId", track.id)
            put("name", track.displayName())
            put("groupId", track.groupId)
            put("groupName", groups.firstOrNull { it.id == track.groupId }?.displayName() ?: "默认分组")
            put("tags", JSONArray().apply {
                track.tags.split(',', '，')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .forEach(::put)
            })
            put("durationMs", track.durationMs)
            put("enabled", track.enabled)
        }
    }

    private fun normalizeCueIndex(value: Int, oneBased: Boolean): Int {
        return if (oneBased && value > 0) value - 1 else value
    }

    private fun JSONObject.firstInt(vararg keys: String): Int? {
        for (key in keys) {
            if (has(key)) return optInt(key)
        }
        return null
    }

    private fun String.compactForPrompt(maxLength: Int): String {
        val compact = replace(Regex("\\s+"), " ").trim()
        return if (compact.length <= maxLength) compact else compact.take(maxLength) + "…"
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
