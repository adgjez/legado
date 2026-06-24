package io.legado.app.help.ai

object AiNsfwFilter {

    private val blockedKeywords = listOf(
        "nsfw", "nude", "naked", "explicit", "pornographic",
        "sexual", "erotic", "obscene", "indecent"
    )

    enum class Strictness { OFF, LOW, MEDIUM, HIGH }

    fun checkPrompt(prompt: String, strictness: Strictness = Strictness.MEDIUM): FilterResult {
        if (strictness == Strictness.OFF) return FilterResult(passed = true)
        val lower = prompt.lowercase()
        val found = blockedKeywords.filter { lower.contains(it) }
        return if (found.isEmpty()) {
            FilterResult(passed = true)
        } else {
            FilterResult(
                passed = false,
                blockedKeywords = found,
                message = "Prompt contains potentially inappropriate content: ${found.joinToString(", ")}"
            )
        }
    }

    data class FilterResult(
        val passed: Boolean,
        val blockedKeywords: List<String> = emptyList(),
        val message: String = ""
    )
}
