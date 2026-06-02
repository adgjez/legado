package io.legado.app.help.readaloud.speech

import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.ReadAloudSpeakerGroup
import io.legado.app.data.entities.ReadAloudSpeakerGroupItem
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.utils.GSON

data class ManagedSpeechVoiceGroup(
    val group: ReadAloudSpeakerGroup,
    val items: List<ReadAloudSpeakerGroupItem>,
    val routes: List<SpeechRoute>
)

object SpeechVoiceGroupRepository {

    fun managedGroups(
        httpTtsList: List<HttpTTS> = appDb.httpTTSDao.all,
        enabledOnly: Boolean = true
    ): List<ManagedSpeechVoiceGroup> {
        val groups = if (enabledOnly) {
            appDb.readAloudSpeakerGroupDao.enabledGroups()
        } else {
            appDb.readAloudSpeakerGroupDao.groups()
        }
        if (groups.isEmpty()) return emptyList()
        val itemsByGroup = appDb.readAloudSpeakerGroupDao.items().groupBy { it.groupId }
        return groups.mapNotNull { group ->
            val validItems = itemsByGroup[group.id]
                .orEmpty()
                .filter { isValidItem(it, httpTtsList) }
            if (validItems.isEmpty()) {
                null
            } else {
                ManagedSpeechVoiceGroup(
                    group = group,
                    items = validItems,
                    routes = validItems.map { it.toSpeechRoute() }.distinctBy {
                        "${it.engineType}|${it.engineValue}|${it.toneID}|${it.speakerName}"
                    }
                )
            }
        }
    }

    fun assignableRoutes(httpTtsList: List<HttpTTS> = appDb.httpTTSDao.all): List<SpeechRoute> {
        return managedGroups(httpTtsList)
            .flatMap { it.routes }
            .distinctBy { "${it.engineType}|${it.engineValue}|${it.toneID}|${it.speakerName}" }
    }

    fun isValidItem(
        item: ReadAloudSpeakerGroupItem,
        httpTtsList: List<HttpTTS> = appDb.httpTTSDao.all
    ): Boolean {
        return when (item.engineType) {
            SpeechRoute.ENGINE_SYSTEM -> item.speakerName.isNotBlank() || item.engineName.isNotBlank()
            SpeechRoute.ENGINE_HTTP -> {
                val httpTts = item.engineValue.toLongOrNull()
                    ?.let { id -> httpTtsList.firstOrNull { it.id == id } }
                    ?: return false
                val speakers = SpeechVoiceCatalogParser.parseSpeakerGroups(httpTts.speakersJson)
                    .flatMap { it.items }
                when {
                    item.toneID.isBlank() -> true
                    speakers.isEmpty() -> false
                    else -> speakers.any { speaker ->
                        speaker.toneID == item.toneID &&
                            (item.speakerName.isBlank() || speaker.speakerName == item.speakerName)
                    }
                }
            }
            else -> false
        }
    }

    fun itemFromOption(
        groupId: Long,
        option: SpeechVoiceOption,
        sortOrder: Int,
        now: Long = System.currentTimeMillis()
    ): ReadAloudSpeakerGroupItem {
        return ReadAloudSpeakerGroupItem(
            groupId = groupId,
            engineType = option.engineType,
            engineValue = option.engineValue,
            engineName = option.engineName,
            speakerName = option.speakerName,
            toneID = option.toneID,
            sourceGroupId = option.groupId,
            sourceGroupName = option.groupName,
            sortOrder = sortOrder,
            createdAt = now,
            updatedAt = now
        )
    }

    fun systemDefaultItem(
        groupId: Long,
        sortOrder: Int,
        now: Long = System.currentTimeMillis()
    ): ReadAloudSpeakerGroupItem {
        return ReadAloudSpeakerGroupItem(
            groupId = groupId,
            engineType = SpeechRoute.ENGINE_SYSTEM,
            engineValue = GSON.toJson(SelectItem("系统默认", "")),
            engineName = "系统默认",
            speakerName = "系统默认",
            sortOrder = sortOrder,
            createdAt = now,
            updatedAt = now
        )
    }
}

fun ReadAloudSpeakerGroupItem.toSpeechRoute(source: String = SpeechRoute.SOURCE_AUTO): SpeechRoute {
    return SpeechRoute(
        engineType = engineType,
        engineValue = engineValue,
        speakerName = speakerName.ifBlank { engineName },
        toneID = toneID,
        source = source
    )
}
