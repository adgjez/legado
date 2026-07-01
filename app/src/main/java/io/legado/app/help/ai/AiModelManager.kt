package io.legado.app.help.ai

import io.legado.app.help.config.AppConfig
import io.legado.app.ui.main.ai.AiModelConfig
import io.legado.app.ui.main.ai.AiProviderConfig
import io.legado.app.ui.main.ai.AiImageProviderConfig
import org.json.JSONObject

/**
 * ArcReel 集成 — 统一AI模型管理器
 * 为管道各阶段智能选择最优模型配置
 */
object AiModelManager {

    enum class PipelineStage {
        CHARACTER_EXTRACTION,   // 角色提取 - 需要高质量分析
        SCENE_EXTRACTION,       // 场景提取 - 需要高质量分析
        PROP_EXTRACTION,        // 道具提取 - 中等质量
        STORYBOARD_GENERATION,  // 分镜生成 - 需要高质量创意
        STORYBOARD_MERGE,       // 分镜合并 - 中等质量
        SCENE_VISUALIZATION,    // 场景可视化 - 中等质量
        CHARACTER_VISUALIZATION,// 角色可视化 - 中等质量
        IMAGE_GENERATION,       // 图像生成 - 需要图像模型
        VIDEO_GENERATION,       // 视频生成 - 需要视频模型
        REVIEW,                 // 审阅 - 高质量分析
        SUMMARY,                // 摘要 - 轻量模型即可
        CONSISTENCY             // 一致性 - 高质量分析
    }

    data class StageConfig(
        val stage: PipelineStage,
        val preferModel: AiModelConfig? = null,
        val preferImageProvider: AiImageProviderConfig? = null,
        val maxTokens: Int = 4096,
        val temperature: Float = 0.7f
    )

    /**
     * 获取管道阶段的聊天模型配置
     */
    fun chatModelFor(stage: PipelineStage): AiModelConfig? {
        return when (stage) {
            PipelineStage.CHARACTER_EXTRACTION,
            PipelineStage.SCENE_EXTRACTION,
            PipelineStage.STORYBOARD_GENERATION,
            PipelineStage.REVIEW,
            PipelineStage.CONSISTENCY -> AppConfig.aiSummaryModelConfig ?: AppConfig.aiCurrentModelConfig
            PipelineStage.SUMMARY,
            PipelineStage.STORYBOARD_MERGE -> AppConfig.aiSummaryModelConfig ?: AppConfig.aiCurrentModelConfig
            else -> AppConfig.aiCurrentModelConfig
        }
    }

    /**
     * 获取管道阶段的图像模型配置
     */
    fun imageProviderFor(stage: PipelineStage): AiImageProviderConfig? {
        return AppConfig.aiCurrentImageProvider
    }

    /**
     * 估算管道阶段所需的token预算
     */
    fun tokenBudgetFor(stage: PipelineStage): Int {
        return when (stage) {
            PipelineStage.CHARACTER_EXTRACTION -> 16_000
            PipelineStage.SCENE_EXTRACTION -> 12_000
            PipelineStage.PROP_EXTRACTION -> 8_000
            PipelineStage.STORYBOARD_GENERATION -> 16_000
            PipelineStage.STORYBOARD_MERGE -> 12_000
            PipelineStage.SCENE_VISUALIZATION -> 8_000
            PipelineStage.CHARACTER_VISUALIZATION -> 8_000
            PipelineStage.REVIEW -> 12_000
            PipelineStage.SUMMARY -> 4_000
            PipelineStage.CONSISTENCY -> 12_000
            else -> 8_000
        }
    }

    /**
     * 获取管道阶段配置
     */
    fun stageConfig(stage: PipelineStage): StageConfig {
        return StageConfig(
            stage = stage,
            preferModel = chatModelFor(stage),
            preferImageProvider = imageProviderFor(stage),
            maxTokens = tokenBudgetFor(stage),
            temperature = when (stage) {
                PipelineStage.CHARACTER_EXTRACTION,
                PipelineStage.SCENE_EXTRACTION,
                PipelineStage.PROP_EXTRACTION -> 0.3f  // 低温度确保一致性
                PipelineStage.STORYBOARD_GENERATION,
                PipelineStage.SCENE_VISUALIZATION -> 0.8f  // 高温度鼓励创意
                else -> 0.7f
            }
        )
    }

    /**
     * 获取所有可用模型列表（用于UI展示）
     */
    fun availableModels(): List<AiModelConfig> {
        return listOfNotNull(AppConfig.aiCurrentModelConfig)
            .plus(AppConfig.aiSummaryModelConfig?.let { setOf(it) }.orEmpty())
            .distinctBy { it.id }
    }

    /**
     * 获取所有可用图像模型列表
     */
    fun availableImageProviders(): List<AiImageProviderConfig> {
        return listOfNotNull(AppConfig.aiCurrentImageProvider)
    }

    /**
     * 生成管道成本预估
     */
    fun estimatePipelineCost(
        characterCount: Int,
        sceneCount: Int,
        chapterCount: Int,
        generateImages: Boolean,
        generateVideos: Boolean
    ): JSONObject {
        val chatTokens = characterCount * 8_000L + sceneCount * 6_000L + chapterCount * 12_000L
        val imageCount = if (generateImages) characterCount + sceneCount + chapterCount * 3 else 0
        val videoCount = if (generateVideos) chapterCount else 0

        return JSONObject().apply {
            put("estimatedChatTokens", chatTokens)
            put("estimatedImageCount", imageCount)
            put("estimatedVideoCount", videoCount)
            put("estimatedCost", "取决于模型定价")
            put("maxConcurrentRequests", 3)
        }
    }
}