package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.AiPurifiedTextCache
import io.legado.app.help.config.AppConfig
import java.security.MessageDigest

data class SanitizeResult(
    val sanitizedText: String,
    val originalLength: Int,
    val sanitizedLength: Int,
    val cached: Boolean
)

object AiSanitizeService {

    private const val MAX_CHUNK_CHARS = 6000

    private val DEFAULT_SYSTEM_PROMPT = """
        你是一个严谨的文本净化和修复专家。请对输入的章节正文进行清洗，仅执行以下操作：
        1. 根据上下文修复明显的 OCR 误识别和错别字；
        2. 合并因阅读器换行导致的错误断词；
        3. 统一标点符号（将英文标点转为中文）。
        严禁改写剧情、添加新内容、删除段落或总结缩写。如果文本无明显问题，请原样返回。
    """.trimIndent()

    suspend fun sanitize(
        text: String,
        intensity: Int,
        bookKey: String,
        chapterIndex: Int,
        providerId: String? = null
    ): SanitizeResult {
        val contentHash = sha256(text)
        // Check cache
        val cached = appDb.aiPurifiedTextCacheDao.get(bookKey, chapterIndex, intensity)
        if (cached != null && cached.contentHash == contentHash) {
            return SanitizeResult(cached.sanitizedText, cached.originalLength, cached.sanitizedLength, true)
        }
        // Sanitize
        val systemPrompt = buildSystemPrompt(intensity)
        val sanitized = sanitizeText(text, systemPrompt, providerId)
        // Save to cache
        val cache = AiPurifiedTextCache(
            bookKey = bookKey,
            chapterIndex = chapterIndex,
            intensity = intensity,
            contentHash = contentHash,
            sanitizedText = sanitized,
            originalLength = text.length,
            sanitizedLength = sanitized.length,
            providerId = providerId ?: ""
        )
        appDb.aiPurifiedTextCacheDao.insert(cache)
        return SanitizeResult(sanitized, text.length, sanitized.length, false)
    }

    private fun buildSystemPrompt(intensity: Int): String {
        val base = AppConfig.aiSanitizeCustomPrompt?.takeIf { it.isNotBlank() } ?: DEFAULT_SYSTEM_PROMPT
        val suffix = when {
            intensity <= 3 -> "\n\n净化强度：保守。仅修复明显的错别字和断行错误，不做其他修改。"
            intensity <= 7 -> "\n\n净化强度：标准。修复错别字、断行、语病，使语句更通顺，但不改变原意。"
            else -> "\n\n净化强度：激进。在保持原意的前提下进行文学润色，提升可读性。注意：此模式风险较高，请谨慎修改。"
        }
        return base + suffix
    }

    private suspend fun sanitizeText(text: String, systemPrompt: String, providerId: String?): String {
        // For long text, split into chunks
        if (text.length <= MAX_CHUNK_CHARS) {
            return callSanitizeModel(text, systemPrompt, providerId)
        }
        val chunks = splitIntoChunks(text, MAX_CHUNK_CHARS)
        return chunks.joinToString("") { chunk ->
            callSanitizeModel(chunk, systemPrompt, providerId)
        }
    }

    private fun splitIntoChunks(text: String, maxChars: Int): List<String> {
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxChars).coerceAtMost(text.length)
            // Try to break at paragraph boundary
            if (end < text.length) {
                val lastNewline = text.lastIndexOf('\n', end)
                if (lastNewline > start) end = lastNewline + 1
            }
            chunks.add(text.substring(start, end))
            start = end
        }
        return chunks
    }

    private suspend fun callSanitizeModel(text: String, systemPrompt: String, providerId: String?): String {
        // Use AiChatService to call the chat model
        // Build a simple chat request with system prompt + user content
        // TODO: implement AiChatService.chatSimple if not exists
        val result = AiChatService.chatSimple(
            systemPrompt = systemPrompt,
            userContent = text,
            providerId = providerId
        )
        return result.trim()
    }

    fun detectGarbageDensity(text: String): Double {
        if (text.isBlank()) return 0.0
        val garbageChars = text.count { it == '�' || it == '口' }
        return garbageChars.toDouble() / text.length
    }

    fun shouldSuggestSanitize(text: String): Boolean {
        return detectGarbageDensity(text) > 0.05
    }

    /**
     * Public stable hash used to key [AiPurifiedTextCache] entries. Exposed so
     * callers (e.g. the sanitize diff dialog) can build cache entries that are
     * compatible with the ones produced by [sanitize].
     */
    fun computeHash(text: String): String = sha256(text)

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
