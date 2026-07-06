package io.legado.app.help.ai

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.NovelVideoSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NovelVideoPromptBuilder] 纯函数测试。
 *
 * 不测 `rewriteVideoPromptForSafety` / `rewriteImagePromptForSafety` —— 这两个是
 * suspend 函数且依赖 [io.legado.app.help.config.AppConfig]（Android SharedPreferences），
 * 需 Robolectric + 协程测试支持，超出当前测试栈范围。其回退分支由
 * [sanitizeVideoPrompt] 覆盖。
 */
class NovelVideoPromptBuilderTest {

    // ============================================================
    // buildDramaSystemPrompt
    // ============================================================

    @Test
    fun buildDramaSystemPromptIncludesSceneCountFromParams() {
        val params = NovelVideoParams(sceneCountPerChapter = 8, sceneDurationSeconds = 6)
        val prompt = NovelVideoPromptBuilder.buildDramaSystemPrompt(params)
        // 8 被钳制在 [3,12] 区间，应原样使用
        assertTrue(prompt.contains("8 场景"))
        assertTrue(prompt.contains("每场 5-6 秒"))
        assertTrue(prompt.contains("总长约 48 秒"))
    }

    @Test
    fun buildDramaSystemPromptClampsSceneCountToLowerBound() {
        val params = NovelVideoParams(sceneCountPerChapter = 1)
        val prompt = NovelVideoPromptBuilder.buildDramaSystemPrompt(params)
        // 1 应被钳制为 3
        assertTrue(prompt.contains("3 场景"))
    }

    @Test
    fun buildDramaSystemPromptClampsSceneCountToUpperBound() {
        val params = NovelVideoParams(sceneCountPerChapter = 99)
        val prompt = NovelVideoPromptBuilder.buildDramaSystemPrompt(params)
        // 99 应被钳制为 12
        assertTrue(prompt.contains("12 场景"))
    }

    @Test
    fun buildDramaSystemPromptInjectsStylePrompt() {
        val params = NovelVideoParams(stylePrompt = "watercolor style, soft pastel")
        val prompt = NovelVideoPromptBuilder.buildDramaSystemPrompt(params)
        assertTrue(prompt.contains("watercolor style, soft pastel"))
    }

    @Test
    fun buildDramaSystemPromptFallsBackToDefaultStyleWhenBlank() {
        val params = NovelVideoParams(stylePrompt = "")
        val prompt = NovelVideoPromptBuilder.buildDramaSystemPrompt(params)
        assertTrue(prompt.contains("anime style, manga art, 2D animation"))
    }

    @Test
    fun buildDramaSystemPromptEnforcesStrictJsonSchema() {
        val prompt = NovelVideoPromptBuilder.buildDramaSystemPrompt(NovelVideoParams())
        assertTrue(prompt.contains("\"task_id\""))
        assertTrue(prompt.contains("\"scene_id\""))
        assertTrue(prompt.contains("\"image_prompt\""))
        assertTrue(prompt.contains("\"video_prompt\""))
        assertTrue(prompt.contains("\"character_description\""))
        assertTrue(prompt.contains("\"emotional_arc\""))
        assertTrue(prompt.contains("\"estimated_duration_seconds\""))
    }

    // ============================================================
    // buildDramaUserPrompt
    // ============================================================

    @Test
    fun buildDramaUserPromptIncludesBookAndChapterMetadata() {
        val book = Book(name = "测试小说", author = "测试作者")
        val chapter = BookChapter(title = "第一章 序章")
        val prompt = NovelVideoPromptBuilder.buildDramaUserPrompt(
            book = book,
            chapter = chapter,
            chapterContent = "这是章节正文。"
        )
        assertTrue(prompt.contains("书名：测试小说"))
        assertTrue(prompt.contains("作者：测试作者"))
        assertTrue(prompt.contains("章节标题：第一章 序章"))
        assertTrue(prompt.contains("这是章节正文。"))
    }

    @Test
    fun buildDramaUserPromptTruncatesLongChapterContent() {
        val longContent = "x".repeat(10_000)
        val prompt = NovelVideoPromptBuilder.buildDramaUserPrompt(
            book = Book(),
            chapter = BookChapter(),
            chapterContent = longContent
        )
        assertTrue(prompt.contains("章节过长，已截断"))
        // 截断后应保留前 8000 字符
        assertTrue(prompt.contains("x".repeat(100)))
        // 总长应小于原始 10000 字符
        assertTrue(prompt.length < longContent.length + 200)
    }

    @Test
    fun buildDramaUserPromptWithoutFeedbackUsesDefaultPhrasing() {
        val prompt = NovelVideoPromptBuilder.buildDramaUserPrompt(
            book = Book(),
            chapter = BookChapter(),
            chapterContent = "正文",
            previousFeedback = null
        )
        assertTrue(prompt.contains("请基于以下章节正文生成漫画风格剧本"))
    }

    @Test
    fun buildDramaUserPromptWithFeedbackIncludesFeedback() {
        val prompt = NovelVideoPromptBuilder.buildDramaUserPrompt(
            book = Book(),
            chapter = BookChapter(),
            chapterContent = "正文",
            previousFeedback = "场景数不够，请补充到 5 个"
        )
        assertTrue(prompt.contains("上次生成的剧本存在问题"))
        assertTrue(prompt.contains("场景数不够，请补充到 5 个"))
        assertTrue(prompt.contains("章节正文"))
    }

    // ============================================================
    // buildCombinedViewPrompt
    // ============================================================

    @Test
    fun buildCombinedViewPromptUsesProvidedDescription() {
        val prompt = NovelVideoPromptBuilder.buildCombinedViewPrompt("A young girl with red hair")
        assertTrue(prompt.contains("A young girl with red hair"))
        assertTrue(prompt.contains("Front view"))
        assertTrue(prompt.contains("Side view"))
        assertTrue(prompt.contains("Back view"))
    }

    @Test
    fun buildCombinedViewPromptFallsBackToDefaultWhenDescriptionBlank() {
        val prompt = NovelVideoPromptBuilder.buildCombinedViewPrompt("")
        assertTrue(prompt.contains("A character in anime/manga style"))
    }

    // ============================================================
    // buildSceneImagePrompt
    // ============================================================

    @Test
    fun buildSceneImagePromptPrependsStylePrefixWhenMissing() {
        val segment = NovelVideoSegment(
            id = "seg_1",
            imagePrompt = "a classroom at sunrise"
        )
        val prompt = NovelVideoPromptBuilder.buildSceneImagePrompt(segment, "anime style")
        assertEquals("anime style, a classroom at sunrise", prompt)
    }

    @Test
    fun buildSceneImagePromptDoesNotDuplicatePrefixWhenAlreadyPresent() {
        val segment = NovelVideoSegment(
            id = "seg_1",
            imagePrompt = "anime style, a classroom at sunrise"
        )
        val prompt = NovelVideoPromptBuilder.buildSceneImagePrompt(segment, "anime style")
        assertEquals("anime style, a classroom at sunrise", prompt)
    }

    @Test
    fun buildSceneImagePrefixMatchIsCaseInsensitive() {
        val segment = NovelVideoSegment(
            id = "seg_1",
            imagePrompt = "Anime Style, classroom"
        )
        val prompt = NovelVideoPromptBuilder.buildSceneImagePrompt(segment, "anime style")
        // 已存在（大小写不敏感），不应再添加前缀
        assertEquals("Anime Style, classroom", prompt)
    }

    @Test
    fun buildSceneImagePromptFallsBackToDefaultStyleWhenBlank() {
        val segment = NovelVideoSegment(id = "seg_1", imagePrompt = "classroom")
        val prompt = NovelVideoPromptBuilder.buildSceneImagePrompt(segment, "")
        assertEquals("anime style, manga art, 2D animation, classroom", prompt)
    }

    @Test
    fun buildSceneImagePromptReturnsPrefixOnlyWhenImagePromptBlank() {
        // P3-4: 空 imagePrompt 不应产生尾逗号 "anime style..., "
        val segment = NovelVideoSegment(id = "seg_1", imagePrompt = "")
        val prompt = NovelVideoPromptBuilder.buildSceneImagePrompt(segment, "anime style")
        assertEquals("anime style", prompt)
        assertFalse(prompt.endsWith(","))
    }

    @Test
    fun buildSceneImagePromptReturnsPrefixOnlyWhenImagePromptBlankAndStyleBlank() {
        // style 和 imagePrompt 都空时，返回默认画风前缀，无尾逗号
        val segment = NovelVideoSegment(id = "seg_1", imagePrompt = "")
        val prompt = NovelVideoPromptBuilder.buildSceneImagePrompt(segment, "")
        assertEquals("anime style, manga art, 2D animation", prompt)
        assertFalse(prompt.endsWith(","))
    }

    // ============================================================
    // buildSceneVideoPrompt
    // ============================================================

    @Test
    fun buildSceneVideoPromptIncludesVideoPromptText() {
        val segment = NovelVideoSegment(
            id = "seg_1",
            videoPrompt = "slow pan across classroom"
        )
        val prompt = NovelVideoPromptBuilder.buildSceneVideoPrompt(segment)
        assertTrue(prompt.startsWith("slow pan across classroom"))
    }

    @Test
    fun buildSceneVideoPromptAppendsCharacterDescriptionWhenPresent() {
        val segment = NovelVideoSegment(
            id = "seg_1",
            videoPrompt = "slow pan",
            characterDescription = "Su Xiao: long black hair"
        )
        val prompt = NovelVideoPromptBuilder.buildSceneVideoPrompt(segment)
        assertTrue(prompt.contains(". Character: Su Xiao: long black hair"))
    }

    @Test
    fun buildSceneVideoPromptOmitsCharacterSectionWhenDescriptionBlank() {
        val segment = NovelVideoSegment(
            id = "seg_1",
            videoPrompt = "slow pan",
            characterDescription = ""
        )
        val prompt = NovelVideoPromptBuilder.buildSceneVideoPrompt(segment)
        assertFalse(prompt.contains("Character:"))
    }

    @Test
    fun buildSceneVideoPromptAppendsAtmosphereWhenNarrationPresent() {
        val segment = NovelVideoSegment(
            id = "seg_1",
            videoPrompt = "slow pan",
            narration = "晨光洒下"
        )
        val prompt = NovelVideoPromptBuilder.buildSceneVideoPrompt(segment)
        assertTrue(prompt.contains("Atmosphere: gentle, peaceful, soft lighting"))
    }

    @Test
    fun buildSceneVideoPromptOmitsAtmosphereWhenNarrationBlank() {
        val segment = NovelVideoSegment(
            id = "seg_1",
            videoPrompt = "slow pan",
            narration = ""
        )
        val prompt = NovelVideoPromptBuilder.buildSceneVideoPrompt(segment)
        assertFalse(prompt.contains("Atmosphere:"))
    }

    // ============================================================
    // sanitizeVideoPrompt / sanitizeImagePrompt
    // ============================================================

    @Test
    fun sanitizeVideoPromptReplacesLightning() {
        val result = NovelVideoPromptBuilder.sanitizeVideoPrompt("a lightning strike in the sky")
        assertTrue(result.contains("gentle flash"))
        assertFalse(result.contains("lightning"))
    }

    @Test
    fun sanitizeVideoPromptReplacesFight() {
        val result = NovelVideoPromptBuilder.sanitizeVideoPrompt("two characters fight in the rain")
        assertTrue(result.contains("soft interaction"))
        assertFalse(result.contains("fight"))
    }

    @Test
    fun sanitizeVideoPromptReplacesMultipleSensitiveWords() {
        val result = NovelVideoPromptBuilder.sanitizeVideoPrompt(
            "intense battle with weapon and blood, dramatic death"
        )
        assertFalse(result.contains("intense"))
        assertFalse(result.contains("battle"))
        assertFalse(result.contains("weapon"))
        assertFalse(result.contains("blood"))
        assertFalse(result.contains("dramatic"))
        assertFalse(result.contains("death"))
        assertTrue(result.contains("soft"))
        assertTrue(result.contains("encounter"))
        assertTrue(result.contains("object"))
        assertTrue(result.contains("red mark"))
        assertTrue(result.contains("peaceful"))
        assertTrue(result.contains("stillness"))
    }

    @Test
    fun sanitizeVideoPromptIsCaseInsensitive() {
        val result = NovelVideoPromptBuilder.sanitizeVideoPrompt("Lightning and FIGHT and Blood")
        assertTrue(result.contains("gentle flash"))
        assertTrue(result.contains("soft interaction"))
        assertTrue(result.contains("red mark"))
    }

    @Test
    fun sanitizeVideoPromptDoesNotReplacePartialMatches() {
        // \b 边界匹配：'lightning' 不应被替换为 'gentle flash' 如果它是 'lightningbug' 的一部分
        val result = NovelVideoPromptBuilder.sanitizeVideoPrompt("lightningbug in the dark")
        // 'lightningbug' 是一个完整单词，不应被部分替换
        assertTrue(result.contains("lightningbug"))
    }

    @Test
    fun sanitizeVideoPromptReturnsInputWhenNoSensitiveWords() {
        val input = "a peaceful garden with flowers"
        val result = NovelVideoPromptBuilder.sanitizeVideoPrompt(input)
        assertEquals(input, result)
    }

    @Test
    fun sanitizeImagePromptUsesSameStrategyAsVideo() {
        val videoResult = NovelVideoPromptBuilder.sanitizeVideoPrompt("lightning and fight")
        val imageResult = NovelVideoPromptBuilder.sanitizeImagePrompt("lightning and fight")
        assertEquals(videoResult, imageResult)
    }

    @Test
    fun sanitizeVideoPromptReplacesAllConfiguredSensitiveWords() {
        // P3-6: 预编译 Regex 后回归测试，确保 18 个敏感词规则全部生效
        val allWords = listOf(
            "lightning", "fight", "explosion", "attack", "intense", "fierce",
            "battle", "weapon", "blood", "kill", "death", "war",
            "glowing", "energy", "powerful", "dramatic", "trembling", "gripping"
        )
        val input = allWords.joinToString(" ")
        val result = NovelVideoPromptBuilder.sanitizeVideoPrompt(input)
        allWords.forEach { w ->
            assertFalse("敏感词未被替换：$w（结果：$result）", result.contains("\\b$w\\b".toRegex(RegexOption.IGNORE_CASE)))
        }
    }

    // ============================================================
    // extractMainCharacters
    // ============================================================

    @Test
    fun extractMainCharactersReturnsEmptyWhenNoDescription() {
        val scenes = listOf(
            Scene(sceneId = 1, narration = "n", imagePrompt = "i", videoPrompt = "v", characterDescription = "")
        )
        val characters = NovelVideoPromptBuilder.extractMainCharacters(scenes, maxCharacters = 2)
        assertTrue(characters.isEmpty())
    }

    @Test
    fun extractMainCharactersSplitsBySentencePattern() {
        val desc = "Alice: a young girl with black hair. Bob: a tall boy with brown hair."
        val scenes = listOf(
            Scene(sceneId = 1, characterDescription = desc)
        )
        val characters = NovelVideoPromptBuilder.extractMainCharacters(scenes, maxCharacters = 2)
        assertEquals(2, characters.size)
        assertEquals("Alice", characters[0].name)
        assertEquals("主角", characters[0].role)
        assertEquals("Bob", characters[1].name)
        assertEquals("第二主角", characters[1].role)
    }

    @Test
    fun extractMainCharactersSplitsBySemicolonWhenNoSentencePattern() {
        val desc = "Alice with black hair; Bob with brown hair; Carol with red hair"
        val scenes = listOf(Scene(sceneId = 1, characterDescription = desc))
        val characters = NovelVideoPromptBuilder.extractMainCharacters(scenes, maxCharacters = 2)
        assertEquals(2, characters.size)
        // 分号切分时，name 提取依赖冒号；若无冒号则使用 "角色N"
        assertEquals("角色1", characters[0].name)
    }

    @Test
    fun extractMainCharactersSplitsByAndWhenDescriptionIsLongAndNoSentenceOrSemicolon() {
        // 描述长度需 > 100 才触发策略3；同时不含句号+大写、不含分号
        val desc = "A young girl with long flowing black hair and a tall boy with short curly brown hair and a small child with bright red hair"
        val scenes = listOf(Scene(sceneId = 1, characterDescription = desc))
        val characters = NovelVideoPromptBuilder.extractMainCharacters(scenes, maxCharacters = 3)
        // 描述长度 > 100 且含 " and "，应触发策略3
        assertEquals(3, characters.size)
    }

    @Test
    fun extractMainCharactersClampsLimitToMax3() {
        // 每段需 > 10 字符才能进入 descriptions
        val desc = "AliceDescription1. BobDescription2. CarolDescription3. DaveDescription4. EveDescription5."
        val scenes = listOf(Scene(sceneId = 1, characterDescription = desc))
        val characters = NovelVideoPromptBuilder.extractMainCharacters(scenes, maxCharacters = 10)
        // maxCharacters 应被钳制到 3
        assertEquals(3, characters.size)
    }

    @Test
    fun extractMainCharactersClampsLimitToMin1() {
        val desc = "Alice: a young girl. Bob: a tall boy."
        val scenes = listOf(Scene(sceneId = 1, characterDescription = desc))
        val characters = NovelVideoPromptBuilder.extractMainCharacters(scenes, maxCharacters = 0)
        // maxCharacters=0 应被钳制到 1
        assertEquals(1, characters.size)
        assertEquals("主角", characters[0].role)
    }

    @Test
    fun extractMainCharactersFallsBackToSingleCharacterWhenNoSplit() {
        val desc = "A single character with no splittable structure"
        val scenes = listOf(Scene(sceneId = 1, characterDescription = desc))
        val characters = NovelVideoPromptBuilder.extractMainCharacters(scenes, maxCharacters = 2)
        assertEquals(1, characters.size)
        assertEquals("主角", characters[0].role)
        assertEquals(desc, characters[0].description)
    }

    @Test
    fun extractMainCharactersUsesFirstSceneWithNonBlankDescription() {
        val scenes = listOf(
            Scene(sceneId = 1, characterDescription = ""),
            Scene(sceneId = 2, characterDescription = "Alice: a girl. Bob: a boy.")
        )
        val characters = NovelVideoPromptBuilder.extractMainCharacters(scenes, maxCharacters = 2)
        assertEquals(2, characters.size)
        assertEquals("Alice", characters[0].name)
    }

    @Test
    fun extractMainCharactersFallsBackToCamelCaseDescription() {
        val scenes = listOf(
            Scene(
                sceneId = 1,
                characterDescription = "",
                characterDescriptionCamel = "Alice: a girl. Bob: a boy."
            )
        )
        val characters = NovelVideoPromptBuilder.extractMainCharacters(scenes, maxCharacters = 2)
        assertEquals(2, characters.size)
        assertEquals("Alice", characters[0].name)
    }

    @Test
    fun extractMainCharactersLabelsThirdCharacterAsSupporting() {
        // P3-8: 第 3 个角色应标为"配角"而非"第二主角"
        val desc = "Alice: a young girl. Bob: a tall boy. Carol: a small child."
        val scenes = listOf(Scene(sceneId = 1, characterDescription = desc))
        val characters = NovelVideoPromptBuilder.extractMainCharacters(scenes, maxCharacters = 3)
        assertEquals(3, characters.size)
        assertEquals("主角", characters[0].role)
        assertEquals("第二主角", characters[1].role)
        assertEquals("配角", characters[2].role)
    }
}
