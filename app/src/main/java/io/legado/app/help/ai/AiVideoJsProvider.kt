package io.legado.app.help.ai

import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import io.legado.app.data.entities.BaseSource
import io.legado.app.help.CacheManager
import io.legado.app.help.http.CookieStore
import io.legado.app.help.source.getShareScope
import io.legado.app.ui.main.ai.AiVideoProviderConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * JS Provider：用户在 [AiVideoProviderConfig.script] 里写 JavaScript。
 *
 * 脚本需要：
 *  - 定义 `submit(prompt, params, provider)` -> String (taskId)
 *  - 定义 `poll(taskId, provider)` -> { status, progress, videoUrl, coverUrl, failReason, durationMs }
 *
 * 也支持直接在脚本顶层写 result：
 *  - `result` 为 submit 结果：字符串 taskId
 *  - `result` 为 poll 结果：带 status 字段的对象
 *
 * 全部以 Rhino 1.7.14 引擎执行，复用 BaseSource 提供 java/cache/cookie 工具。
 */
class AiVideoJsProvider(
    override val config: AiVideoProviderConfig
) : AiVideoProvider {

    private val source = AiVideoJsSource(config)

    override suspend fun submit(prompt: String, params: VideoGenerationParams): String {
        val script = config.script.trim()
        if (script.isBlank()) error("JS script is empty")
        val coroutineContext = currentCoroutineContext()
        val result = withTimeout(config.validTimeout()) {
            val bindings = buildScriptBindings { bindings ->
                bindings["java"] = source
                bindings["source"] = source
                bindings["cache"] = CacheManager
                bindings["cookie"] = CookieStore
                bindings["baseUrl"] = source.getKey()
                bindings["prompt"] = prompt
                bindings["params"] = params.toJson()
                bindings["provider"] = config
                bindings["result"] = ""
            }
            val sharedScope = source.getShareScope(coroutineContext)
            val scope = if (sharedScope == null) {
                RhinoScriptEngine.getRuntimeScope(bindings)
            } else {
                bindings.apply { prototype = sharedScope }
            }
            RhinoScriptEngine.eval(
                buildString {
                    append(script).append('\n')
                    append(
                        """
                        ;(function(){
                            if (typeof submit === 'function') return submit(prompt, params, provider);
                            if (typeof run === 'function') return run(prompt, params, provider);
                            if (typeof generate === 'function') return generate(prompt, params, provider);
                            if (typeof poll === 'function') return poll(result, provider);
                            if (typeof result !== 'undefined' && result !== '' && result != null) return String(result);
                            return null;
                        })();
                        """.trimIndent()
                    )
                },
                scope,
                coroutineContext
            )
        }
        val raw = when (result) {
            null, org.mozilla.javascript.Undefined.instance -> error("Submit script returned no task id")
            else -> result.toString()
        }
        if (raw.isBlank()) error("Submit script returned empty task id")
        return raw
    }

    override suspend fun poll(externalTaskId: String): VideoPollResult {
        val script = config.script.trim()
        if (script.isBlank()) error("JS script is empty")
        val coroutineContext = currentCoroutineContext()
        val result = withTimeout(config.validTimeout()) {
            val bindings = buildScriptBindings { bindings ->
                bindings["java"] = source
                bindings["source"] = source
                bindings["cache"] = CacheManager
                bindings["cookie"] = CookieStore
                bindings["baseUrl"] = source.getKey()
                bindings["taskId"] = externalTaskId
                bindings["provider"] = config
                bindings["result"] = externalTaskId
            }
            val sharedScope = source.getShareScope(coroutineContext)
            val scope = if (sharedScope == null) {
                RhinoScriptEngine.getRuntimeScope(bindings)
            } else {
                bindings.apply { prototype = sharedScope }
            }
            RhinoScriptEngine.eval(
                buildString {
                    append(script).append('\n')
                    append(
                        """
                        ;(function(){
                            if (typeof poll === 'function') return poll(taskId, provider);
                            if (typeof query === 'function') return query(taskId, provider);
                            if (typeof status === 'function') return status(taskId, provider);
                            if (typeof result !== 'undefined' && result != null) return result;
                            return null;
                        })();
                        """.trimIndent()
                    )
                },
                scope,
                coroutineContext
            )
        }
        return parsePollResult(result)
    }

    private fun parsePollResult(raw: Any?): VideoPollResult {
        if (raw == null || raw === org.mozilla.javascript.Undefined.instance) {
            error("Poll script returned no result")
        }
        // 优先处理 Rhino NativeObject
        val json: JSONObject? = when (raw) {
            is org.mozilla.javascript.NativeObject -> {
                val obj = JSONObject()
                raw.ids.forEach { id ->
                    val key = id.toString()
                    val v = raw.get(key, raw)
                    when {
                        v == null || v === org.mozilla.javascript.Undefined.instance -> obj.put(key, JSONObject.NULL)
                        v is Number -> obj.put(key, v)
                        v is Boolean -> obj.put(key, v)
                        else -> obj.put(key, v.toString())
                    }
                }
                obj
            }
            is JSONObject -> raw
            is Map<*, *> -> {
                val obj = JSONObject()
                raw.forEach { (k, v) ->
                    obj.put(k.toString(), v?.toString() ?: JSONObject.NULL)
                }
                obj
            }
            is String -> runCatching { JSONObject(raw) }.getOrNull()
            else -> runCatching { JSONObject(raw.toString()) }.getOrNull()
        } ?: error("Poll script returned unsupported type: ${raw.javaClass.simpleName}")
        val json2: JSONObject = json!!

        return VideoPollResult(
            status = VideoStatus.from(json2.optString("status").ifBlank { json2.optString("task_status") }),
            progress = json2.optInt("progress", -1).let { if (it < 0) {
                when (VideoStatus.from(json2.optString("status"))) {
                    VideoStatus.SUCCESS -> 100
                    VideoStatus.RUNNING -> 50
                    else -> 0
                }
            } else it.coerceIn(0, 100) },
            videoUrl = json2.optString("videoUrl").takeIf { it.isNotBlank() }
                ?: json2.optString("video_url").takeIf { it.isNotBlank() }
                ?: json2.optString("url").takeIf { it.isNotBlank() },
            coverUrl = json2.optString("coverUrl").takeIf { it.isNotBlank() }
                ?: json2.optString("cover_url").takeIf { it.isNotBlank() }
                ?: json2.optString("thumbnail").takeIf { it.isNotBlank() },
            durationMs = json2.optLong("durationMs", 0L).let { if (it == 0L) json2.optLong("duration_ms", 0L) else it },
            failReason = json2.optString("failReason").takeIf { it.isNotBlank() }
                ?: json2.optString("fail_reason").takeIf { it.isNotBlank() }
                ?: json2.optString("error").takeIf { it.isNotBlank() },
            raw = json2
        )
    }

    private fun VideoGenerationParams.toJson(): JSONObject = JSONObject().apply {
        put("prompt", prompt)
        put("negativePrompt", negativePrompt)
        if (firstFrame != null) put("firstFrame", firstFrame)
        if (lastFrame != null) put("lastFrame", lastFrame)
        if (width > 0) put("width", width)
        if (height > 0) put("height", height)
        if (durationSec > 0) put("durationSec", durationSec)
        if (seed >= 0) put("seed", seed)
        if (aspectRatio.isNotBlank()) put("aspectRatio", aspectRatio)
        if (extra.length() > 0) put("extra", extra)
    }

    private class AiVideoJsSource(
        private val provider: AiVideoProviderConfig
    ) : BaseSource {
        override var concurrentRate: String? = null
        override var loginUrl: String? = provider.loginUrl
        override var loginUi: String? = provider.loginUi
        override var header: String? = provider.headers
        override var enabledCookieJar: Boolean? = provider.enabledCookieJar
        override var jsLib: String? = provider.jsLib

        override fun getTag(): String = "AiVideoRule:${provider.id}:${provider.displayName()}"

        override fun getKey(): String = "ai_video_rule_${provider.id}"
    }
}
