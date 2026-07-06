package io.legado.app.help.ai

import com.google.gson.JsonSyntaxException
import com.google.gson.JsonParser
import io.legado.app.utils.GSON

/**
 * 移植自 director_ai `lib/services/screenplay_parser.dart`。
 *
 * 4 策略 JSON 提取：
 * 1. 找包含 `"task_id"` 的 JSON（最可靠）
 * 2. 找包含 `"scenes"` 的 JSON
 * 3. 找包含 `"script_title"` 的 JSON（legacy 字段）
 * 4. 提取最后一个完整 `{...}` 对象
 *
 * 预处理：
 * - 去除 ```` ```json ```` / ```` ``` ```` markdown 围栏
 * - 替换中文引号 `“ ” ‘ ’` 为 ASCII 引号
 * - 替换中文冒号 `：` 为 ASCII 冒号（仅当出现在 `"key"` 后才视为字段分隔；粗暴兜底全替换）
 */
object NovelVideoScreenplayParser {

    /**
     * @throws JsonSyntaxException 解析或字段校验失败
     */
    fun parse(rawLlmResponse: String): ScreenplayDraft {
        val cleaned = cleanResponse(rawLlmResponse)
        val jsonText = extractJson(cleaned)
            ?: throw JsonSyntaxException("未找到合法 JSON：${cleaned.take(200)}")
        val draft = parseDraft(jsonText)
        validate(draft)
        return draft
    }

    fun parseScreenplay(rawLlmResponse: String): Screenplay =
        Screenplay.fromDraft(parse(rawLlmResponse))

    /**
     * 清洗 LLM 返回：去围栏、统一引号、统一冒号。
     */
    fun cleanResponse(raw: String): String {
        var text = raw.trim()
        // 去 markdown 围栏 ```json ... ``` 或 ``` ... ```
        if (text.startsWith("```")) {
            val firstNewline = text.indexOf('\n')
            if (firstNewline > 0) {
                val lastFence = text.lastIndexOf("```")
                if (lastFence > firstNewline) {
                    text = text.substring(firstNewline + 1, lastFence).trim()
                }
            }
        }
        // 中文引号 → ASCII 引号
        text = text
            .replace('“', '"')
            .replace('”', '"')
            .replace('‘', '\'')
            .replace('’', '\'')
        // 中文冒号 → ASCII 冒号（粗暴兜底）
        text = text.replace('：', ':')
        // 中文逗号 → ASCII 逗号（LLM 常把 JSON 分隔符写成中文逗号）
        text = text.replace('，', ',')
        return text
    }

    /**
     * 4 策略提取 JSON 文本片段。
     */
    fun extractJson(text: String): String? {
        // 策略1：含 "task_id"
        if (text.contains("\"task_id\"")) {
            extractJsonContaining(text, "\"task_id\"")?.let { return it }
        }
        // 策略2：含 "scenes"
        if (text.contains("\"scenes\"")) {
            extractJsonContaining(text, "\"scenes\"")?.let { return it }
        }
        // 策略3：含 "script_title"（legacy）
        if (text.contains("\"script_title\"")) {
            extractJsonContaining(text, "\"script_title\"")?.let { return it }
        }
        // 策略4：最后一个完整 JSON 对象
        return extractLastCompleteJson(text)
    }

    /**
     * 找到 [keyword] 在文本中最后一次出现的位置，向前回溯找到最近的 `{`，
     * 然后向前做花括号配对直到回到 0。返回该子串；解析失败返回 null。
     */
    private fun extractJsonContaining(text: String, keyword: String): String? {
        val keywordIndex = text.lastIndexOf(keyword)
        if (keywordIndex < 0) return null
        // 向前找最近的 {
        var openBrace = -1
        for (i in keywordIndex downTo 0) {
            if (text[i] == '{') {
                openBrace = i
                break
            }
        }
        if (openBrace < 0) return null
        // 向后花括号配对
        var depth = 0
        var inString = false
        var escape = false
        for (i in openBrace until text.length) {
            val c = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return text.substring(openBrace, i + 1)
                    }
                }
            }
        }
        return null
    }

    /**
     * 取文本中最后一个 `{`，向前做花括号配对；并用 JsonParser 验证。
     */
    private fun extractLastCompleteJson(text: String): String? {
        val lastOpen = text.lastIndexOf('{')
        if (lastOpen < 0 || text.length - lastOpen < 10) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in lastOpen until text.length) {
            val c = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\') {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            when (c) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val candidate = text.substring(lastOpen, i + 1)
                        return runCatching {
                            JsonParser.parseString(candidate)
                            candidate
                        }.getOrNull()
                    }
                }
            }
        }
        return null
    }

    private fun parseDraft(json: String): ScreenplayDraft =
        // 复用 ScreenplayDraft.fromJson 统一校验逻辑（含 scenes 非空检查），
        // 避免两套解析路径行为不一致
        ScreenplayDraft.fromJson(json)

    /**
     * 字段校验，移植自 director_ai `_validateScreenplay`。
     */
    fun validate(draft: ScreenplayDraft) {
        require(draft.taskId.isNotBlank() || draft.title.isNotBlank() || draft.scriptTitle.isNotBlank()) {
            "剧本缺少 task_id/title/script_title"
        }
        require(draft.scenes.isNotEmpty()) { "剧本没有场景" }
        draft.scenes.forEachIndexed { idx, scene ->
            require(scene.narration.isNotBlank()) { "场景 ${scene.sceneId.takeIf { it > 0 } ?: idx + 1} 缺少 narration" }
            require(scene.imagePrompt.isNotBlank()) { "场景 ${scene.sceneId.takeIf { it > 0 } ?: idx + 1} 缺少 image_prompt" }
            require(scene.videoPrompt.isNotBlank()) { "场景 ${scene.sceneId.takeIf { it > 0 } ?: idx + 1} 缺少 video_prompt" }
        }
    }
}
