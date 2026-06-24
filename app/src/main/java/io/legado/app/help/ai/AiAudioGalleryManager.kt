package io.legado.app.help.ai

import android.util.Base64
import android.webkit.URLUtil
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGeneratedAudio
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.ui.main.ai.AiAudioProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

object AiAudioGalleryManager {

    const val AUDIO_URI_PREFIX = "ai-audio://"
    const val SOURCE_TYPE_CHAT = "chat"
    const val SOURCE_TYPE_READ_INSERT = "read_insert"
    const val SOURCE_TYPE_GALLERY = "gallery"
    private const val MAX_AUDIO_BYTES = 50L * 1024 * 1024 // 50MB

    private val audioDir: File
        get() = File(appCtx.filesDir, "ai_audios").apply { mkdirs() }

    data class AudioMetadata(
        val bookName: String = "",
        val bookAuthor: String = "",
        val chapterIndex: Int = -1,
        val chapterTitle: String = "",
        val sourceType: String = "",
        val sourceText: String = "",
        val audioType: String = "music",
        val format: String = "mp3",
        val duration: Double = 0.0
    ) {
        val bookKey: String
            get() = buildBookKey(bookName, bookAuthor)

        val chapterKey: String
            get() = buildChapterKey(bookKey, chapterIndex, chapterTitle)
    }

    fun audioUri(id: String): String = "$AUDIO_URI_PREFIX$id"

    fun audioIdFromUri(src: String?): String? {
        val value = src?.trim().orEmpty()
        if (!value.startsWith(AUDIO_URI_PREFIX, ignoreCase = true)) return null
        return value.substring(AUDIO_URI_PREFIX.length)
            .substringBefore('?')
            .trim()
            .takeIf { it.isNotBlank() }
    }

    fun resolveAudioFile(src: String?): File? {
        val id = audioIdFromUri(src) ?: return null
        val audio = appDb.aiGeneratedAudioDao.get(id) ?: return null
        val file = File(audio.localPath).takeIf { it.isFile } ?: return null
        appDb.aiGeneratedAudioDao.touchAccess(id, System.currentTimeMillis())
        return file
    }

    fun resolveThumbnailPath(id: String): String? {
        val audio = appDb.aiGeneratedAudioDao.get(id) ?: return null
        // Audio doesn't have thumbnails; return null so caller falls back to raw URI
        return null
    }

    fun getAudio(id: String): AiGeneratedAudio? {
        val audio = appDb.aiGeneratedAudioDao.get(id) ?: return null
        if (!File(audio.localPath).isFile) {
            appDb.aiGeneratedAudioDao.delete(id)
            return null
        }
        return audio
    }

    /**
     * 保存生成的音频：支持 data: URL（base64）、http(s) URL（下载）与本地文件路径（拷贝）。
     * 与 [AiVideoGalleryManager.saveGeneratedVideo] 保持对称。
     */
    suspend fun saveGeneratedAudio(
        audioSource: String,
        prompt: String,
        provider: AiAudioProviderConfig,
        model: String?,
        metadata: AudioMetadata = AudioMetadata()
    ): AiGeneratedAudio = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val format = metadata.format.trim().ifBlank { "mp3" }
        val ext = formatExtension(format)
        val file = File(audioDir, "$id.$ext")
        val byteCount = runCatching {
            writeAudioToFile(audioSource, provider, file)
        }.onFailure {
            runCatching { file.delete() }
        }.getOrThrow()
        if (byteCount <= 0L) {
            runCatching { file.delete() }
            error("Empty audio body")
        }
        val now = System.currentTimeMillis()
        val durationMs = if (metadata.duration > 0.0) (metadata.duration * 1000).toLong() else 0L
        val audio = AiGeneratedAudio(
            id = id,
            name = promptName(prompt),
            prompt = prompt,
            providerId = provider.id,
            providerName = provider.displayName(),
            model = model?.takeIf { it.isNotBlank() }
                ?: provider.model.ifBlank { if (provider.type == AiAudioProviderConfig.TYPE_OPENAI) "audio" else "JS" },
            localPath = file.absolutePath,
            duration = durationMs,
            format = format,
            audioType = metadata.audioType.trim().ifBlank { "music" },
            inputText = metadata.sourceText.trim().take(2000),
            bookKey = metadata.bookKey,
            bookName = metadata.bookName.trim(),
            bookAuthor = metadata.bookAuthor.trim(),
            chapterKey = metadata.chapterKey,
            chapterIndex = metadata.chapterIndex,
            chapterTitle = metadata.chapterTitle.trim(),
            sourceType = metadata.sourceType.trim(),
            remoteTaskId = "",
            createdAt = now,
            updatedAt = now
        )
        runCatching {
            appDb.aiGeneratedAudioDao.insert(audio)
        }.onFailure {
            runCatching { file.delete() }
        }.getOrThrow()
        audio
    }

    fun deleteAudio(id: String) {
        val audio = appDb.aiGeneratedAudioDao.get(id) ?: return
        deleteAudioFile(audio)
        appDb.aiGeneratedAudioDao.delete(id)
    }

    private fun deleteAudioFile(audio: AiGeneratedAudio) {
        runCatching {
            val file = File(audio.localPath)
            if (file.isFile && file.parentFile?.canonicalPath == audioDir.canonicalPath) {
                file.delete()
            }
        }.onFailure {
            AppLog.put("删除 AI 音频失败: ${audio.localPath}", it)
        }
    }

    private suspend fun writeAudioToFile(
        audioSource: String,
        provider: AiAudioProviderConfig,
        target: File
    ): Long {
        if (audioSource.startsWith("data:", true)) {
            val bytes = decodeAudioDataUrl(audioSource)
            if (bytes.size > MAX_AUDIO_BYTES) error("Audio is too large: ${bytes.size} bytes")
            target.writeBytes(bytes)
            return bytes.size.toLong()
        }
        if (URLUtil.isValidUrl(audioSource)) {
            provider.audioDownloadClient().newCallResponse {
                url(audioSource)
                addHeaders(AiChatService.parseCustomHeaders(provider.headers))
            }.use { response ->
                if (!response.isSuccessful) error("${response.code} ${response.message}")
                response.body.contentLength().takeIf { it > MAX_AUDIO_BYTES }?.let {
                    error("Audio is too large: $it bytes")
                }
                return copyToFileLimited(response.body.byteStream(), target)
            }
        }
        val file = File(audioSource)
        if (file.isFile) {
            if (file.length() > MAX_AUDIO_BYTES) error("Audio is too large: ${file.length()} bytes")
            return copyToFileLimited(file.inputStream(), target)
        }
        error(
            "Unsupported audio result: provider=${provider.displayName()}, " +
                "type=${provider.type}, source=${sourceSummary(audioSource)}"
        )
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
                    if (copied > MAX_AUDIO_BYTES) error("Audio is too large: $copied bytes")
                    output.write(buffer, 0, read)
                }
            }
        }
        return copied
    }

    private fun decodeAudioDataUrl(source: String): ByteArray {
        val comma = source.indexOf(',')
        require(comma > 0 && source.substring(0, comma).contains(";base64", true)) {
            "Invalid audio data url"
        }
        val payload = source.substring(comma + 1).filterNot { it.isWhitespace() }
        return Base64.decode(payload, Base64.DEFAULT)
    }

    private fun formatExtension(format: String): String {
        val f = format.trim().lowercase(Locale.ROOT)
        return when (f) {
            "", "mp3" -> "mp3"
            "wav" -> "wav"
            "aac" -> "aac"
            "ogg" -> "ogg"
            "flac" -> "flac"
            "m4a" -> "m4a"
            "opus" -> "opus"
            else -> f.filter { it.isLetterOrDigit() }.ifBlank { "mp3" }
        }
    }

    private fun AiAudioProviderConfig.audioDownloadClient(): OkHttpClient {
        val timeout = validTimeout()
        return okHttpClient.newBuilder()
            .connectTimeout(timeout, TimeUnit.MILLISECONDS)
            .writeTimeout(timeout, TimeUnit.MILLISECONDS)
            .readTimeout(timeout, TimeUnit.MILLISECONDS)
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun promptName(prompt: String): String {
        return prompt.lineSequence()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .trim()
            .take(36)
            .ifBlank { "AI 音频" }
    }

    private fun sourceSummary(source: String): String {
        if (source.startsWith("data:", true)) {
            return source.substringBefore(',', source).take(80)
        }
        return source.take(500)
    }

    fun buildBookKey(bookName: String, author: String): String {
        val name = normalizeKeyPart(bookName)
        val writer = normalizeKeyPart(author)
        return if (name.isBlank() && writer.isBlank()) "" else "$name|$writer"
    }

    fun buildChapterKey(bookKey: String, chapterIndex: Int, chapterTitle: String): String {
        val cleanBookKey = bookKey.trim()
        if (cleanBookKey.isBlank()) return ""
        val title = normalizeKeyPart(chapterTitle)
        return "$cleanBookKey|$chapterIndex|$title"
    }

    private fun normalizeKeyPart(value: String): String {
        return value
            .trim()
            .replace(Regex("""\s+"""), "")
            .lowercase(Locale.ROOT)
    }
}
