package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig

object AiPromptRewriter {

    enum class Modality(val key: String) {
        IMAGE("image"),
        VIDEO("video"),
        AUDIO("audio"),
        STORY("story"),
        TEXT_SANITIZE("text_sanitize")
    }

    data class RewriteContext(
        val bookName: String = "",
        val bookAuthor: String = "",
        val chapterTitle: String = "",
        val selectedText: String = "",
        val characterDescriptions: String = "",
        val stylePreference: String = ""
    )

    suspend fun rewrite(
        prompt: String,
        modality: Modality,
        context: RewriteContext = RewriteContext()
    ): String {
        val systemPrompt = buildSystemPrompt(modality, context)
        val userContent = buildUserContent(prompt, context)
        val result = AiChatService.chatSimple(systemPrompt, userContent)
        return cleanResult(result)
    }

    fun generateProgressHint(modality: Modality, genre: String? = null): String {
        val base = when (modality) {
            Modality.VIDEO -> "正在编织光影..."
            Modality.IMAGE -> "正在描绘画面..."
            Modality.AUDIO -> "正在谱写旋律..."
            Modality.STORY -> "正在编排分镜..."
            Modality.TEXT_SANITIZE -> "正在净化文本..."
        }
        val genreHint = genre?.let { when {
            it.contains("武侠") -> "剑气纵横，快意恩仇"
            it.contains("科幻") -> "星际穿越，未来已来"
            it.contains("言情") -> "情丝万缕，心动瞬间"
            it.contains("悬疑") -> "迷雾重重，真相待揭"
            else -> null
        } }
        return if (genreHint != null) "$base $genreHint" else base
    }

    /**
     * Convenience overload accepting a raw modality key (e.g. "video", "audio")
     * as stored on [io.legado.app.data.entities.AiGenTask]. Unknown keys fall
     * back to [Modality.IMAGE].
     */
    fun generateProgressHint(modality: String, genre: String? = null): String {
        val resolved = Modality.values().firstOrNull { it.key == modality } ?: Modality.IMAGE
        return generateProgressHint(resolved, genre)
    }

    fun applyWeightSyntax(prompt: String, weights: Map<String, Double>): String {
        var result = prompt
        for ((keyword, weight) in weights) {
            result = result.replace(keyword, "($keyword:${String.format("%.1f", weight)})")
        }
        return result
    }

    fun physicalRulesSuffix(modality: Modality): String {
        return when (modality) {
            Modality.VIDEO -> " stable liquid physics, no distortion, consistent object permanence, physically plausible motion"
            else -> ""
        }
    }

    private fun buildSystemPrompt(modality: Modality, context: RewriteContext): String {
        val modalityInstruction = when (modality) {
            Modality.IMAGE -> """
你是一个专业的 AI 图像提示词优化专家。请将用户的描述优化为适合 AI 图像生成的英文提示词。
要求：
1. 输出纯英文提示词，用逗号分隔
2. 包含主体、场景、光影、风格、画质等细节
3. 添加适当的风格修饰词（如 masterpiece, best quality, highly detailed）
4. 保持原意，不添加用户未提及的内容
5. 只输出优化后的提示词，不要输出其他内容
            """.trimIndent()
            Modality.VIDEO -> """
你是一个专业的 AI 视频提示词优化专家。请将用户的描述优化为适合 AI 视频生成的英文提示词。
要求：
1. 输出纯英文提示词，用逗号分隔
2. 包含场景描述、镜头运动、光影变化、时间流逝等动态元素
3. 添加物理规则后缀确保画面稳定
4. 保持原意，不添加用户未提及的内容
5. 只输出优化后的提示词
            """.trimIndent()
            Modality.AUDIO -> """
你是一个专业的 AI 音频生成提示词优化专家。请将用户的描述优化为适合 AI 音乐/音效生成的英文提示词。
要求：
1. 输出纯英文描述
2. 包含音乐风格、乐器、节奏、情绪等细节
3. 如有指定，包含 BPM、调性等技术参数
4. 只输出优化后的描述
            """.trimIndent()
            Modality.STORY -> """
你是一个专业的影视分镜提示词优化专家。请将用户的描述优化为适合 AI 视频分镜的英文视觉提示词。
要求：
1. 输出纯英文提示词
2. 包含场景、人物外观、动作、镜头角度、光影
3. 保持角色外观描述的一致性
4. 每个场景的视觉描述应独立且完整
5. 只输出优化后的提示词
            """.trimIndent()
            Modality.TEXT_SANITIZE -> "" // No prompt rewriting for text sanitization
        }

        val contextInfo = buildString {
            if (context.bookName.isNotBlank()) append("书名：${context.bookName}\n")
            if (context.chapterTitle.isNotBlank()) append("章节：${context.chapterTitle}\n")
            if (context.characterDescriptions.isNotBlank()) append("角色描述：${context.characterDescriptions}\n")
            if (context.stylePreference.isNotBlank()) append("风格偏好：${context.stylePreference}\n")
        }

        return modalityInstruction + (if (contextInfo.isNotBlank()) "\n\n上下文信息：\n$contextInfo" else "")
    }

    private fun buildUserContent(prompt: String, context: RewriteContext): String {
        return buildString {
            append(prompt)
            if (context.selectedText.isNotBlank() && context.selectedText != prompt) {
                append("\n\n参考文本：${context.selectedText}")
            }
        }
    }

    private fun cleanResult(result: String): String {
        // Remove markdown code blocks if present
        val codeBlockRegex = Regex("""```(?:english|en)?\s*\n([\s\S]*?)```""")
        codeBlockRegex.find(result)?.let { return it.groupValues[1].trim() }
        // Remove quotes
        return result.trim().trim('"').trim('\'')
    }
}
