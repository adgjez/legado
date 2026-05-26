package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiChatMessage
import io.legado.app.ui.main.ai.AiContextSummary

object AiContextManager {
    private const val CHARS_PER_TOKEN = 3
    private const val RECENT_MESSAGE_COUNT = 10
    private const val COMPRESS_TRIGGER_PERCENT = 90
    private const val TARGET_PERCENT = 35

    data class PreparedContext(
        val messages: List<AiChatMessage>,
        val summary: AiContextSummary?,
        val compressed: Boolean,
        val inputTokens: Int,
        val limitTokens: Int
    )

    fun prepare(
        messages: List<AiChatMessage>,
        previousSummary: AiContextSummary?
    ): PreparedContext {
        val clean = messages.filterNot { it.pending }.filter { it.content.isNotBlank() }
        val limit = AppConfig.aiContextWindowTokens
        val estimated = estimateMessagesTokens(clean) + estimateTokens(previousSummary?.summary.orEmpty())
        if (!AppConfig.aiContextCompressionEnabled || estimated < limit * COMPRESS_TRIGGER_PERCENT / 100) {
            return PreparedContext(clean, previousSummary, false, estimated, limit)
        }
        val recent = clean.takeLast(RECENT_MESSAGE_COUNT)
        val old = clean.dropLast(recent.size)
        if (old.isEmpty()) return PreparedContext(clean, previousSummary, false, estimated, limit)
        val targetSummaryChars = (limit * TARGET_PERCENT / 100 * CHARS_PER_TOKEN)
            .coerceAtLeast(2_000)
            .coerceAtMost(32_000)
        val summaryText = buildSummary(previousSummary, old, targetSummaryChars)
        val summary = AiContextSummary(
            summary = summaryText,
            sourceMessageCount = clean.size,
            sourceChars = clean.sumOf { it.content.length },
            summaryChars = summaryText.length
        )
        val preparedTokens = estimateMessagesTokens(recent) + estimateTokens(summary.summary)
        return PreparedContext(recent, summary, true, preparedTokens, limit)
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
}
