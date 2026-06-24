package io.legado.app.help.ai

import io.legado.app.data.entities.AiStoryScene
import java.util.UUID

object AiStoryDirector {

    data class StoryContext(
        val chapterText: String,
        val bookName: String = "",
        val bookAuthor: String = "",
        val chapterTitle: String = "",
        val characterDescriptions: String = "",
        val worldBook: String = "",
        val stylePreference: String = ""
    )

    data class ScenePlan(
        val scenes: List<PlannedScene>
    )

    data class PlannedScene(
        val narrativeText: String,
        val visualPrompt: String,
        val cameraControl: String,
        val audioPrompt: String,
        val duration: Long
    )

    suspend fun planScenes(context: StoryContext): ScenePlan {
        val systemPrompt = buildSystemPrompt(context)
        val userContent = context.chapterText
        val response = AiChatService.chatSimple(systemPrompt, userContent)
        return parseScenePlan(response)
    }

    private fun buildSystemPrompt(context: StoryContext): String {
        return """
你是一个专业的影视分镜导演。请将以下章节文本拆解为视频分镜脚本。

要求：
1. 每个场景包含：叙述文本（narrativeText）、视觉描述提示词（visualPrompt）、镜头控制（cameraControl）、音频提示（audioPrompt）、建议时长（duration，秒）
2. visualPrompt 必须是英文，适合 AI 图像/视频生成，包含场景、人物、动作、光影、风格等细节
3. cameraControl 格式：pan/zoom/static/track，可选角度如 close_up/wide/medium
4. audioPrompt 描述背景音效（如"wind howling, footsteps on gravel"）
5. 每个场景时长建议 3-8 秒
6. 总场景数建议 5-15 个，覆盖章节关键情节
7. 保持角色外观一致，在 visualPrompt 中统一描述角色特征

${if (context.characterDescriptions.isNotBlank()) "角色描述：${context.characterDescriptions}" else ""}
${if (context.worldBook.isNotBlank()) "世界观设定：${context.worldBook}" else ""}
${if (context.stylePreference.isNotBlank()) "风格偏好：${context.stylePreference}" else ""}

请按以下 JSON 格式输出（不要输出其他内容）：
```json
[
  {
    "narrativeText": "场景叙述",
    "visualPrompt": "English visual prompt for AI generation",
    "cameraControl": "pan/close_up",
    "audioPrompt": "sound effect description",
    "duration": 5
  }
]
```
        """.trimIndent()
    }

    private fun parseScenePlan(response: String): ScenePlan {
        return try {
            val jsonStr = extractJsonArray(response)
            val jsonArray = org.json.JSONArray(jsonStr)
            val scenes = mutableListOf<PlannedScene>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                scenes.add(PlannedScene(
                    narrativeText = obj.optString("narrativeText", ""),
                    visualPrompt = obj.optString("visualPrompt", ""),
                    cameraControl = obj.optString("cameraControl", "static"),
                    audioPrompt = obj.optString("audioPrompt", ""),
                    duration = (obj.optDouble("duration", 5.0) * 1000).toLong()
                ))
            }
            ScenePlan(scenes)
        } catch (e: Exception) {
            io.legado.app.constant.AppLog.put("Failed to parse scene plan", e)
            ScenePlan(emptyList())
        }
    }

    private fun extractJsonArray(text: String): String {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start >= 0 && end > start) return text.substring(start, end + 1)
        return text
    }

    fun plannedScenesToEntities(playlistId: String, plan: ScenePlan): List<AiStoryScene> {
        return plan.scenes.mapIndexed { index, scene ->
            AiStoryScene(
                id = UUID.randomUUID().toString(),
                playlistId = playlistId,
                sceneIndex = index,
                narrativeText = scene.narrativeText,
                visualPrompt = scene.visualPrompt,
                cameraControl = scene.cameraControl,
                audioPrompt = scene.audioPrompt,
                duration = scene.duration,
                status = "pending"
            )
        }
    }
}
