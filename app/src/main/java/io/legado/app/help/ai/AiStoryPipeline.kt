package io.legado.app.help.ai

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.AiStoryPlaylist
import io.legado.app.help.ai.AiCharacterConsistency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

object AiStoryPipeline {

    data class PipelineProgress(
        val stage: String, // "planning" / "generating_images" / "generating_videos" / "done" / "failed"
        val current: Int,
        val total: Int,
        val message: String,
        val previewImageId: String? = null
    )

    suspend fun execute(
        chapterText: String,
        bookKey: String,
        bookName: String,
        bookAuthor: String,
        chapterTitle: String,
        characterDescriptions: String = "",
        onProgress: (PipelineProgress) -> Unit = {}
    ): AiStoryPlaylist {
        return withContext(Dispatchers.IO) {
            var playlist: AiStoryPlaylist? = null
            try {
                // Step 1: Plan scenes
                onProgress(PipelineProgress("planning", 0, 1, "正在分析章节，生成分镜脚本..."))
                val context = AiStoryDirector.StoryContext(
                    chapterText = chapterText,
                    bookName = bookName,
                    bookAuthor = bookAuthor,
                    chapterTitle = chapterTitle,
                    characterDescriptions = characterDescriptions
                )
                val plan = AiStoryDirector.planScenes(context)
                if (plan.scenes.isEmpty()) error("分镜规划失败，未生成任何场景")

                // Create playlist entity
                val playlistId = UUID.randomUUID().toString()
                val scenes = AiStoryDirector.plannedScenesToEntities(playlistId, plan).toMutableList()
                playlist = AiStoryPlaylist(
                    id = playlistId,
                    bookKey = bookKey,
                    bookName = bookName,
                    chapterTitle = chapterTitle,
                    sceneCount = plan.scenes.size,
                    totalDuration = io.legado.app.help.ai.AiStoryPlaylist.calculateTotalDuration(scenes),
                    status = "processing"
                )
                appDb.aiStoryPlaylistDao.insert(playlist)

                // Save scenes
                scenes.forEach { appDb.aiStorySceneDao.insert(it) }

                // Step 2: Generate keyframe images
                onProgress(PipelineProgress("generating_images", 0, scenes.size, "正在生成关键帧图片..."))
                val imageProvider = AiImageService.currentProviderOrNull()
                for ((index, scene) in scenes.withIndex()) {
                    onProgress(PipelineProgress("generating_images", index, scenes.size,
                        "生成关键帧 ${index + 1}/${scenes.size}..."))
                    try {
                        val metadata = AiImageGalleryManager.ImageMetadata(
                            bookName = bookName,
                            bookAuthor = bookAuthor,
                            chapterTitle = chapterTitle,
                            sourceType = AiImageGalleryManager.SOURCE_TYPE_STORY_MODE
                        )
                        // Check for character reference images
                        val refAddition = AiCharacterConsistency.buildReferencePromptAddition(
                            characterId = "", // or extract from scene if available
                            providerSupportsReference = imageProvider?.supportsReferenceImage ?: false
                        )
                        val effectivePrompt = if (refAddition.isNotBlank()) {
                            scene.visualPrompt + refAddition
                        } else {
                            scene.visualPrompt
                        }
                        val image = AiImageService.generateAndStore(effectivePrompt, imageProvider, metadata)
                        appDb.aiStorySceneDao.updateImage(scene.id, image.id, "image_done")
                        // Update scene in local list
                        scenes[index] = scene.copy(imageId = image.id, status = "image_done")
                        // Send first image as preview
                        if (index == 0) {
                            onProgress(PipelineProgress("generating_images", index, scenes.size,
                                "关键帧预览", previewImageId = image.id))
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        AppLog.put("Failed to generate keyframe for scene ${index}", e)
                        appDb.aiStorySceneDao.updateStatus(scene.id, "failed", e.message ?: "Unknown error")
                        scenes[index] = scene.copy(status = "failed", error = e.message)
                    }
                }

                // Step 3: Generate videos from keyframes
                val videoProvider = AiVideoService.currentProviderOrNull()
                val scenesWithImages = scenes.filter { it.status == "image_done" }
                onProgress(PipelineProgress("generating_videos", 0, scenesWithImages.size, "正在生成视频片段..."))
                for ((index, scene) in scenesWithImages.withIndex()) {
                    onProgress(PipelineProgress("generating_videos", index, scenesWithImages.size,
                        "生成视频 ${index + 1}/${scenesWithImages.size}..."))
                    try {
                        val metadata = AiVideoGalleryManager.VideoMetadata(
                            bookName = bookName,
                            bookAuthor = bookAuthor,
                            chapterTitle = chapterTitle,
                            sourceType = AiVideoGalleryManager.SOURCE_TYPE_STORY_MODE
                        )
                        val video = AiVideoService.generateAndStore(
                            prompt = scene.visualPrompt,
                            inputImageId = scene.imageId,
                            tailImageId = null,
                            referenceImageId = null,
                            provider = videoProvider,
                            metadata = metadata
                        )
                        appDb.aiStorySceneDao.updateVideo(scene.id, video.id, "video_done")
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        AppLog.put("Failed to generate video for scene ${index}", e)
                        appDb.aiStorySceneDao.updateStatus(scene.id, "failed", e.message ?: "Unknown error")
                    }
                }

                // Update playlist status
                val successCount = scenes.count { it.status == "video_done" }
                val finalStatus = when {
                    successCount == 0 -> "failed"
                    successCount < scenes.size -> "partial"
                    else -> "done"
                }
                val updatedPlaylist = playlist.copy(status = finalStatus)
                appDb.aiStoryPlaylistDao.insert(updatedPlaylist)
                onProgress(PipelineProgress("done", scenes.size, scenes.size, "分镜视频生成完成！"))

                updatedPlaylist
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.put("Story pipeline failed", e)
                runCatching {
                    playlist?.let { appDb.aiStoryPlaylistDao.insert(it.copy(status = "failed")) }
                }
                onProgress(PipelineProgress("failed", 0, 0, "生成失败: ${e.message}"))
                throw e
            }
        }
    }
}
