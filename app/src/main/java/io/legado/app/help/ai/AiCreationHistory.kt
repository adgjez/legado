package io.legado.app.help.ai

import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

/**
 * 视频/图片生成历史记录管理（基于 SharedPreferences）
 */
object AiCreationHistory {

    private const val KEY_VIDEO = "aiCreationVideoHistory"
    private const val KEY_IMAGE = "aiCreationImageHistory"
    private const val MAX_HISTORY = 200

    data class VideoRecord(
        val url: String,
        val prompt: String,
        val mode: String,       // "text-to-video" / "image-to-video"
        val timestamp: Long = System.currentTimeMillis()
    )

    data class ImageRecord(
        val url: String,
        val prompt: String,
        val mode: String,       // "text-to-image" / "image-to-image"
        val timestamp: Long = System.currentTimeMillis()
    )

    fun addVideo(record: VideoRecord) {
        val list = getVideos().toMutableList()
        list.add(0, record)
        if (list.size > MAX_HISTORY) list.removeLast()
        appCtx.putPrefString(KEY_VIDEO, GSON.toJson(list))
    }

    fun getVideos(): List<VideoRecord> {
        val json = appCtx.getPrefString(KEY_VIDEO)
        return if (json.isNullOrBlank()) emptyList()
        else GSON.fromJsonArray<VideoRecord>(json).getOrNull() ?: emptyList()
    }

    fun deleteVideo(url: String) {
        val list = getVideos().filter { it.url != url }
        appCtx.putPrefString(KEY_VIDEO, GSON.toJson(list))
    }

    fun addImage(record: ImageRecord) {
        val list = getImages().toMutableList()
        list.add(0, record)
        if (list.size > MAX_HISTORY) list.removeLast()
        appCtx.putPrefString(KEY_IMAGE, GSON.toJson(list))
    }

    fun getImages(): List<ImageRecord> {
        val json = appCtx.getPrefString(KEY_IMAGE)
        return if (json.isNullOrBlank()) emptyList()
        else GSON.fromJsonArray<ImageRecord>(json).getOrNull() ?: emptyList()
    }

    fun deleteImage(url: String) {
        val list = getImages().filter { it.url != url }
        appCtx.putPrefString(KEY_IMAGE, GSON.toJson(list))
    }
}