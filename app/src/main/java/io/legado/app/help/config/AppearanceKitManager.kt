package io.legado.app.help.config

import android.content.Context
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

object AppearanceKitManager {

    const val KIT_FLOATING = "builtin_floating"
    const val KIT_REGULAR = "builtin_regular"
    const val KIT_SIDEBAR = "builtin_sidebar"

    fun builtinKits(): List<AppearanceKit> {
        return listOf(
            AppearanceKit(
                id = KIT_FLOATING,
                name = "悬浮",
                summary = "悬浮底栏 + 默认顶栏",
                type = AppearanceKitType.BUILTIN,
                preset = MainLayoutPresetConfig.PRESET_DEFAULT
            ),
            AppearanceKit(
                id = KIT_REGULAR,
                name = "常规",
                summary = "常规底栏 + 常规顶栏",
                type = AppearanceKitType.BUILTIN,
                preset = MainLayoutPresetConfig.PRESET_REGULAR
            ),
            AppearanceKit(
                id = KIT_SIDEBAR,
                name = "侧栏",
                summary = "侧边栏 + 默认顶栏",
                type = AppearanceKitType.BUILTIN,
                preset = MainLayoutPresetConfig.PRESET_SIDEBAR
            )
        )
    }

    suspend fun importedThemeKits(): List<AppearanceKit> {
        val day = ThemePackageManager.loadLocalOnly(false)
            .filter { it.source != ThemePackageManager.Source.BUILTIN }
        val night = ThemePackageManager.loadLocalOnly(true)
            .filter { it.source != ThemePackageManager.Source.BUILTIN }
        return (day + night)
            .groupBy { it.packageInfo.name }
            .map { (name, entries) ->
                val hasDay = entries.any { !it.packageInfo.isNightTheme }
                val hasNight = entries.any { it.packageInfo.isNightTheme }
                AppearanceKit(
                    id = "theme:${name}",
                    name = name,
                    summary = when {
                        hasDay && hasNight -> "已导入日间 / 夜间界面主题"
                        hasNight -> "已导入夜间界面主题"
                        else -> "已导入日间界面主题"
                    },
                    type = AppearanceKitType.IMPORTED_THEME,
                    themeName = name
                )
            }
            .sortedBy { it.name }
    }

    fun currentKitId(): String {
        return appCtx.getPrefString(PreferKey.currentAppearanceKitId, "")
            ?.takeIf { it.isNotBlank() }
            ?: when (MainLayoutPresetConfig.currentPreset()) {
                MainLayoutPresetConfig.PRESET_REGULAR -> KIT_REGULAR
                MainLayoutPresetConfig.PRESET_SIDEBAR -> KIT_SIDEBAR
                else -> KIT_FLOATING
            }
    }

    suspend fun apply(context: Context, kit: AppearanceKit) {
        when (kit.type) {
            AppearanceKitType.BUILTIN -> {
                MainLayoutPresetConfig.apply(context, kit.preset ?: MainLayoutPresetConfig.PRESET_DEFAULT, notify = false)
            }

            AppearanceKitType.IMPORTED_THEME -> {
                applyImportedTheme(context, kit.themeName.orEmpty())
            }
        }
        context.putPrefString(PreferKey.currentAppearanceKitId, kit.id)
        postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, true)
    }

    private suspend fun applyImportedTheme(context: Context, themeName: String) {
        val isNight = AppConfig.isNightTheme
        val entry = ThemePackageManager.loadLocalOnly(isNight)
            .firstOrNull { it.packageInfo.name == themeName }
            ?: ThemePackageManager.loadLocalOnly(!isNight)
                .firstOrNull { it.packageInfo.name == themeName }
            ?: return
        ThemePackageManager.apply(context, entry, switchNightMode = false)
    }
}

data class AppearanceKit(
    val id: String,
    val name: String,
    val summary: String,
    val type: AppearanceKitType,
    val preset: String? = null,
    val themeName: String? = null
)

enum class AppearanceKitType {
    BUILTIN,
    IMPORTED_THEME
}
