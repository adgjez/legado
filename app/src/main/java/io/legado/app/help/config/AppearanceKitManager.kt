package io.legado.app.help.config

import android.content.Context
import androidx.annotation.Keep
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

object AppearanceKitManager {

    const val KIT_FLOATING = "builtin_floating"
    const val KIT_REGULAR = "builtin_regular"
    const val KIT_SIDEBAR = "builtin_sidebar"
    private const val kitManifestName = "appearance_kit.json"
    private const val kitVersion = 1

    private val tempDir: File
        get() = appCtx.externalFiles.getFile("appearanceKitTemp").apply { mkdirs() }

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

    suspend fun importPackage(file: File): ImportResult = withContext(IO) {
        if (isAppearanceKitPackage(file)) {
            importAppearanceKit(file)
        } else {
            val entries = ThemePackageManager.importPackage(file)
            ImportResult(themeCount = entries.size)
        }
    }

    suspend fun exportCurrent(context: Context): File = withContext(IO) {
        val packageDir = tempDir.getFile("export_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        val components = mutableListOf<KitComponent>()
        try {
            exportThemes(context, packageDir, components)
            exportTopBars(context, packageDir, components)
            exportNavigationBars(packageDir, components)
            exportCoverCollections(packageDir, components)
            if (components.isEmpty()) {
                throw IllegalArgumentException("No local appearance components to export")
            }
            val manifest = AppearanceKitPackage(
                name = "Appearance Kit",
                version = kitVersion,
                exportedAt = System.currentTimeMillis(),
                components = components
            )
            File(packageDir, kitManifestName).writeText(GSON.toJson(manifest))
            val zipFile = tempDir.getFile("appearance_kit_${System.currentTimeMillis()}.zip")
            if (zipFile.exists()) zipFile.delete()
            if (!ZipUtils.zipFile(packageDir, zipFile) || !zipFile.isFile || zipFile.length() <= 0L) {
                throw IllegalStateException("Export failed")
            }
            zipFile
        } finally {
            FileUtils.delete(packageDir, deleteRootDir = true)
        }
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

    private fun isAppearanceKitPackage(file: File): Boolean {
        return runCatching {
            ZipFile(file).use { zip -> zip.getEntry(kitManifestName) != null }
        }.getOrDefault(false)
    }

    private suspend fun importAppearanceKit(file: File): ImportResult {
        val unzipDir = tempDir.getFile("import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        var result = ImportResult()
        try {
            unzipSecure(file, unzipDir)
            val manifestFile = File(unzipDir, kitManifestName)
            val manifest = GSON.fromJsonObject<AppearanceKitPackage>(manifestFile.readText()).getOrThrow()
            manifest.components.forEach { component ->
                val componentFile = File(unzipDir, component.path)
                    .takeIf { it.isFile }
                    ?: return@forEach
                when (component.type) {
                    KitComponentType.THEME.name -> {
                        result = result.copy(themeCount = result.themeCount + ThemePackageManager.importZip(componentFile).let { 1 })
                    }
                    KitComponentType.TOP_BAR.name -> {
                        TopBarConfig.importZip(componentFile)
                        result = result.copy(topBarCount = result.topBarCount + 1)
                    }
                    KitComponentType.NAVIGATION_BAR.name -> {
                        NavigationBarIconConfig.importZip(componentFile)
                        result = result.copy(navigationBarCount = result.navigationBarCount + 1)
                    }
                    KitComponentType.COVER_COLLECTION.name -> {
                        CoverCollectionManager.importZip(appCtx, componentFile, component.isNight)
                        result = result.copy(coverCollectionCount = result.coverCollectionCount + 1)
                    }
                }
            }
            return result
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun unzipSecure(zipFile: File, targetDir: File) {
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                if (entry.isDirectory) return@forEach
                val target = File(targetDir, entry.name)
                if (!target.canonicalPath.startsWith(targetDir.canonicalPath)) {
                    throw IllegalArgumentException("Invalid package")
                }
                target.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private suspend fun exportThemes(
        context: Context,
        packageDir: File,
        components: MutableList<KitComponent>
    ) {
        listOf(false, true).forEach { isNight ->
            val entry = ThemePackageManager.ensureLocalAppliedTheme(context, isNight)
            if (entry.source == ThemePackageManager.Source.BUILTIN) return@forEach
            val zip = ThemePackageManager.exportZip(entry)
            val targetName = "theme_${if (isNight) "night" else "day"}.zip"
            zip.copyTo(File(packageDir, targetName), overwrite = true)
            components += KitComponent(KitComponentType.THEME.name, isNight, targetName)
        }
    }

    private suspend fun exportTopBars(
        context: Context,
        packageDir: File,
        components: MutableList<KitComponent>
    ) {
        listOf(false, true).forEach { isNight ->
            val entry = TopBarConfig.currentEntry(context, isNight)
            if (entry.dirName == TopBarConfig.DEFAULT_DIR_NAME) return@forEach
            val targetName = "top_bar_${if (isNight) "night" else "day"}.zip"
            TopBarConfig.exportZip(entry).copyTo(File(packageDir, targetName), overwrite = true)
            components += KitComponent(KitComponentType.TOP_BAR.name, isNight, targetName)
        }
    }

    private suspend fun exportNavigationBars(
        packageDir: File,
        components: MutableList<KitComponent>
    ) {
        listOf(false, true).forEach { isNight ->
            val entry = NavigationBarIconConfig.currentEntry(isNight)
            if (entry.dirName == NavigationBarIconConfig.DEFAULT_DIR_NAME) return@forEach
            val targetName = "navigation_bar_${if (isNight) "night" else "day"}.zip"
            NavigationBarIconConfig.exportZip(entry).copyTo(File(packageDir, targetName), overwrite = true)
            components += KitComponent(KitComponentType.NAVIGATION_BAR.name, isNight, targetName)
        }
    }

    private suspend fun exportCoverCollections(
        packageDir: File,
        components: MutableList<KitComponent>
    ) {
        listOf(false, true).forEach { isNight ->
            val entry = CoverCollectionManager.selectedEntry(isNight) ?: return@forEach
            val targetName = "cover_collection_${if (isNight) "night" else "day"}.zip"
            CoverCollectionManager.exportZip(entry).copyTo(File(packageDir, targetName), overwrite = true)
            components += KitComponent(KitComponentType.COVER_COLLECTION.name, isNight, targetName)
        }
    }
}

data class ImportResult(
    val themeCount: Int = 0,
    val topBarCount: Int = 0,
    val navigationBarCount: Int = 0,
    val coverCollectionCount: Int = 0
) {
    val total: Int
        get() = themeCount + topBarCount + navigationBarCount + coverCollectionCount
}

@Keep
private data class AppearanceKitPackage(
    val name: String = "",
    val version: Int = 0,
    val exportedAt: Long = 0L,
    val components: List<KitComponent> = emptyList()
)

@Keep
private data class KitComponent(
    val type: String = "",
    val isNight: Boolean = false,
    val path: String = ""
)

private enum class KitComponentType {
    THEME,
    TOP_BAR,
    NAVIGATION_BAR,
    COVER_COLLECTION
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
