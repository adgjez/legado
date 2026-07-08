package io.legado.app.help.ai.scheduling

/**
 * 并发容量配置表（移植 ArcReel `generation_worker.py:89-205` 的 CapacityTable）。
 *
 * 配置真相：per-provider × per-media_type 的并发上限标量。
 *
 * 三态语义（与 ArcReel `get` 一致）：
 * - provider 已知 + media 在表 → 登记值（可能 0=不支持）
 * - provider 已知 + media 不在表 → 0（不支持）
 * - provider 整个未知 → [DEFAULTS] 兜底
 *
 * 装载来源：`AiVideoProviderConfig.defaultParamsJson` 的 `video_max_workers` /
 * `image_max_workers` 字段 > [DEFAULTS] 全局默认。
 *
 * @see <a href="file:///tmp/arcreel/lib/generation_worker.py">ArcReel generation_worker.py L89-205</a>
 */
class CapacityTable {

    private val _limits: MutableMap<Pair<String, String>, Int> = mutableMapOf()

    /**
     * 全局默认并发上限（provider 未登记时兜底）。
     *
     * Android 单进程前台服务模型下比 ArcReel 保守（ArcReel image=5/video=3/audio=10），
     * 手机资源有限，避免 OOM。
     */
    val DEFAULTS: Map<String, Int> = mapOf(
        MEDIA_VIDEO to 1,
        MEDIA_IMAGE to 2,
        MEDIA_AUDIO to 2
    )

    /**
     * 获取指定 provider × media 的并发上限。
     *
     * @return 上限值（0 表示该 provider 不支持此 media 类型）
     */
    fun get(providerId: String, mediaType: String): Int {
        // 已知 provider + 已知 media → 登记值
        _limits[providerId to mediaType]?.let { return it }
        // 已知 provider（至少有一个 media 登记）+ 未知 media → 0（不支持）
        if (isProviderKnown(providerId)) return 0
        // provider 整个未知 → 默认
        return DEFAULTS[mediaType] ?: 0
    }

    /** provider 是否已登记（至少有一个 media_type 配置）。 */
    private fun isProviderKnown(providerId: String): Boolean =
        _limits.keys.any { it.first == providerId }

    /**
     * 设置单个 provider × media 的上限。
     */
    fun set(providerId: String, mediaType: String, limit: Int) {
        require(limit >= 0) { "limit 必须 >= 0，得到 $limit" }
        _limits[providerId to mediaType] = limit
    }

    /**
     * 批量替换配置（配置变更时整表换）。
     */
    fun replace(limits: Map<Pair<String, String>, Int>) {
        _limits.clear()
        limits.forEach { (k, v) ->
            require(v >= 0) { "limit 必须 >= 0，得到 $v" }
            _limits[k] = v
        }
    }

    /** 清空所有配置（测试用）。 */
    fun clear() = _limits.clear()

    companion object {
        const val MEDIA_VIDEO = "video"
        const val MEDIA_IMAGE = "image"
        const val MEDIA_AUDIO = "audio"
    }
}
