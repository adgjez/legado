package io.legado.app.help.readaloud

data class ReadAloudPlaybackState(
    val phase: String = PHASE_STOPPED,
    val chapterIndex: Int = -1,
    val cueIndex: Int = -1,
    val message: String = ""
) {
    val busy: Boolean
        get() = phase == PHASE_PREPARING || phase == PHASE_BUFFERING

    companion object {
        const val PHASE_PREPARING = "preparing"
        const val PHASE_BUFFERING = "buffering"
        const val PHASE_PLAYING = "playing"
        const val PHASE_PAUSED = "paused"
        const val PHASE_STOPPED = "stopped"
        const val PHASE_ERROR = "error"
    }
}
