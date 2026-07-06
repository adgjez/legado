package io.legado.app.help.ai

import android.os.Parcelable
import io.legado.app.constant.AppLog
import io.legado.app.data.entities.NovelVideoJob
import io.legado.app.utils.GSON
import kotlinx.parcelize.Parcelize

/**
 * 小说→视频任务的生成参数。序列化后存入 [NovelVideoJob.paramsJson]。
 *
 * 用户在 [NovelVideoConfigDialog] 中编辑这些参数；流水线执行时读取。
 */
@Parcelize
data class NovelVideoParams(
    /** 每章切分的场景数（3-12）。 */
    val sceneCountPerChapter: Int = 7,
    /** 单段视频时长（秒）。 */
    val sceneDurationSeconds: Int = 5,
    /** 视频分辨率，如 "1280x720"。 */
    val resolution: String = "1280x720",
    /** 全局画风前缀，会拼接到每个 imagePrompt 前。 */
    val stylePrompt: String = "anime style, manga art, 2D animation",
    /** 指定图像 Provider；null = 用当前默认。 */
    val imageProviderId: String? = null,
    /** 指定视频 Provider；null = 用当前默认。 */
    val videoProviderId: String? = null,
    /** 指定分镜用 LLM 模型；null = 用 [io.legado.app.help.config.AppConfig.aiSummaryModelId]。 */
    val llmModelId: String? = null,
    /** 角色三视图上限（1-3）。 */
    val maxCharacters: Int = 2,
    /** 单章内场景级并发数（1-4）。 */
    val concurrency: Int = 2,
    /** 是否在生成前弹出剧本审阅页。 */
    val enableReview: Boolean = true,
    /** 完成后是否写入 BookChapter.resourceUrl。 */
    val attachToBookChapter: Boolean = true,
    /** 完成后是否保存到系统相册。 */
    val saveToGallery: Boolean = false,
    /** 单视频任务轮询超时（毫秒）。 */
    val pollTimeoutMs: Long = 600_000,
    /** 视频任务轮询间隔（毫秒）。 */
    val pollIntervalMs: Long = 2_000
) : Parcelable {

    fun toJson(): String = GSON.toJson(this)

    companion object {
        fun fromJson(json: String): NovelVideoParams {
            val parsed = runCatching { GSON.fromJson(json, NovelVideoParams::class.java) }
            if (parsed.isFailure) {
                // paramsJson 损坏时记录原始 JSON，便于排查用户配置为何被回退默认值
                AppLog.put("NovelVideoParams 解析失败，回退默认值：$json", parsed.exceptionOrNull())
                return NovelVideoParams()
            }
            return parsed.getOrNull()?.coerced() ?: NovelVideoParams()
        }

        fun fromJob(job: NovelVideoJob): NovelVideoParams = fromJson(job.paramsJson)
    }

    /**
     * 对反序列化后的字段做边界兜底，防止旧 job 的 paramsJson 损坏
     * （如 sceneDurationSeconds=0 导致 0 秒视频、concurrency=0 除零）。
     */
    private fun coerced(): NovelVideoParams = copy(
        sceneCountPerChapter = sceneCountPerChapter.coerceIn(1, 20),
        sceneDurationSeconds = sceneDurationSeconds.coerceIn(1, 30),
        maxCharacters = maxCharacters.coerceIn(0, 3),
        concurrency = concurrency.coerceIn(1, 4)
    )
}
