package io.legado.app.help.ai.pipeline

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 小说→剧本 生成器
 *
 * 核心职责：把小说章节文本发给 LLM，要求以 JSON 结构化输出返回分镜剧本。
 * 参考 ArcReel 的 ScriptGenerator + prompt_builders_script，
 * 在 Android 端复用已有 AiChatService 的 LLM 调用能力。
 */
object ScriptGenerator {

    private val gson = Gson()

    /**
     * 从小说章节文本生成分镜剧本
     *
     * @param novelText 小说原文（章节内容）
     * @param novelTitle 小说标题
     * @param chapterName 章节名
     * @param episode 集号
     * @param sceneDuration 每场景时长（秒）
     * @param onProgress 进度回调
     * @return 生成的剧本
     */
    suspend fun generateFromChapter(
        novelText: String,
        novelTitle: String = "",
        chapterName: String = "",
        episode: Int = 1,
        sceneDuration: Int = 5,
        onProgress: (String) -> Unit = {}
    ): EpisodeScript = withContext(Dispatchers.IO) {
        onProgress("正在分析章节内容...")

        val prompt = buildScriptPrompt(novelText, novelTitle, chapterName, episode, sceneDuration)

        onProgress("正在调用 AI 生成剧本...")

        // 复用 AiChatService 的 LLM 能力
        val response = callLLM(prompt)

        onProgress("正在解析剧本结构...")

        parseScriptResponse(response, novelTitle, chapterName, episode)
    }

    /**
     * 从已有角色/场景设定生成分镜
     * 可配合 WorldBook 使用
     */
    suspend fun generateWithContext(
        novelText: String,
        characterProfiles: Map<String, String> = emptyMap(),
        sceneDescriptions: Map<String, String> = emptyMap(),
        novelTitle: String = "",
        chapterName: String = "",
        episode: Int = 1,
        sceneDuration: Int = 5,
        onProgress: (String) -> Unit = {}
    ): EpisodeScript = withContext(Dispatchers.IO) {
        onProgress("正在结合角色/场景设定生成剧本...")

        val contextBlock = buildContextBlock(characterProfiles, sceneDescriptions)
        val prompt = buildScriptPrompt(novelText, novelTitle, chapterName, episode, sceneDuration, contextBlock)

        val response = callLLM(prompt)
        parseScriptResponse(response, novelTitle, chapterName, episode)
    }

    private fun buildScriptPrompt(
        novelText: String,
        novelTitle: String,
        chapterName: String,
        episode: Int,
        sceneDuration: Int,
        extraContext: String = ""
    ): String {
        // 截取前 4000 字避免 token 过长
        val truncatedText = if (novelText.length > 4000) {
            novelText.take(4000) + "\n...(内容过长已截断)"
        } else {
            novelText
        }

        return """
你是一位专业的影视分镜师。请将以下小说章节内容转化为结构化的分镜剧本。

## 小说信息
- 标题：$novelTitle
- 章节：$chapterName
- 集号：$episode
- 每场景时长：${sceneDuration}秒

${if (extraContext.isNotBlank()) "## 参考\n$extraContext\n" else ""}

## 小说原文
$truncatedText

## 要求
1. 将内容拆分为多个场景，每个场景 ${sceneDuration} 秒
2. 为每个场景提供：
   - sceneId: 格式 E{集号}S{序号}，如 E1S01
   - novelText: 原文对应段落（原样保留）
   - charactersInScene: 出场角色
   - imagePrompt: 画面描述（静态构图），包含：
     - scene: 画面内容（角色姿态、环境元素、光影）
     - composition.shotType: 镜头类型（Extreme Close-up/Close-up/Medium Close-up/Medium Shot/Medium Long Shot/Long Shot/Extreme Long Shot/Over-the-shoulder/Point-of-view）
     - composition.lighting: 光线描述（光源、方向、色温）
     - composition.ambiance: 氛围描述
   - videoPrompt: 视频描述（动态动作），包含：
     - action: 物理可观察的动作描述
     - cameraMotion: 镜头运动（Static/Pan Left/Pan Right/Tilt Up/Tilt Down/Zoom In/Zoom Out/Tracking Shot）
     - ambianceAudio: 环境音效
   - transitionToNext: 转场类型（cut/fade/dissolve）
   - segmentBreak: 是否为场景切换点

3. 确保场景之间有视觉连续性
4. image_prompt.scene 只描述静态画面，动作写在 video_prompt.action

## 输出格式
请严格输出以下 JSON 格式，不要添加 markdown 代码块标记：
{
  "title": "剧集标题",
  "scenes": [
    {
      "sceneId": "E${episode}S01",
      "durationSeconds": $sceneDuration,
      "segmentBreak": false,
      "novelText": "原文段落",
      "charactersInScene": ["角色1"],
      "imagePrompt": {
        "scene": "画面静态描述",
        "composition": {
          "shotType": "Medium Shot",
          "lighting": "光线描述",
          "ambiance": "氛围描述"
        }
      },
      "videoPrompt": {
        "action": "动作描述",
        "cameraMotion": "Static",
        "ambianceAudio": "环境音效"
      },
      "transitionToNext": "cut"
    }
  ]
}
""".trimIndent()
    }

    private fun buildContextBlock(
        characterProfiles: Map<String, String>,
        sceneDescriptions: Map<String, String>
    ): String {
        val sb = StringBuilder()
        if (characterProfiles.isNotEmpty()) {
            sb.appendLine("### 角色设定")
            characterProfiles.forEach { (name, desc) ->
                sb.appendLine("- **$name**: $desc")
            }
        }
        if (sceneDescriptions.isNotEmpty()) {
            sb.appendLine("### 场景设定")
            sceneDescriptions.forEach { (name, desc) ->
                sb.appendLine("- **$name**: $desc")
            }
        }
        return sb.toString()
    }

    private suspend fun callLLM(prompt: String): String {
        // 委托给 AiChatService 发起 LLM 请求
        return io.legado.app.help.ai.AiChatService.generateStructured(
            prompt = prompt,
            systemPrompt = "你是一位专业的影视分镜师，擅长将小说文本转化为结构化的分镜剧本。只输出 JSON，不要添加解释。"
        )
    }

    private fun parseScriptResponse(
        response: String,
        novelTitle: String,
        chapterName: String,
        episode: Int
    ): EpisodeScript {
        // 清理可能的 markdown 代码块标记
        val cleanJson = response
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            val root = JSONObject(cleanJson)
            val title = root.optString("title", chapterName.ifEmpty { "第${episode}集" })
            val scenesArray = root.optJSONArray("scenes") ?: return EpisodeScript(
                title = title, episode = episode, novelTitle = novelTitle, novelChapter = chapterName
            )

            val scenes = (0 until scenesArray.length()).mapNotNull { i ->
                val s = scenesArray.optJSONObject(i) ?: return@mapNotNull null
                parseScene(s, episode)
            }

            EpisodeScript(
                title = title,
                episode = episode,
                novelTitle = novelTitle,
                novelChapter = chapterName,
                scenes = scenes
            )
        } catch (e: Exception) {
            // JSON 解析失败，返回空剧本
            EpisodeScript(
                title = chapterName.ifEmpty { "第${episode}集" },
                episode = episode,
                novelTitle = novelTitle,
                novelChapter = chapterName,
                scenes = emptyList()
            )
        }
    }

    private fun parseScene(s: JSONObject, episode: Int): PipelineScene {
        val shotType = try {
            ShotType.valueOf(s.optJSONObject("imagePrompt")
                ?.optJSONObject("composition")
                ?.optString("shotType", "MEDIUM_SHOT")
                ?.replace(" ", "_")
                ?.uppercase() ?: "MEDIUM_SHOT")
        } catch (_: Exception) { ShotType.MEDIUM_SHOT }

        val cameraMotion = try {
            CameraMotion.valueOf(
                s.optJSONObject("videoPrompt")
                    ?.optString("cameraMotion", "STATIC")
                    ?.replace(" ", "_")
                    ?.uppercase() ?: "STATIC"
            )
        } catch (_: Exception) { CameraMotion.STATIC }

        val transition = try {
            TransitionType.valueOf(
                s.optString("transitionToNext", "CUT").uppercase()
            )
        } catch (_: Exception) { TransitionType.CUT }

        val dialogues = s.optJSONObject("videoPrompt")?.optJSONArray("dialogue")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val d = arr.optJSONObject(i) ?: return@mapNotNull null
                Dialogue(d.optString("speaker"), d.optString("line"))
            }
        } ?: emptyList()

        val composition = s.optJSONObject("imagePrompt")?.optJSONObject("composition")
        val imagePrompt = ImagePrompt(
            scene = s.optJSONObject("imagePrompt")?.optString("scene", "") ?: "",
            composition = Composition(
                shotType = shotType,
                lighting = composition?.optString("lighting", "") ?: "",
                ambiance = composition?.optString("ambiance", "") ?: ""
            )
        )

        val videoPromptObj = s.optJSONObject("videoPrompt")
        val videoPrompt = VideoPrompt(
            action = videoPromptObj?.optString("action", "") ?: "",
            cameraMotion = cameraMotion,
            ambianceAudio = videoPromptObj?.optString("ambianceAudio", "") ?: "",
            dialogue = dialogues
        )

        val charactersArray = s.optJSONArray("charactersInScene")
        val characters = charactersArray?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it) }
        } ?: emptyList()

        return PipelineScene(
            sceneId = s.optString("sceneId", "E${episode}S${s.hashCode() % 1000}"),
            durationSeconds = s.optInt("durationSeconds", 5),
            segmentBreak = s.optBoolean("segmentBreak", false),
            novelText = s.optString("novelText", ""),
            charactersInScene = characters,
            imagePrompt = imagePrompt,
            videoPrompt = videoPrompt,
            transitionToNext = transition,
            generatedAssets = GeneratedAssets()
        )
    }
}
