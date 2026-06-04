package io.legado.app.ui.main.ai

import android.os.Bundle
import android.speech.tts.TextToSpeech
import splitties.init.appCtx

object AiChatSpeechPlayer : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String = ""

    fun speak(text: String) {
        val clean = sanitize(text)
        if (clean.isBlank()) return
        val engine = tts
        if (engine == null) {
            pendingText = clean
            tts = TextToSpeech(appCtx, this)
            return
        }
        if (!ready) {
            pendingText = clean
            return
        }
        engine.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle.EMPTY, "ai_chat_${System.currentTimeMillis()}")
    }

    fun stop() {
        pendingText = ""
        tts?.stop()
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready && pendingText.isNotBlank()) {
            val text = pendingText
            pendingText = ""
            speak(text)
        }
    }

    private fun sanitize(text: String): String {
        return text
            .replace(Regex("""!\[[^\]]*]\([^)]*\)"""), "")
            .replace(Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""```[\s\S]*?```"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(4_000)
    }
}
