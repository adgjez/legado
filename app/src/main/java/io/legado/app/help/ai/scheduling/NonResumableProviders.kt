package io.legado.app.help.ai.scheduling

/**
 * 不支持 resume 的 video provider 集合（移植 ArcReel `generation_worker.py:43-56`）。
 *
 * 这些 backend 是同步型（请求-响应一次完成，无 provider 端 job_id），孤儿 running 任务
 * 直接 mark failed `[resume_unsupported_provider]`，**不主动 requeue**——避免对已提交
 * 给供应商的请求二次扣费。
 *
 * 可 resume 的 backend（提交-轮询型，实现 `VideoBackend.resumeVideo`）：
 * ark / gemini / openai / newapi / agnes / dashscope / kling。
 */
object NonResumableProviders {
    /** Grok：同步型 video backend，无 job_id（请求-响应一次完成）。 */
    const val PROVIDER_GROK = "grok"
    /** Vidu：generate 内联 poll，未抽出独立 `resumeVideo`。 */
    const val PROVIDER_VIDU = "vidu"

    val NON_RESUMABLE_VIDEO_PROVIDERS: Set<String> = setOf(PROVIDER_GROK, PROVIDER_VIDU)

    /** 判断 providerId 是否在不可 resume 集合内（容忍大小写/前后缀装饰）。 */
    fun isNonResumable(providerId: String?): Boolean {
        if (providerId.isNullOrBlank()) return false
        val lower = providerId.lowercase()
        return NON_RESUMABLE_VIDEO_PROVIDERS.any { lower.contains(it) }
    }
}
