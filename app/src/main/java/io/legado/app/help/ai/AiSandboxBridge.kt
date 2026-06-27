package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import splitties.init.appCtx
import java.io.File
import android.util.Base64

object AiSandboxBridge {

    private val allowedBaseUrls = mutableSetOf<String>()

    fun setAllowedBaseUrl(url: String) {
        allowedBaseUrls.add(url)
    }

    fun clearAllowedUrls() {
        allowedBaseUrls.clear()
    }

    fun readImageAsBase64(path: String): String? {
        return runCatching {
            val file = resolveSafePath(path) ?: error("Path not allowed: $path")
            Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        }.getOrElse { e ->
            AppLog.put("Sandbox: readImage failed", e)
            null
        }
    }

    suspend fun httpGet(url: String): String? {
        return runCatching {
            validateUrl(url)
            okHttpClient.newCallResponse {
                url(url)
            }.use { it.body.string() }
        }.getOrElse { e ->
            AppLog.put("Sandbox: httpGet failed", e)
            null
        }
    }

    suspend fun httpPost(url: String, body: String, headers: Map<String, String>?): String? {
        return runCatching {
            validateUrl(url)
            val jsonBody = body.toRequestBody("application/json".toMediaType())
            okHttpClient.newCallResponse {
                url(url)
                post(jsonBody)
                headers?.forEach { (k, v) -> addHeader(k, v) }
            }.use { it.body.string() }
        }.getOrElse { e ->
            AppLog.put("Sandbox: httpPost failed", e)
            null
        }
    }

    private fun validateUrl(url: String) {
        val allowed = allowedBaseUrls.any { url.startsWith(it) }
        if (!allowed) error("URL not in whitelist: $url")
    }

    private fun resolveSafePath(path: String): File? {
        val file = File(path)
        val canonical = file.canonicalPath
        // Only allow files within the app's ai_* directories
        val allowedDirs = listOf("ai_images", "ai_videos", "ai_audios")
        val appDir = appCtx.filesDir.canonicalPath
        return if (allowedDirs.any { canonical.startsWith("$appDir/$it/") }) file else null
    }

    /**
     * Restricts which Java classes are reachable from sandboxed JavaScript.
     *
     * Dangerous classes (Runtime, ProcessBuilder, System, file IO, reflection,
     * scripting, android framework, ...) are hidden so a malicious or buggy
     * script cannot escape the sandbox. Only a small, explicit allow-list of
     * safe classes (String, Math, java.util.*, org.json.*, ...) is exposed.
     */
    class AiSandboxClassShutter : ClassShutter {

        private val blockedExact = setOf(
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.System",
            "java.lang.ClassLoader",
            "java.lang.Thread",
            "java.io.File",
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.RandomAccessFile",
            "java.io.FileReader",
            "java.io.FileWriter"
        )

        private val blockedPrefixes = arrayOf(
            "java.lang.reflect.",
            "javax.script.",
            "java.io.",
            "java.net.",
            "android.",
            "sun.",
            "com.android.",
            "dalvik."
        )

        private val allowedExact = setOf(
            "java.lang.String",
            "java.lang.Math",
            "java.lang.Number",
            "java.lang.Object",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Character",
            "java.lang.StringBuilder",
            "java.lang.StringBuffer"
        )

        private val allowedPrefixes = arrayOf(
            "java.util.",
            "org.json."
        )

        override fun visibleToScripts(className: String): Boolean {
            // 1. Hard block list always wins.
            if (className in blockedExact) return false
            if (blockedPrefixes.any { className.startsWith(it) }) return false
            // 2. Explicit allow list.
            if (className in allowedExact) return true
            if (allowedPrefixes.any { className.startsWith(it) }) return true
            // 3. Everything else is hidden by default.
            return false
        }
    }

    /**
     * Installs the sandbox [ClassShutter] on the given Rhino [Context].
     * Must be called before any Java class is touched inside the context.
     */
    fun applyTo(engine: Context) {
        engine.setClassShutter(AiSandboxClassShutter())
    }

    /**
     * Evaluates [script] inside a fresh, sandboxed Rhino context.
     *
     * The [ClassShutter] is applied to block dangerous classes, [bindings] are
     * exposed on the global scope, and the script is evaluated. Returns the
     * evaluation result, or null on failure (errors are logged).
     */
    fun safeEval(script: String, bindings: Map<String, Any?>): Any? {
        val cx = Context.enter()
        return try {
            applyTo(cx)
            val scope = cx.initStandardObjects()
            bindings.forEach { (key, value) ->
                if (value != null) {
                    val jsValue = Context.javaToJS(value, scope)
                    ScriptableObject.putProperty(scope, key, jsValue)
                }
            }
            cx.evaluateString(scope, script, "AiSandbox", 1, null)
        } catch (e: Exception) {
            AppLog.put("Sandbox: safeEval failed", e)
            null
        } finally {
            Context.exit()
        }
    }
}
