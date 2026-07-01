package io.legado.app.ai

/**
 * AI 提示词构建器
 */
object AIPromptBuilder {

    fun characterPortrait(description: String): String = buildString {
        append("生成一张小说角色插图，")
        append("角色描述：$description。")
        append("要求：高质量插画风格，精细细节，适合移动端阅读展示。")
        append("画面比例：竖版 9:16。")
    }

    fun sceneIllustration(
        sceneDescription: String,
        mood: String = "",
        chars: List<String> = emptyList()
    ): String = buildString {
        append("生成一张小说场景插画。")
        append("场景：$sceneDescription。")
        if (mood.isNotBlank()) append("氛围：$mood。")
        if (chars.isNotEmpty()) append("角色：${chars.joinToString("、")}。")
        append("风格：精美插画，电影级构图，适合作为小说插图。")
        append("宽屏比例：16:9。")
    }

    fun bookCover(
        title: String,
        genre: String = "",
        description: String = ""
    ): String = buildString {
        append("为小说《$title》设计一张封面插图。")
        if (genre.isNotBlank()) append("类型：$genre。")
        if (description.isNotBlank()) append("故事梗概：$description。")
        append("要求：专业书籍封面设计，有冲击力，")
        append("竖版 2:3 比例，留白适合叠加标题文字。")
    }

    fun custom(selectedText: String, userExtra: String = ""): String = buildString {
        append("根据以下小说片段生成一张插图：\n")
        append("\"$selectedText\"\n")
        if (userExtra.isNotBlank()) {
            append("额外要求：$userExtra")
        }
    }
}