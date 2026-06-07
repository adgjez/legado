package io.legado.app.model

import android.content.Context
import io.legado.app.R
import io.legado.app.constant.IntentAction
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.CacheManager
import io.legado.app.help.IntentData
import io.legado.app.service.CheckSourceService
import io.legado.app.utils.startService
import splitties.init.appCtx

object CheckSource {
    const val CHECK_DEPTH_RESULT = 0
    const val CHECK_DEPTH_INFO = 1
    const val CHECK_DEPTH_CATEGORY = 2
    const val CHECK_DEPTH_CONTENT = 3

    var keyword = "我的"

    //校验设置
    var timeout = CacheManager.getLong("checkSourceTimeout") ?: 180000L
    var threadCount = CacheManager.getInt("checkSourceThreadCount") ?: 6
    var quickMode = CacheManager.get("checkSourceQuickMode")?.toBoolean() ?: false
    var checkDepth = CacheManager.getInt("checkSourceDepth") ?: CHECK_DEPTH_CONTENT
    var wSourceComment = CacheManager.get("wSourceComment")?.toBoolean() ?: true
    var checkDomain = CacheManager.get("checkDomain")?.toBoolean() ?: false
    var checkSearch = CacheManager.get("checkSearch")?.toBoolean() ?: true
    var checkDiscovery = CacheManager.get("checkDiscovery")?.toBoolean() ?: true
    var checkInfo = CacheManager.get("checkInfo")?.toBoolean() ?: true
    var checkCategory = CacheManager.get("checkCategory")?.toBoolean() ?: true
    var checkContent = CacheManager.get("checkContent")?.toBoolean() ?: true
    val summary get() = upSummary()

    fun start(context: Context, sources: List<BookSourcePart>) {
        val selectedIds = sources.map {
            it.bookSourceUrl
        }
        IntentData.put("checkSourceSelectedIds", selectedIds)
        context.startService<CheckSourceService> {
            action = IntentAction.start
        }
    }

    fun stop(context: Context) {
        context.startService<CheckSourceService> {
            action = IntentAction.stop
        }
    }

    fun resume(context: Context) {
        context.startService<CheckSourceService> {
            action = IntentAction.resume
        }
    }

    fun putConfig() {
        CacheManager.put("checkSourceTimeout", timeout)
        CacheManager.put("checkSourceThreadCount", normalizedThreadCount())
        CacheManager.put("checkSourceQuickMode", quickMode)
        CacheManager.put("checkSourceDepth", normalizedCheckDepth())
        CacheManager.put("wSourceComment", wSourceComment)
        CacheManager.put("checkDomain", checkDomain)
        CacheManager.put("checkSearch", checkSearch)
        CacheManager.put("checkDiscovery", checkDiscovery)
        CacheManager.put("checkInfo", checkInfo)
        CacheManager.put("checkCategory", checkCategory)
        CacheManager.put("checkContent", checkContent)
    }

    fun normalizedThreadCount(): Int {
        return threadCount.coerceIn(1, 10)
    }

    fun normalizedCheckDepth(): Int {
        return checkDepth.coerceIn(CHECK_DEPTH_RESULT, CHECK_DEPTH_CONTENT)
    }

    fun shouldCheckInfo(): Boolean {
        return if (quickMode) normalizedCheckDepth() >= CHECK_DEPTH_INFO else checkInfo
    }

    fun shouldCheckCategory(): Boolean {
        return if (quickMode) normalizedCheckDepth() >= CHECK_DEPTH_CATEGORY else checkCategory
    }

    fun shouldCheckContent(): Boolean {
        return if (quickMode) normalizedCheckDepth() >= CHECK_DEPTH_CONTENT else checkContent
    }

    fun depthLabel(depth: Int = normalizedCheckDepth()): String {
        return when (depth.coerceIn(CHECK_DEPTH_RESULT, CHECK_DEPTH_CONTENT)) {
            CHECK_DEPTH_RESULT -> appCtx.getString(R.string.check_source_depth_result)
            CHECK_DEPTH_INFO -> appCtx.getString(R.string.check_source_depth_info)
            CHECK_DEPTH_CATEGORY -> appCtx.getString(R.string.check_source_depth_category)
            else -> appCtx.getString(R.string.check_source_depth_content)
        }
    }

    private fun upSummary(): String {
        var checkItem = ""
        if (checkDomain) checkItem = "$checkItem ${appCtx.getString(R.string.domain)}"
        if (checkSearch) checkItem = "$checkItem ${appCtx.getString(R.string.search)}"
        if (checkDiscovery) checkItem = "$checkItem ${appCtx.getString(R.string.discovery)}"
        if (shouldCheckInfo()) checkItem = "$checkItem ${appCtx.getString(R.string.source_tab_info)}"
        if (shouldCheckCategory()) checkItem = "$checkItem ${appCtx.getString(R.string.chapter_list)}"
        if (shouldCheckContent()) checkItem = "$checkItem ${appCtx.getString(R.string.main_body)}"
        return appCtx.getString(
            R.string.check_source_config_summary_ext,
            (timeout / 1000).toString(),
            normalizedThreadCount().toString(),
            if (quickMode) depthLabel() else appCtx.getString(R.string.check_source_mode_manual),
            checkItem
        )
    }
}
