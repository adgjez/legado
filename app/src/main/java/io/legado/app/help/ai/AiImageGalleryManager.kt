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
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

object AiImageGalleryManager {

    const val DEFAULT_GROUP_ID = "default"
    const val IMAGE_URI_PREFIX = "ai-image://"
    private const val DEFAULT_GROUP_NAME = "默认分组"
    private const val TEMP_KEEP_DAYS = 3L
    private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024

    private val imageDir: File
        get() = File(appCtx.filesDir, "ai_images").apply { mkdirs() }

    suspend fun saveGeneratedImage(
        imageSource: String,
        prompt: String,
        provider: AiImageProviderConfig,
        model: String? = null
    ): AiGeneratedImage = withContext(Dispatchers.IO) {
        ensureDefaultGroup()
        cleanupExpiredTemporary()
        val id = UUID.randomUUID().toString()
        val tempFile = File(imageDir, "$id.tmp")
        val byteCount = runCatching {
            writeImageToTempFile(imageSource, provider, tempFile)
        }.onFailure {
            runCatching { tempFile.delete() }
        }.getOrThrow()
        if (byteCount <= 0L) {
            runCatching { tempFile.delete() }
            error("Empty image body")
        }
        val header = readHeaderBytes(tempFile)
        val ext = detectExtension(header, imageSource)
        val file = File(imageDir, "$id.$ext")
        if (!tempFile.renameTo(file)) {
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        }
        val now = System.currentTimeMillis()
        val image = AiGeneratedImage(
            id = id,
            name = promptName(prompt),
            prompt = prompt,
            providerId = provider.id,
            providerName = provider.displayName(),
            model = model?.takeIf { it.isNotBlank() }
                ?: provider.model.ifBlank { if (provider.type == AiImageProviderConfig.TYPE_OPENAI) "gpt-image-1" else "JS" },
            localPath = file.absolutePath,
            originalSource = sourceSummary(imageSource),
            createdAt = now,
            updatedAt = now
        )
        runCatching {
            appDb.aiGeneratedImageDao.insert(image)
        }.onFailure {
            runCatching { file.delete() }
        }.getOrThrow()
        image
    }

    suspend fun cleanupExpiredTemporary() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - TEMP_KEEP_DAYS * 24L * 60L * 60L * 1000L
        appDb.aiGeneratedImageDao.expiredTemporary(cutoff).forEach { image ->
            deleteImageFile(image)
            appDb.aiGeneratedImageDao.delete(image.id)
        }
        reconcileStorage()
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
        val image = appDb.aiGeneratedImageDao.get(id) ?: return null
        if (!File(image.localPath).isFile) {
            appDb.aiGeneratedImageDao.delete(id)
            return null
        }
        return image
    }

    fun imageUri(id: String): String {
        return IMAGE_URI_PREFIX + id.trim()
    }

    fun imageIdFromUri(src: String?): String? {
        val value = src?.trim().orEmpty()
        if (!value.startsWith(IMAGE_URI_PREFIX, ignoreCase = true)) return null
        return value.substring(IMAGE_URI_PREFIX.length).substringBefore('?').trim().takeIf { it.isNotBlank() }
    }

    fun resolveImageFile(src: String?): File? {
        val id = imageIdFromUri(src) ?: return null
        val image = getImage(id) ?: return null
        return File(image.localPath).takeIf { it.isFile }
    }

    fun listImages(filter: GalleryFilter): List<AiGeneratedImage> {
        ensureDefaultGroup()
        return when (filter) {
            GalleryFilter.ALL -> appDb.aiGeneratedImageDao.all()
            GalleryFilter.TEMPORARY -> appDb.aiGeneratedImageDao.temporary()
            GalleryFilter.FAVORITE -> appDb.aiGeneratedImageDao.favorites()
            is GalleryFilter.GROUP -> appDb.aiGeneratedImageDao.byGroup(filter.groupId)
        }.filter { image ->
            val exists = File(image.localPath).isFile
            if (!exists) appDb.aiGeneratedImageDao.delete(image.id)
            exists
        }
    }

    fun renameImage(id: String, name: String) {
        val cleanName = name.trim().ifBlank { return }
        appDb.aiGeneratedImageDao.rename(id, cleanName, System.currentTimeMillis())
    }

    fun setFavorite(id: String, favorite: Boolean, groupId: String?) {
        ensureDefaultGroup()
        val targetGroupId = if (favorite) {
            groupId?.takeIf { appDb.aiImageGroupDao.get(it) != null } ?: DEFAULT_GROUP_ID
        } else {
            null
        }
        appDb.aiGeneratedImageDao.setFavorite(id, favorite, targetGroupId, System.currentTimeMillis())
    }

    fun deleteGroup(id: String) {
        if (id == DEFAULT_GROUP_ID) return
        ensureDefaultGroup()
        appDb.runInTransaction {
            appDb.aiGeneratedImageDao.moveGroup(id, DEFAULT_GROUP_ID, System.currentTimeMillis())
            appDb.aiImageGroupDao.delete(id)
        }
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

    private suspend fun writeImageToTempFile(
        imageSource: String,
        provider: AiImageProviderConfig,
        target: File
    ): Long {
        estimateBase64DataUrlBytes(imageSource)?.let { estimatedBytes ->
            if (estimatedBytes > MAX_IMAGE_BYTES) {
                error("Image is too large: $estimatedBytes bytes")
            }
        }
        imageSource.decodeBase64DataUrlBytes()?.let { bytes ->
            if (bytes.size > MAX_IMAGE_BYTES) error("Image is too large: ${bytes.size} bytes")
            target.writeBytes(bytes)
            return bytes.size.toLong()
        }
        if (URLUtil.isValidUrl(imageSource)) {
            provider.imageDownloadClient().newCallResponse {
                url(imageSource)
                addHeaders(AiChatService.parseCustomHeaders(provider.headers))
            }.use { response ->
                if (!response.isSuccessful) error("${response.code} ${response.message}")
                response.body.contentLength().takeIf { it > MAX_IMAGE_BYTES }?.let {
                    error("Image is too large: $it bytes")
                }
                return copyToFileLimited(response.body.byteStream(), target)
            }
        }
        val file = File(imageSource)
        if (file.isFile) {
            if (file.length() > MAX_IMAGE_BYTES) error("Image is too large: ${file.length()} bytes")
            return copyToFileLimited(file.inputStream(), target)
        }
        error("Unsupported image result")
    }

    private fun copyToFileLimited(input: InputStream, target: File): Long {
        var copied = 0L
        target.outputStream().use { output ->
            input.use {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > MAX_IMAGE_BYTES) error("Image is too large: $copied bytes")
                    output.write(buffer, 0, read)
                }
            }
        }
        return copied
    }

    private fun readHeaderBytes(file: File): ByteArray {
        val header = ByteArray(16)
        val count = file.inputStream().use { it.read(header) }
        return if (count > 0) header.copyOf(count) else ByteArray(0)
    }

    private fun estimateBase64DataUrlBytes(source: String): Long? {
        val clean = source.trim()
        val payload = when {
            clean.startsWith("data:", ignoreCase = true) -> {
                val commaIndex = clean.indexOf(',')
                if (commaIndex < 0 || !clean.substring(0, commaIndex).contains(";base64", true)) return null
                clean.substring(commaIndex + 1).substringBefore(",{")
            }
            clean.startsWith("data64:", ignoreCase = true) -> clean.substringAfter(':').substringBefore(",{")
            else -> return null
        }.filterNot { it.isWhitespace() }
        if (payload.isBlank()) return null
        return payload.length.toLong() * 3L / 4L
    }

    private fun reconcileStorage() {
        val images = appDb.aiGeneratedImageDao.all()
        images.forEach { image ->
            if (!File(image.localPath).isFile) {
                appDb.aiGeneratedImageDao.delete(image.id)
            }
        }
        val validPaths = images
            .asSequence()
            .mapNotNull { runCatching { File(it.localPath).canonicalPath }.getOrNull() }
            .toSet()
        imageDir.listFiles()
            ?.filter { it.isFile }
            ?.forEach { file ->
                val canonicalPath = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
                if (canonicalPath !in validPaths) {
                    runCatching { file.delete() }
                }
            }
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
