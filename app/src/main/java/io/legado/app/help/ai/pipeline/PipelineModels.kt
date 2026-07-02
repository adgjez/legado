package io.legado.app.help.ai.pipeline

/**
 * 视频流水线数据模型
 * 参考 ArcReel script_models.py，Kotlin 化精简移植
 */

// ============ 枚举 ============

enum class ShotType(val label: String) {
    EXTREME_CLOSE_UP("特写"),
    CLOSE_UP("近景"),
    MEDIUM_CLOSE_UP("中近景"),
    MEDIUM_SHOT("中景"),
    MEDIUM_LONG_SHOT("中远景"),
    LONG_SHOT("远景"),
    EXTREME_LONG_SHOT("大远景"),
    OVER_THE_SHOULDER("过肩镜头"),
    POINT_OF_VIEW("主观视角")
}

enum class CameraMotion(val label: String) {
    STATIC("固定"),
    PAN_LEFT("左摇"),
    PAN_RIGHT("右摇"),
    TILT_UP("上仰"),
    TILT_DOWN("下俯"),
    ZOOM_IN("推"),
    ZOOM_OUT("拉"),
    TRACKING("跟拍")
}

enum class TransitionType(val label: String) {
    CUT("硬切"),
    FADE("淡入淡出"),
    DISSOLVE("叠化")
}

enum class SceneStatus {
    PENDING,           // 待生成
    STORYBOARD_READY,  // 分镜图已生成
    VIDEO_READY,       // 视频已生成
    COMPLETED          // 全部完成（含旁白音频）
}

// ============ 剧本模型 ============

data class Composition(
    val shotType: ShotType = ShotType.MEDIUM_SHOT,
    val lighting: String = "",      // 光线：光源、方向、色温
    val ambiance: String = ""       // 氛围：环境效果
)

data class ImagePrompt(
    val scene: String = "",         // 画面静态描述
    val composition: Composition = Composition()
)

data class Dialogue(
    val speaker: String = "",
    val line: String = ""
)

data class VideoPrompt(
    val action: String = "",        // 动作描述（物理可观察）
    val cameraMotion: CameraMotion = CameraMotion.STATIC,
    val ambianceAudio: String = "", // 环境音效
    val dialogue: List<Dialogue> = emptyList()
)

data class GeneratedAssets(
    var storyboardImagePath: String? = null,
    var videoClipPath: String? = null,
    var narrationAudioPath: String? = null,
    var status: SceneStatus = SceneStatus.PENDING
)

/**
 * 单个场景/分镜
 * 对应 ArcReel 的 NarrationSegment
 */
data class PipelineScene(
    val sceneId: String,               // 格式 E{集}S{序号}
    val durationSeconds: Int = 5,      // 时长（秒）
    val segmentBreak: Boolean = false, // 是否场景切换点
    val novelText: String = "",        // 小说原文
    val charactersInScene: List<String> = emptyList(),
    val scenesInSegment: List<String> = emptyList(),
    val props: List<String> = emptyList(),
    val imagePrompt: ImagePrompt = ImagePrompt(),
    val videoPrompt: VideoPrompt = VideoPrompt(),
    val transitionToNext: TransitionType = TransitionType.CUT,
    val note: String? = null,
    val generatedAssets: GeneratedAssets = GeneratedAssets()
)

/**
 * 剧集脚本
 * 对应 ArcReel 的 NarrationEpisodeScript
 */
data class EpisodeScript(
    val title: String = "",
    val episode: Int = 1,
    val contentMode: String = "narration",
    val durationSeconds: Int = 0,
    val novelTitle: String = "",
    val novelChapter: String = "",
    val scenes: List<PipelineScene> = emptyList()
) {
    val totalDuration: Int get() = scenes.sumOf { it.durationSeconds }
}

/**
 * 流水线项目配置
 */
data class PipelineConfig(
    val videoProvider: VideoProvider = VideoProvider.AGNES,
    val imageWidth: Int = 1024,
    val imageHeight: Int = 768,
    val videoWidth: Int = 1152,
    val videoHeight: Int = 768,
    val videoFrameRate: Int = 24,
    val sceneDurationSeconds: Int = 5,
    val maxConcurrentGenerations: Int = 1,
    val addNarrationAudio: Boolean = false,
    val addBackgroundMusic: Boolean = false,
    val outputResolution: String = "1152x768"
)

enum class VideoProvider(val label: String, val baseUrl: String) {
    AGNES("Agnes AI", "https://apihub.agnes-ai.com"),
    OPENAI("OpenAI", "https://api.openai.com"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com"),
    CUSTOM("自定义", "")
}

/**
 * 流水线进度
 */
data class PipelineProgress(
    val phase: PipelinePhase,
    val currentScene: Int = 0,
    val totalScenes: Int = 0,
    val percent: Int = 0,
    val message: String = ""
)

enum class PipelinePhase {
    GENERATING_SCRIPT,      // 生成剧本
    GENERATING_STORYBOARDS, // 生成分镜图
    GENERATING_VIDEOS,      // 生成视频片段
    COMPOSING_VIDEO,        // 合成最终视频
    COMPLETED,
    FAILED
}
