package io.legado.app.help.ai

import org.junit.Assert.*
import org.junit.Test

class ArcReelPipelineTest {

    @Test
    fun `project creation has correct defaults`() {
        val project = ArcReelProject(name = "Test Project", bookName = "Test Book")
        assertEquals("Test Project", project.name)
        assertEquals("Test Book", project.bookName)
        assertEquals(ArcReelProject.ProjectStatus.DRAFT, project.status)
        assertEquals(ArcReelProject.PipelinePhase.IDLE, project.currentPhase)
        assertTrue(project.characters.isEmpty())
        assertTrue(project.scenes.isEmpty())
        assertTrue(project.props.isEmpty())
        assertTrue(project.storyboards.isEmpty())
        assertTrue(project.videos.isEmpty())
    }

    @Test
    fun `project copy preserves fields`() {
        val project = ArcReelProject(
            name = "Original",
            bookName = "Book",
            characters = listOf(
                AiCharacterDesignService.CharacterDesign(
                    id = "1", name = "Hero", role = "主角", gender = "男",
                    age = "25", appearance = "tall", personality = "brave",
                    identity = "warrior", skills = "sword", biography = "A hero",
                    relationships = emptyList(), imagePrompt = "prompt", imagePromptCN = "提示词"
                )
            )
        )
        val updated = project.copy(status = ArcReelProject.ProjectStatus.COMPLETED)
        assertEquals(ArcReelProject.ProjectStatus.COMPLETED, updated.status)
        assertEquals(1, updated.characters.size)
        assertEquals("Hero", updated.characters.first().name)
    }

    @Test
    fun `pipeline state initial values`() {
        val state = PipelineState()
        assertEquals(ArcReelProject.PipelinePhase.IDLE, state.phase)
        assertEquals(0f, state.progress)
        assertEquals("", state.message)
        assertNull(state.error)
    }

    @Test
    fun `chapter storyboard creation`() {
        val scenes = listOf(
            AiStoryboardService.SceneCard(
                sceneId = 1, sceneTitle = "Opening", location = "Forest",
                timeOfDay = "Morning", characters = listOf("Hero"),
                description = "A hero walks", visualPrompt = "forest scene"
            )
        )
        val result = AiStoryboardService.StoryboardResult(
            title = "Chapter 1", summary = "A beginning", scenes = scenes, rawMarkdown = "# Chapter 1"
        )
        val chapter = ChapterStoryboard(chapterIndex = 0, chapterTitle = "第1章", result = result)
        assertEquals(0, chapter.chapterIndex)
        assertEquals("第1章", chapter.chapterTitle)
        assertEquals(1, chapter.result.scenes.size)
    }

    @Test
    fun `video output creation`() {
        val video = VideoOutput(
            sceneId = 1, sceneTitle = "Opening",
            videoUrl = "https://example.com/video.mp4"
        )
        assertEquals(1, video.sceneId)
        assertEquals("Opening", video.sceneTitle)
        assertEquals("https://example.com/video.mp4", video.videoUrl)
        assertNull(video.localPath)
    }
}