package io.legado.app.help.ai

data class AiReadAloudRoleState(
    val bookUrl: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val stage: String = STAGE_CURRENT,
    val status: String = STATUS_IDLE,
    val message: String = "",
    val paragraphCount: Int = 0,
    val segmentCount: Int = 0,
    val createdCharacterCount: Int = 0,
    val error: String = ""
) {
    val running: Boolean
        get() = status == STATUS_RUNNING

    companion object {
        const val STAGE_CURRENT = "current"
        const val STAGE_NEXT = "next"

        const val STATUS_IDLE = "idle"
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_FALLBACK = "fallback"
        const val STATUS_FAILED = "failed"
        const val STATUS_SKIPPED = "skipped"
    }
}
