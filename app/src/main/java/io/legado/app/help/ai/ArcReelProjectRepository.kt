package io.legado.app.help.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import splitties.init.appCtx
import java.io.File

object ArcReelProjectRepository {

    private const val PROJECTS_DIR = "arcreel_projects"
    private const val PROGRESS_DIR = "arcreel_progress"

    private fun projectsDir(): File {
        val dir = File(appCtx.cacheDir, PROJECTS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun progressDir(): File {
        val dir = File(appCtx.cacheDir, PROGRESS_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun projectFile(projectId: String): File = File(projectsDir(), "${projectId}.json")
    private fun progressFile(projectId: String): File = File(progressDir(), "${projectId}.json")

    suspend fun saveProject(project: ArcReelProject): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val json = projectToJson(project)
            projectFile(project.id).writeText(json.toString(2))
        }
    }

    suspend fun loadProject(projectId: String): Result<ArcReelProject> = withContext(Dispatchers.IO) {
        runCatching {
            val file = projectFile(projectId)
            if (!file.exists()) throw IllegalStateException("项目文件不存在")
            jsonToProject(JSONObject(file.readText()))
        }
    }

    suspend fun deleteProject(projectId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            projectFile(projectId).delete()
            progressFile(projectId).delete()
        }
    }

    suspend fun listProjects(): Result<List<ArcReelProject>> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = projectsDir()
            if (!dir.exists()) return@runCatching emptyList()
            dir.listFiles()
                ?.filter { it.extension == "json" }
                ?.mapNotNull { runCatching { jsonToProject(JSONObject(it.readText())) }.getOrNull() }
                ?.sortedByDescending { it.updatedAt }
                ?: emptyList()
        }
    }

    suspend fun savePipelineProgress(projectId: String, phase: ArcReelProject.PipelinePhase, progress: Float, message: String) = withContext(Dispatchers.IO) {
        runCatching {
            val json = JSONObject().apply {
                put("projectId", projectId)
                put("phase", phase.name)
                put("progress", progress.toDouble())
                put("message", message)
                put("timestamp", System.currentTimeMillis())
            }
            progressFile(projectId).writeText(json.toString())
        }
    }

    suspend fun loadPipelineProgress(projectId: String): Result<PipelineState?> = withContext(Dispatchers.IO) {
        runCatching {
            val file = progressFile(projectId)
            if (!file.exists()) return@runCatching null
            val json = JSONObject(file.readText())
            PipelineState(
                phase = try { ArcReelProject.PipelinePhase.valueOf(json.getString("phase")) } catch (_: Exception) { ArcReelProject.PipelinePhase.IDLE },
                progress = json.optDouble("progress", 0.0).toFloat(),
                message = json.optString("message", "")
            )
        }
    }

    private fun projectToJson(project: ArcReelProject): JSONObject = JSONObject().apply {
        put("id", project.id)
        put("name", project.name)
        put("bookName", project.bookName)
        put("author", project.author)
        put("bookUrl", project.bookUrl)
        put("createdAt", project.createdAt)
        put("updatedAt", project.updatedAt)
        put("worldStyle", project.worldStyle)
        put("artStyle", project.artStyle)
        put("status", project.status.name)
        put("characters", JSONArray().apply {
            project.characters.forEach { put(characterToJson(it)) }
        })
        put("scenes", JSONArray().apply {
            project.scenes.forEach { put(sceneToJson(it)) }
        })
        put("props", JSONArray().apply {
            project.props.forEach { put(propToJson(it)) }
        })
        put("storyboards", JSONArray().apply {
            project.storyboards.forEach { put(storyboardToJson(it)) }
        })
        put("videos", JSONArray().apply {
            project.videos.forEach { put(videoToJson(it)) }
        })
    }

    private fun jsonToProject(json: JSONObject): ArcReelProject {
        val characters = json.optJSONArray("characters")?.let { arr ->
            (0 until arr.length()).map { jsonToCharacter(arr.getJSONObject(it)) }
        } ?: emptyList()
        val scenes = json.optJSONArray("scenes")?.let { arr ->
            (0 until arr.length()).map { jsonToScene(arr.getJSONObject(it)) }
        } ?: emptyList()
        val props = json.optJSONArray("props")?.let { arr ->
            (0 until arr.length()).map { jsonToProp(arr.getJSONObject(it)) }
        } ?: emptyList()
        val storyboards = json.optJSONArray("storyboards")?.let { arr ->
            (0 until arr.length()).map { jsonToStoryboard(arr.getJSONObject(it)) }
        } ?: emptyList()
        val videos = json.optJSONArray("videos")?.let { arr ->
            (0 until arr.length()).map { jsonToVideo(arr.getJSONObject(it)) }
        } ?: emptyList()

        return ArcReelProject(
            id = json.getString("id"),
            name = json.getString("name"),
            bookName = json.optString("bookName"),
            author = json.optString("author"),
            bookUrl = json.optString("bookUrl"),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            characters = characters,
            scenes = scenes,
            props = props,
            worldStyle = json.optString("worldStyle"),
            artStyle = json.optString("artStyle"),
            storyboards = storyboards,
            videos = videos,
            status = try { ArcReelProject.ProjectStatus.valueOf(json.optString("status", "DRAFT")) } catch (_: Exception) { ArcReelProject.ProjectStatus.DRAFT }
        )
    }

    private fun characterToJson(c: AiCharacterDesignService.CharacterDesign): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("name", c.name)
        put("role", c.role)
        put("gender", c.gender)
        put("age", c.age)
        put("appearance", c.appearance)
        put("personality", c.personality)
        put("identity", c.identity)
        put("skills", c.skills)
        put("biography", c.biography)
        put("imagePrompt", c.imagePrompt)
        put("imagePromptCN", c.imagePromptCN)
        put("generatedImageUrl", c.generatedImageUrl ?: "")
        put("relationships", JSONArray().apply {
            c.relationships.forEach { r ->
                put(JSONObject().apply {
                    put("targetName", r.targetName)
                    put("relation", r.relation)
                })
            }
        })
        put("consistencyTags", JSONArray().apply { c.consistencyTags.forEach { put(it) } })
    }

    private fun jsonToCharacter(json: JSONObject): AiCharacterDesignService.CharacterDesign = AiCharacterDesignService.CharacterDesign(
        id = json.getString("id"),
        name = json.getString("name"),
        role = json.optString("role"),
        gender = json.optString("gender"),
        age = json.optString("age"),
        appearance = json.optString("appearance"),
        personality = json.optString("personality"),
        identity = json.optString("identity"),
        skills = json.optString("skills"),
        biography = json.optString("biography"),
        imagePrompt = json.optString("imagePrompt"),
        imagePromptCN = json.optString("imagePromptCN"),
        generatedImageUrl = json.optString("generatedImageUrl").ifBlank { null },
        relationships = json.optJSONArray("relationships")?.let { arr ->
            (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                AiCharacterDesignService.RelationShip(r.getString("targetName"), r.getString("relation"))
            }
        } ?: emptyList(),
        consistencyTags = json.optJSONArray("consistencyTags")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()
    )

    private fun sceneToJson(s: AiCharacterDesignService.SceneDesign): JSONObject = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("type", s.type)
        put("description", s.description)
        put("atmosphere", s.atmosphere)
        put("imagePrompt", s.imagePrompt)
        put("imagePromptCN", s.imagePromptCN)
        put("generatedImageUrl", s.generatedImageUrl ?: "")
    }

    private fun jsonToScene(json: JSONObject): AiCharacterDesignService.SceneDesign = AiCharacterDesignService.SceneDesign(
        id = json.getString("id"),
        name = json.getString("name"),
        type = json.optString("type"),
        description = json.optString("description"),
        atmosphere = json.optString("atmosphere"),
        imagePrompt = json.optString("imagePrompt"),
        imagePromptCN = json.optString("imagePromptCN"),
        generatedImageUrl = json.optString("generatedImageUrl").ifBlank { null }
    )

    private fun propToJson(p: AiCharacterDesignService.PropDesign): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("type", p.type)
        put("description", p.description)
        put("imagePrompt", p.imagePrompt)
        put("imagePromptCN", p.imagePromptCN)
    }

    private fun jsonToProp(json: JSONObject): AiCharacterDesignService.PropDesign = AiCharacterDesignService.PropDesign(
        id = json.getString("id"),
        name = json.getString("name"),
        type = json.optString("type"),
        description = json.optString("description"),
        imagePrompt = json.optString("imagePrompt"),
        imagePromptCN = json.optString("imagePromptCN")
    )

    private fun storyboardToJson(sb: ChapterStoryboard): JSONObject = JSONObject().apply {
        put("chapterIndex", sb.chapterIndex)
        put("chapterTitle", sb.chapterTitle)
        put("result", JSONObject().apply {
            put("title", sb.result.title)
            put("summary", sb.result.summary)
            put("scenes", JSONArray().apply {
                sb.result.scenes.forEach { s ->
                    put(JSONObject().apply {
                        put("sceneId", s.sceneId)
                        put("sceneTitle", s.sceneTitle)
                        put("location", s.location)
                        put("timeOfDay", s.timeOfDay)
                        put("characters", JSONArray().apply { s.characters.forEach { put(it) } })
                        put("description", s.description)
                        put("visualPrompt", s.visualPrompt)
                        put("dialogue", s.dialogue)
                    })
                }
            })
            put("rawMarkdown", sb.result.rawMarkdown)
        })
        put("sceneImages", JSONObject().apply {
            sb.sceneImages.forEach { (k, v) -> put(k.toString(), v) }
        })
    }

    private fun jsonToStoryboard(json: JSONObject): ChapterStoryboard {
        val resultJson = json.getJSONObject("result")
        val scenes = resultJson.getJSONArray("scenes").let { arr ->
            (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i)
                AiStoryboardService.SceneCard(
                    sceneId = s.getInt("sceneId"),
                    sceneTitle = s.getString("sceneTitle"),
                    location = s.optString("location"),
                    timeOfDay = s.optString("timeOfDay"),
                    characters = s.optJSONArray("characters")?.let { ca ->
                        (0 until ca.length()).map { ca.getString(it) }
                    } ?: emptyList(),
                    description = s.optString("description"),
                    visualPrompt = s.optString("visualPrompt"),
                    dialogue = s.optString("dialogue")
                )
            }
        }
        val result = AiStoryboardService.StoryboardResult(
            title = resultJson.getString("title"),
            summary = resultJson.getString("summary"),
            scenes = scenes,
            rawMarkdown = resultJson.optString("rawMarkdown")
        )
        val sceneImages = json.optJSONObject("sceneImages")?.let { si ->
            si.keys().asSequence().associate { key -> key.toInt() to si.getString(key) }
        } ?: emptyMap()
        return ChapterStoryboard(
            chapterIndex = json.getInt("chapterIndex"),
            chapterTitle = json.getString("chapterTitle"),
            result = result,
            sceneImages = sceneImages
        )
    }

    private fun videoToJson(v: VideoOutput): JSONObject = JSONObject().apply {
        put("id", v.id)
        put("sceneId", v.sceneId)
        put("sceneTitle", v.sceneTitle)
        put("videoUrl", v.videoUrl)
        put("localPath", v.localPath ?: "")
        put("createdAt", v.createdAt)
    }

    private fun jsonToVideo(json: JSONObject): VideoOutput = VideoOutput(
        id = json.getString("id"),
        sceneId = json.getInt("sceneId"),
        sceneTitle = json.getString("sceneTitle"),
        videoUrl = json.getString("videoUrl"),
        localPath = json.optString("localPath").ifBlank { null },
        createdAt = json.optLong("createdAt", System.currentTimeMillis())
    )
}