package io.legado.app.ui.main.ai

import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.script.ScriptException
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.help.readaloud.speech.SpeechVoiceCatalogRepository
import io.legado.app.help.readaloud.speech.SpeechVoiceGroupRepository
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.printOnDebug
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException

object AiChatSpeechPlayer : TextToSpeech.OnInitListener {

    private const val AI_CHAT_SPEECH_SPEED = 10

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var speakJob: Job? = null
    private var ready = false
    private var pendingText: String = ""
    private var pendingRouteJson: String = ""
    private var enginePackage: String? = null

    fun speak(text: String, routeJson: String = "") {
        val clean = sanitize(text)
        if (clean.isBlank()) return
        val route = SpeechRoute.fromJson(routeJson)
        if (route.engineType == SpeechRoute.ENGINE_HTTP) {
            speakHttp(clean, route)
            return
        }
        stopHttpPlayback()
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
        stopHttpPlayback()
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

    private fun speakHttp(text: String, route: SpeechRoute) {
        stopHttpPlayback()
        tts?.stop()
        speakJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                synthesizeHttp(text, route)
            }
            val file = result.file
            if (file != null) {
                playFile(file)
            } else {
                val fallback = result.fallbackRoute
                if (fallback != null) {
                    speak(text, fallback.toJson())
                }
            }
        }
    }

    private suspend fun synthesizeHttp(text: String, route: SpeechRoute): HttpSynthesisResult {
        val httpTts = httpTtsForRoute(route) ?: return HttpSynthesisResult(fallbackRoute = fallbackRoute(route))
        val file = File(speechCacheDir(), speechCacheName(text, route))
        if (file.exists() && file.length() > 0L) {
            return HttpSynthesisResult(file = file)
        }
        return runCatching {
            val stream = getSpeakStream(httpTts, text, route)
            if (stream != null) {
                file.outputStream().use { output ->
                    stream.use { input -> input.copyTo(output) }
                }
                HttpSynthesisResult(file = file)
            } else {
                HttpSynthesisResult(fallbackRoute = fallbackRoute(route))
            }
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            SpeechVoiceGroupRepository.markInvalidRoute(route, reason = "AI 聊天合成失败")
            AppLog.put("AI 聊天 TTS 合成失败\n${throwable.localizedMessage}", throwable)
            HttpSynthesisResult(fallbackRoute = fallbackRoute(route), error = throwable)
        }
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String,
        route: SpeechRoute
    ): java.io.InputStream? {
        var errorCount = 0
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = AI_CHAT_SPEECH_SPEED,
                    currentToneID = route.toneID,
                    currentSpeakerName = route.speakerName,
                    currentEmotionName = route.emotionName,
                    currentEmotionTag = route.emotionTag,
                    currentSpeechRouteJson = route.toJson(),
                    source = httpTts,
                    readTimeout = 300_000L,
                    coroutineContext = currentCoroutineContext()
                )
                val response = runCatching {
                    analyzeUrl.getResponseAwait().let { raw ->
                        currentCoroutineContext().ensureActive()
                        httpTts.loginCheckJs?.takeIf { it.isNotBlank() }
                            ?.let { analyzeUrl.evalJS(it, raw) as Response }
                            ?: raw
                    }
                }.getOrElse { throwable ->
                    currentCoroutineContext().ensureActive()
                    val checkJs = httpTts.loginCheckJs
                    if (!checkJs.isNullOrBlank()) {
                        runCatching {
                            analyzeUrl.evalJS(checkJs, analyzeUrl.getErrResponse(throwable)) as Response
                        }.getOrElse { throw throwable }
                    } else {
                        throw throwable
                    }
                }
                response.headers["Content-Type"]?.substringBefore(";")?.let { contentType ->
                    val expected = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        throw NoStackTraceException(response.body.string())
                    }
                    if (!expected.isNullOrBlank() && !contentType.matches(expected.toRegex())) {
                        throw NoStackTraceException("TTS服务器返回错误：" + response.body.string())
                    }
                }
                currentCoroutineContext().ensureActive()
                return response.body.byteStream()
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("AI 聊天 TTS JS 错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }
                    is SocketTimeoutException, is ConnectException -> {
                        errorCount++
                        if (errorCount > 2) throw e
                        delay((errorCount * 800L).coerceAtMost(2_400L))
                    }
                    else -> {
                        errorCount++
                        e.printOnDebug()
                        if (errorCount > 2) throw e
                        delay((errorCount * 800L).coerceAtMost(2_400L))
                    }
                }
            }
        }
    }

    private fun playFile(file: File) {
        stopHttpPlayback(cancelJob = false)
        mediaPlayer = MediaPlayer().apply {
            setOnCompletionListener { stopHttpPlayback(cancelJob = false) }
            setOnErrorListener { _, _, _ ->
                stopHttpPlayback(cancelJob = false)
                true
            }
            setDataSource(file.absolutePath)
            prepareAsync()
            setOnPreparedListener { it.start() }
        }
    }

    private fun stopHttpPlayback(cancelJob: Boolean = true) {
        if (cancelJob) {
            speakJob?.cancel()
            speakJob = null
        }
        mediaPlayer?.let { player ->
            runCatching {
                player.stop()
                player.release()
            }
        }
        mediaPlayer = null
    }

    private fun speechCacheDir(): File {
        return File(appCtx.cacheDir, "aiChatTts").apply { mkdirs() }
    }

    private fun speechCacheName(text: String, route: SpeechRoute): String {
        val raw = listOf(text, route.toJson()).joinToString("|")
        return MD5Utils.md5Encode(raw) + ".audio"
    }

    private fun httpTtsForRoute(route: SpeechRoute): HttpTTS? {
        val id = route.engineValue.toLongOrNull() ?: return null
        return appDb.httpTTSDao.get(id)
    }

    private fun fallbackRoute(route: SpeechRoute): SpeechRoute? {
        val originalKey = SpeechVoiceGroupRepository.routeKey(route)
        return SpeechVoiceGroupRepository.assignableRoutes()
            .ifEmpty { SpeechVoiceCatalogRepository.assignableRoutes(appDb.httpTTSDao.all) }
            .firstOrNull { SpeechVoiceGroupRepository.routeKey(it) != originalKey }
    }

    private fun systemEnginePackage(routeJson: String): String? {
        val route = SpeechRoute.fromJson(routeJson)
        if (route.engineType != SpeechRoute.ENGINE_SYSTEM) return null
        return route.engineValue.takeIf { it.isNotBlank() }
    }

    private data class HttpSynthesisResult(
        val file: File? = null,
        val fallbackRoute: SpeechRoute? = null,
        val error: Throwable? = null
    )
}
