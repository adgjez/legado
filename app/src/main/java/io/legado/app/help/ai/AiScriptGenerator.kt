package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import org.json.JSONObject
import org.mozilla.javascript.Context

object AiScriptGenerator {

    data class ScriptValidationResult(
        val valid: Boolean,
        val errors: List<String> = emptyList(),
        val warnings: List<String> = emptyList()
    )

    data class ScriptTestResult(
        val success: Boolean,
        val responseSnippet: String = "",
        val error: String? = null
    )

    data class GenerationResult(
        val script: String,
        val success: Boolean,
        val error: String? = null
    )

    suspend fun generateFromUrl(url: String, modality: String, providerName: String): GenerationResult {
        return runCatching {
            val response = okHttpClient.newCallResponse { url(url) }
            response.use {
                if (!it.isSuccessful) error("Failed to fetch documentation: ${it.code} ${it.message}")
                val docText = it.body.string()
                generateFromDoc(docText, modality, providerName)
            }
        }.getOrElse { e ->
            GenerationResult("", false, e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun generateFromDoc(docText: String, modality: String, providerName: String): GenerationResult {
        return runCatching {
            val systemPrompt = buildSystemPrompt(modality)
            val response = AiChatService.chatSimple(systemPrompt, docText, null)
            val script = extractJsCode(response)
            if (script.isBlank()) error("AI did not generate valid JS code")
            GenerationResult(script, true, null)
        }.getOrElse { e ->
            GenerationResult("", false, e.message ?: e.javaClass.simpleName)
        }
    }

    fun validateScript(script: String, modality: String): ScriptValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val requiredFunctions = when (modality) {
            "image" -> listOf("generate", "run")
            else -> listOf("submit", "queryStatus", "download")
        }

        for (funcName in requiredFunctions) {
            if (!Regex("""function\s+$funcName\s*\(""").containsMatchIn(script)) {
                errors.add("Missing required function: $funcName()")
            }
        }

        // Syntax check using Rhino context (compile without executing)
        runCatching {
            val cx = Context.enter()
            try {
                val scope = cx.initStandardObjects()
                cx.compileString(script, "ai_script_validation", 1, null)
            } finally {
                Context.exit()
            }
        }.onFailure { e ->
            errors.add("Syntax error: ${e.message}")
        }

        if (!script.contains("try") && !script.contains("catch")) {
            warnings.add("Script lacks try/catch error handling")
        }
        if (!script.contains("JSON.parse") && !script.contains("JSON.stringify")) {
            warnings.add("Script may need JSON parsing for API responses")
        }

        return ScriptValidationResult(errors.isEmpty(), errors, warnings)
    }

    suspend fun testScript(script: String, providerConfigJson: JSONObject): ScriptTestResult {
        return runCatching {
            val cx = Context.enter()
            try {
                val scope = cx.initStandardObjects()
                cx.evaluateString(scope, script, "ai_script_test", 1, null)
                // Try to call submit function
                val submitFn = scope.get("submit", scope)
                if (submitFn is org.mozilla.javascript.Function) {
                    val result = submitFn.call(cx, scope, scope, arrayOf("test prompt", providerConfigJson))
                    val snippet = result?.toString()?.take(500) ?: "(no response)"
                    ScriptTestResult(true, snippet, null)
                } else {
                    ScriptTestResult(false, "", "submit function not found in script")
                }
            } finally {
                Context.exit()
            }
        }.getOrElse { e ->
            AppLog.put("AI script test failed", e)
            ScriptTestResult(false, "", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun buildSystemPrompt(modality: String): String {
        val interfaceSpec = when (modality) {
            "image" -> """
                // 必需函数：生成图片
                function generate(prompt, provider) {
                  // 发送 HTTP 请求到 provider.baseUrl
                  // 返回图片 URL 或 base64 字符串
                }
            """.trimIndent()
            else -> """
                // 必需函数：提交生成任务
                function submit(prompt, provider, inputImage, tailImage, referenceImage, params) {
                  // 发送 HTTP 请求到 provider.baseUrl + submitEndpoint
                  // 返回 { remoteTaskId, status }
                }

                // 必需函数：查询任务状态
                function queryStatus(remoteTaskId, provider) {
                  // 返回 { status, progress, downloadUrl, previewUrl }
                }

                // 必需函数：下载产物
                function download(remoteTaskId, provider) {
                  // 返回 { filePath } 或 { error }
                }
            """.trimIndent()
        }

        return """
你是一个 JavaScript 脚本生成专家。请根据用户提供的 API 文档，生成一个符合以下接口规范的 JS 脚本：

$interfaceSpec

可用 API：
- java.connect(url) / java.post(url, body, headers)  // 网络请求
- java.ajax(url) // GET 请求
- JSON.parse / JSON.stringify  // JSON 处理

请根据以下 API 文档生成脚本。
要求：
1. 严格遵循上述函数签名。
2. 正确处理 API 的认证（Bearer token / API Key）。
3. 归一化 API 返回的字段差异（url/video_url/download_url/output 等）。
4. 包含错误处理与超时处理。
5. 添加中文注释说明每步逻辑。
6. 只输出 JS 代码，不要输出其他内容。用 ```javascript 包裹。
        """.trimIndent()
    }

    private fun extractJsCode(response: String): String {
        val codeBlockRegex = Regex("""```(?:javascript|js)?\s*\n([\s\S]*?)```""")
        codeBlockRegex.find(response)?.let { return it.groupValues[1].trim() }
        val funcStart = response.indexOf("function ")
        if (funcStart >= 0) return response.substring(funcStart).trim()
        return response.trim()
    }
}
