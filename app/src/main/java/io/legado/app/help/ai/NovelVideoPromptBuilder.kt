package io.legado.app.help.ai

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.NovelVideoSegment
import io.legado.app.help.config.AppConfig

/**
 * 小说→视频流水线所有提示词的构造、净化、改写。
 *
 * 移植自 director_ai `lib/services/api_service.dart`：
 * - [buildDramaSystemPrompt]：剧本生成系统提示词（基于 `_dramaSystemPrompt`）
 * - [buildCombinedViewPrompt]：角色三视图组合提示词（基于 `_buildCombinedViewPrompt`）
 * - [extractMainCharacters]：从场景描述提取主要角色（基于 `_extractMainCharacters`）
 * - [sanitizeVideoPrompt] / [sanitizeImagePrompt]：提示词敏感词替换
 * - [rewriteVideoPromptForSafety]：调用 LLM 改写不安全提示词
 */
object NovelVideoPromptBuilder {

    /**
     * 剧本生成系统提示词，移植自 director_ai `_dramaSystemPrompt`。
     * 动态注入 [params] 的 sceneCount、stylePrompt 等。
     */
    fun buildDramaSystemPrompt(params: NovelVideoParams): String {
        val sceneCount = params.sceneCountPerChapter.coerceIn(3, 12)
        return """
你是一位专业的漫画风格短视频剧本编剧。你的任务是把小说章节改写成一个 ${sceneCount} 场景、每场 5-${params.sceneDurationSeconds} 秒、总长约 ${sceneCount * params.sceneDurationSeconds} 秒的剧本。

【创作准则】
1. 场景数：严格输出 $sceneCount 个场景（不多不少）。
2. 情绪：每个场景都要有正向的情绪钩子，避免悲剧、暴力、恐怖、犯罪、复仇。
3. 题材：校园 / 友情 / 青春 / 治愈 / 日常 / 甜系恋爱 优先；避免打斗、武器、血腥。
4. 风格：所有 image_prompt 必须以 "${params.stylePrompt.ifBlank { "anime style, manga art, 2D animation" }}" 开头。
5. 视频提示词：video_prompt 必须是 "镜头类型 + 镜头运动 + 动作" 的英文短语，避免 lightning/attack/explosion 等敏感词，可用 gentle/soft/calm/peaceful 替代。
6. 旁白 narration 用中文，富有感染力，每段不超过 60 字。
7. character_description 用英文，保持人物在多场景间的一致性（发型、衣着、肤色等关键特征）。

【严格 JSON 输出，禁止任何解释或 markdown】
{
  "task_id": "唯一ID",
  "title": "剧本标题（中文）",
  "genre": "题材类型",
  "estimated_duration_seconds": ${sceneCount * params.sceneDurationSeconds},
  "emotional_arc": ["情绪1", "情绪2", "..."],
  "scenes": [
    {
      "scene_id": 1,
      "narration": "中文旁白",
      "mood": "情绪标签",
      "emotional_hook": "情绪钩子说明",
      "image_prompt": "英文画面描述，必须以画风前缀开头",
      "video_prompt": "英文镜头运动描述",
      "character_description": "英文人物特征"
    }
  ]
}

【绝对规则】
- 严格按 JSON Schema 输出，不要包裹 markdown 代码块，不要在 JSON 外加任何文字。
- 字段名一律用 snake_case（task_id/scene_id/image_prompt/video_prompt/character_description/emotional_arc/estimated_duration_seconds）。
- 双引号一律用 ASCII "，不要用中文“”，不要在键或值结尾加多余逗号。
- 角色一致性：同一人物在不同场景的 character_description 必须保持发型/衣着/肤色一致。
- 镜头多样性：不同场景的 video_prompt 应使用不同镜头类型（特写/中景/远景/俯视/仰视）。
- 严禁出现：lightning、fight、explosion、attack、weapon、blood、kill、death、war 等敏感词。
        """.trimIndent()
    }

    /**
     * 构造剧本生成的 user prompt，注入书名、章节标题、章节正文摘要。
     * 支持 [previousFeedback] 用于"重新生成"。
     */
    fun buildDramaUserPrompt(
        book: Book,
        chapter: BookChapter,
        chapterContent: String,
        previousFeedback: String? = null
    ): String {
        val contentPreview = if (chapterContent.length > 8000) {
            chapterContent.substring(0, 8000) + "\n...（章节过长，已截断）"
        } else {
            chapterContent
        }
        val sb = StringBuilder()
        sb.append("书名：").append(book.name).append('\n')
        sb.append("作者：").append(book.author).append('\n')
        sb.append("章节标题：").append(chapter.title).append('\n')
        if (previousFeedback.isNullOrBlank()) {
            sb.append("请基于以下章节正文生成漫画风格剧本。\n\n")
        } else {
            sb.append("上次生成的剧本存在问题，请按以下反馈重新生成：\n")
            sb.append("反馈：").append(previousFeedback).append("\n\n")
            sb.append("章节正文：\n")
        }
        sb.append(contentPreview)
        return sb.toString()
    }

    /**
     * 角色三视图组合提示词，移植自 director_ai `_buildCombinedViewPrompt`。
     */
    fun buildCombinedViewPrompt(description: String): String {
        val baseDesc = description.ifBlank { "A character in anime/manga style" }
        return """
Character turnaround sheet with three views side by side:
LEFT: Front view (facing forward)
CENTER: Side view (profile, facing right)
RIGHT: Back view (showing the back)

Character: $baseDesc

Layout: Three full body shots arranged horizontally in a single image
Style: anime/manga art style, clean line art, flat colors, professional character design sheet, character reference sheet
Quality: high quality, detailed, 4k, consistent proportions across all views
Background: plain white or light gray background
Composition: all three views same size, equal spacing, full body visible, neutral standing pose, T-pose or A-pose preferred
        """.trimIndent()
    }

    /**
     * 场景图片生成提示词，自动加上画风前缀。
     */
    fun buildSceneImagePrompt(segment: NovelVideoSegment, stylePrompt: String): String {
        val prefix = stylePrompt.ifBlank { "anime style, manga art, 2D animation" }
        val prompt = segment.imagePrompt.trim()
        return if (prompt.startsWith(prefix, ignoreCase = true)) {
            prompt
        } else {
            "$prefix, $prompt"
        }
    }

    /**
     * 视频生成提示词，附加场景描述与角色描述。
     */
    fun buildSceneVideoPrompt(segment: NovelVideoSegment): String {
        val sb = StringBuilder()
        sb.append(segment.videoPrompt.trim())
        if (segment.characterDescription.isNotBlank()) {
            sb.append(". Character: ").append(segment.characterDescription)
        }
        if (segment.narration.isNotBlank()) {
            sb.append(". Atmosphere: gentle, peaceful, soft lighting.")
        }
        return sb.toString()
    }

    // ============================================================
    // 内容安全：敏感词替换 + LLM 改写
    // ============================================================

    private val VIDEO_SENSITIVE_WORDS = listOf(
        "lightning", "fight", "explosion", "attack", "intense", "fierce",
        "battle", "weapon", "blood", "kill", "death", "war",
        "glowing", "energy", "powerful", "dramatic", "trembling", "gripping"
    )

    private val VIDEO_SAFE_REPLACEMENTS = mapOf(
        "lightning" to "gentle flash",
        "fight" to "soft interaction",
        "explosion" to "burst of light",
        "attack" to "approach",
        "intense" to "soft",
        "fierce" to "calm",
        "battle" to "encounter",
        "weapon" to "object",
        "blood" to "red mark",
        "kill" to "defeat",
        "death" to "stillness",
        "war" to "gathering",
        "glowing" to "bright",
        "energy" to "atmosphere",
        "powerful" to "beautiful",
        "dramatic" to "peaceful",
        "trembling" to "gentle",
        "gripping" to "holding"
    )

    /**
     * 替换敏感词为安全替代词，大小写不敏感。
     */
    fun sanitizeVideoPrompt(prompt: String): String {
        var sanitized = prompt
        VIDEO_SENSITIVE_WORDS.forEach { w ->
            val replacement = VIDEO_SAFE_REPLACEMENTS[w] ?: return@forEach
            sanitized = sanitized.replace(
                Regex("(?i)\\b${Regex.escape(w)}\\b"),
                replacement
            )
        }
        return sanitized
    }

    /** 图片提示词的净化与视频提示词同策略。 */
    fun sanitizeImagePrompt(prompt: String): String = sanitizeVideoPrompt(prompt)

    /**
     * 用 LLM 改写提示词为安全版本。失败时回退到 [sanitizeVideoPrompt]。
     */
    suspend fun rewriteVideoPromptForSafety(prompt: String): String {
        val sys = """
你是视频提示词安全审查员。把以下英文视频提示词改写为内容安全版本，
保留核心动作和场景，但替换所有可能触发审核的词汇（暴力/血腥/武器/战争等）。
只输出改写后的英文提示词，不要解释，不要加 markdown。
        """.trimIndent()
        return runCatching {
            val messages = listOf(
                io.legado.app.ui.main.ai.AiChatMessage(
                    role = io.legado.app.ui.main.ai.AiChatMessage.Role.USER,
                    content = "$sys\n\n[Prompt to rewrite]\n$prompt"
                )
            )
            AiChatService.chatStream(
                messages = messages,
                onPartial = {},
                includeStructuredBlocks = false,
                modelConfigOverride = AppConfig.aiSummaryModelConfig
            ).takeIf { it.isNotBlank() } ?: sanitizeVideoPrompt(prompt)
        }.getOrElse { sanitizeVideoPrompt(prompt) }
    }

    suspend fun rewriteImagePromptForSafety(prompt: String): String =
        rewriteVideoPromptForSafety(prompt)

    /**
     * 从场景描述里提取主要角色，移植自 director_ai `_extractMainCharacters`。
     *
     * 三种切分策略：
     * 1. 句号 + 大写字母开头（最可靠）
     * 2. 分号
     * 3. " and " 且描述 > 100 字符
     *
     * 取前 [maxCharacters] 个，第一个标 "主角"，第二个标 "第二主角"。
     */
    fun extractMainCharacters(
        scenes: List<Scene>,
        maxCharacters: Int = 2
    ): List<CharacterCandidate> {
        val limit = maxCharacters.coerceIn(1, 3)
        val characterDesc = scenes.firstOrNull { it.effectiveCharacterDescription.isNotBlank() }
            ?.effectiveCharacterDescription?.trim()
            ?: return emptyList()

        val descriptions = mutableListOf<String>()
        // 方法1：句号 + 大写字母
        val sentencePattern = Regex("\\.(?=\\s+[A-Z])")
        val sentences = characterDesc.split(sentencePattern)
        if (sentences.size >= 2) {
            sentences.forEach { s ->
                val trimmed = s.trim()
                if (trimmed.length > 10) {
                    descriptions.add(if (trimmed.endsWith('.')) trimmed else "$trimmed.")
                }
            }
        } else {
            // 方法2：分号
            val semiParts = characterDesc.split(';')
            if (semiParts.size >= 2) {
                semiParts.forEach { s ->
                    val trimmed = s.trim()
                    if (trimmed.length > 10) descriptions.add(trimmed)
                }
            } else if (characterDesc.contains(" and ") && characterDesc.length > 100) {
                // 方法3：" and "
                characterDesc.split(" and ").forEach { s ->
                    val trimmed = s.trim()
                    if (trimmed.length > 10) descriptions.add(trimmed)
                }
            }
        }
        if (descriptions.isEmpty()) descriptions.add(characterDesc)

        return descriptions.take(limit).mapIndexed { idx, desc ->
            val (name, _) = extractNameAndBody(desc, idx)
            val role = if (idx == 0) "主角" else "第二主角"
            CharacterCandidate(name = name, description = desc, role = role)
        }
    }

    /** 形如 "Alice: a young girl..." 取 "Alice" 当 name；前缀超过 15 字符则不取。 */
    private fun extractNameAndBody(desc: String, fallbackIdx: Int): Pair<String, String> {
        val colonIdx = desc.indexOfAny(listOf(':', '：'))
        if (colonIdx in 1..15) {
            val name = desc.substring(0, colonIdx).trim()
            if (name.length <= 15 && name.all { it.isLetterOrDigit() || it.isWhitespace() }) {
                return name to desc.substring(colonIdx + 1).trim()
            }
        }
        return "角色${fallbackIdx + 1}" to desc
    }
}
