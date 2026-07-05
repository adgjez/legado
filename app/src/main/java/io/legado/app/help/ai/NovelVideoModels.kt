package io.legado.app.help.ai

import androidx.annotation.Keep
import com.google.gson.JsonSyntaxException
import io.legado.app.utils.GSON

/**
 * 小说→视频流水线的中途数据结构（非 Room 实体）。
 *
 * - [Screenplay]：审阅通过、落库 [io.legado.app.data.entities.NovelVideoJob.screenplayJson] 的最终剧本。
 * - [ScreenplayDraft]：Stage 2 LLM 直接产出、待审阅的草稿，字段比 [Screenplay] 多 `genre`/`estimatedDurationSeconds`/`emotionalArc`。
 * - [Scene]：单个分镜段，对应一行 [io.legado.app.data.entities.NovelVideoSegment]。
 */

@Keep
data class Screenplay(
    val taskId: String,
    val title: String,
    val scenes: List<Scene>
) {
    companion object {
        fun fromJson(json: String): Screenplay =
            runCatching { GSON.fromJson(json, Screenplay::class.java) }.getOrNull()
                ?: throw JsonSyntaxException("Screenplay 解析失败")

        fun fromDraft(draft: ScreenplayDraft): Screenplay = Screenplay(
            taskId = draft.taskId.ifBlank { "nv_${System.currentTimeMillis()}" },
            title = draft.title.ifBlank { draft.scriptTitle }.ifBlank { "未命名剧本" },
            scenes = draft.scenes
        )
    }
}

@Keep
data class ScreenplayDraft(
    val taskId: String = "",
    val title: String = "",
    /** 兼容 legacy 字段名 `script_title`。 */
    val scriptTitle: String = "",
    val genre: String = "",
    val estimatedDurationSeconds: Int = 0,
    val emotionalArc: List<String> = emptyList(),
    val scenes: List<Scene> = emptyList()
) {
    fun toJson(): String = GSON.toJson(this)

    /** 主标题优先取 `title`，回退到 legacy `scriptTitle`。 */
    val displayTitle: String get() = title.ifBlank { scriptTitle }

    companion object {
        fun fromJson(json: String): ScreenplayDraft =
            runCatching { GSON.fromJson(json, ScreenplayDraft::class.java) }.getOrDefault(ScreenplayDraft())
    }
}

@Keep
data class Scene(
    val sceneId: Int = 1,
    val narration: String = "",
    val mood: String = "",
    val emotionalHook: String = "",
    val imagePrompt: String = "",
    val videoPrompt: String = "",
    /** 兼容 camelCase 命名 `characterDescription`。 */
    val characterDescription: String = "",
    val characterDescriptionCamel: String = ""
) {
    /** 统一取 snake_case 优先，回退 camelCase。 */
    val effectiveCharacterDescription: String
        get() = characterDescription.ifBlank { characterDescriptionCamel }
}

/**
 * 从场景描述里抽出的候选角色，用于 Stage 4 三视图生成。
 */
data class CharacterCandidate(
    val name: String,
    val description: String,
    val role: String
)
