package io.legado.app.help.ai.asr

import com.script.rhino.RhinoScriptEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.Scriptable
import java.io.File

/**
 * 用户 JS 脚本 ASR 引擎（P3）。
 *
 * 用户提供的 JS 必须定义全局函数 `transcribe(audioPath, language)`，
 * 返回以下任一格式：
 *   1) `Array<{ start: Number(ms), end: Number(ms), text: String }>`
 *   2) 形如 `[[startMs, endMs, text], ...]` 的二维数组
 *   3) `JSON.stringify()` 后的上述任一格式字符串
 *
 * Rhino 运行（与书源 JS 复用），保证与 Legado 既有脚本生态一致。
 */
class JsAsrEngine(
    private val config: AsrConfig
) : AsrEngine {

    override suspend fun transcribe(audio: File, language: String): List<AsrSegment> =
        withContext(Dispatchers.IO) {
            require(config.script.isNotBlank()) { "JS script is empty" }
            val bindings: MutableMap<String, Any?> = HashMap()
            bindings["audioPath"] = audio.absolutePath
            bindings["language"] = language
            bindings["config"] = config
            val scope: Scriptable = RhinoScriptEngine.getRuntimeScope(bindings)
            val compiled = RhinoScriptEngine.compile(config.script)
            // 用户脚本应定义 transcribe(audioPath, language) -> segments
            val result = compiled.eval(scope)
            parseResult(result)
        }

    private fun parseResult(raw: Any?): List<AsrSegment> {
        return try {
            // 1) 字符串
            if (raw is String) return parseResult(JSONArray(raw))
            // 2) Rhino native array（NativeArray 在 Rhino 中）
            //    NativeJavaArray / NativeArray 都可转 JSONArray（已通过反射或显式 NativeArray 处理）
            if (raw is org.mozilla.javascript.NativeArray) {
                val arr = JSONArray()
                for (i in 0 until raw.length) {
                    arr.put(JSONObject.wrapOrNull(raw.get(i)))
                }
                return parseFromJsonArray(arr)
            }
            if (raw is JSONArray) {
                return parseFromJsonArray(raw)
            }
            if (raw is List<*>) {
                val arr = JSONArray()
                raw.forEach { arr.put(JSONObject.wrapOrNull(it)) }
                return parseFromJsonArray(arr)
            }
            // 3) 兜底：toString 解析
            parseResult(raw?.toString())
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private fun parseFromJsonArray(arr: JSONArray): List<AsrSegment> {
        val out = ArrayList<AsrSegment>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.opt(i) ?: continue
            val seg = when (item) {
                is JSONObject -> {
                    val start = item.optLong("start")
                    val end = item.optLong("end")
                    val text = item.optString("text", "").trim()
                    if (text.isNotEmpty() && end >= start) AsrSegment(start, end, text) else null
                }
                is JSONArray -> {
                    // [startMs, endMs, text]
                    if (item.length() >= 3) {
                        val start = item.optLong(0)
                        val end = item.optLong(1)
                        val text = item.optString(2, "").trim()
                        if (text.isNotEmpty() && end >= start) AsrSegment(start, end, text) else null
                    } else null
                }
                else -> null
            }
            if (seg != null) out.add(seg)
        }
        return out
    }

    private fun JSONObject.Companion.wrapOrNull(any: Any?): Any {
        if (any == null) return JSONObject.NULL
        return when (any) {
            is Number, is Boolean, is String -> any
            is JSONObject -> any
            is JSONArray -> any
            is Map<*, *> -> {
                val obj = JSONObject()
                any.forEach { (k, v) -> obj.put(k.toString(), wrapOrNull(v)) }
                obj
            }
            is List<*> -> {
                val arr = JSONArray()
                any.forEach { arr.put(wrapOrNull(it)) }
                arr
            }
            else -> any.toString()
        }
    }
}
