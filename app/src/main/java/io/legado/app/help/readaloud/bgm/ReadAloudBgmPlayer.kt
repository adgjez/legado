package io.legado.app.help.readaloud.bgm

import android.animation.ValueAnimator
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.legado.app.help.ai.AiReadAloudBgmService
import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.ReadAloudPlaybackState
import io.legado.app.model.ReadBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReadAloudBgmPlayer(
    context: Context,
    private val scope: CoroutineScope
) {

    private val appContext = context.applicationContext
    private var player: ExoPlayer? = null
    private var currentTrackId: Long? = null
    private var currentCueKey: String = ""
    private var resolveJob: Job? = null
    private var volumeAnimator: ValueAnimator? = null

    fun onPlaybackState(state: ReadAloudPlaybackState) {
        scope.launch(Dispatchers.Main.immediate) {
            handlePlaybackState(state)
        }
    }

    fun release() {
        resolveJob?.cancel()
        resolveJob = null
        volumeAnimator?.cancel()
        volumeAnimator = null
        player?.release()
        player = null
        currentTrackId = null
        currentCueKey = ""
    }

    private fun handlePlaybackState(state: ReadAloudPlaybackState) {
        if (!AppConfig.aiReadAloudBgmEnabled ||
            state.phase == ReadAloudPlaybackState.PHASE_STOPPED ||
            state.phase == ReadAloudPlaybackState.PHASE_ERROR ||
            !state.serviceRunning
        ) {
            fadeOutAndStop()
            return
        }
        if (state.playing != true || state.busy) {
            player?.pause()
            return
        }
        val bookUrl = ReadBook.book?.bookUrl
        val chapterIndex = state.chapterIndex.takeIf { it >= 0 } ?: ReadBook.durChapterIndex
        val cueIndex = state.cueIndex
        if (bookUrl.isNullOrBlank() || chapterIndex < 0 || cueIndex < 0) {
            fadeOutAndStop()
            return
        }
        val cueKey = "$bookUrl|$chapterIndex|$cueIndex"
        if (cueKey == currentCueKey && player?.isPlaying == true) return
        resolveJob?.cancel()
        resolveJob = scope.launch(Dispatchers.IO) {
            val resolved = AiReadAloudBgmService.cachedAssignmentForCue(bookUrl, chapterIndex, cueIndex)
            withContext(Dispatchers.Main.immediate) {
                applyResolvedAssignment(cueKey, resolved)
            }
        }
    }

    private fun applyResolvedAssignment(
        cueKey: String,
        resolved: AiReadAloudBgmService.ResolvedAssignment?
    ) {
        if (!AppConfig.aiReadAloudBgmEnabled) {
            fadeOutAndStop()
            return
        }
        currentCueKey = cueKey
        if (resolved == null) {
            fadeOutAndStop()
            return
        }
        val file = File(resolved.track.filePath)
        if (!file.exists() || file.length() <= 0L) {
            fadeOutAndStop()
            return
        }
        val bgmPlayer = ensurePlayer()
        val targetVolume = resolved.assignment.volume
        if (currentTrackId == resolved.track.id) {
            if (!bgmPlayer.isPlaying) {
                bgmPlayer.play()
            }
            animateVolume(targetVolume, resolved.assignment.fadeInMs)
            return
        }
        currentTrackId = resolved.track.id
        volumeAnimator?.cancel()
        bgmPlayer.stop()
        bgmPlayer.clearMediaItems()
        bgmPlayer.repeatMode = Player.REPEAT_MODE_ONE
        bgmPlayer.volume = 0f
        bgmPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        bgmPlayer.prepare()
        bgmPlayer.play()
        animateVolume(targetVolume, resolved.assignment.fadeInMs)
    }

    private fun ensurePlayer(): ExoPlayer {
        return player ?: ExoPlayer.Builder(appContext)
            .build()
            .also {
                it.repeatMode = Player.REPEAT_MODE_ONE
                it.volume = 0f
                player = it
            }
    }

    private fun fadeOutAndStop(durationMs: Int = 1200) {
        val bgmPlayer = player ?: return
        if (currentTrackId == null && !bgmPlayer.isPlaying) return
        animateVolume(0f, durationMs) {
            bgmPlayer.stop()
            bgmPlayer.clearMediaItems()
            currentTrackId = null
            currentCueKey = ""
        }
    }

    private fun animateVolume(
        target: Float,
        durationMs: Int,
        onEnd: (() -> Unit)? = null
    ) {
        val bgmPlayer = player ?: return
        volumeAnimator?.cancel()
        val start = bgmPlayer.volume
        if (durationMs <= 0 || kotlin.math.abs(start - target) < 0.01f) {
            bgmPlayer.volume = target
            onEnd?.invoke()
            return
        }
        volumeAnimator = ValueAnimator.ofFloat(start, target).apply {
            duration = durationMs.toLong()
            addUpdateListener { animator ->
                bgmPlayer.volume = animator.animatedValue as Float
            }
            addListener(
                object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        onEnd?.invoke()
                    }
                }
            )
            start()
        }
    }
}
