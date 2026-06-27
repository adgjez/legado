package io.legado.app.help.ai

import io.legado.app.data.appDb
import io.legado.app.data.entities.BookCharacter

object AiCharacterConsistency {

    data class ReferenceImage(
        val characterId: String,
        val imageId: String,
        val localPath: String
    )

    fun getReferenceImage(characterId: String): ReferenceImage? {
        val character = appDb.bookCharacterDao.getCharacter(characterId.toLongOrNull() ?: return null) ?: return null
        val refId = character.referenceImageId.takeIf { it.isNotBlank() } ?: return null
        val image = appDb.aiGeneratedImageDao.get(refId) ?: return null
        return ReferenceImage(characterId, image.id, image.localPath)
    }

    fun buildReferencePromptAddition(characterId: String, providerSupportsReference: Boolean): String {
        if (!providerSupportsReference) return ""
        val ref = getReferenceImage(characterId) ?: return ""
        return " --ref ${ref.localPath}"
    }
}
