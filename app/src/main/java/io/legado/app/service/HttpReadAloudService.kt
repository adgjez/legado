package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.script.ScriptException
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.appDb
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.ai.AiReadAloudRoleService
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.readaloud.ReadAloudPlaybackState
import io.legado.app.help.readaloud.ReadAloudSpeechPlan
import io.legado.app.help.readaloud.ReadAloudSpeechPlanner
import io.legado.app.help.readaloud.speech.SpeechRoute
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.page.entities.TextChapter
import io.legado.app.ui.book.read.page.entities.buildReadAloudCues
import io.legado.app.utils.FileUtils
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Response
import org.json.JSONObject
import org.mozilla.javascript.WrappedException
import splitties.init.appCtx
import java.io.File
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).build()
    }
    private val ttsFolderPath: String by lazy {
        cacheDir.absolutePath + File.separator + "httpTTS" + File.separator
    }
    private val cache by lazy {
        SimpleCache(
            File(cacheDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(appCtx)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var playIndexJob: Job? = null
    private var downloadErrorNo: Int = 0
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()

    private data class NextChapterSpeechPlan(
        val chapter: TextChapter,
        val plan: ReadAloudSpeechPlan
    )

    private data class SpeakAudioRequest(
        val index: Int,
        val text: String,
        val speakText: String,
        val fileName: String,
        val route: SpeechRoute?,
        val httpTts: HttpTTS?
    )

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            removeCacheFile()
        }
    }

    override fun play() {
        pageChanged = false
        exoPlayer.stop()
        if (!requestFocus()) return
        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            super.play()
            if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    override fun playStop() {
        exoPlayer.stop()
        playIndexJob?.cancel()
    }

    private fun updateNextPos() {
        if (!moveToNextCue()) {
            nextChapter()
        }
    }

    private fun downloadAndPlayAudios() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS
                val requests = buildCurrentAudioRequests(httpTts)
                val chunkSize = maxSynthesisThreadCount(requests)
                requests.chunked(chunkSize).forEach { chunk ->
                    ensureActive()
                    if (!prepareAudioRequests(chunk, pauseOnFailure = true)) return@execute
                    launch(Main) {
                        chunk.forEach { request ->
                            val file = getSpeakFileAsMd5(request.fileName)
                            exoPlayer.addMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                        }
                    }
                }
                preDownloadAudios(httpTts)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun preDownloadAudios(httpTts: HttpTTS?) {
        val requests = buildNextChapterAudioRequests(httpTts).take(10)
        prepareAudioRequests(requests, pauseOnFailure = false)
    }

    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS
                val requests = buildCurrentAudioRequests(httpTts)
                val downloaderChannel = Channel<Downloader>(Channel.UNLIMITED)
                repeat(maxSynthesisThreadCount(requests)) {
                    launch {
                        for (downloader in downloaderChannel) {
                            downloader.download(null)
                        }
                    }
                }
                try {
                    requests.forEach { request ->
                        ensureActive()
                        val dataSourceFactory = createDataSourceFactory(
                            request.httpTts,
                            request.speakText,
                            request.route,
                            request.fileName
                        )
                        val downloader = createDownloader(dataSourceFactory, request.fileName)
                        downloaderChannel.send(downloader)
                        val mediaSource = createMediaSource(dataSourceFactory, request.fileName)
                        launch(Main) {
                            exoPlayer.addMediaSource(mediaSource)
                        }
                    }
                    preDownloadAudiosStream(httpTts, downloaderChannel)
                } finally {
                    downloaderChannel.close()
                }
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS?,
        downloaderChannel: Channel<Downloader>
    ) {
        buildNextChapterAudioRequests(httpTts).take(10).forEach { request ->
            currentCoroutineContext().ensureActive()
            val dataSourceFactory = createDataSourceFactory(
                request.httpTts,
                request.speakText,
                request.route,
                request.fileName
            )
            val downloader = createDownloader(dataSourceFactory, request.fileName)
            downloaderChannel.send(downloader)
        }
    }

    private fun buildCurrentAudioRequests(httpTts: HttpTTS?): List<SpeakAudioRequest> {
        return contentList.mapIndexedNotNull { index, content ->
            if (index < nowSpeak) return@mapIndexedNotNull null
            var text = content
            if (paragraphStartPos > 0 && index == nowSpeak) {
                text = text.substring(paragraphStartPos.coerceIn(0, text.length))
            }
            val route = speechRouteForIndex(index)
            val routeHttpTts = httpTtsForRoute(httpTts, route)
            buildAudioRequest(
                index = index,
                text = text,
                chapter = textChapter,
                route = route,
                httpTts = routeHttpTts
            )
        }
    }

    private fun buildNextChapterAudioRequests(httpTts: HttpTTS?): List<SpeakAudioRequest> {
        val nextPlan = buildNextChapterSpeechPlan() ?: return emptyList()
        return nextPlan.plan.cues.mapIndexed { index, cue ->
            val route = nextPlan.plan.routes.getOrNull(index)
            buildAudioRequest(
                index = index,
                text = cue.text,
                chapter = nextPlan.chapter,
                route = route,
                httpTts = httpTtsForRoute(httpTts, route)
            )
        }
    }

    private fun buildAudioRequest(
        index: Int,
        text: String,
        chapter: TextChapter?,
        route: SpeechRoute?,
        httpTts: HttpTTS?
    ): SpeakAudioRequest {
        val speakText = text.replace(AppPattern.notReadAloudRegex, "")
        if (speakText.isEmpty()) {
            AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
        }
        return SpeakAudioRequest(
            index = index,
            text = text,
            speakText = speakText,
            fileName = md5SpeakFileName(text, chapter, route),
            route = route,
            httpTts = httpTts
        )
    }

    private suspend fun prepareAudioRequests(
        requests: List<SpeakAudioRequest>,
        pauseOnFailure: Boolean
    ): Boolean = coroutineScope {
        if (requests.isEmpty()) return@coroutineScope true
        val distinctRequests = requests.distinctBy { it.fileName }
        val engineSemaphores = distinctRequests
            .groupBy { it.engineLimitKey() }
            .mapValues { (_, items) ->
                Semaphore(items.maxOf { it.synthesisThreadCount() }.coerceIn(1, 8))
            }
        val globalSemaphore = Semaphore(maxSynthesisThreadCount(distinctRequests))
        distinctRequests.map { request ->
            async {
                globalSemaphore.withPermit {
                    engineSemaphores.getValue(request.engineLimitKey()).withPermit {
                        prepareAudioRequest(request, pauseOnFailure)
                    }
                }
            }
        }.awaitAll().all { it }
    }

    private suspend fun prepareAudioRequest(
        request: SpeakAudioRequest,
        pauseOnFailure: Boolean
    ): Boolean {
        if (request.speakText.isEmpty()) {
            createSilentSound(request.fileName)
            return true
        }
        if (hasSpeakFile(request.fileName)) {
            return true
        }
        return runCatching {
            if (request.route?.engineType == SpeechRoute.ENGINE_SYSTEM || request.httpTts == null) {
                if (!synthesizeSystemSpeakFile(request.fileName, request.speakText, request.route ?: defaultSystemRoute())) {
                    createSilentSound(request.fileName)
                }
            } else {
                val inputStream = getSpeakStream(request.httpTts, request.speakText, request.route)
                if (inputStream != null) {
                    createSpeakFile(request.fileName, inputStream)
                } else {
                    createSilentSound(request.fileName)
                }
            }
            true
        }.getOrElse { throwable ->
            if (throwable is CancellationException) throw throwable
            if (pauseOnFailure) pauseReadAloud()
            false
        }
    }

    private fun maxSynthesisThreadCount(requests: List<SpeakAudioRequest>): Int {
        return requests.maxOfOrNull { it.synthesisThreadCount() }?.coerceIn(1, 8) ?: 1
    }

    private fun SpeakAudioRequest.synthesisThreadCount(): Int {
        if (route?.engineType == SpeechRoute.ENGINE_SYSTEM || httpTts == null) return 1
        return httpTts.synthesisThreadCount.coerceIn(1, 8)
    }

    private fun SpeakAudioRequest.engineLimitKey(): String {
        return if (route?.engineType == SpeechRoute.ENGINE_SYSTEM || httpTts == null) {
            "system:${route?.engineValue.orEmpty()}"
        } else {
            "http:${httpTts.id}"
        }
    }

    private fun buildNextChapterSpeechPlan(): NextChapterSpeechPlan? {
        val chapter = ReadBook.nextTextChapter?.takeIf { it.isCompleted } ?: return null
        val baseCues = chapter.buildReadAloudCues(readAloudByPage)
        if (baseCues.isEmpty()) return null
        val roleCacheKey = if (AppConfig.aiReadAloudRoleEnabled) {
            AiReadAloudRoleService.cacheForPlayback(
                ReadBook.book,
                chapter,
                baseCues.map { it.text }
            )?.cacheKey
        } else {
            null
        }
        val plan = ReadAloudSpeechPlanner.build(
            bookUrl = ReadBook.book?.bookUrl,
            chapter = chapter,
            baseCues = baseCues,
            multiRoleEnabled = AppConfig.aiReadAloudRoleEnabled,
            roleCacheKey = roleCacheKey
        )
        return NextChapterSpeechPlan(chapter, plan)
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS?,
        speakText: String,
        route: SpeechRoute? = null,
        fileName: String? = null
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    resources.openRawResource(R.raw.silent_sound)
                } else if ((route?.engineType == SpeechRoute.ENGINE_SYSTEM || httpTts == null) && !fileName.isNullOrBlank()) {
                    val file = getSpeakFileAsMd5(fileName)
                    if (!hasSpeakFile(fileName)) {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            synthesizeSystemSpeakFile(fileName, speakText, route ?: defaultSystemRoute())
                        }
                    }
                    file.takeIf { it.exists() && it.length() > 0L }?.inputStream()
                        ?: resources.openRawResource(R.raw.silent_sound)
                } else {
                    kotlin.runCatching {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            val sourceHttpTts = httpTts ?: return@runBlocking resources.openRawResource(R.raw.silent_sound)
                            getSpeakStream(sourceHttpTts, speakText, route)
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException,
                            is CancellationException -> Unit

                            else -> pauseReadAloud()
                        }
                    }.getOrThrow()
                } ?: resources.openRawResource(R.raw.silent_sound)
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = fileName.toUri()
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    private fun createMediaSource(factory: DataSource.Factory, fileName: String): MediaSource {
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(MediaItem.fromUri(fileName))
    }

    private suspend fun getSpeakStream(
        httpTts: HttpTTS,
        speakText: String,
        route: SpeechRoute? = null
    ): InputStream? {
        while (true) {
            try {
                val analyzeUrl = AnalyzeUrl(
                    httpTts.url,
                    speakText = speakText,
                    speakSpeed = speechRate,
                    currentToneID = route?.toneID,
                    currentSpeakerName = route?.speakerName,
                    currentEmotionName = route?.emotionName,
                    currentEmotionTag = route?.emotionTag,
                    currentSpeechRouteJson = route?.toJson(),
                    source = httpTts,
                    readTimeout = 300 * 1000L,
                    coroutineContext = currentCoroutineContext()
                )
                val checkJs = httpTts.loginCheckJs
                val response = kotlin.runCatching {
                    analyzeUrl.getResponseAwait().let {
                        currentCoroutineContext().ensureActive()
                        if (!checkJs.isNullOrBlank()) {
                            analyzeUrl.evalJS(checkJs, it) as Response
                        } else {
                            it
                        }
                    }
                }.getOrElse { throwable ->
                    currentCoroutineContext().ensureActive()
                    if (!checkJs.isNullOrBlank()) {
                        val errResponse = analyzeUrl.getErrResponse(throwable)
                        try {
                            (analyzeUrl.evalJS(checkJs, errResponse) as Response).also {
                                if (it.code == 500) {
                                    throw throwable
                                }
                            }
                        } catch (_: Throwable) {
                            throw throwable
                        }
                    } else {
                        throw throwable
                    }
                }
                response.headers["Content-Type"]?.let { contentType ->
                    val contentType = contentType.substringBefore(";")
                    val ct = httpTts.contentType
                    if (contentType == "application/json" || contentType.startsWith("text/")) {
                        throw NoStackTraceException(response.body.string())
                    } else if (ct?.isNotBlank() == true) {
                        if (!contentType.matches(ct.toRegex())) {
                            throw NoStackTraceException(
                                "TTS服务器返回错误：" + response.body.string()
                            )
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                response.body.byteStream().let { stream ->
                    downloadErrorNo = 0
                    return stream
                }
            } catch (e: Exception) {
                when (e) {
                    is CancellationException -> throw e
                    is ScriptException, is WrappedException -> {
                        AppLog.put("js错误\n${e.localizedMessage}", e, true)
                        e.printOnDebug()
                        throw e
                    }

                    is SocketTimeoutException, is ConnectException -> {
                        downloadErrorNo++
                        if (downloadErrorNo > 5) {
                            val msg = "tts超时或连接错误超过5次\n${e.localizedMessage}"
                            AppLog.put(msg, e, true)
                            throw e
                        }
                    }

                    else -> {
                        downloadErrorNo++
                        val msg = "tts下载错误\n${e.localizedMessage}"
                        AppLog.put(msg, e)
                        e.printOnDebug()
                        if (downloadErrorNo > 5) {
                            val msg1 = "TTS服务器连续5次错误，已暂停阅读。"
                            AppLog.put(msg1, e, true)
                            throw e
                        } else {
                            AppLog.put("TTS下载音频出错，使用无声音频代替。\n朗读文本：$speakText")
                            delay((downloadErrorNo * 800L).coerceAtMost(4000L))
                            continue
                        }
                    }
                }
            }
        }
        return null
    }

    private fun speechRouteForIndex(index: Int): SpeechRoute? {
        val defaultRoute = ReadAloud.speechRoute.takeIf { it.isConfigured }
        if (!AppConfig.aiReadAloudRoleEnabled) return defaultRoute
        if (index in speechRoutes.indices) {
            return speechRoutes[index] ?: defaultRoute
        }
        val book = ReadBook.book ?: return null
        val chapter = textChapter ?: return null
        return AiReadAloudRoleService.routeForCue(
            bookUrl = book.bookUrl,
            chapterIndex = chapter.chapter.index,
            cueIndex = index,
            cueText = contentList.getOrNull(index)
        ) ?: defaultRoute
    }

    private fun httpTtsForRoute(defaultHttpTts: HttpTTS?, route: SpeechRoute?): HttpTTS? {
        if (route?.engineType == SpeechRoute.ENGINE_SYSTEM) return null
        val id = route?.engineValue?.toLongOrNull() ?: return defaultHttpTts
        return appDb.httpTTSDao.get(id) ?: defaultHttpTts
    }

    private fun defaultSystemRoute(): SpeechRoute {
        val route = ReadAloud.speechRoute
        if (route.engineType == SpeechRoute.ENGINE_SYSTEM && route.isConfigured) return route
        return SpeechRoute(
            engineType = SpeechRoute.ENGINE_SYSTEM,
            engineValue = ReadAloud.ttsEngine.orEmpty(),
            speakerName = "系统默认",
            source = SpeechRoute.SOURCE_AUTO
        )
    }

    private suspend fun synthesizeSystemSpeakFile(
        fileName: String,
        speakText: String,
        route: SpeechRoute
    ): Boolean {
        val file = createSpeakFile(fileName)
        if (file.exists() && file.length() > 0L) return true
        return runCatching {
            val engine = resolveSystemTtsEngine(route.engineValue)
            val initResult = CompletableDeferred<Int>()
            val tts = withContext(Main) {
                if (engine.isNullOrBlank()) {
                    TextToSpeech(this@HttpReadAloudService) { status ->
                        initResult.complete(status)
                    }
                } else {
                    TextToSpeech(this@HttpReadAloudService, { status ->
                        initResult.complete(status)
                    }, engine)
                }
            }
            try {
                if (withTimeout(20_000L) { initResult.await() } != TextToSpeech.SUCCESS) {
                    return@runCatching false
                }
                if (!AppConfig.ttsFlowSys) {
                    tts.setSpeechRate((AppConfig.ttsSpeechRate + 5) / 10f)
                }
                val utteranceId = "mixed_tts_$fileName"
                val done = CompletableDeferred<Boolean>()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        done.complete(true)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        done.complete(false)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        done.complete(false)
                    }
                })
                val params = Bundle()
                val result = tts.synthesizeToFile(speakText, params, file, utteranceId)
                if (result == TextToSpeech.ERROR) return@runCatching false
                withTimeout(120_000L) { done.await() } && file.exists() && file.length() > 0L
            } finally {
                withContext(Main) {
                    tts.stop()
                    tts.shutdown()
                }
            }
        }.onFailure {
            file.takeIf { it.exists() && it.length() <= 0L }?.delete()
            AppLog.put("系统 TTS 合成失败，使用静音占位\n${it.localizedMessage ?: it.javaClass.simpleName}", it)
        }.getOrDefault(false)
    }

    private fun resolveSystemTtsEngine(engineValue: String): String? {
        val value = engineValue.trim()
        if (value.isBlank()) return null
        return runCatching {
            JSONObject(value).optString("value").takeIf { it.isNotBlank() }
        }.getOrNull() ?: value
    }

    private fun md5SpeakFileName(
        content: String,
        textChapter: TextChapter? = this.textChapter,
        route: SpeechRoute? = null
    ): String {
        val routeKey = route?.takeIf { it.isConfigured }?.toJson().orEmpty()
        val engineKey = ReadAloud.httpTTS?.url ?: ReadAloud.ttsEngine.orEmpty()
        return MD5Utils.md5Encode16(textChapter.readAloudTitle()) + "_" +
                MD5Utils.md5Encode16("$engineKey-|-$routeKey-|-$speechRate-|-$content")
    }

    private fun TextChapter?.readAloudTitle(): String {
        return this?.chapter?.title?.takeIf { it.isNotBlank() }.orEmpty()
    }

    private fun createSilentSound(fileName: String) {
        val file = createSpeakFile(fileName)
        file.writeBytes(resources.openRawResource(R.raw.silent_sound).readBytes())
    }

    private fun hasSpeakFile(name: String): Boolean {
        return FileUtils.exist("${ttsFolderPath}$name.mp3")
    }

    private fun getSpeakFileAsMd5(name: String): File {
        return File("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String): File {
        return FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3")
    }

    private fun createSpeakFile(name: String, inputStream: InputStream) {
        FileUtils.createFileIfNotExist("${ttsFolderPath}$name.mp3").outputStream().use { out ->
            inputStream.use {
                it.copyTo(out)
            }
        }
    }

    /**
     * 移除缓存文件
     */
    private fun removeCacheFile() {
        val titleMd5 = MD5Utils.md5Encode16(textChapter.readAloudTitle())
        FileUtils.listDirsAndFiles(ttsFolderPath)?.forEach {
            val isSilentSound = it.length() == 2160L
            if ((!it.name.startsWith(titleMd5)
                        && System.currentTimeMillis() - it.lastModified() > 600000)
                || isSilentSound
            ) {
                FileUtils.delete(it.absolutePath)
            }
        }
    }


    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }
    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                postExoPlaybackPhase()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = contentList[nowSpeak].length
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..contentList[nowSpeak].length) {
                if (pageIndex + 1 < textChapter.pageSize
                    && readAloudNumber + i > textChapter.getReadLength(pageIndex + 1)
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + i.toInt())
                }
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        if (AppConfig.streamReadAloudAudio) {
            downloadAndPlayAudiosStream()
        } else {
            downloadAndPlayAudios()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
                postExoPlaybackPhase()
            }

            Player.STATE_BUFFERING -> {
                postExoPlaybackPhase("音频加载中")
            }

            Player.STATE_READY -> {
                // 准备好
                if (pause) return
                exoPlayer.play()
                postExoPlaybackPhase()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                // 结束
                playErrorNo = 0
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        updateNextPos()
        postExoPlaybackPhase()
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.put("朗读错误\n${contentList[nowSpeak]}", error)
        deleteCurrentSpeakFile()
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            postReadAloudPlaybackPhase(ReadAloudPlaybackState.PHASE_ERROR, message = error.localizedMessage ?: "")
            pauseReadAloud()
        } else {
            postReadAloudPlaybackPhase(ReadAloudPlaybackState.PHASE_BUFFERING, message = "重试当前音频")
            retryCurrentCue()
        }
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        super.onIsPlayingChanged(isPlaying)
        postExoPlaybackPhase()
        if (isPlaying) {
            upPlayPos()
        }
    }

    private fun retryCurrentCue() {
        playIndexJob?.cancel()
        downloadTask?.cancel()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        lifecycleScope.launch {
            delay((playErrorNo * 700L).coerceAtMost(3500L))
            if (pause || nowSpeak !in contentList.indices) return@launch
            syncReadAloudPositionToCue()
            upTtsProgress(readAloudNumber + 1)
            if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    private fun postExoPlaybackPhase(message: String = "") {
        val actualPlaying = exoPlayer.isPlaying
        val buffering = exoPlayer.playbackState == Player.STATE_BUFFERING ||
                (!pause && exoPlayer.playbackState == Player.STATE_READY && !actualPlaying)
        val phase = when {
            actualPlaying -> ReadAloudPlaybackState.PHASE_PLAYING
            pause -> ReadAloudPlaybackState.PHASE_PAUSED
            buffering -> ReadAloudPlaybackState.PHASE_BUFFERING
            exoPlayer.playbackState == Player.STATE_ENDED -> ReadAloudPlaybackState.PHASE_STOPPED
            else -> ReadAloudPlaybackState.PHASE_PREPARING
        }
        postReadAloudPlaybackPhase(
            phase = phase,
            message = message,
            playing = actualPlaying,
            buffering = buffering
        )
    }

    private fun deleteCurrentSpeakFile() {
        if (AppConfig.streamReadAloudAudio) {
            return
        }
        val mediaItem = exoPlayer.currentMediaItem ?: return
        val filePath = mediaItem.localConfiguration!!.uri.path!!
        File(filePath).delete()
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

}
