package io.legado.app.help.ai

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiGeneratedVideo
import io.legado.app.data.entities.BookCharacter
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream

/**
 * P2：AI 视频相关 5 个 LLM tool。
 *
 * - generate_video：提交文生视频/图生视频任务（异步，立即返回 taskId）
 * - list_ai_gallery_videos：列出画廊视频
 * - get_ai_gallery_video：取一条视频元信息
 * - set_book_character_avatar_from_video_gallery：从画廊视频抽帧做角色头像
 * - generate_book_character_short_video：角色卡对应短视（基于角色描述 prompt）
 */
object AiVideoTool {

    private const val TOOL_GENERATE = "generate_video"
    private const val TOOL_LIST = "list_ai_gallery_videos"
    private const val TOOL_GET = "get_ai_gallery_video"
    private const val TOOL_SET_AVATAR = "set_book_character_avatar_from_video_gallery"
    private const val TOOL_GENERATE_CHARACTER_VIDEO = "generate_book_character_short_video"

    private const val MAX_LIST_LIMIT = 50
    private const val DEFAULT_LIST_LIMIT = 20
    private const val MAX_AVATAR_SIDE = 720
    private const val FRAME_TIME_DEFAULT_MS = 0L
    private const val SOURCE_TYPE_CHARACTER_SHORT_VIDEO = "character_short_video"

    fun resolvedTools(): List<AiResolvedTool> {
        return listOf(
            AiResolvedTool(TOOL_GENERATE, generateDefinition()) { args -> executeGenerate(args) },
            AiResolvedTool(TOOL_LIST, listDefinition()) { args -> executeList(args) },
            AiResolvedTool(TOOL_GET, getDefinition()) { args -> executeGet(args) },
            AiResolvedTool(TOOL_SET_AVATAR, setAvatarDefinition()) { args -> executeSetAvatar(args) },
            AiResolvedTool(TOOL_GENERATE_CHARACTER_VIDEO, generateCharacterVideoDefinition()) { args ->
                executeGenerateCharacterVideo(args)
            }
        )
    }

    private fun generateDefinition(): JSONObject {
        return function(
            TOOL_GENERATE,
            "提交一个 AI 视频生成任务。文生视频/图生视频。生成通常需要 1~30 分钟，提交后任务在后台运行，结果会写入 AI 视频画廊。返回 taskId 字符串，不要在当前轮阻塞等待。"
        ) {
            put("prompt", stringProp("文本提示词，必填。"))
            put("negativePrompt", stringProp("负向提示词，可选。"))
            put("providerId", stringProp("可选，指定视频供应商 ID；只有用户明确选择某个视频模型时才传入，否则留空走当前默认。"))
            put("firstFrame", stringProp("可选，图生视频的首帧 URL 或本地路径。"))
            put("aspectRatio", stringProp("可选，比例，可选值 16:9 / 9:16 / 1:1 / 4:3。"))
            put("durationSec", intProp("可选，时长（秒），1-60。"))
        }
    }

    private fun listDefinition(): JSONObject {
        return function(
            TOOL_LIST,
            "列出 AI 视频画廊里的视频，可按书、角色、来源类型、状态、是否收藏或关键词过滤。"
        ) {
            put("bookKey", stringProp("可选，bookKey，书名+作者组合。"))
            put("characterId", intProp("可选，角色 ID。"))
            put("sourceType", stringProp("可选，来源类型，例如 character_short_video。"))
            put("status", stringProp("可选，状态：pending / running / success / failed / cancelled。"))
            put("favorite", booleanProp("可选，只看已收藏。"))
            put("keyword", stringProp("可选，模糊匹配名称 / 提示词 / 书名 / 角色名。"))
            put("limit", intProp("可选，返回数量上限，默认 20，最大 50。"))
        }
    }

    private fun getDefinition(): JSONObject {
        return function(
            TOOL_GET,
            "读取 AI 视频画廊中某一条视频的完整元信息（提示词、状态、路径、时长、宽高等）。"
        ) {
            put("videoId", stringProp("视频 ID，必填。"))
        }
    }

    private fun setAvatarDefinition(): JSONObject {
        return function(
            TOOL_SET_AVATAR,
            "从 AI 视频画廊中的某条视频抽取一帧画面，保存为角色头像。支持指定 frameIndexMs 选取哪一帧；不传则取首帧。"
        ) {
            put("characterId", intProp("角色 ID，必填。"))
            put("videoId", stringProp("视频 ID，必填。"))
            put("frameIndexMs", intProp("可选，抽帧时间（毫秒），默认 0 表示首帧。"))
        }
    }

    private fun generateCharacterVideoDefinition(): JSONObject {
        return function(
            TOOL_GENERATE_CHARACTER_VIDEO,
            "根据指定角色名和外貌描述生成一条角色短视频，sourceType=character_short_video，并自动绑定 characterId。返回 taskId，1~30 分钟后在画廊可见。"
        ) {
            put("characterId", intProp("角色 ID，必填。"))
            put("stylePrompt", stringProp("可选，附加风格提示词。"))
        }
    }

    private suspend fun executeGenerate(args: JSONObject?): String = withContext(Dispatchers.IO) {
        val prompt = args?.optString("prompt").orEmpty().trim()
        if (prompt.isBlank()) {
            return@withContext errorJson("prompt is empty")
        }
        val negativePrompt = args?.optString("negativePrompt").orEmpty().trim()
        val firstFrame = args?.optString("firstFrame")?.trim()?.takeIf { it.isNotBlank() }
        val aspectRatio = args?.optString("aspectRatio").orEmpty().trim()
        val durationSec = args?.optInt("durationSec", 0)?.coerceIn(0, 60) ?: 0
        val providerId = args?.optString("providerId").orEmpty().trim()
        val provider = when {
            providerId.isBlank() -> AppConfig.aiCurrentVideoProvider
            else -> AppConfig.findEnabledVideoProvider(providerId)
        }
        if (provider == null) {
            return@withContext errorJson("AI 视频供应商不可用")
        }
        val metadata = AiVideoGalleryManager.VideoMetadata(
            sourceType = "ai_chat",
            sourceText = prompt
        )
        runCatching {
            val row = AiVideoService.submitAndStore(
                prompt = prompt,
                provider = provider,
                negativePrompt = negativePrompt,
                firstFrame = firstFrame,
                durationSec = durationSec,
                aspectRatio = aspectRatio,
                metadata = metadata
            )
            JSONObject().apply {
                put("ok", true)
                put("success", true)
                put("taskId", row.id)
                put("videoId", row.id)
                put("status", row.status)
                put("provider", row.providerName)
                put("model", row.model)
            }.toString()
        }.getOrElse { t ->
            errorJson(t.localizedMessage ?: t.javaClass.simpleName)
        }
    }

    private suspend fun executeList(args: JSONObject?): String = withContext(Dispatchers.IO) {
        val bookKey = args?.optString("bookKey")?.trim().orEmpty()
        val characterId = args?.optLong("characterId", 0L) ?: 0L
        val sourceType = args?.optString("sourceType")?.trim().orEmpty()
        val status = args?.optString("status")?.trim().orEmpty()
        val favorite = args?.takeIf { it.has("favorite") }?.optBoolean("favorite") ?: false
        val keyword = args?.optString("keyword")?.trim().orEmpty()
        val limit = (args?.optInt("limit", DEFAULT_LIST_LIMIT) ?: DEFAULT_LIST_LIMIT)
            .coerceIn(1, MAX_LIST_LIMIT)
        val all = if (favorite) {
            appDb.aiGeneratedVideoDao.favorites()
        } else {
            appDb.aiGeneratedVideoDao.all()
        }
        val filtered = all.asSequence()
            .filter { v ->
                bookKey.isBlank() || v.bookKey == bookKey
            }
            .filter { v ->
                characterId <= 0L || v.characterId == characterId
            }
            .filter { v ->
                sourceType.isBlank() || v.sourceType == sourceType
            }
            .filter { v ->
                status.isBlank() || v.status == status
            }
            .filter { v ->
                if (keyword.isBlank()) true else runCatching {
                    val like = "%$keyword%"
                    v.name.contains(keyword, true) ||
                        v.prompt.contains(keyword, true) ||
                        v.bookName.contains(keyword, true) ||
                        v.characterName.contains(keyword, true)
                }.getOrDefault(false)
            }
            .take(limit)
            .toList()
        JSONObject().apply {
            put("ok", true)
            put("count", filtered.size)
            put("items", JSONArray().apply {
                filtered.forEach { put(videoSummaryJson(it)) }
            })
        }.toString()
    }

    private suspend fun executeGet(args: JSONObject?): String = withContext(Dispatchers.IO) {
        val videoId = args?.optString("videoId")?.trim().orEmpty()
        if (videoId.isBlank()) return@withContext errorJson("videoId 不能为空")
        val row = appDb.aiGeneratedVideoDao.get(videoId)
            ?: return@withContext errorJson("未找到视频")
        JSONObject().apply {
            put("ok", true)
            put("video", videoFullJson(row))
        }.toString()
    }

    private suspend fun executeSetAvatar(args: JSONObject?): String = withContext(Dispatchers.IO) {
        val characterId = args?.optLong("characterId", 0L) ?: 0L
        if (characterId <= 0L) return@withContext errorJson("characterId 不能为空")
        val videoId = args?.optString("videoId")?.trim().orEmpty()
        if (videoId.isBlank()) return@withContext errorJson("videoId 不能为空")
        val frameMs = args?.optLong("frameIndexMs", FRAME_TIME_DEFAULT_MS)
            ?.coerceAtLeast(0L) ?: FRAME_TIME_DEFAULT_MS
        val character = appDb.bookCharacterDao.getCharacter(characterId)
            ?: return@withContext errorJson("未找到角色")
        val video = appDb.aiGeneratedVideoDao.get(videoId)
            ?: return@withContext errorJson("未找到视频")
        if (video.localPath.isBlank() || !File(video.localPath).isFile) {
            return@withContext errorJson("视频文件已丢失")
        }
        val savedPath = runCatching {
            extractFrameToAvatarFile(video.localPath, characterId, frameMs)
        }.getOrElse { t ->
            return@withContext errorJson("抽帧失败：${t.localizedMessage ?: t.javaClass.simpleName}")
        }
        val updated = character.copy(
            avatar = savedPath,
            updatedAt = System.currentTimeMillis()
        )
        appDb.bookCharacterDao.updateCharacter(updated)
        JSONObject().apply {
            put("ok", true)
            put("success", true)
            put("characterId", characterId)
            put("characterName", character.name)
            put("videoId", videoId)
            put("avatarPath", savedPath)
            put("frameIndexMs", frameMs)
        }.toString()
    }

    private suspend fun executeGenerateCharacterVideo(args: JSONObject?): String = withContext(Dispatchers.IO) {
        val characterId = args?.optLong("characterId", 0L) ?: 0L
        if (characterId <= 0L) return@withContext errorJson("characterId 不能为空")
        val stylePrompt = args?.optString("stylePrompt")?.trim().orEmpty()
        val character = appDb.bookCharacterDao.getCharacter(characterId)
            ?: return@withContext errorJson("未找到角色")
        val prompt = buildCharacterVideoPrompt(character, stylePrompt)
        val provider = AppConfig.aiCurrentVideoProvider
            ?: return@withContext errorJson("AI 视频供应商不可用")
        val metadata = AiVideoGalleryManager.VideoMetadata(
            bookName = "",
            bookAuthor = "",
            characterId = character.id,
            characterName = character.name,
            sourceType = SOURCE_TYPE_CHARACTER_SHORT_VIDEO,
            sourceText = prompt
        )
        runCatching {
            val row = AiVideoService.submitAndStore(
                prompt = prompt,
                provider = provider,
                metadata = metadata
            )
            JSONObject().apply {
                put("ok", true)
                put("success", true)
                put("taskId", row.id)
                put("videoId", row.id)
                put("status", row.status)
                put("characterId", character.id)
                put("characterName", character.name)
                put("prompt", prompt)
            }.toString()
        }.getOrElse { t ->
            errorJson(t.localizedMessage ?: t.javaClass.simpleName)
        }
    }

    private fun extractFrameToAvatarFile(
        videoPath: String,
        characterId: Long,
        frameMs: Long
    ): String {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoPath)
            val bitmap: Bitmap? = retriever.getFrameAtTime(
                frameMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(
                frameMs * 1000L,
                MediaMetadataRetriever.OPTION_NEXT_SYNC
            )
            if (bitmap == null) error("未能抽取视频帧")
            val scaled = scaleBitmap(bitmap, MAX_AVATAR_SIDE)
            val avatarDir = File(appCtx.filesDir, "book_character").apply { mkdirs() }
            val target = File(avatarDir, "${characterId}_avatar.jpg")
            FileOutputStream(target).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
                out.flush()
            }
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            target.absolutePath
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun scaleBitmap(src: Bitmap, maxSide: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxSide && h <= maxSide) return src
        val ratio = if (w >= h) {
            maxSide.toFloat() / w.toFloat()
        } else {
            maxSide.toFloat() / h.toFloat()
        }
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    private fun buildCharacterVideoPrompt(character: BookCharacter, stylePrompt: String): String {
        return buildString {
            append("为小说角色 ${character.name} 生成一条 5~10 秒的短视频片段。")
            if (character.identity.isNotBlank()) append("身份：${character.identity}。")
            if (character.appearance.isNotBlank()) append("外貌：${character.appearance}。")
            if (character.attributes.isNotBlank()) append("属性：${character.attributes}。")
            if (character.skills.isNotBlank()) append("技能：${character.skills}。")
            if (character.personality.isNotBlank()) append("性格：${character.personality}。")
            append("电影感运镜，角色清晰，光线自然，背景虚化。")
            if (stylePrompt.isNotBlank()) append("风格：$stylePrompt。")
        }
    }

    private fun videoSummaryJson(v: AiGeneratedVideo): JSONObject {
        return JSONObject().apply {
            put("id", v.id)
            put("name", v.name)
            put("prompt", v.prompt)
            put("providerName", v.providerName)
            put("model", v.model)
            put("durationMs", v.durationMs)
            put("width", v.width)
            put("height", v.height)
            put("coverPath", v.coverPath)
            put("localPath", v.localPath)
            put("status", v.status)
            put("favorite", v.favorite)
            put("bookName", v.bookName)
            put("characterName", v.characterName)
            put("sourceType", v.sourceType)
            put("createdAt", v.createdAt)
        }
    }

    private fun videoFullJson(v: AiGeneratedVideo): JSONObject {
        return JSONObject().apply {
            put("id", v.id)
            put("name", v.name)
            put("prompt", v.prompt)
            put("negativePrompt", v.negativePrompt)
            put("providerId", v.providerId)
            put("providerName", v.providerName)
            put("model", v.model)
            put("localPath", v.localPath)
            put("remoteUrl", v.remoteUrl)
            put("coverPath", v.coverPath)
            put("durationMs", v.durationMs)
            put("width", v.width)
            put("height", v.height)
            put("sizeBytes", v.sizeBytes)
            put("aspectRatio", v.aspectRatio)
            put("status", v.status)
            put("failReason", v.failReason)
            put("progress", v.progress)
            put("favorite", v.favorite)
            put("groupId", v.groupId ?: JSONObject.NULL)
            put("bookKey", v.bookKey)
            put("bookName", v.bookName)
            put("bookAuthor", v.bookAuthor)
            put("chapterKey", v.chapterKey)
            put("chapterIndex", v.chapterIndex)
            put("chapterTitle", v.chapterTitle)
            put("characterId", v.characterId)
            put("characterName", v.characterName)
            put("sourceType", v.sourceType)
            put("sourceText", v.sourceText)
            put("createdAt", v.createdAt)
            put("updatedAt", v.updatedAt)
            put("completedAt", v.completedAt)
        }
    }

    private fun function(name: String, description: String, props: JSONObject.() -> Unit): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply(props))
                    put("additionalProperties", false)
                })
            })
        }
    }

    private fun stringProp(description: String) = JSONObject().apply {
        put("type", "string")
        put("description", description)
    }

    private fun intProp(description: String) = JSONObject().apply {
        put("type", "integer")
        put("description", description)
    }

    private fun booleanProp(description: String) = JSONObject().apply {
        put("type", "boolean")
        put("description", description)
    }

    private fun errorJson(message: String): String {
        return JSONObject().apply {
            put("ok", false)
            put("success", false)
            put("error", message)
        }.toString()
    }
}
