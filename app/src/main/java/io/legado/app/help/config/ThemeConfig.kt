package io.legado.app.help.config

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.help.DefaultData
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.model.BookCover
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.stackBlur
import splitties.init.appCtx
import java.io.File
import androidx.core.graphics.drawable.toDrawable
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.toastOnUi
import java.io.FileOutputStream

@Keep
object ThemeConfig {
    const val configFileName = "themeConfig.json"
    val configFilePath = FileUtils.getPath(appCtx.filesDir, configFileName)

    val configList: ArrayList<Config> by lazy {
        val cList = getConfigs() ?: DefaultData.themeConfigs
        ArrayList(cList)
    }

    private var needClearImg = true

    fun getTheme() = when {
        AppConfig.isEInkMode -> Theme.EInk
        AppConfig.isNightTheme -> Theme.Dark
        else -> Theme.Light
    }

    fun isDarkTheme(): Boolean {
        return getTheme() == Theme.Dark
    }

    fun applyDayNight(context: Context) {
        applyTheme(context)
        initNightMode()
        BookCover.upDefaultCover()
        postEvent(EventBus.RECREATE, "")
    }

    fun applyDayNightInit(context: Context) {
        applyTheme(context)
        initNightMode()
    }

    private fun initNightMode() {
        val targetMode =
            if (AppConfig.isNightTheme) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        AppCompatDelegate.setDefaultNightMode(targetMode)
    }

    private fun getAssetSuffix(url: String, contentType: String? = null): String {
        val lowerUrl = url.lowercase()
        val lowerContentType = contentType?.lowercase().orEmpty()
        return when {
            lowerUrl.contains(".9.png") -> ".9.png"
            lowerUrl.contains(".json") || lowerContentType.contains("json") -> ".json"
            lowerUrl.contains(".png") || lowerContentType.contains("png") -> ".png"
            lowerUrl.contains(".gif") || lowerContentType.contains("gif") -> ".gif"
            lowerUrl.contains(".webp") || lowerContentType.contains("webp") -> ".webp"
            else -> ".jpg"
        }
    }

    /**
     * 获取链接获取背景资源文件名
     */
    private fun getUrlToFile(url: String, contentType: String? = null): String {
        val suffix = getAssetSuffix(url, contentType)
        return MD5Utils.md5Encode16(url) + suffix
    }

    private fun resolveBackgroundPath(context: Context, preferenceKey: String): String? {
        var path = context.getPrefString(preferenceKey)
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) {
            val fileRoot = context.externalFiles
            val cachedFile = findCachedUrlFile(fileRoot.getFile(preferenceKey), path)
            if (cachedFile == null) {
                appCtx.toastOnUi("未缓存在线背景资源\n请重新应用主题")
                return null
            }
            path = cachedFile.absolutePath
        }
        return path
    }

    private fun findCachedUrlFile(root: File, url: String): File? {
        val hash = MD5Utils.md5Encode16(url)
        val exact = File(root, getUrlToFile(url))
        if (exact.exists()) return exact
        return root.listFiles()?.firstOrNull { it.isFile && it.name.startsWith(hash) }
    }

    private fun isDynamicBackground(path: String): Boolean {
        val lowerPath = path.lowercase()
        return lowerPath.endsWith(".gif") || lowerPath.endsWith(".json")
    }

    enum class DynamicBackgroundType {
        GIF,
        LOTTIE
    }

    data class DynamicBackgroundSpec(
        val path: String,
        val type: DynamicBackgroundType
    )

    fun getDynamicBackground(context: Context): DynamicBackgroundSpec? {
        val preferenceKey = when (getTheme()) {
            Theme.Light -> PreferKey.bgImage
            Theme.Dark -> PreferKey.bgImageN
            else -> return null
        }
        val path = resolveBackgroundPath(context, preferenceKey) ?: return null
        return when {
            path.endsWith(".gif", true) -> DynamicBackgroundSpec(path, DynamicBackgroundType.GIF)
            path.endsWith(".json", true) -> DynamicBackgroundSpec(path, DynamicBackgroundType.LOTTIE)
            else -> null
        }
    }

    fun getBgImage(context: Context, metrics: DisplayMetrics): Drawable? {
        val themeMode = getTheme()
        val preferenceKey = when (themeMode) {
            Theme.Light -> PreferKey.bgImage
            Theme.Dark -> PreferKey.bgImageN
            else -> return  null
        }
        val path = resolveBackgroundPath(context, preferenceKey) ?: return null
        if (isDynamicBackground(path)) return null
        if (path.endsWith(".9.png")) {
            val bgDrawable = BitmapUtils.decodeNinePatchDrawable(path)
            return bgDrawable
        }
        val bgImgBlu = when (themeMode) {
            Theme.Light -> context.getPrefInt(PreferKey.bgImageBlurring, 0)
            Theme.Dark -> context.getPrefInt(PreferKey.bgImageNBlurring, 0)
            else -> 0
        }
        val bgImage = BitmapUtils
            .decodeBitmap(path, metrics.widthPixels, metrics.heightPixels)
        if (bgImgBlu == 0) {
            return bgImage?.toDrawable(context.resources)
        }
        return bgImage?.stackBlur(bgImgBlu)?.toDrawable(context.resources)
    }

    fun upConfig() {
        addConfigs(getConfigs())
    }

    fun save() {
        val json = GSON.toJson(configList)
        FileUtils.delete(configFilePath)
        FileUtils.createFileIfNotExist(configFilePath).writeText(json)
    }

    fun delConfig(index: Int) {
        configList.removeAt(index)
        save()
    }

    fun addConfig(json: String): Boolean {
        GSON.fromJsonObject<Config>(json.trim { it < ' ' }).getOrNull()
            ?.let {
                if (validateConfig(it)) {
                    addConfig(it)
                    return true
                }
            }
        return false
    }

    fun addConfig(newConfig: Config) {
        if (!validateConfig(newConfig)) {
            return
        }
        var hasTheme = false
        configList.forEachIndexed { index, config ->
            if (newConfig.themeName == config.themeName) {
                configList[index] = newConfig
                hasTheme = true
                return@forEachIndexed
            }
        }
        if (!hasTheme) {
            configList.add(newConfig)
        }
        save()
    }

    fun addConfigs(newConfigs: List<Config>?) {
        val newConfigs = newConfigs?.filter{
            validateConfig(it)
        }
        if (newConfigs.isNullOrEmpty()) {
            return
        }
        newConfigs.forEach { newConfig ->
            val existingIndex = configList.indexOfFirst { it.themeName == newConfig.themeName }
            if (existingIndex != -1) {
                configList[existingIndex] = newConfig
            } else {
                configList.add(newConfig)
            }
        }
        save()
    }

    private fun validateConfig(config: Config): Boolean {
        try {
            config.primaryColor.toColorInt()
            config.accentColor.toColorInt()
            config.backgroundColor.toColorInt()
            config.bottomBackground.toColorInt()
            config.primaryTextColor?.toColorInt()
            config.secondaryTextColor?.toColorInt()
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun getConfigs(): List<Config>? {
        val configFile = File(configFilePath)
        if (configFile.exists()) {
            kotlin.runCatching {
                val json = configFile.readText()
                return GSON.fromJsonArray<Config>(json).getOrThrow()
            }.onFailure {
                it.printOnDebug()
            }
        }
        return null
    }

    fun applyConfig(context: Context, config: Config, switchNightMode: Boolean = true) {
        try {
            if (needClearImg) {
                needClearImg = false
                clearBg(context)
            }
            val primary = config.primaryColor.toColorInt()
            val accent = config.accentColor.toColorInt()
            val background = config.backgroundColor.toColorInt()
            val bBackground = config.bottomBackground.toColorInt()
            val isNightTheme = config.isNightTheme
            val transparentNavBar = config.transparentNavBar
            val backgroundPath = config.backgroundImgPath
            val bookInfoBackgroundPath = config.bookInfoBackgroundImgPath
            val primaryTextColor = config.primaryTextColor?.toColorInt()
            val secondaryTextColor = config.secondaryTextColor?.toColorInt()
            if (backgroundPath != null && backgroundPath.startsWith("http")) {
                val fileRoot = context.externalFiles
                val preferenceKey = if (isNightTheme) {
                    PreferKey.bgImageN
                } else {
                    PreferKey.bgImage
                }
                val name = getUrlToFile(backgroundPath)
                val fileFold = File(fileRoot, preferenceKey)
                if (!fileFold.exists()) {
                    fileFold.mkdirs()
                }
                if (findCachedUrlFile(fileFold, backgroundPath) == null) {
                    appCtx.toastOnUi("下载背景资源中...")
                    Coroutine.async {
                        kotlin.runCatching {
                            val res = okHttpClient.newCallResponse(0) {
                                url(backgroundPath)
                            }
                            val contentType = res.header("Content-Type")
                            val resolvedFile = File(fileFold, getUrlToFile(backgroundPath, contentType))
                            res.body.byteStream().use { inputStream ->
                                FileOutputStream(resolvedFile).use { outputStream ->
                                    inputStream.copyTo(outputStream)
                                }
                            }
                        }.onSuccess {
                            appCtx.toastOnUi("背景资源下载成功\n请重新应用主题")
                        }.onFailure {
                            appCtx.toastOnUi(it.localizedMessage)
                        }
                    }
                    return
                }
            }
            val backgroundBlur = config.backgroundImgBlur
            if (isNightTheme) {
                context.putPrefString(PreferKey.dNThemeName, config.themeName)
                context.putPrefInt(PreferKey.cNPrimary, primary)
                context.putPrefInt(PreferKey.cNAccent, accent)
                context.putPrefInt(PreferKey.cNBackground, background)
                context.putPrefInt(PreferKey.cNBBackground, bBackground)
                context.putPrefBoolean(PreferKey.tNavBarN, false)
                context.putPrefString(PreferKey.bgImageN, backgroundPath)
                context.putPrefInt(PreferKey.bgImageNBlurring, backgroundBlur)
                context.putPrefString(PreferKey.bookInfoBgImageN, bookInfoBackgroundPath)
            } else {
                context.putPrefString(PreferKey.dThemeName, config.themeName)
                context.putPrefInt(PreferKey.cPrimary, primary)
                context.putPrefInt(PreferKey.cAccent, accent)
                context.putPrefInt(PreferKey.cBackground, background)
                context.putPrefInt(PreferKey.cBBackground, bBackground)
                context.putPrefBoolean(PreferKey.tNavBar, false)
                context.putPrefString(PreferKey.bgImage, backgroundPath)
                context.putPrefInt(PreferKey.bgImageBlurring, backgroundBlur)
                context.putPrefString(PreferKey.bookInfoBgImage, bookInfoBackgroundPath)
            }
            if (switchNightMode) {
                AppConfig.isNightTheme = isNightTheme
            }
            val themeEditor = ThemeStore.editTheme(context)
            primaryTextColor?.let { themeEditor.textColorPrimary(it) }
            secondaryTextColor?.let { themeEditor.textColorSecondary(it) }
            themeEditor.apply()
            if (switchNightMode) {
                applyDayNight(context)
            } else {
                applyTheme(context)
                BookCover.upDefaultCover()
                postEvent(EventBus.RECREATE, "")
            }
        } catch (e: Exception) {
            AppLog.put("设置主题出错\n$e", e, true)
        }
    }

    fun getDurConfig(context: Context): Config {
        val isNight = AppConfig.isNightTheme
        val name = if (isNight) {
            context.getPrefString(PreferKey.dNThemeName) ?: ""
        } else {
            context.getPrefString(PreferKey.dThemeName) ?: ""
        }
        return if (isNight) {
            getNightTheme(context, name)
        } else {
            getDayTheme(context, name)
        }
    }

    private fun getDayTheme(context: Context, name: String): Config {
        val primary =
            context.getPrefInt(PreferKey.cPrimary, context.getCompatColor(R.color.md_brown_500))
        val accent =
            context.getPrefInt(PreferKey.cAccent, context.getCompatColor(R.color.md_red_600))
        val background =
            context.getPrefInt(PreferKey.cBackground, context.getCompatColor(R.color.md_grey_100))
        val bBackground =
            context.getPrefInt(PreferKey.cBBackground, context.getCompatColor(R.color.md_grey_200))
        val transparentNavBar =
            context.getPrefBoolean(PreferKey.tNavBar, false)
        val bgImgPath =
            context.getPrefString(PreferKey.bgImage)
        val bgImgBlur =
            context.getPrefInt(PreferKey.bgImageBlurring, 0)
        val bookInfoBgImgPath =
            context.getPrefString(PreferKey.bookInfoBgImage)

        return mergeStoredThemeAssets(
            Config(
            themeName = name,
            isNightTheme = false,
            primaryColor = "#${primary.hexString}",
            accentColor = "#${accent.hexString}",
            backgroundColor = "#${background.hexString}",
            bottomBackground = "#${bBackground.hexString}",
            transparentNavBar = transparentNavBar,
            backgroundImgPath = bgImgPath,
            backgroundImgBlur = bgImgBlur,
            bookInfoBackgroundImgPath = bookInfoBgImgPath,
            primaryTextColor = "#${ThemeStore.textColorPrimary(context).hexString}",
            secondaryTextColor = "#${ThemeStore.textColorSecondary(context).hexString}"
            )
        )
    }

    fun saveDayTheme(context: Context, name: String) {
        val config = getDayTheme(context, name)
        addConfig(config)
    }

    private fun getNightTheme(context: Context, name: String): Config {
        val primary =
            context.getPrefInt(
                PreferKey.cNPrimary,
                context.getCompatColor(R.color.md_blue_grey_600)
            )
        val accent =
            context.getPrefInt(
                PreferKey.cNAccent,
                context.getCompatColor(R.color.md_deep_orange_800)
            )
        val background =
            context.getPrefInt(PreferKey.cNBackground, context.getCompatColor(R.color.md_grey_900))
        val bBackground =
            context.getPrefInt(PreferKey.cNBBackground, context.getCompatColor(R.color.md_grey_850))
        val transparentNavBar =
            context.getPrefBoolean(PreferKey.tNavBarN, false)
        val bgImgPath =
            context.getPrefString(PreferKey.bgImageN)
        val bgImgBlur =
            context.getPrefInt(PreferKey.bgImageNBlurring, 0)
        val bookInfoBgImgPath =
            context.getPrefString(PreferKey.bookInfoBgImageN)
        return mergeStoredThemeAssets(
            Config(
            themeName = name,
            isNightTheme = true,
            primaryColor = "#${primary.hexString}",
            accentColor = "#${accent.hexString}",
            backgroundColor = "#${background.hexString}",
            bottomBackground = "#${bBackground.hexString}",
            transparentNavBar = transparentNavBar,
            backgroundImgPath = bgImgPath,
            backgroundImgBlur = bgImgBlur,
            bookInfoBackgroundImgPath = bookInfoBgImgPath,
            primaryTextColor = "#${ThemeStore.textColorPrimary(context).hexString}",
            secondaryTextColor = "#${ThemeStore.textColorSecondary(context).hexString}"
            )
        )
    }

    private fun mergeStoredThemeAssets(config: Config): Config {
        if (config.themeName.isBlank()) return config
        val stored = configList.firstOrNull {
            it.themeName == config.themeName && it.isNightTheme == config.isNightTheme
        } ?: return config
        return config.copy(
            backgroundImgPath = preferThemeAsset(config.backgroundImgPath, stored.backgroundImgPath),
            bookInfoBackgroundImgPath = preferThemeAsset(
                config.bookInfoBackgroundImgPath,
                stored.bookInfoBackgroundImgPath
            ),
            backgroundImgBlur = if (config.backgroundImgPath.isNullOrBlank() && !stored.backgroundImgPath.isNullOrBlank()) {
                stored.backgroundImgBlur
            } else {
                config.backgroundImgBlur
            },
            primaryTextColor = config.primaryTextColor ?: stored.primaryTextColor,
            secondaryTextColor = config.secondaryTextColor ?: stored.secondaryTextColor
        )
    }

    private fun preferThemeAsset(current: String?, fallback: String?): String? {
        if (!current.isNullOrBlank()) {
            if (current.startsWith("http", ignoreCase = true)) return current
            if (File(current).exists()) return current
        }
        return fallback?.takeIf {
            it.startsWith("http", ignoreCase = true) || File(it).exists()
        } ?: current
    }

    fun saveNightTheme(context: Context, name: String) {
        val config = getNightTheme(context, name)
        addConfig(config)
    }

    /**
     * 更新主题
     */
    fun applyTheme(context: Context) = with(context) {
        when {
            AppConfig.isEInkMode -> {
                ThemeStore.editTheme(this)
                    .primaryColor(Color.WHITE)
                    .accentColor(Color.BLACK)
                    .backgroundColor(Color.WHITE)
                    .bottomBackground(Color.WHITE)
                    .transparentNavBar(false)
                    .apply()
            }

            AppConfig.isNightTheme -> {
                val primary =
                    getPrefInt(PreferKey.cNPrimary, getCompatColor(R.color.md_blue_grey_600))
                val accent =
                    getPrefInt(PreferKey.cNAccent, getCompatColor(R.color.md_deep_orange_800))
                var background =
                    getPrefInt(PreferKey.cNBackground, getCompatColor(R.color.md_grey_900))
                if (ColorUtils.isColorLight(background)) {
                    background = getCompatColor(R.color.md_grey_900)
                    putPrefInt(PreferKey.cNBackground, background)
                }
                val bBackground =
                    getPrefInt(PreferKey.cNBBackground, getCompatColor(R.color.md_grey_850))
                val transparentNavBar =
                    getPrefBoolean(PreferKey.tNavBarN, false)
                ThemeStore.editTheme(this)
                    .primaryColor(ColorUtils.withAlpha(primary, 1f))
                    .accentColor(ColorUtils.withAlpha(accent, 1f))
                    .backgroundColor(ColorUtils.withAlpha(background, 1f))
                    .bottomBackground(ColorUtils.withAlpha(bBackground, 1f))
                    .transparentNavBar(transparentNavBar)
                    .apply()
            }

            else -> {
                val primary =
                    getPrefInt(PreferKey.cPrimary, getCompatColor(R.color.md_brown_500))
                val accent =
                    getPrefInt(PreferKey.cAccent, getCompatColor(R.color.md_red_600))
                var background =
                    getPrefInt(PreferKey.cBackground, getCompatColor(R.color.md_grey_100))
                if (!ColorUtils.isColorLight(background)) {
                    background = getCompatColor(R.color.md_grey_100)
                    putPrefInt(PreferKey.cBackground, background)
                }
                val bBackground =
                    getPrefInt(PreferKey.cBBackground, getCompatColor(R.color.md_grey_200))
                val transparentNavBar =
                    getPrefBoolean(PreferKey.tNavBar, false)
                ThemeStore.editTheme(this)
                    .primaryColor(ColorUtils.withAlpha(primary, 1f))
                    .accentColor(ColorUtils.withAlpha(accent, 1f))
                    .backgroundColor(ColorUtils.withAlpha(background, 1f))
                    .bottomBackground(ColorUtils.withAlpha(bBackground, 1f))
                    .transparentNavBar(transparentNavBar)
                    .apply()
            }
        }
    }

    fun clearBg(context: Context) {
        val (nightConfigs, dayConfigs) = configList.partition { it.isNightTheme }
        val fileRoot = context.externalFiles
        val nightBackgroundImgPaths = nightConfigs.mapNotNull {
            val path = it.backgroundImgPath ?: return@mapNotNull null
            if (path.startsWith("http")) {
                findCachedUrlFile(fileRoot.getFile(PreferKey.bgImageN), path)?.absolutePath
            } else {
                path
            }
        }
        val dayBackgroundImgPaths = dayConfigs.mapNotNull {
            val path = it.backgroundImgPath ?: return@mapNotNull null
            if (path.startsWith("http")) {
                findCachedUrlFile(fileRoot.getFile(PreferKey.bgImage), path)?.absolutePath
            } else {
                path
            }
        }
        appCtx.externalFiles.getFile(PreferKey.bgImage).listFiles()?.forEach {
            if (!dayBackgroundImgPaths.contains(it.absolutePath)) {
                it.delete()
            }
        }
        appCtx.externalFiles.getFile(PreferKey.bgImageN).listFiles()?.forEach {
            if (!nightBackgroundImgPaths.contains(it.absolutePath)) {
                it.delete()
            }
        }
    }

    @Keep
    data class Config(
        var themeName: String,
        var isNightTheme: Boolean,
        var primaryColor: String,
        var accentColor: String,
        var backgroundColor: String,
        var bottomBackground: String,
        var transparentNavBar: Boolean,
        var backgroundImgPath: String?,
        var backgroundImgBlur: Int,
        var bookInfoBackgroundImgPath: String? = null,
        var primaryTextColor: String? = null,
        var secondaryTextColor: String? = null
    ) {

        override fun hashCode(): Int {
            return GSON.toJson(this).hashCode()
        }

        override fun equals(other: Any?): Boolean {
            other ?: return false
            if (other is Config) {
                return other.themeName == themeName
                        && other.isNightTheme == isNightTheme
                        && other.primaryColor == primaryColor
                        && other.accentColor == accentColor
                        && other.backgroundColor == backgroundColor
                        && other.bottomBackground == bottomBackground
                        && other.transparentNavBar == transparentNavBar
                        && other.backgroundImgPath == backgroundImgPath
                        && other.backgroundImgBlur == backgroundImgBlur
                        && other.bookInfoBackgroundImgPath == bookInfoBackgroundImgPath
                        && other.primaryTextColor == primaryTextColor
                        && other.secondaryTextColor == secondaryTextColor
            }
            return false
        }

        fun toMap() = mapOf(
            "themeName" to themeName,
            "isNightTheme" to isNightTheme,
            "primaryColor" to primaryColor,
            "accentColor" to accentColor,
            "backgroundColor" to backgroundColor,
            "bottomBackground" to bottomBackground,
            "transparentNavBar" to transparentNavBar,
            "backgroundImgPath" to backgroundImgPath,
            "backgroundImgBlur" to backgroundImgBlur,
            "bookInfoBackgroundImgPath" to bookInfoBackgroundImgPath,
            "primaryTextColor" to primaryTextColor,
            "secondaryTextColor" to secondaryTextColor
        )

    }

}
