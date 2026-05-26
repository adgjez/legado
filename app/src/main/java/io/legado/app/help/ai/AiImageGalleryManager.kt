package io.legado.app.help.ai

import android.webkit.URLUtil
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGeneratedImage
import io.legado.app.data.entities.AiImageGroup
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.utils.decodeBase64DataUrlBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import splitties.init.appCtx
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

object AiImageGalleryManager {

    const val DEFAULT_GROUP_ID = "default"
    private const val DEFAULT_GROUP_NAME = "默认分组"
    private const val TEMP_KEEP_DAYS = 3L

    private val imageDir: File
        get() = File(appCtx.filesDir, "ai_images").apply { mkdirs() }

    suspend fun saveGeneratedImage(
        imageSource: String,
        prompt: String,
        provider: AiImageProviderConfig
    ): AiGeneratedImage = withContext(Dispatchers.IO) {
        ensureDefaultGroup()
        cleanupExpiredTemporary()
        val bytes = readImageBytes(imageSource, provider)
        val id = UUID.randomUUID().toString()
        val ext = detectExtension(bytes, imageSource)
        val file = File(imageDir, "$id.$ext")
        file.writeBytes(bytes)
        val now = System.currentTimeMillis()
        val image = AiGeneratedImage(
            id = id,
            name = promptName(prompt),
            prompt = prompt,
            providerId = provider.id,
            providerName = provider.displayName(),
            model = provider.model.ifBlank { if (provider.type == AiImageProviderConfig.TYPE_OPENAI) "gpt-image-1" else "JS" },
            localPath = file.absolutePath,
            originalSource = sourceSummary(imageSource),
            createdAt = now,
            updatedAt = now
        )
        appDb.aiGeneratedImageDao.insert(image)
        image
    }

    suspend fun cleanupExpiredTemporary() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - TEMP_KEEP_DAYS * 24L * 60L * 60L * 1000L
        appDb.aiGeneratedImageDao.expiredTemporary(cutoff).forEach { image ->
            deleteImageFile(image)
            appDb.aiGeneratedImageDao.delete(image.id)
        }
    }

    fun ensureDefaultGroup() {
        if (appDb.aiImageGroupDao.get(DEFAULT_GROUP_ID) == null) {
            appDb.aiImageGroupDao.insert(
                AiImageGroup(
                    id = DEFAULT_GROUP_ID,
                    name = DEFAULT_GROUP_NAME,
                    sortOrder = 0
                )
            )
        }
    }

    fun listGroups(): List<AiImageGroup> {
        ensureDefaultGroup()
        return appDb.aiImageGroupDao.all()
    }

    fun createGroup(name: String): AiImageGroup {
        ensureDefaultGroup()
        val cleanName = name.trim().ifBlank { DEFAULT_GROUP_NAME }
        val group = AiImageGroup(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            sortOrder = appDb.aiImageGroupDao.all().size
        )
        appDb.aiImageGroupDao.insert(group)
        return group
    }

    fun getImage(id: String): AiGeneratedImage? {
        return appDb.aiGeneratedImageDao.get(id)
    }

    fun listImages(filter: GalleryFilter): List<AiGeneratedImage> {
        ensureDefaultGroup()
        return when (filter) {
            GalleryFilter.ALL -> appDb.aiGeneratedImageDao.all()
            GalleryFilter.TEMPORARY -> appDb.aiGeneratedImageDao.temporary()
            GalleryFilter.FAVORITE -> appDb.aiGeneratedImageDao.favorites()
            is GalleryFilter.GROUP -> appDb.aiGeneratedImageDao.byGroup(filter.groupId)
        }.filter { File(it.localPath).isFile }
    }

    fun renameImage(id: String, name: String) {
        val cleanName = name.trim().ifBlank { return }
        appDb.aiGeneratedImageDao.rename(id, cleanName, System.currentTimeMillis())
    }

    fun setFavorite(id: String, favorite: Boolean, groupId: String?) {
        ensureDefaultGroup()
        val targetGroupId = if (favorite) groupId ?: DEFAULT_GROUP_ID else null
        appDb.aiGeneratedImageDao.setFavorite(id, favorite, targetGroupId, System.currentTimeMillis())
    }

    fun deleteImage(id: String) {
        val image = appDb.aiGeneratedImageDao.get(id) ?: return
        deleteImageFile(image)
        appDb.aiGeneratedImageDao.delete(id)
    }

    private fun deleteImageFile(image: AiGeneratedImage) {
        runCatching {
            val file = File(image.localPath)
            if (file.isFile && file.parentFile?.canonicalPath == imageDir.canonicalPath) {
                file.delete()
            }
        }.onFailure {
            AppLog.put("删除 AI 图片失败: ${image.localPath}", it)
        }
    }

    private suspend fun readImageBytes(
        imageSource: String,
        provider: AiImageProviderConfig
    ): ByteArray {
        imageSource.decodeBase64DataUrlBytes()?.let { return it }
        if (URLUtil.isValidUrl(imageSource)) {
            provider.imageDownloadClient().newCallResponse {
                url(imageSource)
                addHeaders(AiChatService.parseCustomHeaders(provider.headers))
            }.use { response ->
                if (!response.isSuccessful) error("${response.code} ${response.message}")
                return response.body?.bytes() ?: error("Empty image body")
            }
        }
        val file = File(imageSource)
        if (file.isFile) return file.readBytes()
        error("Unsupported image result")
    }

    private fun AiImageProviderConfig.imageDownloadClient(): OkHttpClient {
        val timeout = validTimeout()
        return okHttpClient.newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun detectExtension(bytes: ByteArray, source: String): String {
        val lower = source.substringBefore('?').lowercase()
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "jpg"
        if (lower.endsWith(".webp")) return "webp"
        if (lower.endsWith(".gif")) return "gif"
        return when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg"
            bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.ISO_8859_1) == "RIFF" &&
                bytes.copyOfRange(8, 12).toString(Charsets.ISO_8859_1) == "WEBP" -> "webp"
            bytes.size >= 3 && bytes.copyOfRange(0, 3).toString(Charsets.ISO_8859_1) == "GIF" -> "gif"
            else -> "png"
        }
    }

    private fun promptName(prompt: String): String {
        return prompt.lineSequence()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .trim()
            .take(36)
            .ifBlank { "AI 图片" }
    }

    private fun sourceSummary(source: String): String {
        return if (source.startsWith("data:image", true)) "data:image" else source.take(500)
    }

    sealed class GalleryFilter {
        data object ALL : GalleryFilter()
        data object TEMPORARY : GalleryFilter()
        data object FAVORITE : GalleryFilter()
        data class GROUP(val groupId: String) : GalleryFilter()
    }
}
