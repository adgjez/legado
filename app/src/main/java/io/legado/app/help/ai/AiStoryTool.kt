package io.legado.app.help.ai

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

object AiStoryTool {

    fun resolvedTools(): List<AiResolvedTool> = listOf(
        AiResolvedTool(
            name = "generate_scene",
            definition = JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", "generate_scene")
                    put("description", "将章节文本转换为有声视频分镜（自动拆分场景→生成关键帧→生成视频片段→播放列表）")
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("chapterText", JSONObject(mapOf("type" to "string", "description" to "章节正文")))
                            put("bookKey", JSONObject(mapOf("type" to "string", "description" to "书籍标识")))
                            put("bookName", JSONObject(mapOf("type" to "string", "description" to "书名")))
                            put("bookAuthor", JSONObject(mapOf("type" to "string", "description" to "作者")))
                            put("chapterTitle", JSONObject(mapOf("type" to "string", "description" to "章节标题")))
                            put("characterDescriptions", JSONObject(mapOf("type" to "string", "description" to "角色描述（可选）")))
                        })
                        put("required", listOf("chapterText", "bookKey"))
                    })
                })
            },
            execute = { params ->
                try {
                    val chapterText = params?.optString("chapterText") ?: return@AiResolvedTool "{\"ok\":false,\"error\":\"Missing chapterText\"}"
                    val bookKey = params?.optString("bookKey") ?: ""
                    val bookName = params?.optString("bookName") ?: ""
                    val bookAuthor = params?.optString("bookAuthor") ?: ""
                    val chapterTitle = params?.optString("chapterTitle") ?: ""
                    val characterDescriptions = params?.optString("characterDescriptions") ?: ""

                    val playlist = AiStoryPipeline.execute(
                        chapterText = chapterText,
                        bookKey = bookKey,
                        bookName = bookName,
                        bookAuthor = bookAuthor,
                        chapterTitle = chapterTitle,
                        characterDescriptions = characterDescriptions
                    )

                    JSONObject().apply {
                        put("ok", true)
                        put("success", true)
                        put("type", "story_playlist")
                        put("playlistId", playlist.id)
                        put("sceneCount", playlist.sceneCount)
                        put("totalDuration", playlist.totalDuration)
                        put("status", playlist.status)
                        put("message", "分镜视频生成完成，共 ${playlist.sceneCount} 个场景")
                    }.toString()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message ?: "Unknown error").toString()
                }
            }
        )
    )
}
