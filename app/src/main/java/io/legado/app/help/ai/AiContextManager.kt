package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.ui.main.ai.AiContextSummary

object AiContextManager {
    private const val CHARS_PER_TOKEN = 3
    private const val RECENT_MESSAGE_COUNT = 10
    private const val COMPRESS_TRIGGER_PERCENT = 90
    private const val TARGET_PERCENT = 35
    private const val MIN_RECENT_TOKENS = 2_000

    data class PreparedContext(
        val messages: List<AiChatMessage>,
        val summary: AiContextSummary?,
        val compressed: Boolean,
        val inputTokens: Int,
        val limitTokens: Int
    )

    fun prepare(
        messages: List<AiChatMessage>,
        previousSummary: AiContextSummary?,
        reserveTokens: Int = 0
    ): PreparedContext {
        val clean = messages.filterNot { it.pending }.filter { it.content.isNotBlank() }
        if (!AppConfig.aiContextCompressionEnabled) {
            val estimated = estimateMessagesTokens(clean)
            return PreparedContext(clean, null, false, estimated, AppConfig.aiContextWindowTokens)
        }
        val limit = AppConfig.aiContextWindowTokens
        val usableLimit = (limit - reserveTokens).coerceAtLeast(MIN_RECENT_TOKENS)
        val summaryEndIndex = summaryEndIndex(clean, previousSummary)
        val activeSummary = previousSummary?.takeIf { it.isValid && summaryEndIndex >= 0 }
        val unsummarized = if (summaryEndIndex >= 0) clean.drop(summaryEndIndex + 1) else clean
        val estimated = estimateMessagesTokens(unsummarized) + estimateTokens(activeSummary?.summary.orEmpty())
        if (estimated < usableLimit * COMPRESS_TRIGGER_PERCENT / 100) {
            val fitted = fitMessages(unsummarized, usableLimit - estimateTokens(activeSummary?.summary.orEmpty()))
            val preparedTokens = estimateMessagesTokens(fitted) + estimateTokens(activeSummary?.summary.orEmpty())
            return PreparedContext(fitted, activeSummary, false, preparedTokens, limit)
        }
        val recent = unsummarized.takeLast(RECENT_MESSAGE_COUNT)
        val old = unsummarized.dropLast(recent.size)
        if (old.isEmpty()) {
            val fitted = fitMessages(recent, usableLimit - estimateTokens(activeSummary?.summary.orEmpty()))
            val preparedTokens = estimateMessagesTokens(fitted) + estimateTokens(activeSummary?.summary.orEmpty())
            return PreparedContext(fitted, activeSummary, false, preparedTokens, limit)
        }
        val targetSummaryChars = (limit * TARGET_PERCENT / 100 * CHARS_PER_TOKEN)
            .coerceAtLeast(2_000)
            .coerceAtMost(32_000)
        val summaryText = buildSummary(activeSummary, old, targetSummaryChars)
        val lastSummarized = old.last()
        val summarizedCount = clean.indexOfLast { it.id == lastSummarized.id }
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: clean.size
        val summary = AiContextSummary(
            summary = summaryText,
            sourceMessageCount = summarizedCount,
            sourceChars = clean.sumOf { it.content.length },
            summaryChars = summaryText.length,
            lastMessageId = lastSummarized.id,
            lastMessageCreatedAt = lastSummarized.createdAt
        )
        val fittedRecent = fitMessages(recent, usableLimit - estimateTokens(summary.summary))
        val preparedTokens = estimateMessagesTokens(fittedRecent) + estimateTokens(summary.summary)
        return PreparedContext(fittedRecent, summary, true, preparedTokens, limit)
    }

    fun estimateMessagesTokens(messages: List<AiChatMessage>): Int {
        return messages.sumOf { estimateTokens(it.content) + 8 }
    }

    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        var ascii = 0
        var nonAscii = 0
        text.forEach { char -> if (char.code <= 0x7f) ascii++ else nonAscii++ }
        return (ascii / 4) + nonAscii + 1
    }

    private fun buildSummary(
        previousSummary: AiContextSummary?,
        oldMessages: List<AiChatMessage>,
        maxChars: Int
    ): String {
        val lines = mutableListOf<String>()
        previousSummary?.summary?.takeIf { it.isNotBlank() }?.let {
            lines += "Existing summary:"
            lines += it
        }
        lines += "Condensed conversation facts:"
        oldMessages.forEach { message ->
            val role = if (message.role == AiChatMessage.Role.USER) "User" else "Assistant"
            val content = message.content.replace(Regex("\\s+"), " ").trim().take(900)
            if (content.isNotBlank()) lines += "- $role: $content"
        }
        val joined = lines.joinToString("\n")
        return if (joined.length <= maxChars) joined else joined.takeLast(maxChars).trimStart()
    }

    private fun summaryEndIndex(messages: List<AiChatMessage>, summary: AiContextSummary?): Int {
        if (summary?.isValid != true) return -1
        summary.lastMessageId.takeIf { it.isNotBlank() }?.let { id ->
            messages.indexOfLast { it.id == id }.takeIf { it >= 0 }?.let { return it }
        }
        return (summary.sourceMessageCount - 1).coerceIn(-1, messages.lastIndex)
    }

    private fun fitMessages(messages: List<AiChatMessage>, tokenBudget: Int): List<AiChatMessage> {
        val budget = tokenBudget.coerceAtLeast(MIN_RECENT_TOKENS)
        val result = ArrayDeque<AiChatMessage>()
        var used = 0
        messages.asReversed().forEach { message ->
            val tokens = estimateTokens(message.content) + 8
            if (used + tokens <= budget) {
                result.addFirst(message)
                used += tokens
            } else if (result.isEmpty()) {
                val maxChars = (budget * CHARS_PER_TOKEN).coerceAtLeast(1_000)
                result.addFirst(message.copy(content = message.content.takeLast(maxChars)))
                return result.toList()
            } else {
                return result.toList()
            }
        }
        return result.toList()
    }
}
