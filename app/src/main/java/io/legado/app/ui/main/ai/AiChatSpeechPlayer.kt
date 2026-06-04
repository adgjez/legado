package io.legado.app.ui.main.ai

import android.os.Bundle
import android.speech.tts.TextToSpeech
import io.legado.app.help.readaloud.speech.SpeechRoute
import splitties.init.appCtx

object AiChatSpeechPlayer : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingText: String = ""
    private var pendingRouteJson: String = ""
    private var enginePackage: String? = null

    fun speak(text: String, routeJson: String = "") {
        val clean = sanitize(text)
        if (clean.isBlank()) return
        val targetEnginePackage = systemEnginePackage(routeJson)
        val engine = tts
        if (engine == null || enginePackage != targetEnginePackage) {
            pendingText = clean
            pendingRouteJson = routeJson
            ready = false
            engine?.stop()
            engine?.shutdown()
            enginePackage = targetEnginePackage
            tts = if (targetEnginePackage.isNullOrBlank()) {
                TextToSpeech(appCtx, this)
            } else {
                TextToSpeech(appCtx, this, targetEnginePackage)
            }
            return
        }
        if (!ready) {
            pendingText = clean
            pendingRouteJson = routeJson
            return
        }
        engine.speak(clean, TextToSpeech.QUEUE_FLUSH, Bundle.EMPTY, "ai_chat_${System.currentTimeMillis()}")
    }

    fun stop() {
        pendingText = ""
        pendingRouteJson = ""
        tts?.stop()
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready && pendingText.isNotBlank()) {
            val text = pendingText
            val routeJson = pendingRouteJson
            pendingText = ""
            pendingRouteJson = ""
            speak(text, routeJson)
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

    private fun systemEnginePackage(routeJson: String): String? {
        val route = SpeechRoute.fromJson(routeJson)
        if (route.engineType != SpeechRoute.ENGINE_SYSTEM) return null
        return route.engineValue.takeIf { it.isNotBlank() }
    }
}
