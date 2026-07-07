package io.legado.app.help.ai.backends.compress

import io.legado.app.constant.AppLog
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 统一「比例优先、清晰度其次」的尺寸计算（移植 ArcReel `aspect_size.py`）。
 *
 * 核心原则：**比例只有一个来源——项目的 aspect_ratio，永远优先**；分辨率（预设档位或
 * 自定义值）只决定清晰度规模，不决定比例。合法尺寸取 `(aw·round_to·t, ah·round_to·t)`
 * 的整数倍 → 比例零偏差、长宽天然整除（gpt-image-2 要求宽高被 16 整除即由此满足）。
 *
 * - [aspectSize]：给定比例 + 短边目标，算出精确遵循比例、且被 round_to 整除的 (宽, 高)
 * - [resolutionToShortEdge]：把分辨率（None/档位词/宽x高/纯数字）规范化成短边像素
 *
 * 档位短边表跨后端统一（[IMAGE_TIER_SHORT_EDGE]/[VIDEO_TIER_SHORT_EDGE]），各后端再按自身
 * 像素约束用 max_long_edge / max_total_pixels 夹取。
 */
object AspectSize {

    /** 缺分辨率但必需尺寸来控制比例时的兜底短边（720P）。 */
    const val DEFAULT_SHORT_EDGE = 720

    /** 图片档位 → 短边像素：512px/1K/2K/4K。 */
    val IMAGE_TIER_SHORT_EDGE: Map<String, Int> = mapOf(
        "512px" to 512, "1k" to 1024, "2k" to 1440, "4k" to 2160
    )

    /** 视频档位 → 短边像素：480p/720p/1080p/4K。 */
    val VIDEO_TIER_SHORT_EDGE: Map<String, Int> = mapOf(
        "480p" to 480, "720p" to 720, "1080p" to 1080, "4k" to 2160
    )

    /** 默认比例：竖屏 9:16（短剧居多，无法解析时回退，最小惊讶）。 */
    private val DEFAULT_ASPECT = 9 to 16

    /** 最大公约数（kotlin.math.gcd 在部分 stdlib 版本不可用，手写欧几里得）。 */
    private fun gcd(a: Int, b: Int): Int {
        var x = a; var y = b
        while (y != 0) { val t = x % y; x = y; y = t }
        return x
    }

    /** 自定义 "宽x高" 分隔符：英文 x、X、星号、全角叉号。 */
    private val WH_RE = Regex("""^\s*(\d+)\s*[xX×*]\s*(\d+)\s*$""")

    /** 比例分隔符：英文/全角冒号。 */
    private val RATIO_SEP_RE = Regex("[:：]")

    /**
     * 把 "9:16" 解析成约简互质的 (9, 16)；非法值回退 (9, 16) 并 warn。
     */
    fun parseAspectRatio(aspectRatio: String): Pair<Int, Int> {
        return try {
            val parts = RATIO_SEP_RE.split(aspectRatio.trim())
            if (parts.size != 2) throw IllegalArgumentException(aspectRatio)
            val aw = parts[0].toInt()
            val ah = parts[1].toInt()
            if (aw <= 0 || ah <= 0) throw IllegalArgumentException(aspectRatio)
            val g = gcd(aw, ah)
            aw / g to ah / g
        } catch (e: Exception) {
            AppLog.put("无法解析 aspect_ratio=$aspectRatio，回退默认 ${DEFAULT_ASPECT.first}:${DEFAULT_ASPECT.second}")
            DEFAULT_ASPECT
        }
    }

    /**
     * 按比例 + 短边目标算出精确遵循比例、且被 round_to 整除的 (宽, 高)。
     *
     * 合法尺寸 = (aw·round_to·t, ah·round_to·t)（aw:ah 为约简比例，t 正整数）
     * → 比例零偏差、长宽均被 round_to 整除。t 取使短边最接近 short_edge 的值，
     * 再受 max_long_edge / max_total_pixels 夹取（取更严者），下限 t≥1。
     *
     * - max_long_edge：长边像素上限（如 gpt-image-2 的 4K=3840；qwen-edit 单边 2048）
     * - max_total_pixels：总像素预算上限（如 DashScope 标准 2048²、4K 4096²）
     * - max_ratio：后端支持的最极端比例；超出仅 warn 不抛错，留 API 判定
     */
    fun aspectSize(
        aspectRatio: String,
        shortEdge: Int,
        roundTo: Int = 16,
        maxLongEdge: Int? = null,
        maxTotalPixels: Int? = null,
        maxRatio: Float? = null
    ): Pair<Int, Int> {
        val (aw, ah) = parseAspectRatio(aspectRatio)

        if (maxRatio != null) {
            val ratio = max(aw.toFloat() / ah, ah.toFloat() / aw)
            if (ratio > maxRatio + 1e-6f) {
                AppLog.put("aspect_ratio=$aspectRatio 比例 $ratio 超出后端支持上限 $maxRatio，可能被 API 拒绝或裁剪")
            }
        }

        val shortComp = min(aw, ah)
        val longComp = max(aw, ah)
        require(roundTo > 0) { "roundTo 必须 > 0，当前=$roundTo（否则会整数除零）" }
        val shortUnit = roundTo * shortComp

        var t = max(1, (shortEdge.toFloat() / shortUnit).roundToInt())

        if (maxLongEdge != null) {
            val maxTLong = maxLongEdge / (roundTo * longComp)
            t = min(t, max(1, maxTLong))
        }

        if (maxTotalPixels != null) {
            val denom = aw * ah * roundTo * roundTo
            if (denom > 0) {
                val maxTPixels = sqrt((maxTotalPixels / denom).toFloat()).toInt()
                t = min(t, max(1, maxTPixels))
            }
        }

        t = max(1, t)
        return aw * roundTo * t to ah * roundTo * t
    }

    /**
     * 把分辨率规范化成短边像素。
     *
     * - null/空 → defaultShort
     * - 档位词（大小写不敏感，如 "2K"/"720p"）→ 查 tierMap
     * - 自定义 "宽x高" → min(宽, 高)，剥离自带比例
     * - 纯数字 → 直接当短边
     * - 无法解析 → defaultShort + warn
     */
    fun resolutionToShortEdge(
        resolution: String?,
        tierMap: Map<String, Int>,
        defaultShort: Int = DEFAULT_SHORT_EDGE
    ): Int {
        if (resolution.isNullOrBlank()) return defaultShort
        val s = resolution.trim()
        tierMap[s.lowercase()]?.let { return it }
        val m = WH_RE.matchEntire(s)
        if (m != null) {
            val w = m.groupValues[1].toIntOrNull() ?: return defaultShort
            val h = m.groupValues[2].toIntOrNull() ?: return defaultShort
            return min(w, h)
        }
        s.toIntOrNull()?.let { return it }
        AppLog.put("无法解析 resolution=$resolution，回退默认短边 $defaultShort")
        return defaultShort
    }
}
