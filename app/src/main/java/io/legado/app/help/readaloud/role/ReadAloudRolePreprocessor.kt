package io.legado.app.help.readaloud.role

data class ReadAloudRoleRange(
    val paragraphIndex: Int,
    val start: Int,
    val end: Int
)

data class ReadAloudRoleUnit(
    val id: String,
    val kind: String,
    val roleType: String,
    val characterName: String,
    val characterId: Long = 0L,
    val ranges: List<ReadAloudRoleRange>,
    val text: String,
    val speakerHint: String = "",
    val emotionName: String = "",
    val emotionTag: String = "",
    val confidence: Double = 0.0,
    val needsAi: Boolean = false,
    val reason: String = ""
) {
    val firstParagraphIndex: Int
        get() = ranges.firstOrNull()?.paragraphIndex ?: -1

    val firstStart: Int
        get() = ranges.firstOrNull()?.start ?: 0

    fun touches(paragraphIndices: Set<Int>): Boolean {
        return ranges.any { it.paragraphIndex in paragraphIndices }
    }
}

data class ReadAloudRolePreprocessResult(
    val version: String,
    val units: List<ReadAloudRoleUnit>
)

object ReadAloudRolePreprocessor {

    const val VERSION = "builtin-quote-colon-v2"

    private val quotePairs = mapOf(
        '“' to '”',
        '‘' to '’',
        '"' to '"',
        '\'' to '\'',
        '「' to '」',
        '『' to '』'
    )
    private val sentencePunctuation = setOf('。', '！', '？', '…', '!', '?')
    private val speechCueRegex = Regex(
        "([\\p{IsHan}A-Za-z0-9_·]{1,24})\\s*(?:说道|说|道|问道|问|答道|答|笑道|冷声道|沉声道|低声道|怒道|喝道|喊道|叫道|开口道|喃喃道|心道|暗道|想道|心想)\\s*[，,、：:]?\\s*$"
    )
    private val speechCueAfterRegex = Regex(
        "^\\s*[，,、。.!！?？…]*\\s*([\\p{IsHan}A-Za-z0-9_·]{1,24})\\s*(?:说道|说|道|问道|问|答道|答|笑道|冷声道|沉声道|低声道|怒道|喝道|喊道|叫道|开口道|喃喃道|心道|暗道|想道|心想)"
    )
    private val thoughtCueRegex = Regex("(?:心道|暗道|想道|心想)\\s*[，,、：:]?\\s*$")
    private val colonSpeakerRegex = Regex("([\\p{IsHan}A-Za-z0-9_·]{1,24})\\s*[：:]\\s*$")
    private val invalidSpeakerSuffixes = listOf("说道", "说", "问道", "问", "答道", "答", "心道", "想道", "心想")

    fun process(
        paragraphs: List<String>,
        paragraphOffset: Int = 0
    ): ReadAloudRolePreprocessResult {
        if (paragraphs.isEmpty()) {
            return ReadAloudRolePreprocessResult(VERSION, emptyList())
        }
        val units = mutableListOf<ReadAloudRoleUnit>()
        var openQuote: OpenQuote? = null
        paragraphs.forEachIndexed { localIndex, text ->
            val paragraphIndex = paragraphOffset + localIndex
            var cursor = 0
            var index = 0

            openQuote?.let { open ->
                val closeIndex = text.indexOf(open.closeChar)
                if (closeIndex >= 0) {
                    val ranges = open.ranges + ReadAloudRoleRange(paragraphIndex, 0, closeIndex + 1)
                    units += quoteUnit(paragraphs, paragraphOffset, ranges, open.openChar, open.closeChar)
                    cursor = closeIndex + 1
                    index = cursor
                    openQuote = null
                } else {
                    open.ranges += ReadAloudRoleRange(paragraphIndex, 0, text.length)
                    cursor = text.length
                    index = text.length
                }
            }

            while (index < text.length) {
                val closeChar = quotePairs[text[index]]
                if (closeChar == null) {
                    index++
                    continue
                }
                val endQuote = findClosingQuote(text, index + 1, closeChar)
                if (endQuote >= 0) {
                    addPlainUnits(units, paragraphIndex, text, cursor, index)
                    val range = ReadAloudRoleRange(paragraphIndex, index, endQuote + 1)
                    units += quoteUnit(paragraphs, paragraphOffset, listOf(range), text[index], closeChar)
                    cursor = endQuote + 1
                    index = cursor
                } else {
                    addPlainUnits(units, paragraphIndex, text, cursor, index)
                    openQuote = OpenQuote(
                        openChar = text[index],
                        closeChar = closeChar,
                        ranges = mutableListOf(ReadAloudRoleRange(paragraphIndex, index, text.length))
                    )
                    cursor = text.length
                    index = text.length
                }
            }
            addPlainUnits(units, paragraphIndex, text, cursor, text.length)
        }

        openQuote?.let { open ->
            if (open.ranges.isNotEmpty()) {
                units += quoteUnit(paragraphs, paragraphOffset, open.ranges, open.openChar, open.closeChar)
            }
        }

        return ReadAloudRolePreprocessResult(
            version = VERSION,
            units = units
                .filter { it.ranges.isNotEmpty() && it.text.isNotBlank() }
                .sortedWith(compareBy<ReadAloudRoleUnit> { it.firstParagraphIndex }.thenBy { it.firstStart })
        )
    }

    private fun addPlainUnits(
        units: MutableList<ReadAloudRoleUnit>,
        paragraphIndex: Int,
        text: String,
        start: Int,
        end: Int
    ) {
        if (start >= end) return
        val raw = text.substring(start, end)
        if (raw.isBlank()) return
        val colonIndex = raw.indexOfFirst { it == '：' || it == ':' }
        if (colonIndex in 1..24) {
            val speaker = raw.substring(0, colonIndex).trim()
            val speechStart = start + colonIndex + 1
            if (speechStart < end && isLikelySpeakerName(speaker)) {
                units += unit(
                    kind = "dialogue",
                    roleType = "character",
                    characterName = speaker,
                    ranges = listOf(ReadAloudRoleRange(paragraphIndex, start, end)),
                    text = raw,
                    speakerHint = speaker,
                    confidence = 0.78,
                    needsAi = false,
                    reason = "speaker_colon"
                )
                return
            }
        }
        units += unit(
            kind = "narrator",
            roleType = "narrator",
            characterName = "旁白",
            ranges = listOf(ReadAloudRoleRange(paragraphIndex, start, end)),
            text = raw,
            confidence = 0.82,
            reason = "plain_text"
        )
    }

    private fun quoteUnit(
        paragraphs: List<String>,
        paragraphOffset: Int,
        ranges: List<ReadAloudRoleRange>,
        openChar: Char,
        closeChar: Char
    ): ReadAloudRoleUnit {
        val text = ranges.joinToString("\n") { range ->
            val paragraph = paragraphs.getOrNull(range.paragraphIndex - paragraphOffset).orEmpty()
            paragraph.substring(range.start.coerceIn(0, paragraph.length), range.end.coerceIn(0, paragraph.length))
        }
        val prefix = contextBefore(paragraphs, paragraphOffset, ranges.first())
        val suffix = contextAfter(paragraphs, paragraphOffset, ranges.last())
        val speakerBefore = inferSpeakerBefore(prefix)
        val speakerAfter = inferSpeakerAfter(suffix)
        val colonSpeaker = inferColonSpeaker(prefix)
        val speaker = listOf(speakerBefore, colonSpeaker, speakerAfter).firstOrNull { it.isNotBlank() }.orEmpty()
        val inner = text
            .trim()
            .trim(openChar, closeChar, '“', '”', '‘', '’', '"', '\'', '「', '」', '『', '』')
            .trim()
        val hasSpeechCue = speaker.isNotBlank() ||
            speechCueRegex.containsMatchIn(prefix.takeLast(80)) ||
            speechCueAfterRegex.containsMatchIn(suffix.take(80))
        val isCrossParagraph = ranges.map { it.paragraphIndex }.distinct().size > 1
        val looksLikeDialogue = hasSpeechCue ||
            isCrossParagraph ||
            inner.length >= 6 ||
            inner.any { it in sentencePunctuation }
        val thought = thoughtCueRegex.containsMatchIn(prefix.takeLast(40)) ||
            suffix.take(40).contains("心想") ||
            suffix.take(40).contains("心道") ||
            suffix.take(40).contains("暗道")

        if (!looksLikeDialogue) {
            val kind = if (inner.length <= 8) "emphasis" else "citation"
            return unit(
                kind = kind,
                roleType = "narrator",
                characterName = "旁白",
                ranges = ranges,
                text = text,
                confidence = 0.72,
                needsAi = false,
                reason = "quoted_$kind"
            )
        }

        val roleType = if (thought) "thought" else "character"
        return unit(
            kind = if (thought) "thought" else "dialogue",
            roleType = roleType,
            characterName = speaker,
            ranges = ranges,
            text = text,
            speakerHint = speaker,
            confidence = if (speaker.isBlank()) 0.46 else 0.78,
            needsAi = speaker.isBlank(),
            reason = if (speaker.isBlank()) "quoted_dialogue_unknown_speaker" else "quoted_dialogue_with_speaker"
        )
    }

    private fun unit(
        kind: String,
        roleType: String,
        characterName: String,
        characterId: Long = 0L,
        ranges: List<ReadAloudRoleRange>,
        text: String,
        speakerHint: String = "",
        emotionName: String = "",
        emotionTag: String = "",
        confidence: Double = 0.0,
        needsAi: Boolean = false,
        reason: String = ""
    ): ReadAloudRoleUnit {
        val first = ranges.first()
        val last = ranges.last()
        val hash = Integer.toHexString(text.hashCode())
        val id = "u_${first.paragraphIndex}_${first.start}_${last.paragraphIndex}_${last.end}_${kind}_$hash"
        return ReadAloudRoleUnit(
            id = id,
            kind = kind,
            roleType = roleType,
            characterName = characterName,
            characterId = characterId,
            ranges = ranges,
            text = text,
            speakerHint = speakerHint,
            emotionName = emotionName,
            emotionTag = emotionTag,
            confidence = confidence.coerceIn(0.0, 1.0),
            needsAi = needsAi,
            reason = reason
        )
    }

    private fun findClosingQuote(text: String, start: Int, closeChar: Char): Int {
        if (start >= text.length) return -1
        return text.indexOf(closeChar, start)
    }

    private fun contextBefore(
        paragraphs: List<String>,
        paragraphOffset: Int,
        range: ReadAloudRoleRange
    ): String {
        val localIndex = range.paragraphIndex - paragraphOffset
        val current = paragraphs.getOrNull(localIndex).orEmpty()
            .substring(0, range.start.coerceIn(0, paragraphs.getOrNull(localIndex).orEmpty().length))
        val previous = paragraphs.getOrNull(localIndex - 1).orEmpty().takeLast(40)
        return (previous + "\n" + current).takeLast(100)
    }

    private fun contextAfter(
        paragraphs: List<String>,
        paragraphOffset: Int,
        range: ReadAloudRoleRange
    ): String {
        val localIndex = range.paragraphIndex - paragraphOffset
        val currentParagraph = paragraphs.getOrNull(localIndex).orEmpty()
        val current = currentParagraph.substring(range.end.coerceIn(0, currentParagraph.length))
        val next = paragraphs.getOrNull(localIndex + 1).orEmpty().take(40)
        return (current + "\n" + next).take(100)
    }

    private fun inferSpeakerBefore(prefix: String): String {
        val match = speechCueRegex.find(prefix.takeLast(80)) ?: return ""
        val name = match.groupValues.getOrNull(1).orEmpty().trim()
        return name.takeIf(::isLikelySpeakerName).orEmpty()
    }

    private fun inferSpeakerAfter(suffix: String): String {
        val match = speechCueAfterRegex.find(suffix.take(80)) ?: return ""
        val name = match.groupValues.getOrNull(1).orEmpty().trim()
        return name.takeIf(::isLikelySpeakerName).orEmpty()
    }

    private fun inferColonSpeaker(prefix: String): String {
        val match = colonSpeakerRegex.find(prefix.takeLast(40)) ?: return ""
        val name = match.groupValues.getOrNull(1).orEmpty().trim()
        return name.takeIf(::isLikelySpeakerName).orEmpty()
    }

    private fun isLikelySpeakerName(value: String): Boolean {
        val name = value.trim().trim('“', '”', '‘', '’', '"', '\'', '，', ',', '。', '：', ':')
        if (name.length !in 2..24) return false
        if (name.any { it.isWhitespace() }) return false
        if (name.any { it in "，。！？；,.!?;、（）()《》<>[]【】" }) return false
        if (invalidSpeakerSuffixes.any { name.endsWith(it) }) return false
        if (name in setOf("旁白", "作者", "读者", "我", "你", "他", "她", "它", "我们", "你们", "他们", "她们", "它们", "众人", "有人")) {
            return false
        }
        return name.none { it.isDigit() } || name.any { it.code > 127 }
    }

    private data class OpenQuote(
        val openChar: Char,
        val closeChar: Char,
        val ranges: MutableList<ReadAloudRoleRange>
    )
}
