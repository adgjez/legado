package io.legado.app.help.ai

import com.google.gson.JsonSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [Screenplay] / [ScreenplayDraft] / [Scene] 的序列化往返与边界行为测试。
 *
 * 走 [io.legado.app.utils.GSON] 懒加载实例，已在 [io.legado.app.LibraryCloudKeysTest]
 * 验证可在纯 JVM 单元测试中构造 @Parcelize @Entity 实体，此处同理。
 */
class NovelVideoModelsTest {

    @Test
    fun screenplayRoundTripPreservesAllFields() {
        val original = Screenplay(
            taskId = "nv_round_001",
            title = "校园清晨",
            scenes = listOf(
                Scene(
                    sceneId = 1,
                    narration = "晨光洒在窗台上。",
                    mood = "宁静",
                    emotionalHook = "新一天开始",
                    imagePrompt = "anime style, classroom at sunrise",
                    videoPrompt = "slow pan",
                    characterDescription = "Su Xiao: long black hair, white shirt"
                ),
                Scene(
                    sceneId = 2,
                    narration = "林然走进教室。",
                    mood = "期待",
                    emotionalHook = "相遇",
                    imagePrompt = "anime style, boy entering classroom",
                    videoPrompt = "medium shot dolly in",
                    characterDescription = "Lin Ran: short brown hair, school uniform"
                )
            )
        )
        val json = io.legado.app.utils.GSON.toJson(original)
        val restored = Screenplay.fromJson(json)
        assertEquals(original.taskId, restored.taskId)
        assertEquals(original.title, restored.title)
        assertEquals(original.scenes.size, restored.scenes.size)
        assertEquals(original.scenes[0].narration, restored.scenes[0].narration)
        assertEquals(original.scenes[1].characterDescription, restored.scenes[1].characterDescription)
    }

    @Test
    fun screenplayFromJsonThrowsOnInvalidJson() {
        try {
            Screenplay.fromJson("not a json")
            fail("expected JsonSyntaxException")
        } catch (e: JsonSyntaxException) {
            assertTrue(e.message?.contains("Screenplay 解析失败") == true)
        }
    }

    @Test
    fun screenplayFromDraftFillsBlankTaskIdWithGeneratedId() {
        val draft = ScreenplayDraft(
            taskId = "",
            title = "无 ID 剧本",
            scenes = listOf(Scene(sceneId = 1, narration = "n", imagePrompt = "i", videoPrompt = "v"))
        )
        val screenplay = Screenplay.fromDraft(draft)
        assertTrue("fromDraft 应生成 nv_ 前缀的 taskId", screenplay.taskId.startsWith("nv_"))
        assertEquals("无 ID 剧本", screenplay.title)
    }

    @Test
    fun screenplayFromDraftFallsBackToScriptTitleWhenTitleBlank() {
        val draft = ScreenplayDraft(
            taskId = "nv_x",
            title = "",
            scriptTitle = "Legacy 标题",
            scenes = listOf(Scene(sceneId = 1, narration = "n", imagePrompt = "i", videoPrompt = "v"))
        )
        val screenplay = Screenplay.fromDraft(draft)
        assertEquals("Legacy 标题", screenplay.title)
    }

    @Test
    fun screenplayFromDraftFallsBackToDefaultWhenAllTitlesBlank() {
        val draft = ScreenplayDraft(
            taskId = "nv_x",
            title = "",
            scriptTitle = "",
            scenes = listOf(Scene(sceneId = 1, narration = "n", imagePrompt = "i", videoPrompt = "v"))
        )
        val screenplay = Screenplay.fromDraft(draft)
        assertEquals("未命名剧本", screenplay.title)
    }

    @Test
    fun screenplayDraftFromJsonReturnsEmptyOnInvalidJson() {
        // 解析失败或场景为空时抛 JsonSyntaxException，避免空剧本被静默确认完成
        try {
            ScreenplayDraft.fromJson("not a json")
            fail("应抛 JsonSyntaxException")
        } catch (e: JsonSyntaxException) {
            // 预期
        }
    }

    @Test
    fun screenplayDraftToJsonRoundTrip() {
        val original = ScreenplayDraft(
            taskId = "nv_draft_001",
            title = "测试剧本",
            genre = "校园",
            estimatedDurationSeconds = 35,
            emotionalArc = listOf("宁静", "期待"),
            scenes = listOf(
                Scene(
                    sceneId = 1,
                    narration = "n1",
                    imagePrompt = "i1",
                    videoPrompt = "v1"
                )
            )
        )
        val json = original.toJson()
        val restored = ScreenplayDraft.fromJson(json)
        assertEquals(original.taskId, restored.taskId)
        assertEquals(original.title, restored.title)
        assertEquals(original.genre, restored.genre)
        assertEquals(original.estimatedDurationSeconds, restored.estimatedDurationSeconds)
        assertEquals(original.emotionalArc, restored.emotionalArc)
        assertEquals(original.scenes.size, restored.scenes.size)
    }

    @Test
    fun screenplayDraftDisplayTitlePrefersTitleOverScriptTitle() {
        val draft = ScreenplayDraft(title = "新标题", scriptTitle = "旧标题")
        assertEquals("新标题", draft.displayTitle)
    }

    @Test
    fun screenplayDraftDisplayTitleFallsBackToScriptTitle() {
        val draft = ScreenplayDraft(title = "", scriptTitle = "旧标题")
        assertEquals("旧标题", draft.displayTitle)
    }

    @Test
    fun sceneEffectiveCharacterDescriptionPrefersSnakeCase() {
        val scene = Scene(
            characterDescription = "snake case desc",
            characterDescriptionCamel = "camel case desc"
        )
        assertEquals("snake case desc", scene.effectiveCharacterDescription)
    }

    @Test
    fun sceneEffectiveCharacterDescriptionFallsBackToCamelCase() {
        val scene = Scene(
            characterDescription = "",
            characterDescriptionCamel = "camel case desc"
        )
        assertEquals("camel case desc", scene.effectiveCharacterDescription)
    }

    @Test
    fun sceneEffectiveCharacterDescriptionEmptyWhenBothBlank() {
        val scene = Scene()
        assertEquals("", scene.effectiveCharacterDescription)
    }

    @Test
    fun screenplayDraftFromJsonAcceptsLegacyCamelCaseCharacterDescription() {
        val json = """
            {
              "task_id": "nv_legacy",
              "title": "legacy",
              "scenes": [
                {
                  "scene_id": 1,
                  "narration": "n",
                  "image_prompt": "i",
                  "video_prompt": "v",
                  "characterDescription": "camelCase desc"
                }
              ]
            }
        """.trimIndent()
        val draft = ScreenplayDraft.fromJson(json)
        assertEquals("camelCase desc", draft.scenes[0].characterDescriptionCamel)
        // snake_case 字段优先；当它为空时回退到 camelCase
        assertEquals("camelCase desc", draft.scenes[0].effectiveCharacterDescription)
    }
}
