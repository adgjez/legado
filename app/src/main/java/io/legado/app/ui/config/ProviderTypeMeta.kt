package io.legado.app.ui.config

import androidx.annotation.StringRes
import io.legado.app.R
import io.legado.app.ui.main.ai.AiImageProviderConfig
import io.legado.app.ui.main.ai.AiVideoProviderConfig

/** 供应商鉴权方案。决定连通性测试与运行时如何携带 API Key。 */
enum class ProviderAuthScheme {
    /** Authorization: Bearer {apiKey}（绝大多数 backend） */
    BEARER,
    /** Authorization: Token {apiKey}（Vidu） */
    TOKEN,
    /** x-goog-api-key: {apiKey}（Gemini） */
    X_GOOG_API_KEY
}

/**
 * 供应商类型元数据。驱动编辑页 UI 渲染：
 * - [displayNameRes] / [description]：类型选择卡片的展示文本
 * - [defaultBaseUrl]：Base URL 字段的 placeholder（留空运行时用此默认）
 * - [defaultModel]：模型字段的 placeholder（留空运行时用此默认）
 * - [authScheme]：连通性测试时携带 Key 的方式
 *
 * defaultBaseUrl / defaultModel 与各 backend companion object 常量保持一致；
 * 修改 backend 默认值时请同步更新此处。来源标注于各条目注释。
 */
data class ProviderTypeMeta(
    val typeId: String,
    @StringRes val displayNameRes: Int,
    val description: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val authScheme: ProviderAuthScheme = ProviderAuthScheme.BEARER
)

/** 视频供应商类型注册表（11 家）。顺序即 UI 展示顺序。 */
object VideoProviderTypeRegistry {
    val metas: List<ProviderTypeMeta> = listOf(
        // ArkVideoBackend: ARK_BASE_URL / DEFAULT_MODEL
        ProviderTypeMeta(
            AiVideoProviderConfig.TYPE_ARK, R.string.ai_video_provider_ark,
            "火山方舟豆包视频", "https://ark.cn-beijing.volces.com/api/v3",
            "doubao-seedance-1-5-pro-251215"
        ),
        // AgnesVideoBackend: DEFAULT_BASE_HOST / DEFAULT_MODEL
        ProviderTypeMeta(
            AiVideoProviderConfig.TYPE_AGNES, R.string.ai_video_provider_agnes,
            "Agnes 视频生成", "https://apihub.agnes-ai.com",
            "agnes-video-v2.0"
        ),
        // SoraVideoBackend: 无默认 baseUrl（必填）
        ProviderTypeMeta(
            AiVideoProviderConfig.TYPE_SORA, R.string.ai_video_provider_sora,
            "OpenAI Sora 视频", "", "sora-2"
        ),
        // VeoVideoBackend: 无默认 baseUrl（必填）
        ProviderTypeMeta(
            AiVideoProviderConfig.TYPE_VEO, R.string.ai_video_provider_veo,
            "Google Veo 视频", "", "veo-3.1"
        ),
        // KlingVideoBackend: 无默认 baseUrl（必填）
        ProviderTypeMeta(
            AiVideoProviderConfig.TYPE_KLING, R.string.ai_video_provider_kling,
            "可灵视频生成", "", "kling-v2-5-turbo"
        ),
        // NewApiVideoBackend: 无默认 baseUrl（中转，必填）
        ProviderTypeMeta(
            AiVideoProviderConfig.TYPE_NEWAPI, R.string.ai_video_provider_newapi,
            "NewAPI 中转视频", "", "kling-v1"
        ),
        // V2VideoBackend: 无默认 baseUrl（中转，必填）
        ProviderTypeMeta(
            AiVideoProviderConfig.TYPE_V2, R.string.ai_video_provider_v2,
            "V2 通用中转视频", "", "kling-v1"
        ),
        // DashScopeVideoBackend: DEFAULT_BASE / DEFAULT_MODEL
        ProviderTypeMeta(
            AiVideoProviderConfig.TYPE_DASHSCOPE, R.string.ai_video_provider_dashscope,
            "阿里 DashScope 视频", "https://dashscope.aliyuncs.com",
            "happyhorse-1.0-i2v"
        ),
        // MiniMaxVideoBackend: DEFAULT_BASE / DEFAULT_MODEL
        ProviderTypeMeta(
            AiVideoProviderConfig.TYPE_MINIMAX, R.string.ai_video_provider_minimax,
            "MiniMax 海螺视频", "https://api.minimaxi.com/v1",
            "MiniMax-Hailuo-2.3"
        ),
        // ViduVideoBackend: DEFAULT_BASE / DEFAULT_MODEL，Token 鉴权
        ProviderTypeMeta(
            AiVideoProviderConfig.TYPE_VIDU, R.string.ai_video_provider_vidu,
            "Vidu 视频生成", "https://api.vidu.cn/ent/v2",
            "viduq3-turbo", ProviderAuthScheme.TOKEN
        ),
        // GrokVideoBackend: DEFAULT_BASE / DEFAULT_MODEL
        ProviderTypeMeta(
            AiVideoProviderConfig.TYPE_GROK, R.string.ai_video_provider_grok,
            "Grok 视频生成", "https://api.x.ai",
            "grok-imagine-video"
        )
    )

    private val byId = metas.associateBy { it.typeId }

    fun get(typeId: String): ProviderTypeMeta =
        byId[typeId] ?: error("未知 video provider type: $typeId")

    fun getOrNull(typeId: String): ProviderTypeMeta? = byId[typeId]
}

/** 图像供应商类型注册表（9 家）。顺序即 UI 展示顺序。 */
object ImageProviderTypeRegistry {
    val metas: List<ProviderTypeMeta> = listOf(
        // OpenAiImageBackend: DEFAULT_BASE / DEFAULT_MODEL
        ProviderTypeMeta(
            AiImageProviderConfig.TYPE_OPENAI, R.string.ai_image_provider_openai,
            "OpenAI 图像生成", "https://api.openai.com/v1",
            "gpt-image-2"
        ),
        // AgnesImageBackend: DEFAULT_BASE / DEFAULT_MODEL
        ProviderTypeMeta(
            AiImageProviderConfig.TYPE_AGNES, R.string.ai_image_provider_agnes,
            "Agnes 图像生成", "https://apihub.agnes-ai.com",
            "agnes-image-2.1-flash"
        ),
        // ArkImageBackend: DEFAULT_BASE / DEFAULT_MODEL
        ProviderTypeMeta(
            AiImageProviderConfig.TYPE_ARK, R.string.ai_image_provider_ark,
            "火山方舟豆包图像", "https://ark.cn-beijing.volces.com/api/v3",
            "doubao-seedream-5-0-lite-260128"
        ),
        // DashScopeImageBackend: DEFAULT_BASE / DEFAULT_MODEL
        ProviderTypeMeta(
            AiImageProviderConfig.TYPE_DASHSCOPE, R.string.ai_image_provider_dashscope,
            "阿里 DashScope 图像", "https://dashscope.aliyuncs.com",
            "qwen-image-2.0"
        ),
        // GeminiImageBackend: DEFAULT_BASE / DEFAULT_MODEL，x-goog-api-key 鉴权
        ProviderTypeMeta(
            AiImageProviderConfig.TYPE_GEMINI, R.string.ai_image_provider_gemini,
            "Google Gemini 图像", "https://generativelanguage.googleapis.com",
            "gemini-3.1-flash-image-preview", ProviderAuthScheme.X_GOOG_API_KEY
        ),
        // GrokImageBackend: DEFAULT_BASE / DEFAULT_MODEL
        ProviderTypeMeta(
            AiImageProviderConfig.TYPE_GROK, R.string.ai_image_provider_grok,
            "Grok 图像生成", "https://api.x.ai",
            "grok-imagine-image"
        ),
        // KlingImageBackend: DEFAULT_BASE / DEFAULT_MODEL
        ProviderTypeMeta(
            AiImageProviderConfig.TYPE_KLING, R.string.ai_image_provider_kling,
            "可灵图像生成", "https://api.klingai.com",
            "kling-image-o1"
        ),
        // MiniMaxImageBackend: DEFAULT_BASE / DEFAULT_MODEL
        ProviderTypeMeta(
            AiImageProviderConfig.TYPE_MINIMAX, R.string.ai_image_provider_minimax,
            "MiniMax 图像生成", "https://api.minimaxi.com/v1",
            "image-01"
        ),
        // ViduImageBackend: DEFAULT_BASE / DEFAULT_MODEL，Token 鉴权
        ProviderTypeMeta(
            AiImageProviderConfig.TYPE_VIDU, R.string.ai_image_provider_vidu,
            "Vidu 图像生成", "https://api.vidu.cn/ent/v2",
            "viduq2", ProviderAuthScheme.TOKEN
        )
    )

    private val byId = metas.associateBy { it.typeId }

    fun get(typeId: String): ProviderTypeMeta =
        byId[typeId] ?: error("未知 image provider type: $typeId")

    fun getOrNull(typeId: String): ProviderTypeMeta? = byId[typeId]
}
