package io.legado.app.help.ai.scheduling

/**
 * 结构化任务失败码编码/渲染（移植 ArcReel `lib/task_failure.py`）。
 *
 * worker 层把失败原因编码成机器稳定的 `[code]` 或 `[code] {sorted-json}` 字符串存入
 * `NovelVideoJob.errorMessage` / `NovelVideoSegment.errorMessage`，而非 locale-locked 文本。
 *
 * UI 读取时调 [renderFailure] 按当前 App locale 渲染成中文文案；未识别格式（裸异常文本、
 * 历史行）原样透传，不丢失。
 *
 * 与 ArcReel 一致：code 字典稳定 + 正则锚定两端 + params 用 sorted JSON 保证序列化确定性。
 */
object TaskFailure {

    /** 失败码 → i18n key（key 解析在 [renderFailure] 内）。 */
    private val FAILURE_CODE_KEYS: Map<String, String> = mapOf(
        "provider_unsupported_media" to "task_fail_provider_unsupported_media",
        "restart_lost_image" to "task_fail_restart_lost_image",
        "restart_lost_audio" to "task_fail_restart_lost_audio",
        "restart_lost_no_job_id" to "task_fail_restart_lost_no_job_id",
        "restart_lost_resume_no_job_id" to "task_fail_restart_lost_resume_no_job_id",
        "resume_unsupported_provider" to "task_fail_resume_unsupported_provider",
        "resume_unsupported_capacity_zero" to "task_fail_resume_unsupported_capacity_zero",
        "resume_unsupported_detail" to "task_fail_resume_unsupported_detail",
        "resume_expired_detail" to "task_fail_resume_expired_detail"
    )

    /**
     * 结构化失败码正则：`[code]` 可选后接单个空格 + JSON object。
     *
     * 锚定首尾，避免历史 `[/restart_lost] 中文`（非 JSON 尾）和任意异常文本误匹配。
     */
    private val STRUCTURED_RE = Regex("^\\[(\\w+)](?:[ ](\\{.*}))?$", RegexOption.DOT_MATCHES_ALL)

    /** 已注册的失败码集合（用于 [encodeFailure] 校验）。 */
    val knownCodes: Set<String> get() = FAILURE_CODE_KEYS.keys

    /**
     * 编码失败码为存储字符串。
     *
     * - 无 params → `[code]`
     * - 有 params → `[code] {sorted-json}`（ensure_ascii=false，sort_keys=true）
     *
     * @throws IllegalArgumentException 未知 code（fail-fast，避免存入不可渲染的原因）
     */
    fun encodeFailure(code: String, params: Map<String, Any?>? = null): String {
        require(code in FAILURE_CODE_KEYS) { "unknown failure code: $code" }
        if (params.isNullOrEmpty()) return "[$code]"
        // 按 key 字母序拼接 JSON（与 ArcReel sort_keys=True 等价）。
        // 不用 JSONObject 避免 unit test 环境 stub 问题；手动转义保证确定性。
        val parts = params.entries
            .filter { it.value != null }
            .sortedBy { it.key }
            .joinToString(",") { (k, v) ->
                "\"" + escapeJson(k) + "\":" + jsonValue(v)
            }
        return "[$code] {$parts}"
    }

    /** JSON 字符串转义（仅处理常见特殊字符）。 */
    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** 值 → JSON 字面量（字符串加引号转义，其他类型直接 toString）。 */
    private fun jsonValue(v: Any?): String = when (v) {
        is String -> "\"" + escapeJson(v) + "\""
        is Boolean, is Int, is Long, is Double -> v.toString()
        else -> "\"" + escapeJson(v.toString()) + "\""
    }

    /**
     * 渲染存储的失败原因为当前 locale 的展示文案。
     *
     * - 识别的 `[code]` / `[code] {params}` → 按 [LanguageUtils] 当前 locale 渲染
     * - 其他（裸异常文本、历史行、malformed）→ 原样透传
     * - null/空 → 原样返回
     */
    fun renderFailure(errorMessage: String?): String? {
        if (errorMessage.isNullOrBlank()) return errorMessage
        val match = STRUCTURED_RE.matchEntire(errorMessage) ?: return errorMessage
        val code = match.groupValues[1]
        val key = FAILURE_CODE_KEYS[code] ?: return errorMessage
        val rawParams = match.groupValues[2]
        // 从 rawParams 提取简单字段值（避免 JSONObject stub 问题）。
        // 仅支持 renderByLocale 实际用到的字段：provider / detail。
        // 解析失败（非合法 JSON）原样透传，与 ArcReel json.loads ValueError 行为一致。
        val params: Map<String, String> = if (rawParams.isNotEmpty()) {
            extractSimpleParams(rawParams) ?: return errorMessage
        } else emptyMap()
        return renderByLocale(key, params)
    }

    /**
     * 从 JSON 字符串提取简单 `"key":"value"` 或 `"key":value` 对（不处理嵌套对象）。
     * @return null 表示非合法 JSON（原样透传）；空 map 表示合法但无字段。
     */
    private fun extractSimpleParams(json: String): Map<String, String>? {
        // 严格校验：必须是 { 开头 } 结尾，且整体只含 "key":value 对（逗号分隔）。
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return null
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return emptyMap()
        val result = mutableMapOf<String, String>()
        // 按逗号分割（简化处理，不嵌套对象/数组）
        val parts = inner.split(",")
        val pairRegex = Regex("\"(\\w+)\":(\"([^\"]*)\"|([^,}]+))")
        for (part in parts) {
            val m = pairRegex.matchEntire(part.trim()) ?: return null
            val k = m.groupValues[1]
            val v = m.groupValues[3].ifEmpty { m.groupValues[4].trim() }
            result[k] = v
        }
        return result
    }

    /** 按 locale 渲染文案（中文模板，其他 locale 暂用中文兜底）。 */
    private fun renderByLocale(key: String, params: Map<String, String>): String {
        return when (key) {
            "task_fail_provider_unsupported_media" ->
                "该 provider 不支持此 media 类型"
            "task_fail_restart_lost_image" ->
                "任务因进程重启丢失（图像），需重新生成"
            "task_fail_restart_lost_audio" ->
                "任务因进程重启丢失（音频），需重新生成"
            "task_fail_restart_lost_no_job_id" ->
                "任务因进程重启丢失：未持久化 provider 任务 ID，无法恢复"
            "task_fail_restart_lost_resume_no_job_id" ->
                "恢复路径失败：未持久化 provider 任务 ID"
            "task_fail_resume_unsupported_provider" ->
                "该 provider（${params["provider"] ?: "?"}）不支持 resume，任务无法恢复"
            "task_fail_resume_unsupported_capacity_zero" ->
                "该 provider 容量为 0，无法 resume"
            "task_fail_resume_unsupported_detail" ->
                "该 backend 未实现 resume_video：${params["detail"] ?: ""}"
            "task_fail_resume_expired_detail" ->
                "provider 端任务已过期或不存在：${params["detail"] ?: ""}"
            else -> key
        }
    }
}
