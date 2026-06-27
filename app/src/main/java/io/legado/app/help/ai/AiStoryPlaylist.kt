package io.legado.app.help.ai

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiStoryPlaylist
import io.legado.app.data.entities.AiStoryScene
import splitties.init.appCtx
import java.io.File

object AiStoryPlaylist {

    fun buildPlaylist(playlistEntity: AiStoryPlaylist, scenes: List<AiStoryScene>): List<MediaItem> {
        return scenes.mapNotNull { scene ->
            if (scene.videoId.isBlank()) return@mapNotNull null
            val video = appDb.aiGeneratedVideoDao.get(scene.videoId) ?: return@mapNotNull null
            val file = File(video.localPath)
            if (!file.exists()) return@mapNotNull null
            MediaItem.fromUri(Uri.fromFile(file))
        }
    }

    fun createExoPlayer(): ExoPlayer {
        return ExoPlayer.Builder(appCtx).build()
    }

    fun getSceneVideoPath(scene: AiStoryScene): String? {
        if (scene.videoId.isBlank()) return null
        val video = appDb.aiGeneratedVideoDao.get(scene.videoId) ?: return null
        return video.localPath
    }

    fun calculateTotalDuration(scenes: List<AiStoryScene>): Long {
        return scenes.sumOf { it.duration }
    }
}
