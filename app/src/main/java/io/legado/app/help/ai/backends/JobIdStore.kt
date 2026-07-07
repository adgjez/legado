package io.legado.app.help.ai.backends

import io.legado.app.constant.AppLog
import splitties.init.appCtx
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * jobId 持久化（移植 ArcReel `ProviderJobIdPersistenceMixin`，最小实现）。
 *
 * [VideoBackend.resumeVideo] 用：记录某次生成请求的 taskId + 请求摘要，
 * 便于进程被杀或取消后恢复轮询。
 *
 * 存储：`filesDir/ai_backend_jobids.json`，格式 `{ "providerId:taskId": { ... } }`。
 * 读写失败仅记日志，绝不抛——持久化是 best-effort，不得阻塞生成主流程。
 */
object JobIdStore {

    private const val FILE_NAME = "ai_backend_jobids.json"

    private data class Entry(
        val providerId: String,
        val taskId: String,
        val model: String,
        val promptDigest: String,
        val savedAt: Long
    )

    private val storeFile: File by lazy { File(appCtx.filesDir, FILE_NAME) }
    private val cache: MutableMap<String, Entry> by lazy { load() }

    fun save(providerId: String, taskId: String, model: String, promptDigest: String) {
        cache[key(providerId, taskId)] =
            Entry(providerId, taskId, model, promptDigest, System.currentTimeMillis())
        persist()
    }

    fun load(providerId: String, taskId: String): Boolean =
        cache.containsKey(key(providerId, taskId))

    fun clear(providerId: String, taskId: String) {
        cache.remove(key(providerId, taskId))
        persist()
    }

    private fun key(providerId: String, taskId: String) = "$providerId:$taskId"

    private fun load(): MutableMap<String, Entry> {
        val map = mutableMapOf<String, Entry>()
        if (!storeFile.exists()) return map
        runCatching {
            val root = JsonParser.parseString(storeFile.readText()).takeIf { it.isJsonObject }?.asJsonObject
                ?: return@runCatching
            root.entrySet().forEach { (k, v) ->
                // 单条 entry 解析失败不影响其余 entry（savedAt 若为非数字 Primitive 由内层 runCatching 兜底）
                val obj = v.takeIf { it.isJsonObject }?.asJsonObject
                if (obj != null) {
                    map[k] = runCatching {
                        Entry(
                            obj.get("providerId")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                            obj.get("taskId")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                            obj.get("model")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                            obj.get("promptDigest")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                            runCatching { obj.get("savedAt")?.takeIf { !it.isJsonNull }?.asLong }.getOrNull() ?: 0L
                        )
                    }.getOrElse {
                        AppLog.put("JobIdStore 加载 entry 失败（key=$k）：${it.message}")
                        return@forEach
                    }
                }
            }
        }.onFailure { AppLog.put("JobIdStore 加载失败：${it.message}") }
        return map
    }

    private fun persist() {
        runCatching {
            val root = JsonObject()
            cache.forEach { (k, e) ->
                root.add(k, JsonObject().apply {
                    addProperty("providerId", e.providerId)
                    addProperty("taskId", e.taskId)
                    addProperty("model", e.model)
                    addProperty("promptDigest", e.promptDigest)
                    addProperty("savedAt", e.savedAt)
                })
            }
            storeFile.writeText(root.toString())
        }.onFailure { AppLog.put("JobIdStore 持久化失败：${it.message}") }
    }
}
