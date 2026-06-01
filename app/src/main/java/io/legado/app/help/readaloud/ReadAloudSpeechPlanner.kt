package io.legado.app.help.readaloud

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.ai.AiReadAloudRoleService
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.ui.book.read.page.entities.ReadAloudCue
import io.legado.app.ui.book.read.page.entities.TextChapter

data class ReadAloudSpeechPlan(
    val cues: List<ReadAloudCue>,
    val routes: List<SpeechRoute?>,
    val items: List<ReadAloudSpeechPlanItem>
)

data class ReadAloudSpeechPlanItem(
    val index: Int,
    val cue: ReadAloudCue,
    val route: SpeechRoute?,
    val roleType: String,
    val characterId: Long,
    val characterName: String,
    val avatar: String,
    val emotionName: String,
    val leftSide: Boolean,
    val narrator: Boolean,
    val sourceCueIndex: Int,
    val sourceStart: Int,
    val sourceEnd: Int
)

object ReadAloudSpeechPlanner {

    fun build(
        bookUrl: String?,
        chapter: TextChapter,
        baseCues: List<ReadAloudCue>,
        multiRoleEnabled: Boolean,
        roleCacheKey: String? = null
    ): ReadAloudSpeechPlan {
        if (baseCues.isEmpty()) {
            return ReadAloudSpeechPlan(emptyList(), emptyList(), emptyList())
        }
        if (!multiRoleEnabled || bookUrl.isNullOrBlank()) {
            val items = baseCues.mapIndexed { index, cue ->
                ReadAloudSpeechPlanItem(
                    index = index,
                    cue = cue.copy(index = index),
                    route = null,
                    roleType = "narrator",
                    characterId = 0L,
                    characterName = "旁白",
                    avatar = "",
                    emotionName = "",
                    leftSide = false,
                    narrator = true,
                    sourceCueIndex = cue.index,
                    sourceStart = 0,
                    sourceEnd = cue.text.length
                )
            }
            return ReadAloudSpeechPlan(
                cues = items.map { it.cue },
                routes = List(items.size) { null },
                items = items
            )
        }

        val characters = appDb.bookCharacterDao.characters(bookUrl)
        val byId = characters.associateBy { it.id }
        val byName = characters.associateBy { it.name }
        val plannedItems = arrayListOf<ReadAloudSpeechPlanItem>()
        baseCues.forEachIndexed { cueIndex, cue ->
            val beforeSize = plannedItems.size
            val segments = completeCueSegments(
                cueIndex = cueIndex,
                cueText = cue.text,
                rawSegments = AiReadAloudRoleService
                    .assignedSegmentsForCue(bookUrl, chapter.chapter.index, cueIndex, roleCacheKey)
            )
            segments.forEach { segment ->
                val text = cue.text.substring(segment.start, segment.end)
                if (text.isEmpty()) return@forEach
                val chapterPosition = cue.chapterPosition + segment.start
                val pageIndex = chapter.getPageIndexByCharIndex(chapterPosition)
                    .takeIf { it >= 0 }
                    ?: cue.pageIndex
                val pageStartPos = (chapterPosition - chapter.getReadLength(pageIndex)).coerceAtLeast(0)
                val character = resolveCharacter(segment, byId, byName)
                val displayName = when {
                    segment.roleType == "narrator" -> "旁白"
                    character != null -> character.displayName()
                    segment.characterName.isNotBlank() -> segment.characterName
                    segment.roleType == "thought" -> "心理"
                    else -> "角色"
                }
                val characterId = character?.id ?: segment.characterId
                val route = AiReadAloudRoleService.routeForSegment(bookUrl, segment)
                val plannedCue = ReadAloudCue(
                    index = plannedItems.size,
                    text = text,
                    chapterPosition = chapterPosition,
                    pageIndex = pageIndex,
                    pageStartPos = pageStartPos,
                    key = "${cue.key}:role:${segment.start}:${segment.end}:${text.hashCode()}"
                )
                plannedItems += ReadAloudSpeechPlanItem(
                    index = plannedItems.size,
                    cue = plannedCue,
                    route = route,
                    roleType = segment.roleType,
                    characterId = characterId,
                    characterName = displayName,
                    avatar = character?.avatar.orEmpty(),
                    emotionName = segment.emotionName.ifBlank { route?.emotionName.orEmpty() },
                    leftSide = sideFor(characterId, displayName),
                    narrator = segment.roleType == "narrator" || displayName == "旁白",
                    sourceCueIndex = cueIndex,
                    sourceStart = segment.start,
                    sourceEnd = segment.end
                )
            }
            if (plannedItems.size == beforeSize) {
                val plannedCue = cue.copy(index = plannedItems.size)
                plannedItems += ReadAloudSpeechPlanItem(
                    index = plannedItems.size,
                    cue = plannedCue,
                    route = null,
                    roleType = "narrator",
                    characterId = 0L,
                    characterName = "旁白",
                    avatar = "",
                    emotionName = "",
                    leftSide = false,
                    narrator = true,
                    sourceCueIndex = cueIndex,
                    sourceStart = 0,
                    sourceEnd = cue.text.length
                )
            }
        }
        return ReadAloudSpeechPlan(
            cues = plannedItems.map { it.cue },
            routes = plannedItems.map { it.route },
            items = plannedItems
        )
    }

    private fun resolveCharacter(
        segment: AiReadAloudRoleService.Segment,
        byId: Map<Long, BookCharacter>,
        byName: Map<String, BookCharacter>
    ): BookCharacter? {
        return when {
            segment.characterId > 0L -> byId[segment.characterId]
            segment.characterName.isNotBlank() -> byName[segment.characterName]
            else -> null
        }
    }

    private fun sideFor(characterId: Long, name: String): Boolean {
        val seed = characterId.takeIf { it > 0 } ?: name.hashCode().toLong()
        return Math.floorMod(seed.hashCode(), 2) == 0
    }

    private fun completeCueSegments(
        cueIndex: Int,
        cueText: String,
        rawSegments: List<AiReadAloudRoleService.Segment>
    ): List<AiReadAloudRoleService.Segment> {
        if (cueText.isEmpty()) return emptyList()
        val normalized = rawSegments
            .mapNotNull { segment ->
                val start = segment.start.coerceIn(0, cueText.length)
                val end = segment.end.coerceIn(start, cueText.length)
                if (start >= end) null else segment.copy(start = start, end = end)
            }
            .sortedWith(compareBy<AiReadAloudRoleService.Segment> { it.start }.thenBy { it.end })
        val result = mutableListOf<AiReadAloudRoleService.Segment>()
        var cursor = 0
        normalized.forEach { segment ->
            if (segment.end <= cursor) return@forEach
            if (segment.start > cursor) {
                result += narratorSegment(cueIndex, cursor, segment.start)
            }
            val start = segment.start.coerceAtLeast(cursor)
            if (start < segment.end) {
                result += segment.copy(start = start)
                cursor = segment.end
            }
        }
        if (cursor < cueText.length) {
            result += narratorSegment(cueIndex, cursor, cueText.length)
        }
        return mergeAdjacentSegments(result)
    }

    private fun narratorSegment(
        cueIndex: Int,
        start: Int,
        end: Int
    ): AiReadAloudRoleService.Segment {
        return AiReadAloudRoleService.Segment(
            paragraphIndex = cueIndex,
            start = start,
            end = end,
            roleType = "narrator",
            characterName = "旁白",
            confidence = 0.5
        )
    }

    private fun mergeAdjacentSegments(
        segments: List<AiReadAloudRoleService.Segment>
    ): List<AiReadAloudRoleService.Segment> {
        if (segments.size <= 1) return segments
        val result = mutableListOf<AiReadAloudRoleService.Segment>()
        segments.forEach { segment ->
            val last = result.lastOrNull()
            if (last != null &&
                last.end == segment.start &&
                last.roleType == segment.roleType &&
                last.characterId == segment.characterId &&
                last.characterName == segment.characterName &&
                last.emotionName == segment.emotionName &&
                last.emotionTag == segment.emotionTag
            ) {
                result[result.lastIndex] = last.copy(end = segment.end)
            } else {
                result += segment
            }
        }
        return result
    }
}
