package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiReadAloudRoleCache
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.role.ReadAloudRolePreprocessor
import io.legado.app.help.readaloud.role.ReadAloudRoleUnit
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechVoiceAssigner
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.postEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object AiReadAloudRoleService {

    private const val TOOL_RECORD_SEGMENTS = "record_read_aloud_role_segments"
    private const val TOOL_CONFIRM_UNITS = "confirm_read_aloud_role_units"
    private const val TARGET_GROUP_SIZE = 12
    private const val MAX_SEARCH_BATCH_TARGET_UNITS = 48
    private const val MAX_SEARCH_BATCH_CONTEXT_CHARS = 18_000
    private const val PLAYBACK_ASSIGNMENT_ATTEMPTS = 3
    private const val UNKNOWN_RETRY_ATTEMPTS = 1
    private const val RUNNING_WAIT_STEP_MILLIS = 600L
    private const val RUNNING_WAIT_TIMEOUT_MILLIS = 120_000L
    private val runningCacheKeys = ConcurrentHashMap.newKeySet<String>()
    private val speechVerbRegex = Regex(
        "([\\p{IsHan}A-Za-z0-9_·]{1,24})\\s*(?:说道|说|道|问道|问|答道|答|笑道|冷声道|沉声道|低声道|怒道|喝道|喊道|叫道|开口道|喃喃道)\\s*[，,、：:]?\\s*$"
    )
    private val invalidCharacterNameKeywordRegex = Regex(
        "(?:说道|问道|答道|笑道|冷声道|沉声道|低声道|怒道|喝道|喊道|叫道|开口道|喃喃道|警告|提醒|告诉|炫耀|得意|有些|突然|已经|正在)"
    )

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
        val candidates: List<CharacterCandidate> = emptyList(),
        val aiRequired: Boolean = false,
        val aiSatisfied: Boolean = true
    )

    private class RoleUsageTracker {
        private val startAt = System.currentTimeMillis()
        private var requestCounter = 0
        private var usage = AiUsageStats()

        @Synchronized
        fun onRequest() {
            requestCounter += 1
        }

        @Synchronized
        fun onUsage(stats: AiUsageStats) {
            usage += stats
        }

        @Synchronized
        fun snapshot(): RoleUsageSnapshot {
            return RoleUsageSnapshot(
                elapsedMillis = System.currentTimeMillis() - startAt,
                requestCount = requestCounter,
                usage = usage
            )
        }
    }

    private data class RoleUsageSnapshot(
        val elapsedMillis: Long = 0L,
        val requestCount: Int = 0,
        val usage: AiUsageStats = AiUsageStats()
    )

    private data class UnitResolution(
        val unitId: String,
        val roleType: String,
        val characterName: String,
        val characterId: Long,
        val emotionName: String,
        val emotionTag: String,
        val confidence: Double,
        val status: String = "assigned",
        val evidence: String = ""
    )

    private data class RoleCacheKey(
        val cacheKey: String,
        val promptCacheKey: String,
        val mode: String,
        val prompt: String,
        val contentHash: String,
        val contextParagraphs: Int,
        val mergeGapParagraphs: Int
    )

    private data class UnitAssignmentBatch(
        val index: Int,
        val targetParagraphs: List<Int>,
        val units: List<ReadAloudRoleUnit>
    )

    private data class UnitAssignmentResult(
        val resolutions: List<UnitResolution> = emptyList(),
        val candidates: List<CharacterCandidate> = emptyList()
    )

    data class EnsureResult(
        val status: String,
        val segmentCount: Int = 0,
        val createdCharacterCount: Int = 0,
        val message: String = "",
        val error: String = "",
        val cacheKey: String = ""
    ) {
        val usable: Boolean
            get() = segmentCount > 0
    }

    fun cacheKeyFor(
        book: Book?,
        textChapter: TextChapter?,
        paragraphs: List<String>
    ): String? {
        val currentBook = book ?: return null
        val currentChapter = textChapter ?: return null
        val cleanParagraphs = paragraphs.map { it.trimEnd() }.filter { it.isNotBlank() }
        if (cleanParagraphs.isEmpty()) return null
        return buildRoleCacheKey(currentBook, currentChapter, cleanParagraphs)?.cacheKey
    }

    fun cacheForPlayback(
        book: Book?,
        textChapter: TextChapter?,
        paragraphs: List<String>
    ): AiReadAloudRoleCache? {
        val currentBook = book ?: return null
        val currentChapter = textChapter ?: return null
        val cleanParagraphs = paragraphs.map { it.trimEnd() }.filter { it.isNotBlank() }
        if (cleanParagraphs.isEmpty()) return null
        val roleKey = buildRoleCacheKey(currentBook, currentChapter, cleanParagraphs) ?: return null
        return appDb.aiReadAloudRoleCacheDao.get(roleKey.cacheKey)
            ?.takeIf { it.status == AiReadAloudRoleCache.STATUS_SUCCESS && it.segmentsJson.isNotBlank() }
            ?: latestSuccessCache(currentBook, currentChapter, roleKey)
    }

    fun isRunningCacheActive(cacheKey: String?, updatedAt: Long): Boolean {
        if (cacheKey.isNullOrBlank() || updatedAt <= 0L) return false
        return cacheKey in runningCacheKeys &&
                System.currentTimeMillis() - updatedAt < 5 * 60 * 1000L
    }

    private fun buildRoleCacheKey(
        book: Book,
        textChapter: TextChapter,
        cleanParagraphs: List<String>
    ): RoleCacheKey? {
        val modelConfig = AppConfig.aiReadAloudRoleModelConfig ?: return null
        val prompt = AppConfig.aiReadAloudRolePrompt.trim()
        val baseMode = AppConfig.aiReadAloudRoleMode
        val mode = "$baseMode|${ReadAloudRolePreprocessor.VERSION}"
        val contentHash = MD5Utils.md5Encode(cleanParagraphs.joinToString("\n"))
        val promptHash = MD5Utils.md5Encode(prompt)
        val contextParagraphs = AppConfig.aiReadAloudRoleContextParagraphs
        val mergeGapParagraphs = AppConfig.aiReadAloudRoleMergeGapParagraphs
        val cacheKey = MD5Utils.md5Encode(
            "read-aloud-role|${book.bookUrl}|${textChapter.chapter.index}|${textChapter.chapter.url}|$contentHash|$mode|ctx=$contextParagraphs|gap=$mergeGapParagraphs|$promptHash|${modelConfig.id}"
        )
        val promptCacheKey = MD5Utils.md5Encode(
            "read-aloud-role-prompt|${book.bookUrl}|$mode|ctx=$contextParagraphs|gap=$mergeGapParagraphs|$promptHash|${modelConfig.id}"
        )
        return RoleCacheKey(
            cacheKey = cacheKey,
            promptCacheKey = "read_aloud_role_$promptCacheKey",
            mode = mode,
            prompt = prompt,
            contentHash = contentHash,
            contextParagraphs = contextParagraphs,
            mergeGapParagraphs = mergeGapParagraphs
        )
    }

    private fun latestSuccessCache(
        book: Book,
        textChapter: TextChapter,
        roleKey: RoleCacheKey
    ): AiReadAloudRoleCache? {
        return appDb.aiReadAloudRoleCacheDao.latestSuccessByChapterContent(
            bookUrl = book.bookUrl,
            chapterIndex = textChapter.chapter.index,
            contentHash = roleKey.contentHash,
            preprocessVersion = ReadAloudRolePreprocessor.VERSION
        )
    }

    suspend fun ensurePlayableCache(
        book: Book?,
        textChapter: TextChapter?,
        paragraphs: List<String>,
        stage: String = AiReadAloudRoleState.STAGE_CURRENT
    ): EnsureResult {
        var lastResult = EnsureResult(AiReadAloudRoleState.STATUS_FAILED, error = "多角色分配失败")
        repeat(PLAYBACK_ASSIGNMENT_ATTEMPTS) {
            val result = ensureCache(book, textChapter, paragraphs, stage)
            lastResult = result
            if (result.status == AiReadAloudRoleState.STATUS_SUCCESS ||
                result.status == AiReadAloudRoleState.STATUS_SKIPPED && result.segmentCount > 0
            ) {
                return result.copy(status = AiReadAloudRoleState.STATUS_SUCCESS)
            }
            if (result.status == AiReadAloudRoleState.STATUS_RUNNING) {
                val currentBook = book ?: return result
                val currentChapter = textChapter ?: return result
                val waited = waitForRunningCache(result.cacheKey, currentBook, currentChapter, paragraphs, stage)
                lastResult = waited ?: result
                if (waited != null && waited.segmentCount > 0) {
                    return waited.copy(status = AiReadAloudRoleState.STATUS_SUCCESS)
                }
                return lastResult
            }
        }
        return lastResult
    }

    fun clearChapterCache(bookUrl: String?, chapterIndex: Int) {
        if (bookUrl.isNullOrBlank() || chapterIndex < 0) return
        appDb.aiReadAloudRoleCacheDao.deleteByChapter(bookUrl, chapterIndex)
    }

    suspend fun ensureCache(
        book: Book?,
        textChapter: TextChapter?,
        paragraphs: List<String>,
        stage: String = AiReadAloudRoleState.STAGE_CURRENT
    ): EnsureResult {
        if (!AppConfig.aiReadAloudRoleEnabled) {
            return EnsureResult(AiReadAloudRoleState.STATUS_SKIPPED, message = "多角色未开启")
        }
        val modelConfig = AppConfig.aiReadAloudRoleModelConfig
            ?: return EnsureResult(AiReadAloudRoleState.STATUS_SKIPPED, message = "未配置多角色模型")
        if (AppConfig.aiProviderForModel(modelConfig)?.baseUrl.isNullOrBlank()) {
            return EnsureResult(AiReadAloudRoleState.STATUS_SKIPPED, message = "多角色模型供应商不可用")
        }
        val currentBook = book
            ?: return EnsureResult(AiReadAloudRoleState.STATUS_SKIPPED, message = "书籍为空")
        val currentChapter = textChapter
            ?: return EnsureResult(AiReadAloudRoleState.STATUS_SKIPPED, message = "章节为空")
        val cleanParagraphs = paragraphs.map { it.trimEnd() }.filter { it.isNotBlank() }
        if (cleanParagraphs.isEmpty()) {
            return EnsureResult(AiReadAloudRoleState.STATUS_SKIPPED, message = "当前章节无可朗读段落")
        }
        val roleKey = buildRoleCacheKey(currentBook, currentChapter, cleanParagraphs)
            ?: return EnsureResult(AiReadAloudRoleState.STATUS_SKIPPED, message = "未配置多角色模型")
        val mode = roleKey.mode
        val prompt = roleKey.prompt
        val contentHash = roleKey.contentHash
        val contextParagraphs = roleKey.contextParagraphs
        val mergeGapParagraphs = roleKey.mergeGapParagraphs
        val cacheKey = roleKey.cacheKey
        val previewBuffer = mutableListOf<AiReadAloudRolePreviewSegment>()
        val usageTracker = RoleUsageTracker()
        fun postPreview(
            status: String,
            message: String,
            source: String,
            segments: List<AiReadAloudRolePreviewSegment>,
            createdCharacterCount: Int = 0,
            newCharacterCandidateCount: Int = 0,
            error: String = ""
        ) {
            val snapshot = synchronized(previewBuffer) {
                if (source == AiReadAloudRoleState.SOURCE_RESOLVED ||
                    source == AiReadAloudRoleState.SOURCE_CACHE ||
                    source == AiReadAloudRoleState.SOURCE_FALLBACK
                ) {
                    previewBuffer.clear()
                }
                if (segments.isNotEmpty()) {
                    val byKey = (previewBuffer + segments).associateBy { it.key }
                    previewBuffer.clear()
                    previewBuffer += byKey.values.sortedWith(
                        compareBy<AiReadAloudRolePreviewSegment> { it.paragraphIndex }
                            .thenBy { it.start }
                            .thenBy { it.end }
                    )
                }
                previewBuffer.toList()
            }
            postState(
                currentBook,
                currentChapter,
                stage,
                status,
                message,
                cleanParagraphs.size,
                snapshot.size,
                createdCharacterCount,
                newCharacterCandidateCount,
                source,
                snapshot,
                error,
                usageTracker.snapshot()
            )
        }
        val oldCache = appDb.aiReadAloudRoleCacheDao.get(cacheKey)
        if (oldCache?.status == AiReadAloudRoleCache.STATUS_SUCCESS && oldCache.segmentsJson.isNotBlank()) {
            val cachedSegments = segmentsFromJson(oldCache.segmentsJson)
            val preview = buildPreviewSegments(
                currentBook.bookUrl,
                cachedSegments,
                cleanParagraphs,
                AiReadAloudRoleState.SOURCE_CACHE
            )
            val count = cachedSegments.size
            postPreview(
                AiReadAloudRoleState.STATUS_SKIPPED,
                "当前章节角色已分配",
                AiReadAloudRoleState.SOURCE_CACHE,
                preview
            )
            return EnsureResult(
                AiReadAloudRoleState.STATUS_SKIPPED,
                count,
                message = "当前章节角色已分配",
                cacheKey = cacheKey
            )
        }
        latestSuccessCache(currentBook, currentChapter, roleKey)
            ?.takeIf { it.cacheKey != cacheKey }
            ?.let { usableCache ->
                val cachedSegments = segmentsFromJson(usableCache.segmentsJson)
                val preview = buildPreviewSegments(
                    currentBook.bookUrl,
                    cachedSegments,
                    cleanParagraphs,
                    AiReadAloudRoleState.SOURCE_CACHE
                )
                postPreview(
                    AiReadAloudRoleState.STATUS_SKIPPED,
                    "当前章节角色已分配",
                    AiReadAloudRoleState.SOURCE_CACHE,
                    preview
                )
                return EnsureResult(
                    AiReadAloudRoleState.STATUS_SKIPPED,
                    cachedSegments.size,
                    message = "当前章节角色已分配",
                    cacheKey = usableCache.cacheKey
                )
            }
        if (oldCache?.status == AiReadAloudRoleCache.STATUS_RUNNING) {
            if (isRunningCacheActive(cacheKey, oldCache.updatedAt)) {
                postState(currentBook, currentChapter, stage, AiReadAloudRoleState.STATUS_RUNNING, stageMessage(stage, "分配角色中"), cleanParagraphs.size)
                return EnsureResult(AiReadAloudRoleState.STATUS_RUNNING, message = "分配角色中", cacheKey = cacheKey)
            }
            val error = "上次多角色分配中断，请重新分配当前章节"
            appDb.aiReadAloudRoleCacheDao.upsert(
                oldCache.copy(
                    status = AiReadAloudRoleCache.STATUS_FAILED,
                    retryCount = PLAYBACK_ASSIGNMENT_ATTEMPTS,
                    lastError = error,
                    updatedAt = System.currentTimeMillis()
                )
            )
            postState(currentBook, currentChapter, stage, AiReadAloudRoleState.STATUS_FAILED, error, cleanParagraphs.size, error = error)
            return EnsureResult(AiReadAloudRoleState.STATUS_FAILED, error = error, cacheKey = cacheKey)
        }
        if ((oldCache?.retryCount ?: 0) >= 3 && oldCache?.segmentsJson?.isNotBlank() == true) {
            val fallbackSegments = segmentsFromJson(oldCache.segmentsJson)
            val preview = buildPreviewSegments(
                currentBook.bookUrl,
                fallbackSegments,
                cleanParagraphs,
                AiReadAloudRoleState.SOURCE_FALLBACK
            )
            val count = fallbackSegments.size
            postPreview(
                AiReadAloudRoleState.STATUS_FAILED,
                oldCache.lastError.ifBlank { "AI分角色连续失败，请重新分配当前章节" },
                AiReadAloudRoleState.SOURCE_FALLBACK,
                preview,
                error = oldCache.lastError.ifBlank { "AI分角色连续失败" }
            )
            return EnsureResult(
                AiReadAloudRoleState.STATUS_FAILED,
                error = oldCache.lastError.ifBlank { "AI分角色连续失败" },
                cacheKey = cacheKey
            )
        }
        if ((oldCache?.retryCount ?: 0) >= 3) {
            val error = oldCache?.lastError?.ifBlank { "AI分角色连续失败" } ?: "AI分角色连续失败"
            postState(currentBook, currentChapter, stage, AiReadAloudRoleState.STATUS_FAILED, error, cleanParagraphs.size, error = error)
            return EnsureResult(AiReadAloudRoleState.STATUS_FAILED, error = error, cacheKey = cacheKey)
        }
        if (!runningCacheKeys.add(cacheKey)) {
            return EnsureResult(AiReadAloudRoleState.STATUS_RUNNING, message = "分配角色中", cacheKey = cacheKey)
        }
        val now = System.currentTimeMillis()
        postState(currentBook, currentChapter, stage, AiReadAloudRoleState.STATUS_RUNNING, stageMessage(stage, "分配角色中"), cleanParagraphs.size)
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
            val preprocess = ReadAloudRolePreprocessor.process(cleanParagraphs)
            val result = requestBatchedUnitAssignments(
                book = currentBook,
                textChapter = currentChapter,
                paragraphs = cleanParagraphs,
                preprocess = preprocess,
                contextParagraphs = contextParagraphs,
                mergeGapParagraphs = mergeGapParagraphs,
                prompt = prompt,
                promptCacheKey = roleKey.promptCacheKey,
                usageTracker = usageTracker,
                fullChapterMode = mode.substringBefore("|") == AppConfig.AI_READ_ALOUD_ROLE_MODE_FULL,
                onPreview = { preview, candidateCount, source ->
                    postPreview(
                        AiReadAloudRoleState.STATUS_RUNNING,
                        if (source == AiReadAloudRoleState.SOURCE_RULE) "本地预处理 ${preview.size} 个片段" else "已确认 ${preview.size} 个分配片段",
                        source,
                        preview,
                        newCharacterCandidateCount = candidateCount
                    )
                }
            )
            val aiSegments = result.segments
                .distinctBy { "${it.paragraphIndex}:${it.start}:${it.end}:${it.roleType}:${it.characterName}" }
                .sortedWith(compareBy<Segment> { it.paragraphIndex }.thenBy { it.start }.thenBy { it.end })
            val successAt = System.currentTimeMillis()
            if (aiSegments.isEmpty() || result.aiRequired && !result.aiSatisfied) {
                val fallback = resolveSegmentCharacters(
                    aiSegments.ifEmpty { buildDefaultSegments(cleanParagraphs) },
                    appDb.bookCharacterDao.characters(currentBook.bookUrl)
                )
                val error = if (aiSegments.isEmpty()) {
                    "AI未返回有效分角色片段，已使用默认分角色"
                } else {
                    "AI未完整确认不确定角色片段，请重新分配当前章节"
                }
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
                        lastError = error,
                        segmentsJson = fallback.toJsonArray().toString(),
                        characterHash = characterHash(currentBook.bookUrl),
                        voiceHash = voiceHash(currentBook.bookUrl),
                        createdAt = oldCache?.createdAt?.takeIf { it > 0 } ?: successAt,
                        updatedAt = successAt
                    )
                )
                postPreview(
                    AiReadAloudRoleState.STATUS_FAILED,
                    if (aiSegments.isEmpty()) "AI未返回有效分角色片段，请重新分配当前章节" else "AI未完整确认不确定角色片段，请重新分配当前章节",
                    AiReadAloudRoleState.SOURCE_FALLBACK,
                    buildPreviewSegments(
                        currentBook.bookUrl,
                        fallback,
                        cleanParagraphs,
                        AiReadAloudRoleState.SOURCE_FALLBACK
                    ),
                    error = error
                )
                return EnsureResult(AiReadAloudRoleState.STATUS_FAILED, error = error, cacheKey = cacheKey)
            }
            val resolved = persistDetectedCharacters(currentBook.bookUrl, aiSegments, result.candidates)
            val finalUsage = usageTracker.snapshot()
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
                    segmentsJson = resolved.first.toCacheJson(
                        preprocessVersion = ReadAloudRolePreprocessor.VERSION,
                        contentHash = contentHash,
                        usageSnapshot = finalUsage
                    ),
                    createdCharacterIdsJson = JSONArray(resolved.second).toString(),
                    characterHash = characterHash(currentBook.bookUrl),
                    voiceHash = voiceHash(currentBook.bookUrl),
                    createdAt = oldCache?.createdAt?.takeIf { it > 0 } ?: successAt,
                    updatedAt = successAt
                )
            )
            postPreview(
                AiReadAloudRoleState.STATUS_SUCCESS,
                stageMessage(stage, "角色分配完成"),
                AiReadAloudRoleState.SOURCE_RESOLVED,
                buildPreviewSegments(
                    currentBook.bookUrl,
                    resolved.first,
                    cleanParagraphs,
                    AiReadAloudRoleState.SOURCE_RESOLVED
                ),
                createdCharacterCount = resolved.second.size,
                newCharacterCandidateCount = result.candidates.size
            )
            return EnsureResult(
                status = AiReadAloudRoleState.STATUS_SUCCESS,
                segmentCount = resolved.first.size,
                createdCharacterCount = resolved.second.size,
                message = "角色分配完成",
                cacheKey = cacheKey
            )
        } catch (throwable: Throwable) {
            val failedAt = System.currentTimeMillis()
            val fallback = resolveSegmentCharacters(
                buildDefaultSegments(cleanParagraphs),
                appDb.bookCharacterDao.characters(currentBook.bookUrl)
            )
            val error = throwable.localizedMessage ?: throwable.javaClass.simpleName
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
                    lastError = error.take(400),
                    segmentsJson = fallback.toJsonArray().toString(),
                    characterHash = characterHash(currentBook.bookUrl),
                    voiceHash = voiceHash(currentBook.bookUrl),
                    createdAt = oldCache?.createdAt?.takeIf { it > 0 } ?: failedAt,
                    updatedAt = failedAt
                )
            )
            postPreview(
                AiReadAloudRoleState.STATUS_FAILED,
                "AI分角色失败，请重新分配当前章节",
                AiReadAloudRoleState.SOURCE_FALLBACK,
                buildPreviewSegments(
                    currentBook.bookUrl,
                    fallback,
                    cleanParagraphs,
                    AiReadAloudRoleState.SOURCE_FALLBACK
                ),
                error = error
            )
            AppLog.put("AI分角色标注失败\n$error", throwable)
            return EnsureResult(AiReadAloudRoleState.STATUS_FAILED, error = error, cacheKey = cacheKey)
        } finally {
            runningCacheKeys.remove(cacheKey)
        }
    }

    private suspend fun waitForRunningCache(
        cacheKey: String,
        book: Book,
        textChapter: TextChapter,
        paragraphs: List<String>,
        stage: String
    ): EnsureResult? {
        if (cacheKey.isBlank()) return null
        val cleanParagraphs = paragraphs.map { it.trimEnd() }.filter { it.isNotBlank() }
        val deadline = System.currentTimeMillis() + RUNNING_WAIT_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val cache = appDb.aiReadAloudRoleCacheDao.get(cacheKey)
            when {
                cache?.status == AiReadAloudRoleCache.STATUS_SUCCESS &&
                        cache.segmentsJson.isNotBlank() -> {
                    val segments = segmentsFromJson(cache.segmentsJson)
                    postState(
                        book = book,
                        chapter = textChapter,
                        stage = stage,
                        status = AiReadAloudRoleState.STATUS_SUCCESS,
                        message = stageMessage(stage, "角色分配完成"),
                        paragraphCount = cleanParagraphs.size,
                        segmentCount = segments.size,
                        previewSource = AiReadAloudRoleState.SOURCE_CACHE,
                        previewSegments = buildPreviewSegments(
                            book.bookUrl,
                            segments,
                            cleanParagraphs,
                            AiReadAloudRoleState.SOURCE_CACHE
                        )
                    )
                    return EnsureResult(
                        status = AiReadAloudRoleState.STATUS_SUCCESS,
                        segmentCount = segments.size,
                        message = "角色分配完成",
                        cacheKey = cacheKey
                    )
                }
                cache?.status == AiReadAloudRoleCache.STATUS_FAILED -> {
                    return EnsureResult(
                        status = AiReadAloudRoleState.STATUS_FAILED,
                        error = cache.lastError.ifBlank { "多角色分配失败" },
                        cacheKey = cacheKey
                    )
                }
            }
            delay(RUNNING_WAIT_STEP_MILLIS)
        }
        return EnsureResult(AiReadAloudRoleState.STATUS_FAILED, error = "等待多角色分配超时", cacheKey = cacheKey)
    }

    fun routeForCue(
        bookUrl: String?,
        chapterIndex: Int,
        cueIndex: Int,
        cueText: String? = null,
        cacheKey: String? = null
    ): SpeechRoute? {
        if (bookUrl.isNullOrBlank() || cueIndex < 0) return null
        val best = assignedSegmentsForCue(bookUrl, chapterIndex, cueIndex, cacheKey)
            .filter { it.roleType == "character" || it.roleType == "thought" }
            .maxWithOrNull(compareBy<Segment> { it.confidence }.thenBy { it.end - it.start })
            ?: return null
        return routeForSegment(bookUrl, best)
    }

    fun routeForSegment(
        bookUrl: String?,
        segment: Segment
    ): SpeechRoute? {
        if (bookUrl.isNullOrBlank()) return null
        val character = when {
            segment.characterId > 0L -> appDb.bookCharacterDao.getCharacter(segment.characterId)
            segment.characterName.isNotBlank() -> appDb.bookCharacterDao.getCharacter(bookUrl, segment.characterName)
            else -> null
        } ?: return null
        val route = SpeechRoute.fromJson(character.speechRouteJson)
        if (!route.isConfigured) return null
        val emotionName = segment.emotionName.trim()
        val emotionTag = segment.emotionTag.trim()
        return if (emotionName.isNotBlank() || emotionTag.isNotBlank()) {
            route.copy(emotionName = emotionName, emotionTag = emotionTag)
        } else {
            route
        }
    }

    fun segmentsForCue(
        bookUrl: String?,
        chapterIndex: Int,
        cueIndex: Int,
        cueText: String? = null,
        cacheKey: String? = null
    ): List<Segment> {
        return assignedSegmentsForCue(bookUrl, chapterIndex, cueIndex, cacheKey)
            .ifEmpty { cueText?.let { buildDefaultSegments(listOf(it), paragraphOffset = cueIndex) }.orEmpty() }
    }

    fun assignedSegmentsForCue(
        bookUrl: String?,
        chapterIndex: Int,
        cueIndex: Int,
        cacheKey: String? = null
    ): List<Segment> {
        if (bookUrl.isNullOrBlank() || cueIndex < 0) return emptyList()
        val cache = if (!cacheKey.isNullOrBlank()) {
            appDb.aiReadAloudRoleCacheDao.get(cacheKey)
                ?.takeIf { it.status == AiReadAloudRoleCache.STATUS_SUCCESS }
        } else {
            appDb.aiReadAloudRoleCacheDao.latestByChapter(bookUrl, chapterIndex)
                ?.takeIf { it.mode.contains(ReadAloudRolePreprocessor.VERSION) }
        } ?: return emptyList()
        val result = segmentsFromJson(cache.segmentsJson).filter { it.paragraphIndex == cueIndex }
        return result
            .sortedWith(compareBy<Segment> { it.start }.thenBy { it.end })
    }

    fun defaultSegmentsForCue(cueIndex: Int, cueText: String): List<Segment> {
        return buildDefaultSegments(listOf(cueText), paragraphOffset = cueIndex)
    }

    private fun buildDefaultSegments(
        paragraphs: List<String>,
        paragraphOffset: Int = 0
    ): List<Segment> {
        return segmentsFromUnits(ReadAloudRolePreprocessor.process(paragraphs, paragraphOffset).units)
    }

    private fun buildDefaultSegmentsForText(paragraphIndex: Int, text: String): List<Segment> {
        if (text.isBlank()) return emptyList()
        parseSpeakerColonSegment(paragraphIndex, text)?.let { return listOf(it) }
        val speechRanges = parseQuotedSpeechRanges(text)
        if (speechRanges.isEmpty()) {
            return listOf(narratorSegment(paragraphIndex, 0, text.length))
        }
        val result = mutableListOf<Segment>()
        var cursor = 0
        speechRanges.forEach { range ->
            if (range.start > cursor) {
                result += narratorSegment(paragraphIndex, cursor, range.start)
            }
            result += Segment(
                paragraphIndex = paragraphIndex,
                start = range.start,
                end = range.end,
                roleType = "character",
                characterName = range.speakerName,
                confidence = if (range.speakerName.isBlank()) 0.45 else 0.62
            )
            cursor = range.end.coerceAtLeast(cursor)
        }
        if (cursor < text.length) {
            result += narratorSegment(paragraphIndex, cursor, text.length)
        }
        return result.filter { it.start < it.end }
    }

    private data class SpeechRange(
        val start: Int,
        val end: Int,
        val speakerName: String
    )

    private fun parseSpeakerColonSegment(paragraphIndex: Int, text: String): Segment? {
        val colonIndex = text.indexOfFirst { it == '：' || it == ':' }
        if (colonIndex !in 1..24) return null
        val speaker = text.substring(0, colonIndex).trim()
        if (!isLikelySpeakerName(speaker)) return null
        val start = (colonIndex + 1).coerceAtMost(text.length)
        if (start >= text.length) return null
        return Segment(
            paragraphIndex = paragraphIndex,
            start = start,
            end = text.length,
            roleType = "character",
            characterName = speaker,
            confidence = 0.68
        )
    }

    private fun parseQuotedSpeechRanges(text: String): List<SpeechRange> {
        val result = mutableListOf<SpeechRange>()
        var index = 0
        while (index < text.length) {
            val close = when (text[index]) {
                '“' -> '”'
                '‘' -> '’'
                '"' -> '"'
                '\'' -> '\''
                else -> null
            }
            if (close == null) {
                index++
                continue
            }
            val endQuote = text.indexOf(close, index + 1)
            if (endQuote <= index + 1) {
                index++
                continue
            }
            val start = index
            val end = endQuote + 1
            result += SpeechRange(
                start = start,
                end = end,
                speakerName = inferSpeakerNameBeforeQuote(text, index)
            )
            index = endQuote + 1
        }
        return result
    }

    private fun inferSpeakerNameBeforeQuote(text: String, quoteIndex: Int): String {
        val prefix = text.substring((quoteIndex - 48).coerceAtLeast(0), quoteIndex)
        val match = speechVerbRegex.find(prefix) ?: return ""
        val name = match.groupValues.getOrNull(1).orEmpty().trim()
        return name.takeIf(::isLikelySpeakerName).orEmpty()
    }

    private fun narratorSegment(paragraphIndex: Int, start: Int, end: Int): Segment {
        return Segment(
            paragraphIndex = paragraphIndex,
            start = start,
            end = end,
            roleType = "narrator",
            characterName = "旁白",
            confidence = 0.5
        )
    }

    private fun isLikelySpeakerName(value: String): Boolean {
        val name = value.trim().trim('“', '”', '‘', '’', '"', '\'', '，', ',', '。', '：', ':')
        if (name.length !in 2..24) return false
        if (name.any { it.isWhitespace() }) return false
        if (name.any { it in "，。！？；,.!?;、（）()《》<>[]【】" }) return false
        return name !in setOf("旁白", "作者", "读者", "我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "众人", "有人")
    }

    private fun postState(
        book: Book,
        chapter: TextChapter,
        stage: String,
        status: String,
        message: String,
        paragraphCount: Int,
        segmentCount: Int = 0,
        createdCharacterCount: Int = 0,
        newCharacterCandidateCount: Int = 0,
        previewSource: String = AiReadAloudRoleState.SOURCE_NONE,
        previewSegments: List<AiReadAloudRolePreviewSegment> = emptyList(),
        error: String = "",
        usageSnapshot: RoleUsageSnapshot = RoleUsageSnapshot()
    ) {
        val usage = usageSnapshot.usage
        postEvent(
            EventBus.AI_READ_ALOUD_ROLE_STATE,
            AiReadAloudRoleState(
                bookUrl = book.bookUrl,
                chapterIndex = chapter.chapter.index,
                chapterTitle = chapter.chapter.title,
                stage = stage,
                status = status,
                message = message,
                paragraphCount = paragraphCount,
                segmentCount = segmentCount,
                createdCharacterCount = createdCharacterCount,
                newCharacterCandidateCount = newCharacterCandidateCount,
                previewSource = previewSource,
                previewSegments = previewSegments,
                elapsedMillis = usageSnapshot.elapsedMillis,
                requestCount = usageSnapshot.requestCount,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                totalTokens = usage.totalTokens,
                cachedInputTokens = usage.cachedInputTokens,
                error = error
            )
        )
    }

    private fun stageMessage(stage: String, action: String): String {
        val prefix = if (stage == AiReadAloudRoleState.STAGE_NEXT) "下一章节" else "当前章节"
        return "$prefix$action"
    }

    private fun segmentCount(json: String): Int {
        val text = json.trim()
        if (text.isBlank()) return 0
        if (text.startsWith("{")) {
            return runCatching {
                JSONObject(text).optJSONArray("segments")?.length() ?: 0
            }.getOrDefault(0)
        }
        return runCatching { JSONArray(text).length() }.getOrDefault(0)
    }

    private fun segmentsFromJson(json: String): List<Segment> {
        val text = json.trim()
        if (text.isBlank()) return emptyList()
        val array = if (text.startsWith("{")) {
            runCatching { JSONObject(text).optJSONArray("segments") }.getOrNull()
        } else {
            runCatching { JSONArray(text) }.getOrNull()
        } ?: return emptyList()
        val result = mutableListOf<Segment>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val start = item.optInt("start", -1)
            val end = item.optInt("end", -1)
            if (start < 0 || end <= start) continue
            result += Segment(
                paragraphIndex = item.optInt("paragraphIndex", -1),
                start = start,
                end = end,
                roleType = item.optString("roleType").trim()
                    .takeIf { it in setOf("narrator", "character", "thought", "other") }
                    ?: "other",
                characterName = item.optString("characterName").trim().take(80),
                characterId = item.optLong("characterId", 0L).coerceAtLeast(0L),
                emotionName = item.optString("emotionName").trim().take(40),
                emotionTag = item.optString("emotionTag").trim().take(40),
                confidence = item.optDouble("confidence", 0.0).coerceIn(0.0, 1.0)
            )
        }
        return result.filter { it.paragraphIndex >= 0 }
    }

    private fun buildPreviewSegments(
        bookUrl: String,
        segments: List<Segment>,
        paragraphs: List<String>,
        source: String,
        paragraphOffset: Int = 0
    ): List<AiReadAloudRolePreviewSegment> {
        if (segments.isEmpty()) return emptyList()
        val characters = appDb.bookCharacterDao.characters(bookUrl)
        val byId = characters.associateBy { it.id }
        val byName = characters.associateBy { it.name }
        return segments
            .sortedWith(compareBy<Segment> { it.paragraphIndex }.thenBy { it.start }.thenBy { it.end })
            .mapNotNull { segment ->
                val paragraph = paragraphs.getOrNull(segment.paragraphIndex - paragraphOffset)
                    ?: return@mapNotNull null
                val start = segment.start.coerceIn(0, paragraph.length)
                val end = segment.end.coerceIn(start, paragraph.length)
                if (start >= end) return@mapNotNull null
                val character = when {
                    segment.characterId > 0L -> byId[segment.characterId]
                    segment.characterName.isNotBlank() -> byName[segment.characterName]
                    else -> null
                }
                val route = character
                    ?.speechRouteJson
                    ?.let(SpeechRoute::fromJson)
                    ?.let { route ->
                        if (segment.emotionName.isNotBlank() || segment.emotionTag.isNotBlank()) {
                            route.copy(
                                emotionName = segment.emotionName.ifBlank { route.emotionName },
                                emotionTag = segment.emotionTag.ifBlank { route.emotionTag }
                            )
                        } else {
                            route
                        }
                    }
                AiReadAloudRolePreviewSegment(
                    paragraphIndex = segment.paragraphIndex,
                    start = start,
                    end = end,
                    text = paragraph.substring(start, end),
                    roleType = segment.roleType,
                    characterName = character?.name
                        ?: segment.characterName.ifBlank {
                            if (segment.roleType == "narrator") "旁白" else ""
                        },
                    characterId = character?.id ?: segment.characterId,
                    matchedCharacter = character != null,
                    emotionName = segment.emotionName.ifBlank { route?.emotionName.orEmpty() },
                    emotionTag = segment.emotionTag.ifBlank { route?.emotionTag.orEmpty() },
                    speakerName = route?.speakerName.orEmpty(),
                    toneID = route?.toneID.orEmpty(),
                    confidence = segment.confidence,
                    source = source
                )
            }
    }

    private suspend fun requestBatchedUnitAssignments(
        book: Book,
        textChapter: TextChapter,
        paragraphs: List<String>,
        preprocess: io.legado.app.help.readaloud.role.ReadAloudRolePreprocessResult,
        contextParagraphs: Int,
        mergeGapParagraphs: Int,
        prompt: String,
        promptCacheKey: String,
        usageTracker: RoleUsageTracker,
        fullChapterMode: Boolean,
        onPreview: (List<AiReadAloudRolePreviewSegment>, Int, String) -> Unit
    ): RequestResult = coroutineScope {
        val allTargetParagraphs = paragraphs.indices.toSet()
        val localSegments = segmentsFromUnits(preprocess.units, allTargetParagraphs)
        if (localSegments.isNotEmpty()) {
            onPreview(
                buildPreviewSegments(
                    book.bookUrl,
                    localSegments,
                    paragraphs,
                    AiReadAloudRoleState.SOURCE_RULE
                ),
                0,
                AiReadAloudRoleState.SOURCE_RULE
            )
        }
        val uncertainUnits = preprocess.units.filter { it.needsAi }
        if (uncertainUnits.isEmpty()) {
            return@coroutineScope RequestResult(localSegments, aiRequired = false, aiSatisfied = true)
        }

        val resolutionMap = linkedMapOf<String, UnitResolution>()
        val candidates = mutableListOf<CharacterCandidate>()
        val firstPassBatches = buildUnitAssignmentBatches(
            uncertainUnits,
            paragraphs,
            fullChapterMode,
            contextParagraphs,
            mergeGapParagraphs
        )
        requestUnitAssignmentBatches(
            book = book,
            textChapter = textChapter,
            paragraphs = paragraphs,
            allUnits = preprocess.units,
            batches = firstPassBatches,
            fullChapterMode = fullChapterMode,
            prompt = prompt,
            promptCacheKey = promptCacheKey,
            attempt = 0,
            knownResolutions = emptyList(),
            candidates = candidates,
            resolutionMap = resolutionMap,
            usageTracker = usageTracker,
            onPreview = onPreview
        )

        repeat(UNKNOWN_RETRY_ATTEMPTS) { retryIndex ->
            val unresolved = unresolvedUnits(uncertainUnits, resolutionMap)
            if (unresolved.isEmpty()) {
                return@repeat
            }
            val retryBatches = buildUnitAssignmentBatches(
                unresolved,
                paragraphs,
                fullChapterMode,
                contextParagraphs,
                mergeGapParagraphs
            )
            requestUnitAssignmentBatches(
                book = book,
                textChapter = textChapter,
                paragraphs = paragraphs,
                allUnits = preprocess.units,
                batches = retryBatches,
                fullChapterMode = fullChapterMode,
                prompt = prompt,
                promptCacheKey = promptCacheKey,
                attempt = retryIndex + 1,
                knownResolutions = resolutionMap.values.toList(),
                candidates = candidates,
                resolutionMap = resolutionMap,
                usageTracker = usageTracker,
                onPreview = onPreview
            )
        }

        unresolvedUnits(uncertainUnits, resolutionMap)
            .map(::unknownFinalResolution)
            .forEach { resolutionMap[it.unitId] = it }
        val resolvedUnits = applyUnitResolutions(preprocess.units, resolutionMap.values.toList())
        val resolvedSegments = segmentsFromUnits(resolvedUnits, allTargetParagraphs)
        RequestResult(
            segments = resolvedSegments,
            candidates = candidates.distinctBy { it.name },
            aiRequired = true,
            aiSatisfied = true
        )
    }

    private suspend fun requestUnitAssignmentBatches(
        book: Book,
        textChapter: TextChapter,
        paragraphs: List<String>,
        allUnits: List<ReadAloudRoleUnit>,
        batches: List<UnitAssignmentBatch>,
        fullChapterMode: Boolean,
        prompt: String,
        promptCacheKey: String,
        attempt: Int,
        knownResolutions: List<UnitResolution>,
        candidates: MutableList<CharacterCandidate>,
        resolutionMap: MutableMap<String, UnitResolution>,
        usageTracker: RoleUsageTracker,
        onPreview: (List<AiReadAloudRolePreviewSegment>, Int, String) -> Unit
    ) = coroutineScope {
        if (batches.isEmpty()) return@coroutineScope
        val semaphore = Semaphore(
            if (fullChapterMode) 1
            else AppConfig.aiReadAloudRoleThreadCount
        )
        batches.map { batch ->
            async {
                semaphore.withPermit {
                    requestUnitAssignmentBatch(
                        book = book,
                        textChapter = textChapter,
                        paragraphs = paragraphs,
                        allUnits = allUnits,
                        batch = batch,
                        fullChapterMode = fullChapterMode,
                        prompt = prompt,
                        promptCacheKey = promptCacheKey,
                        attempt = attempt,
                        knownResolutions = knownResolutions,
                        usageTracker = usageTracker
                    )
                }
            }
        }.awaitAll().forEach { result ->
            result.resolutions.forEach { resolution ->
                resolutionMap[resolution.unitId] = resolution
            }
            candidates += result.candidates
            val previewSegments = segmentsFromUnits(
                applyUnitResolutions(allUnits, resolutionMap.values.toList()),
                paragraphs.indices.toSet()
            )
            onPreview(
                buildPreviewSegments(
                    book.bookUrl,
                    previewSegments,
                    paragraphs,
                    AiReadAloudRoleState.SOURCE_AI_CONFIRM
                ),
                candidates.distinctBy { it.name }.size,
                AiReadAloudRoleState.SOURCE_AI_CONFIRM
            )
        }
    }

    private suspend fun requestUnitAssignmentBatch(
        book: Book,
        textChapter: TextChapter,
        paragraphs: List<String>,
        allUnits: List<ReadAloudRoleUnit>,
        batch: UnitAssignmentBatch,
        fullChapterMode: Boolean,
        prompt: String,
        promptCacheKey: String,
        attempt: Int,
        knownResolutions: List<UnitResolution>,
        usageTracker: RoleUsageTracker
    ): UnitAssignmentResult {
        val requestedUnitIds = batch.units.map { it.id }.toSet()
        val collectedCandidates = mutableListOf<CharacterCandidate>()
        val collectedResolutions = mutableListOf<UnitResolution>()
        val tool = AiResolvedTool(TOOL_CONFIRM_UNITS, confirmUnitsDefinition()) { args ->
            val resolutions = parseUnitResolutions(args, requestedUnitIds)
            val candidates = parseCandidates(args)
            collectedResolutions += resolutions
            collectedCandidates += candidates
            JSONObject().apply {
                put("ok", true)
                put("recorded", resolutions.size)
                put("newCharacters", candidates.size)
            }.toString()
        }
        usageTracker.onRequest()
        val response = AiChatService.requestSingleToolCall(
            messages = listOf(
                AiChatMessage(
                    role = AiChatMessage.Role.USER,
                    content = buildBatchUnitPrompt(
                        book = book,
                        textChapter = textChapter,
                        paragraphs = paragraphs,
                        allUnits = allUnits,
                        batch = batch,
                        fullChapterMode = fullChapterMode,
                        prompt = prompt,
                        attempt = attempt,
                        knownResolutions = knownResolutions
                    )
                )
            ),
            tool = tool,
            modelConfigOverride = AppConfig.aiReadAloudRoleModelConfig,
            promptCacheKeyOverride = promptCacheKey,
            onUsage = usageTracker::onUsage
        )
        if (response.hasToolCall) {
            val args = runCatching { JSONObject(response.arguments) }.getOrNull()
            collectedResolutions += parseUnitResolutions(args, requestedUnitIds)
            collectedCandidates += parseCandidates(args)
        }
        if (collectedResolutions.isEmpty()) {
            val fallback = parseUnitFallbackResult(response.content, requestedUnitIds)
            collectedResolutions += fallback.first
            collectedCandidates += fallback.second
        }
        return UnitAssignmentResult(
            resolutions = collectedResolutions
                .map { normalizeUnitResolution(it) }
                .distinctBy { it.unitId },
            candidates = collectedCandidates.distinctBy { it.name }
        )
    }

    private fun buildUnitAssignmentBatches(
        units: List<ReadAloudRoleUnit>,
        paragraphs: List<String>,
        fullChapterMode: Boolean,
        contextParagraphs: Int,
        mergeGapParagraphs: Int
    ): List<UnitAssignmentBatch> {
        val paragraphCount = paragraphs.size
        if (units.isEmpty() || paragraphCount <= 0) return emptyList()
        val sortedUnits = units.sortedWith(
            compareBy<ReadAloudRoleUnit> { it.firstParagraphIndex }.thenBy { it.firstStart }
        )
        if (fullChapterMode) {
            return listOf(
                UnitAssignmentBatch(
                    index = 0,
                    targetParagraphs = (0 until paragraphCount).toList(),
                    units = sortedUnits
                )
            )
        }
        val safeContext = contextParagraphs.coerceIn(0, 20)
        val safeMergeGap = mergeGapParagraphs.coerceIn(0, 10)
        val batches = mutableListOf<UnitAssignmentBatch>()
        var currentUnits = mutableListOf<ReadAloudRoleUnit>()
        var currentStart = -1
        var currentEnd = -1

        fun contextCharCount(start: Int, end: Int): Int {
            if (start < 0 || end < start) return 0
            return (start..end).sumOf { paragraphs.getOrNull(it)?.length ?: 0 }
        }

        fun flushCurrent() {
            if (currentUnits.isEmpty()) return
            batches += UnitAssignmentBatch(
                index = batches.size,
                targetParagraphs = (currentStart..currentEnd).toList(),
                units = currentUnits.sortedWith(
                    compareBy<ReadAloudRoleUnit> { it.firstParagraphIndex }.thenBy { it.firstStart }
                )
            )
            currentUnits = mutableListOf()
            currentStart = -1
            currentEnd = -1
        }

        sortedUnits.forEach { unit ->
            val firstParagraph = unit.ranges.minOfOrNull { it.paragraphIndex } ?: unit.firstParagraphIndex
            val lastParagraph = unit.ranges.maxOfOrNull { it.paragraphIndex } ?: firstParagraph
            val unitStart = (firstParagraph - safeContext).coerceAtLeast(0)
            val unitEnd = (lastParagraph + safeContext).coerceAtMost(paragraphCount - 1)
            if (currentUnits.isEmpty()) {
                currentUnits += unit
                currentStart = unitStart
                currentEnd = unitEnd
                return@forEach
            }
            val mergedStart = minOf(currentStart, unitStart)
            val mergedEnd = maxOf(currentEnd, unitEnd)
            val contextGap = (unitStart - currentEnd - 1).coerceAtLeast(0)
            val shouldMerge = contextGap <= safeMergeGap &&
                    currentUnits.size < MAX_SEARCH_BATCH_TARGET_UNITS &&
                    contextCharCount(mergedStart, mergedEnd) <= MAX_SEARCH_BATCH_CONTEXT_CHARS
            if (!shouldMerge) {
                flushCurrent()
                currentUnits += unit
                currentStart = unitStart
                currentEnd = unitEnd
            } else {
                currentUnits += unit
                currentStart = mergedStart
                currentEnd = mergedEnd
            }
        }
        flushCurrent()
        return batches
    }

    private fun unresolvedUnits(
        units: List<ReadAloudRoleUnit>,
        resolutions: Map<String, UnitResolution>
    ): List<ReadAloudRoleUnit> {
        return units.filter { unit ->
            val resolution = resolutions[unit.id] ?: return@filter true
            isUnresolvedResolution(resolution)
        }
    }

    private fun isUnresolvedResolution(resolution: UnitResolution): Boolean {
        if (resolution.status == "unknown-final") return false
        if (resolution.status == "unknown") return true
        if (resolution.roleType !in setOf("character", "thought")) return false
        if (resolution.characterName.isBlank()) return true
        return resolution.confidence < 0.62
    }

    private fun unknownFinalResolution(unit: ReadAloudRoleUnit): UnitResolution {
        return UnitResolution(
            unitId = unit.id,
            roleType = unit.roleType.takeIf { it in setOf("character", "thought") } ?: "character",
            characterName = "",
            characterId = 0L,
            emotionName = unit.emotionName,
            emotionTag = unit.emotionTag,
            confidence = unit.confidence.coerceAtLeast(0.45),
            status = "unknown-final",
            evidence = "retry_exhausted"
        )
    }

    private fun normalizeUnitResolution(resolution: UnitResolution): UnitResolution {
        val status = resolution.status.trim().lowercase().ifBlank { "assigned" }
            .takeIf { it in setOf("assigned", "unknown", "unknown-final") }
            ?: "assigned"
        val roleType = resolution.roleType.takeIf { it in setOf("narrator", "character", "thought", "other") }
            ?: "other"
        val rawName = resolution.characterName.trim()
        val characterName = when {
            status.startsWith("unknown") -> ""
            rawName in setOf("unknown", "未知", "不确定", "未识别", "旁白") && roleType != "narrator" -> ""
            roleType == "narrator" -> "旁白"
            else -> rawName
        }
        return resolution.copy(
            roleType = roleType,
            characterName = characterName.take(80),
            status = status,
            confidence = resolution.confidence.coerceIn(0.0, 1.0),
            evidence = resolution.evidence.take(200)
        )
    }

    private fun buildBatchUnitPrompt(
        book: Book,
        textChapter: TextChapter,
        paragraphs: List<String>,
        allUnits: List<ReadAloudRoleUnit>,
        batch: UnitAssignmentBatch,
        fullChapterMode: Boolean,
        prompt: String,
        attempt: Int,
        knownResolutions: List<UnitResolution>
    ): String {
        val characters = appDb.bookCharacterDao.characters(book.bookUrl)
            .sortedWith(compareBy<BookCharacter> { it.name }.thenBy { it.id })
            .joinToString("\n") {
                val detail = listOf(it.identity, it.personality)
                    .filter { value -> value.isNotBlank() }
                    .joinToString("/")
                "${it.id}|${it.name}|${detail.ifBlank { "-" }}"
            }
            .ifBlank { "无" }
        val targetParagraphSet = batch.units
            .flatMap { unit -> unit.ranges.map { it.paragraphIndex } }
            .toSet()
        val contextParagraphSet = batch.targetParagraphs.toSet()
        val contextText = if (fullChapterMode) {
            paragraphs.mapIndexed { index, text ->
                "P${index + 1}: $text"
            }.joinToString("\n")
        } else {
            batch.targetParagraphs.joinToString("\n") { index ->
                val mark = if (index in targetParagraphSet) "[TARGET]" else "[CONTEXT]"
                "$mark P${index + 1}: ${paragraphs.getOrNull(index).orEmpty()}"
            }
        }
        val relatedUnits = if (fullChapterMode) {
            batch.units
        } else {
            allUnits
                .asSequence()
                .filter { it.needsAi && it.touches(contextParagraphSet) }
                .sortedWith(compareBy<ReadAloudRoleUnit> { it.firstParagraphIndex }.thenBy { it.firstStart })
                .toList()
        }
        val unitSummary = relatedUnits.joinToString("\n") { unit ->
            "${unit.id}|${unit.roleType}|${rangeLabel(unit)}|${unit.reason}|前=${unit.cueBefore.compactForPrompt(50)}|后=${unit.cueAfter.compactForPrompt(50)}|${unit.text.compactForPrompt(220)}"
        }.ifBlank { "无" }
        val targetUnits = batch.units.joinToString("\n") { unit ->
            "${unit.id}|${unit.roleType}|${rangeLabel(unit)}|${unit.text.compactForPrompt(360)}"
        }
        val resolvedSummary = knownResolutions
            .sortedBy { it.unitId }
            .joinToString("\n") { resolution ->
                "${resolution.unitId}=>${resolution.roleType}/${resolution.characterName.ifBlank { "unknown" }}/${resolution.status}/${"%.2f".format(resolution.confidence)}"
            }
            .ifBlank { "无" }
        val retryInstruction = when (attempt) {
            0 -> "首轮：覆盖全部 targetUnitIds。证据不足返回 status=unknown，characterName 空。"
            else -> "补救：只处理未解决 unit；仍无明确证据就 unknown，不猜。"
        }
        return """
            任务：小说朗读分角色。客户端已切好 unit；只给 targetUnitIds 归因，不返回 start/end，不改原文。
            规则：必须调用 $TOOL_CONFIRM_UNITS；units 只含 targetUnitIds 且逐个覆盖。roleType=narrator/character/thought/other。证据不足 status=unknown、characterName 空。引号和句末符号跟随同一句台词。强调/称号/书名引用不是发言时归旁白。优先用角色卡 name；新角色必须有明确证据并写 newCharacters。不要把代词、称呼对象、动作、语气、副词当角色。情绪不明确留空。
            角色(id|name|info)：
            $characters

            附加提示：
            ${prompt.ifBlank { "无" }}

            书：${book.name} / ${book.author}
            章：${textChapter.chapter.title}

            ${if (fullChapterMode) "整章原文（用于上下文缓存和归因，不可改写）" else "局部上下文（只发送当前请求附近段落，[TARGET] 是目标 unit 所在段落）"}：
            $contextText

            相关unit：
            $unitSummary

            已确认结果：
            $resolvedSummary

            $retryInstruction
            batchIndex=${batch.index}
            targetParagraphs=${batch.targetParagraphs.joinToString { "P${it + 1}" }}
            targetUnitIds=${batch.units.joinToString { it.id }}

            本轮目标 unit：
            $targetUnits
        """.trimIndent()
    }

    private suspend fun requestChunkedSegments(
        book: Book,
        textChapter: TextChapter,
        paragraphs: List<String>,
        contextParagraphs: Int,
        prompt: String,
        stage: String,
        onPreview: (List<AiReadAloudRolePreviewSegment>, Int, String) -> Unit
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
                            paragraphOffset = start,
                            totalParagraphCount = paragraphs.size,
                            stage = stage,
                            onPreview = onPreview
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
                .distinctBy { it.name },
            aiRequired = results.any { it.aiRequired },
            aiSatisfied = results.all { it.aiSatisfied }
        )
    }

    private suspend fun requestSegments(
        book: Book,
        textChapter: TextChapter,
        paragraphs: List<String>,
        targetIndices: List<Int>,
        contextTitle: String,
        prompt: String,
        paragraphOffset: Int = 0,
        totalParagraphCount: Int,
        stage: String,
        onPreview: (List<AiReadAloudRolePreviewSegment>, Int, String) -> Unit
    ): RequestResult {
        val preprocess = ReadAloudRolePreprocessor.process(paragraphs, paragraphOffset)
        val targetSet = targetIndices.toSet()
        val targetUnits = preprocess.units.filter { it.touches(targetSet) }
        val localSegments = segmentsFromUnits(targetUnits, targetSet)
        if (localSegments.isNotEmpty()) {
            onPreview(
                buildPreviewSegments(
                    book.bookUrl,
                    localSegments,
                    paragraphs,
                    AiReadAloudRoleState.SOURCE_RULE,
                    paragraphOffset
                ),
                0,
                AiReadAloudRoleState.SOURCE_RULE
            )
        }
        val uncertainUnits = targetUnits.filter { it.needsAi }
        if (uncertainUnits.isEmpty()) {
            return RequestResult(localSegments, aiRequired = false, aiSatisfied = true)
        }
        val collectedCandidates = mutableListOf<CharacterCandidate>()
        val collectedResolutions = mutableListOf<UnitResolution>()
        val requestedUnitIds = uncertainUnits.map { it.id }.toSet()
        val tool = AiResolvedTool(TOOL_CONFIRM_UNITS, confirmUnitsDefinition()) { args ->
            val resolutions = parseUnitResolutions(args, requestedUnitIds)
            val candidates = parseCandidates(args)
            collectedResolutions += resolutions
            collectedCandidates += candidates
            val confirmedSegments = segmentsFromUnits(applyUnitResolutions(targetUnits, collectedResolutions), targetSet)
            onPreview(
                buildPreviewSegments(
                    book.bookUrl,
                    confirmedSegments,
                    paragraphs,
                    AiReadAloudRoleState.SOURCE_AI_CONFIRM,
                    paragraphOffset
                ),
                collectedCandidates.distinctBy { it.name }.size,
                AiReadAloudRoleState.SOURCE_AI_CONFIRM
            )
            JSONObject().apply {
                put("ok", true)
                put("recorded", resolutions.size)
                put("newCharacters", candidates.size)
            }.toString()
        }
        val response = AiChatService.chatStream(
            messages = listOf(
                AiChatMessage(
                    role = AiChatMessage.Role.USER,
                    content = buildUnitPrompt(
                        book = book,
                        textChapter = textChapter,
                        paragraphs = paragraphs,
                        targetIndices = targetIndices,
                        contextTitle = contextTitle,
                        prompt = prompt,
                        paragraphOffset = paragraphOffset,
                        totalParagraphCount = totalParagraphCount,
                        units = targetUnits,
                        uncertainUnits = uncertainUnits
                    )
                )
            ),
            onPartial = {},
            includeStructuredBlocks = false,
            useAllTools = false,
            extraTools = listOf(tool),
            modelConfigOverride = AppConfig.aiReadAloudRoleModelConfig
        )
        if (collectedResolutions.isEmpty()) {
            val fallback = parseUnitFallbackResult(response, requestedUnitIds)
            collectedResolutions += fallback.first
            collectedCandidates += fallback.second
        }
        val answeredUnitIds = collectedResolutions.map { it.unitId }.toSet()
        val resolvedSegments = segmentsFromUnits(applyUnitResolutions(targetUnits, collectedResolutions), targetSet)
        if (collectedResolutions.isNotEmpty()) {
            onPreview(
                buildPreviewSegments(
                    book.bookUrl,
                    resolvedSegments,
                    paragraphs,
                    AiReadAloudRoleState.SOURCE_AI_CONFIRM,
                    paragraphOffset
                ),
                collectedCandidates.distinctBy { it.name }.size,
                AiReadAloudRoleState.SOURCE_AI_CONFIRM
            )
        }
        return RequestResult(
            segments = resolvedSegments,
            candidates = collectedCandidates,
            aiRequired = true,
            aiSatisfied = requestedUnitIds.all { it in answeredUnitIds }
        )
    }

    private fun buildUnitPrompt(
        book: Book,
        textChapter: TextChapter,
        paragraphs: List<String>,
        targetIndices: List<Int>,
        contextTitle: String,
        prompt: String,
        paragraphOffset: Int,
        totalParagraphCount: Int,
        units: List<ReadAloudRoleUnit>,
        uncertainUnits: List<ReadAloudRoleUnit>
    ): String {
        val characters = appDb.bookCharacterDao.characters(book.bookUrl)
            .joinToString("\n") {
                "- ${it.name}：${listOf(it.identity, it.skills, it.attributes).filter { value -> value.isNotBlank() }.joinToString("；")}"
            }
            .ifBlank { "暂无角色卡" }
        val indexed = paragraphs.mapIndexed { index, text ->
            val absolute = paragraphOffset + index
            val mark = if (absolute in targetIndices) "[TARGET]" else "[CONTEXT]"
            "$mark 段落${absolute + 1}: $text"
        }.joinToString("\n")
        val localSummary = units.joinToString("\n") { unit ->
            "- ${unit.id} | ${unit.kind}/${unit.roleType} | ${rangeLabel(unit)} | speakerHint=${unit.speakerHint.ifBlank { "无" }} | confidence=${"%.2f".format(unit.confidence)} | text=${unit.text.compactForPrompt(220)}"
        }.ifBlank { "无" }
        val uncertainSummary = uncertainUnits.joinToString("\n") { unit ->
            "- ${unit.id} | reason=${unit.reason} | ${rangeLabel(unit)} | text=${unit.text.compactForPrompt(360)}"
        }
        return """
            你是小说朗读分角色确认器。客户端已经用本地规则把章节切成稳定 unit，已经确定的 unit 不需要你重复标注；你只需要确认“不确定 unit”的朗读身份、说话人和情绪。

            必须遵守：
            1. 必须调用工具 $TOOL_CONFIRM_UNITS 记录结果。
            2. 只处理“不确定 unit 列表”里的 unitId，不要新造 unitId，不要返回 start/end。
            3. roleType 只能使用 narrator、character、thought、other。
            4. 引号、句末标点、省略号属于同一句台词时，应跟随台词角色，不要把符号单独标为旁白。
            5. ““卧龙”军师”这类强调、称号、书名、外号引用，不是直接发言时应保持 narrator。
            6. 跨段引号如果是同一句直接发言，应使用同一个说话人；无法判断具体角色时 roleType 可为 character，但 characterName 留空。
            7. 优先使用已有角色卡里的准确名称；稳定新角色或路人称谓可以写入 newCharacters。
            8. 情绪明确时填写 emotionName 和 emotionTag，例如 高兴 / [高兴]；不明确时留空。
            9. 如果工具不可用，最终只输出 JSON：{"units":[...],"newCharacters":[...]}。

            书籍：${book.name}
            作者：${book.author}
            章节：${textChapter.chapter.title}
            模式：$contextTitle
            全章节段落数：$totalParagraphCount
            目标段落：${targetIndices.joinToString { (it + 1).toString() }}

            已有角色卡：
            $characters

            用户附加提示：
            ${prompt.ifBlank { "无" }}

            本地预处理结果：
            $localSummary

            不确定 unit 列表：
            $uncertainSummary

            段落上下文：
            $indexed
        """.trimIndent()
    }

    private fun rangeLabel(unit: ReadAloudRoleUnit): String {
        return unit.ranges.joinToString(",") { range ->
            "P${range.paragraphIndex + 1}[${range.start},${range.end})"
        }
    }

    private fun String.compactForPrompt(maxLength: Int): String {
        val value = replace("\n", "\\n").trim()
        return if (value.length <= maxLength) value else value.take(maxLength) + "..."
    }

    private fun parseUnitResolutions(
        args: JSONObject?,
        requestedUnitIds: Set<String>
    ): List<UnitResolution> {
        val array = args?.optJSONArray("units")
            ?: args?.optJSONArray("resolutions")
            ?: args?.optJSONArray("segments")
            ?: return emptyList()
        return parseUnitResolutionArray(array, requestedUnitIds)
    }

    private fun parseUnitFallbackResult(
        response: String,
        requestedUnitIds: Set<String>
    ): Pair<List<UnitResolution>, List<CharacterCandidate>> {
        val jsonText = response.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList<UnitResolution>() to emptyList()
        val array = root.optJSONArray("units")
            ?: root.optJSONArray("resolutions")
            ?: root.optJSONArray("segments")
        return (array?.let { parseUnitResolutionArray(it, requestedUnitIds) }.orEmpty()) to parseCandidates(root)
    }

    private fun parseUnitResolutionArray(
        array: JSONArray,
        requestedUnitIds: Set<String>
    ): List<UnitResolution> {
        val result = mutableListOf<UnitResolution>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val unitId = item.optString("unitId").trim()
            if (unitId !in requestedUnitIds) continue
            val roleType = item.optString("roleType").trim()
                .takeIf { it in setOf("narrator", "character", "thought", "other") }
                ?: "other"
            result += UnitResolution(
                unitId = unitId,
                roleType = roleType,
                characterName = item.optString("characterName").trim().take(80),
                characterId = item.optLong("characterId", 0L).coerceAtLeast(0L),
                emotionName = item.optString("emotionName").trim().take(40),
                emotionTag = item.optString("emotionTag").trim().take(40),
                confidence = item.optDouble("confidence", 0.72).coerceIn(0.0, 1.0),
                status = item.optString("status", "assigned").trim(),
                evidence = item.optString("evidence").trim().take(200)
            )
        }
        return result.distinctBy { it.unitId }
    }

    private fun applyUnitResolutions(
        units: List<ReadAloudRoleUnit>,
        resolutions: List<UnitResolution>
    ): List<ReadAloudRoleUnit> {
        if (resolutions.isEmpty()) return units
        val byId = resolutions.associateBy { it.unitId }
        return units.map { unit ->
            val resolution = byId[unit.id] ?: return@map unit
            val roleType = resolution.roleType.ifBlank { unit.roleType }
            val characterName = when {
                roleType == "narrator" -> "旁白"
                resolution.characterName.isNotBlank() -> resolution.characterName
                roleType == "character" || roleType == "thought" -> ""
                else -> unit.characterName
            }
            unit.copy(
                roleType = roleType,
                characterName = characterName,
                characterId = resolution.characterId.takeIf { it > 0L } ?: unit.characterId,
                emotionName = resolution.emotionName.ifBlank { unit.emotionName },
                emotionTag = resolution.emotionTag.ifBlank { unit.emotionTag },
                confidence = maxOf(unit.confidence, resolution.confidence),
                needsAi = false,
                reason = "ai_confirmed"
            )
        }
    }

    private fun segmentsFromUnits(
        units: List<ReadAloudRoleUnit>,
        paragraphFilter: Set<Int>? = null
    ): List<Segment> {
        return units.flatMap { unit ->
            unit.ranges
                .filter { range -> paragraphFilter == null || range.paragraphIndex in paragraphFilter }
                .mapNotNull { range ->
                if (range.start >= range.end) return@mapNotNull null
                Segment(
                    paragraphIndex = range.paragraphIndex,
                    start = range.start,
                    end = range.end,
                    roleType = unit.roleType.takeIf { it in setOf("narrator", "character", "thought", "other") } ?: "other",
                    characterName = unit.characterName.take(80),
                    characterId = unit.characterId.coerceAtLeast(0L),
                    emotionName = unit.emotionName.take(40),
                    emotionTag = unit.emotionTag.take(40),
                    confidence = unit.confidence.coerceIn(0.0, 1.0)
                )
            }
        }
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

            默认规则：
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
            11. 引号内文本优先判断为台词；角色台词片段必须尽量包含紧贴台词的开闭引号、句末标点和省略号，不要把“”“。”等符号单独拆成 narrator。
            12. “张三道/问/笑道/冷声道”等说话提示要反推 speaker，不要把整段标成旁白。
            13. “张三：你好”这类冒号格式应把冒号后的内容标成张三台词。
            14. 第一人称叙述不要直接当作角色名；只有明确“我说/我问”且角色卡能对应时才填角色名。
            15. 不确定说话人时 roleType 仍可标 character，但 characterName 留空，不要改成 narrator。
            16. 不要返回只包含引号、逗号、句号、感叹号、问号、省略号的独立片段。

            书籍：${book.name}
            作者：${book.author}
            章节：${textChapter.chapter.title}
            模式：$contextTitle
            目标段落：${targetIndices.joinToString { (it + 1).toString() }}
            已有角色卡：
            $characters

            用户附加提示：
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
            val characterName = item.optString("characterName").trim().take(80)
            val confidence = if (item.has("confidence")) {
                item.optDouble("confidence", 0.0)
            } else if (characterName.isNotBlank() && roleType in setOf("character", "thought")) {
                0.76
            } else {
                0.5
            }
            result += Segment(
                paragraphIndex = paragraphIndex,
                start = start,
                end = end,
                roleType = roleType,
                characterName = characterName,
                characterId = item.optLong("characterId", 0L).coerceAtLeast(0L),
                emotionName = item.optString("emotionName").trim().take(40),
                emotionTag = item.optString("emotionTag").trim().take(40),
                confidence = confidence.coerceIn(0.0, 1.0)
            )
        }
        return normalizeSegmentBoundaries(result, paragraphs, paragraphOffset)
    }

    private fun parseCandidates(args: JSONObject?): List<CharacterCandidate> {
        val array = args?.optJSONArray("newCharacters")
            ?: args?.optJSONArray("newCharacterCandidates")
            ?: args?.optJSONArray("characterCandidates")
            ?: args?.optJSONArray("characters")
            ?: return emptyList()
        val result = mutableListOf<CharacterCandidate>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val name = item.optString("name")
                .ifBlank { item.optString("characterName") }
                .trim()
                .take(80)
            if (name.isBlank()) continue
            result += CharacterCandidate(
                name = name,
                identity = item.optString("identity").trim().take(200),
                roleLevel = item.optInt("roleLevel", BookCharacter.ROLE_NORMAL)
                    .coerceIn(BookCharacter.ROLE_NORMAL, BookCharacter.ROLE_MAIN),
                confidence = item.optDouble("confidence", 0.72).coerceIn(0.0, 1.0),
                evidence = item.optString("evidence").trim().take(200)
            )
        }
        return result
    }

    private fun normalizeSegmentBoundaries(
        segments: List<Segment>,
        paragraphs: List<String>,
        paragraphOffset: Int
    ): List<Segment> {
        if (segments.isEmpty()) return emptyList()
        return segments
            .groupBy { it.paragraphIndex }
            .flatMap { (paragraphIndex, rawSegments) ->
                val text = paragraphs.getOrNull(paragraphIndex - paragraphOffset) ?: return@flatMap emptyList()
                normalizeParagraphSegments(text, rawSegments)
            }
            .sortedWith(compareBy<Segment> { it.paragraphIndex }.thenBy { it.start }.thenBy { it.end })
    }

    private fun normalizeParagraphSegments(text: String, rawSegments: List<Segment>): List<Segment> {
        if (text.isBlank()) return emptyList()
        val expanded = rawSegments
            .map { segment ->
                val start = segment.start.coerceIn(0, text.length)
                val end = segment.end.coerceIn(start, text.length)
                if (segment.roleType == "character" || segment.roleType == "thought") {
                    expandDialogueBoundary(text, segment.copy(start = start, end = end))
                } else {
                    segment.copy(start = start, end = end)
                }
            }
            .filter { it.start < it.end }
        val dialogueRanges = expanded
            .filter { it.roleType == "character" || it.roleType == "thought" }
            .sortedWith(compareBy<Segment> { it.start }.thenByDescending { it.end })
        val result = mutableListOf<Segment>()
        expanded.forEach { segment ->
            if (segment.roleType == "character" || segment.roleType == "thought") {
                if (!text.substring(segment.start, segment.end).isPunctuationOnly()) {
                    result += segment
                }
                return@forEach
            }
            subtractRanges(segment.start, segment.end, dialogueRanges).forEach { (start, end) ->
                val part = text.substring(start, end)
                if (!part.isPunctuationOnly()) {
                    result += segment.copy(start = start, end = end)
                }
            }
        }
        return result
            .distinctBy { "${it.paragraphIndex}:${it.start}:${it.end}:${it.roleType}:${it.characterName}" }
            .sortedWith(compareBy<Segment> { it.start }.thenBy { it.end })
    }

    private fun expandDialogueBoundary(text: String, segment: Segment): Segment {
        var start = segment.start
        var end = segment.end
        while (start > 0 && text[start - 1] in openingQuoteChars) {
            start--
        }
        while (end < text.length && text[end] in closingDialogueChars) {
            end++
        }
        return segment.copy(start = start, end = end)
    }

    private fun subtractRanges(
        start: Int,
        end: Int,
        blockers: List<Segment>
    ): List<Pair<Int, Int>> {
        if (start >= end) return emptyList()
        val result = mutableListOf<Pair<Int, Int>>()
        var cursor = start
        blockers.forEach { blocker ->
            if (blocker.end <= cursor || blocker.start >= end) return@forEach
            if (blocker.start > cursor) {
                result += cursor to blocker.start.coerceAtMost(end)
            }
            cursor = cursor.coerceAtLeast(blocker.end.coerceAtMost(end))
        }
        if (cursor < end) {
            result += cursor to end
        }
        return result.filter { it.first < it.second }
    }

    private fun String.isPunctuationOnly(): Boolean {
        val value = trim()
        return value.isNotEmpty() && value.all { it in punctuationOnlyChars }
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
            .filter { it.confidence >= 0.75 && it.evidence.isNotBlank() && isCreatableCharacterName(it.name) }
            .associateBy { it.name }
            .toMutableMap()
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
        segments.asSequence()
            .filter { it.roleType == "character" || it.roleType == "thought" }
            .mapNotNull { byName[it.characterName] }
            .filter { it.speechRouteJson.isBlank() }
            .distinctBy { it.id }
            .forEach { old ->
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
            } else if (!isCreatableCharacterName(segment.characterName) && segment.characterName != "旁白") {
                segment.copy(characterName = "", characterId = 0L)
            } else {
                segment.copy(characterId = byName[segment.characterName]?.id ?: 0L)
            }
        }
    }

    private fun isCreatableCharacterName(name: String): Boolean {
        val value = name.trim()
        if (value.length !in 2..40) return false
        if (value.any { it == '\n' || it == '\r' || it == '\t' }) return false
        if (value.any { it in "，。！？；,.!?;、（）()《》<>[]【】\"“”‘’：" }) return false
        if (invalidCharacterNameKeywordRegex.containsMatchIn(value)) return false
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

    private fun confirmUnitsDefinition(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_CONFIRM_UNITS)
                put("description", "批量确认朗读unit的角色、说话人和情绪。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("units", JSONObject().apply {
                            put("type", "array")
                            put("items", JSONObject().apply {
                                put("type", "object")
                                put("properties", JSONObject().apply {
                                    put("unitId", stringProp("prompt unit id"))
                                    put("roleType", stringProp("narrator/character/thought/other"))
                                    put("characterName", stringProp("blank if unknown"))
                                    put("characterId", intProp("known id or 0"))
                                    put("emotionName", stringProp("optional"))
                                    put("emotionTag", stringProp("optional"))
                                    put("confidence", numberProp("0..1"))
                                    put("status", stringProp("assigned/unknown"))
                                    put("evidence", stringProp("short evidence"))
                                })
                                put("required", JSONArray().put("unitId").put("roleType").put("status"))
                                put("additionalProperties", false)
                            })
                        })
                        put("newCharacters", JSONObject().apply {
                            put("type", "array")
                            put("items", JSONObject().apply {
                                put("type", "object")
                                put("properties", JSONObject().apply {
                                    put("name", stringProp("new character name"))
                                    put("identity", stringProp("short identity"))
                                    put("roleLevel", intProp("0 normal, 1 important, 2 main"))
                                    put("confidence", numberProp("0..1"))
                                    put("evidence", stringProp("current chapter evidence"))
                                })
                                put("required", JSONArray().put("name"))
                                put("additionalProperties", false)
                            })
                        })
                    })
                    put("required", JSONArray().put("units"))
                    put("additionalProperties", false)
                })
            })
        }
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

    private fun List<Segment>.toCacheJson(
        preprocessVersion: String,
        contentHash: String,
        usageSnapshot: RoleUsageSnapshot = RoleUsageSnapshot()
    ): String {
        return JSONObject().apply {
            put("schemaVersion", 4)
            put("preprocessVersion", preprocessVersion)
            put("contentHash", contentHash)
            put("usage", JSONObject().apply {
                put("elapsedMillis", usageSnapshot.elapsedMillis)
                put("requestCount", usageSnapshot.requestCount)
                put("inputTokens", usageSnapshot.usage.inputTokens)
                put("cachedInputTokens", usageSnapshot.usage.cachedInputTokens)
                put("outputTokens", usageSnapshot.usage.outputTokens)
                put("totalTokens", usageSnapshot.usage.totalTokens)
            })
            put("segments", toJsonArray())
        }.toString()
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

    private val openingQuoteChars = setOf('“', '‘', '"', '\'', '「', '『', '（', '(', '【', '[', '《')
    private val closingDialogueChars = setOf(
        '”', '’', '"', '\'', '」', '』',
        '，', ',', '。', '.', '！', '!', '？', '?',
        '；', ';', '：', ':', '、', '…'
    )
    private val punctuationOnlyChars = openingQuoteChars + closingDialogueChars + setOf(
        '）', ')', '】', ']', '》', '—', '-', ' '
    )
}
