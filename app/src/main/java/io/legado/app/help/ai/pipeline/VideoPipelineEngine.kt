package io.legado.app.help.ai.pipeline

import android.util.Log
import io.legado.app.help.ai.AiCreationService
import io.legado.app.help.ai.AiImageService
import io.legado.app.ui.main.ai.AiImageProviderConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File

/**
 * 视频流水线引擎
 *
 * 编排：剧本生成 → 分镜图生成 → 视频片段生成 → 最终合成
 * 参考 ArcReel 的 generation_tasks + storyboard_sequence 流程
 */
class VideoPipelineEngine(
    private val config: PipelineConfig = PipelineConfig()
) {
    private val tag = "VideoPipelineEngine"

    /**
     * 执行完整流水线
     *
     * @param novelText 小说章节文本
     * @param novelTitle 小说标题
     * @param chapterName 章节名
     * @param episode 集号
     * @param outputDir 输出目录
     * @param onProgress 进度回调
     * @return 合成后的视频文件路径，null 表示失败
     */
    suspend fun execute(
        novelText: String,
        novelTitle: String = "",
        chapterName: String = "",
        episode: Int = 1,
        outputDir: File,
        onProgress: (PipelineProgress) -> Unit = {}
    ): String? {
        val totalPhases = 4

        // ═══ Phase 1: 生成剧本 ═══
        onProgress(PipelineProgress(
            PipelinePhase.GENERATING_SCRIPT, 0, 0, 0, "正在生成分镜剧本..."
        ))

        val script = try {
            ScriptGenerator.generateFromChapter(
                novelText = novelText,
                novelTitle = novelTitle,
                chapterName = chapterName,
                episode = episode,
                sceneDuration = config.sceneDurationSeconds,
                onProgress = { msg ->
                    onProgress(PipelineProgress(
                        PipelinePhase.GENERATING_SCRIPT, 0, 0, 5, msg
                    ))
                }
            )
        } catch (e: Exception) {
            Log.e(tag, "剧本生成失败", e)
            onProgress(PipelineProgress(PipelinePhase.FAILED, message = "剧本生成失败: ${e.message}"))
            return null
        }

        if (script.scenes.isEmpty()) {
            onProgress(PipelineProgress(PipelinePhase.FAILED, message = "剧本场景为空"))
            return null
        }

        val totalScenes = script.scenes.size

        // ═══ Phase 2: 生成分镜图 ═══
        onProgress(PipelineProgress(
            PipelinePhase.GENERATING_STORYBOARDS, 0, totalScenes, 25, "开始生成分镜图..."
        ))

        val storyboardDir = File(outputDir, "storyboards").also { it.mkdirs() }
        val semaphore = Semaphore(config.maxConcurrentGenerations)

        coroutineScope {
            script.scenes.mapIndexed { index, scene ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        generateStoryboardImage(scene, storyboardDir, index, totalScenes, onProgress)
                    }
                }
            }.awaitAll()
        }

        // ═══ Phase 3: 生成视频片段 ═══
        onProgress(PipelineProgress(
            PipelinePhase.GENERATING_VIDEOS, 0, totalScenes, 50, "开始生成视频片段..."
        ))

        val videoDir = File(outputDir, "clips").also { it.mkdirs() }

        coroutineScope {
            script.scenes.mapIndexed { index, scene ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        generateVideoClip(scene, videoDir, index, totalScenes, onProgress)
                    }
                }
            }.awaitAll()
        }

        // ═══ Phase 4: 合成最终视频 ═══
        onProgress(PipelineProgress(
            PipelinePhase.COMPOSING_VIDEO, 0, totalScenes, 85, "正在合成最终视频..."
        ))

        val clips = script.scenes.mapNotNull { it.generatedAssets.videoClipPath }
        val outputPath = if (clips.isNotEmpty()) {
            val outputFile = File(outputDir, "${novelTitle}_${chapterName}_E${episode}.mp4")
            VideoComposer.compose(
                inputPaths = clips,
                outputPath = outputFile.absolutePath,
                onProgress = { percent ->
                    onProgress(PipelineProgress(
                        PipelinePhase.COMPOSING_VIDEO, 0, totalScenes, 85 + percent / 7, "合成中 $percent%"
                    ))
                }
            )
        } else null

        if (outputPath != null) {
            onProgress(PipelineProgress(
                PipelinePhase.COMPLETED, totalScenes, totalScenes, 100,
                "完成！视频已保存到 $outputPath"
            ))
        } else {
            onProgress(PipelineProgress(
                PipelinePhase.COMPLETED, totalScenes, totalScenes, 100,
                "流水线完成，但视频合成失败。分镜图和片段已保存到 $outputDir"
            ))
        }

        return outputPath
    }

    /**
     * 只生成分镜图（不生成视频），适合预览场景
     */
    suspend fun generateStoryboardsOnly(
        novelText: String,
        novelTitle: String = "",
        chapterName: String = "",
        episode: Int = 1,
        outputDir: File,
        onProgress: (PipelineProgress) -> Unit = {}
    ): EpisodeScript {
        val script = ScriptGenerator.generateFromChapter(
            novelText = novelText,
            novelTitle = novelTitle,
            chapterName = chapterName,
            episode = episode,
            sceneDuration = config.sceneDurationSeconds,
            onProgress = { msg ->
                onProgress(PipelineProgress(PipelinePhase.GENERATING_SCRIPT, message = msg))
            }
        )

        val storyboardDir = File(outputDir, "storyboards").also { it.mkdirs() }
        val semaphore = Semaphore(config.maxConcurrentGenerations)

        coroutineScope {
            script.scenes.mapIndexed { index, scene ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        generateStoryboardImage(scene, storyboardDir, index, script.scenes.size, onProgress)
                    }
                }
            }.awaitAll()
        }

        return script
    }

    /**
     * 从已有剧本继续生成（跳过剧本生成阶段）
     */
    suspend fun continueFromScript(
        script: EpisodeScript,
        outputDir: File,
        skipStoryboards: Boolean = false,
        onProgress: (PipelineProgress) -> Unit = {}
    ): String? {
        val totalScenes = script.scenes.size

        if (!skipStoryboards) {
            val storyboardDir = File(outputDir, "storyboards").also { it.mkdirs() }
            val semaphore = Semaphore(config.maxConcurrentGenerations)

            coroutineScope {
                script.scenes.mapIndexed { index, scene ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            generateStoryboardImage(scene, storyboardDir, index, totalScenes, onProgress)
                        }
                    }
                }.awaitAll()
            }
        }

        val videoDir = File(outputDir, "clips").also { it.mkdirs() }
        val semaphore = Semaphore(config.maxConcurrentGenerations)

        coroutineScope {
            script.scenes.mapIndexed { index, scene ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        generateVideoClip(scene, videoDir, index, totalScenes, onProgress)
                    }
                }
            }.awaitAll()
        }

        val clips = script.scenes.mapNotNull { it.generatedAssets.videoClipPath }
        val outputFile = File(outputDir, "${script.novelTitle}_${script.novelChapter}_E${script.episode}.mp4")
        return VideoComposer.compose(
            inputPaths = clips,
            outputPath = outputFile.absolutePath
        )
    }

    private suspend fun generateStoryboardImage(
        scene: PipelineScene,
        outputDir: File,
        index: Int,
        total: Int,
        onProgress: (PipelineProgress) -> Unit
    ) {
        if (scene.generatedAssets.status.ordinal >= SceneStatus.STORYBOARD_READY.ordinal) return

        onProgress(PipelineProgress(
            PipelinePhase.GENERATING_STORYBOARDS, index + 1, total,
            25 + (index + 1) * 25 / total,
            "生成分镜图 ${index + 1}/$total: ${scene.sceneId}"
        ))

        try {
            // 构建图片生成 prompt
            val prompt = buildImagePromptString(scene)

            // 优先用 AiCreationService（Agnes），fallback 到 AiImageService
            val imageUrl = try {
                val result = AiCreationService.textToImage(
                    prompt = prompt,
                    size = "${config.imageWidth}x${config.imageHeight}"
                )
                result.url
            } catch (_: Exception) {
                // fallback: 使用 AiImageService 的已配置 provider
                try {
                    AiImageService.generate(prompt)
                } catch (_: Exception) {
                    null
                }
            }

            if (imageUrl != null) {
                // 下载图片到本地
                val localFile = File(outputDir, "scene_${scene.sceneId}.png")
                downloadImage(imageUrl, localFile)
                scene.generatedAssets.storyboardImagePath = localFile.absolutePath
            }
            scene.generatedAssets.status = SceneStatus.STORYBOARD_READY
        } catch (e: Exception) {
            Log.w(tag, "分镜图生成失败: ${scene.sceneId}", e)
            // 标记为已尝试但失败，继续后续场景
            scene.generatedAssets.status = SceneStatus.STORYBOARD_READY
        }
    }

    private suspend fun generateVideoClip(
        scene: PipelineScene,
        outputDir: File,
        index: Int,
        total: Int,
        onProgress: (PipelineProgress) -> Unit
    ) {
        if (scene.generatedAssets.status.ordinal >= SceneStatus.VIDEO_READY.ordinal) return

        onProgress(PipelineProgress(
            PipelinePhase.GENERATING_VIDEOS, index + 1, total,
            50 + (index + 1) * 35 / total,
            "生成视频 ${index + 1}/$total: ${scene.sceneId}"
        ))

        try {
            val videoPrompt = buildVideoPromptString(scene)
            val numFrames = scene.durationSeconds * config.videoFrameRate + 1

            val result = when {
                // 如果有分镜图，用图生视频
                scene.generatedAssets.storyboardImagePath != null -> {
                    val imagePath = scene.generatedAssets.storyboardImagePath!!
                    // 需要把本地路径转为可访问 URL，或直接用 imageToVideo
                    AiCreationService.imageToVideo(
                        prompt = videoPrompt,
                        imageUrl = imagePath,  // 本地路径，Agnes 可能需要 URL
                        numFrames = numFrames,
                        width = config.videoWidth,
                        height = config.videoHeight,
                        onProgress = { progress ->
                            onProgress(PipelineProgress(
                                PipelinePhase.GENERATING_VIDEOS, index + 1, total,
                                50 + (index + 1) * 35 / total,
                                "视频 ${scene.sceneId}: ${progress.statusText} ${progress.percent}%"
                            ))
                        }
                    )
                }
                // 否则文生视频
                else -> {
                    AiCreationService.textToVideo(
                        prompt = videoPrompt,
                        numFrames = numFrames,
                        width = config.videoWidth,
                        height = config.videoHeight,
                        onProgress = { progress ->
                            onProgress(PipelineProgress(
                                PipelinePhase.GENERATING_VIDEOS, index + 1, total,
                                50 + (index + 1) * 35 / total,
                                "视频 ${scene.sceneId}: ${progress.statusText} ${progress.percent}%"
                            ))
                        }
                    )
                }
            }

            scene.generatedAssets.videoClipPath = result.localPath ?: result.videoUrl
            scene.generatedAssets.status = SceneStatus.VIDEO_READY
        } catch (e: Exception) {
            Log.w(tag, "视频生成失败: ${scene.sceneId}", e)
        }
    }

    private fun buildImagePromptString(scene: PipelineScene): String {
        val sb = StringBuilder()
        val img = scene.imagePrompt
        val comp = img.composition

        // 角色信息
        if (scene.charactersInScene.isNotEmpty()) {
            sb.append("Characters: ${scene.charactersInScene.joinToString(", ")}. ")
        }

        // 画面描述
        sb.append(img.scene)

        // 构图信息
        if (comp.shotType != ShotType.MEDIUM_SHOT) {
            sb.append(". Shot: ${comp.shotType.name.lowercase().replace("_", " ")}")
        }
        if (comp.lighting.isNotBlank()) {
            sb.append(". Lighting: ${comp.lighting}")
        }
        if (comp.ambiance.isNotBlank()) {
            sb.append(". Atmosphere: ${comp.ambiance}")
        }

        return sb.toString()
    }

    private fun buildVideoPromptString(scene: PipelineScene): String {
        val sb = StringBuilder()
        val vp = scene.videoPrompt

        // 动作描述
        sb.append(vp.action)

        // 镜头运动
        if (vp.cameraMotion != CameraMotion.STATIC) {
            sb.append(". Camera: ${vp.cameraMotion.name.lowercase().replace("_", " ")}")
        }

        // 角色
        if (scene.charactersInScene.isNotEmpty()) {
            sb.append(". Characters: ${scene.charactersInScene.joinToString(", ")}")
        }

        // 对话（视频模型可能用不到，但 prompt 中包含可增加语义连贯性）
        if (vp.dialogue.isNotEmpty()) {
            sb.append(". Dialogue: ")
            vp.dialogue.forEach { d ->
                sb.append("${d.speaker}: \"${d.line}\" ")
            }
        }

        return sb.toString()
    }

    private suspend fun downloadImage(url: String, targetFile: File) = withContext(Dispatchers.IO) {
        try {
            if (targetFile.exists()) return@withContext
            val client = io.legado.app.help.http.okHttpClient
            val request = okhttp3.Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "图片下载失败: $url", e)
        }
    }
}
