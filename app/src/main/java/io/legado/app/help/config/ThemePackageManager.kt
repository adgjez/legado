package io.legado.app.help.config

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.annotation.Keep
import androidx.documentfile.provider.DocumentFile
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppCloudStorage
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.ImageTypeUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.normalizeFileName
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.compress.ZipUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

object ThemePackageManager {

    private const val packageFileName = "theme.json"
    private const val remoteListTimeoutMillis = 4_000L
    private const val mainBackgroundPrefix = "background"
    private const val bookInfoBackgroundPrefix = "book_info_background"
    private const val panelBackgroundPrefix = "panel_background"
    private const val uiFontPrefix = "ui_font"
    private const val titleFontPrefix = "title_font"
    private const val redReaderSchemaDirName = "reader_schema"
    private const val builtinDayDirName = "builtin_day"
    private const val builtinNightDirName = "builtin_night"
    private const val builtinDayName = "\u5185\u7f6e\u65e5\u95f4\u4e3b\u9898"
    private const val builtinNightName = "\u5185\u7f6e\u591c\u95f4\u4e3b\u9898"
    private const val RED10_RESOURCE_REF_MAX_DISTANCE = 256 * 1024
    private const val RED10_RESOURCE_GROUP_MAX_GAP = 512
    private val imageMagic = listOf(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) to ".png",
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) to ".jpg",
        byteArrayOf(0x52, 0x49, 0x46, 0x46) to ".webp",
        byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61) to ".gif",
        byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) to ".gif"
    )

    val rootDir: File
        get() = appCtx.externalFiles.getFile("themePackages")

    suspend fun load(isNightTheme: Boolean, containerId: String? = null, scope: String? = null): List<Entry> = withContext(IO) {
        val local = loadLocal(isNightTheme).filterNot(::isBuiltinDuplicate).associateBy { it.dirName }
        val remote = loadRemoteOrCache(isNightTheme, containerId, scope).associateBy { it.dirName }
        val keys = local.keys + remote.keys
        val mergedEntries = keys.mapNotNull { key ->
            val localEntry = local[key]
            val remoteEntry = remote[key]
            when {
                localEntry != null && remoteEntry != null -> localEntry.copy(
                    source = Source.BOTH,
                    remoteUpdatedAt = remoteEntry.remoteUpdatedAt
                )

                localEntry != null -> localEntry
                remoteEntry != null -> remoteEntry
                else -> null
            }
        }
        sortEntries(listOf(builtinEntry(isNightTheme)) + mergedEntries)
    }

    suspend fun loadLocalOnly(isNightTheme: Boolean): List<Entry> = withContext(IO) {
        sortEntries(listOf(builtinEntry(isNightTheme)) + loadLocal(isNightTheme).filterNot(::isBuiltinDuplicate))
    }

    suspend fun localThemeExists(
        isNightTheme: Boolean,
        themeName: String,
        excludeDirName: String? = null
    ): Boolean = withContext(IO) {
        val normalizedDirName = themeName.trim().normalizeFileName()
        loadLocal(isNightTheme).any {
            it.dirName == normalizedDirName && it.dirName != excludeDirName
        }
    }

    suspend fun addFromCurrent(context: Context, name: String, isNightTheme: Boolean): Entry =
        withContext(IO) {
            val normalizedName = name.trim().ifBlank { builtinName(isNightTheme) }
            val config = ThemeConfig.getDurConfig(context).copy(
                themeName = normalizedName,
                isNightTheme = isNightTheme
            )
            saveConfig(config)
        }

    suspend fun addFromConfig(config: ThemeConfig.Config): Entry = withContext(IO) {
        saveConfig(config)
    }

    suspend fun themeExists(
        isNightTheme: Boolean,
        themeName: String,
        excludeDirName: String? = null
    ): Boolean = withContext(IO) {
        val normalizedDirName = themeName.trim().normalizeFileName()
        val localExists = loadLocal(isNightTheme).any {
            it.dirName == normalizedDirName && it.dirName != excludeDirName
        }
        if (localExists) {
            return@withContext true
        }
        loadRemoteOrCache(isNightTheme).any {
            it.dirName == normalizedDirName && it.dirName != excludeDirName
        }
    }

    suspend fun upload(entry: Entry, containerId: String? = null, scope: String? = null) = withContext(IO) {
        if (entry.source == Source.BUILTIN) return@withContext
        AppCloudStorage.uploadThemePackage(
            entry.packageInfo.isNightTheme,
            entry.dirName,
            exportZip(entry),
            containerId,
            scope
        )
    }

    suspend fun download(entry: Entry, containerId: String? = null, scope: String? = null): Entry = withContext(IO) {
        val zipFile = tempDir.getFile("${entry.dirName}.zip")
        AppCloudStorage.downloadThemePackage(entry.packageInfo.isNightTheme, entry.dirName, zipFile, containerId, scope)
        importZipInternal(zipFile, entry.remoteUpdatedAt).copy(source = Source.BOTH, remoteUpdatedAt = entry.remoteUpdatedAt)
    }

    suspend fun importZip(zipFile: File): Entry = withContext(IO) {
        val pkg = peekPackage(zipFile)
        importZipInternal(zipFile, 0L)
    }

    suspend fun importPackage(file: File): List<Entry> = withContext(IO) {
        when (detectRedPackageFormat(file)) {
            RedPackageFormat.RED04_ZIP,
            RedPackageFormat.RED_GZIP_JSON,
            RedPackageFormat.RAW_GZIP_JSON -> importPackageDetailed(file).themes
            RedPackageFormat.RED10_PRIVATE -> {
                if (file.isArcRecoveryFile()) {
                    importRed10ArcRecoveryDetailed(file).themes
                } else {
                    throw IllegalArgumentException(appCtx.getString(R.string.theme_red_private_encrypted_unsupported))
                }
            }
            null -> listOf(importZip(file))
        }
    }

    suspend fun importPackageDetailed(file: File): ThemeImportResult = withContext(IO) {
        when (detectRedPackageFormat(file)) {
            RedPackageFormat.RED04_ZIP -> importRedZipDetailed(file)
            RedPackageFormat.RED_GZIP_JSON,
            RedPackageFormat.RAW_GZIP_JSON -> {
                val themes = importRedGzip(file)
                ThemeImportResult(sourceName = themes.firstOrNull()?.packageInfo?.name.orEmpty(), themes = themes)
            }
            RedPackageFormat.RED10_PRIVATE -> {
                if (file.isArcRecoveryFile()) {
                    importRed10ArcRecoveryDetailed(file)
                } else {
                    throw IllegalArgumentException(appCtx.getString(R.string.theme_red_private_encrypted_unsupported))
                }
            }
            null -> {
                val theme = importZip(file)
                ThemeImportResult(sourceName = theme.packageInfo.name, themes = listOf(theme))
            }
        }
    }

    suspend fun exportZip(entry: Entry): File = withContext(IO) {
        if (entry.source == Source.BUILTIN) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_builtin_export_forbidden))
        }
        val localEntry = if (entry.source == Source.REMOTE) download(entry) else entry
        val dir = localEntry.localDir ?: localDir(localEntry.packageInfo.isNightTheme, localEntry.dirName)
        val zipFile = tempDir.getFile("${localEntry.dirName}.zip")
        if (zipFile.exists()) zipFile.delete()
        ZipUtils.zipFile(dir, zipFile)
        zipFile
    }

    suspend fun deleteLocal(entry: Entry) = withContext(IO) {
        if (entry.source == Source.BUILTIN) return@withContext
        entry.localDir?.let { FileUtils.delete(it, deleteRootDir = true) }
        removeThemeConfig(entry.packageInfo.isNightTheme, entry.packageInfo.name, entry.dirName)
    }

    suspend fun deleteRemote(entry: Entry, containerId: String? = null, scope: String? = null) = withContext(IO) {
        if (entry.source == Source.BUILTIN) return@withContext
        AppCloudStorage.deleteThemePackage(entry.packageInfo.isNightTheme, entry.dirName, containerId, scope)
    }

    suspend fun apply(
        context: Context,
        entry: Entry,
        switchNightMode: Boolean = true,
        notify: Boolean = true
    ) {
        val config = withContext(IO) {
            validatedConfig(entry)
        }
        ThemeConfig.applyConfig(context, config, switchNightMode, notify)
    }

    fun builtinEntryForKit(isNightTheme: Boolean): Entry {
        return builtinEntry(isNightTheme)
    }

    fun isBuiltinThemeForKit(isNightTheme: Boolean, name: String): Boolean {
        return isBuiltinTheme(isNightTheme, name)
    }

    suspend fun reapplyRestoredAppliedThemes(context: Context) = withContext(IO) {
        val currentNight = AppConfig.isNightTheme
        reapplyRestoredAppliedTheme(context, !currentNight)
        reapplyRestoredAppliedTheme(context, currentNight)
    }

    suspend fun restoreAppliedThemes(context: Context) = withContext(IO) {
        restoreAppliedTheme(context, false)
        restoreAppliedTheme(context, true)
    }

    fun getConfig(entry: Entry): ThemeConfig.Config {
        val dir = entry.localDir ?: localDir(entry.packageInfo.isNightTheme, entry.dirName)
        return resolveConfigPaths(entry.packageInfo, dir)
    }

    suspend fun ensureLocalAppliedTheme(context: Context, isNightTheme: Boolean): Entry =
        withContext(IO) {
            val currentConfig = ThemeConfig.getThemeConfig(context, isNightTheme)
            val themeName = currentConfig.themeName.trim()
            if (isBuiltinTheme(isNightTheme, themeName)) {
                return@withContext builtinEntry(isNightTheme)
            }
            val config = currentConfig.copy(
                isNightTheme = isNightTheme,
                themeName = themeName.ifBlank { builtinName(isNightTheme) }
            )
            if (isBuiltinTheme(isNightTheme, config.themeName)) {
                return@withContext builtinEntry(isNightTheme)
            }
            val dirName = config.themeName.normalizeFileName()
            val dir = localDir(isNightTheme, dirName)
            readPackage(dir)?.let { pkg ->
                if (!isBuiltinPackage(pkg)) {
                    return@withContext Entry(pkg, Source.LOCAL, localDir = dir)
                }
            }
            saveConfig(config.copy(isNightTheme = isNightTheme))
        }

    private suspend fun reapplyRestoredAppliedTheme(context: Context, isNightTheme: Boolean) {
        val themeName = context.getPrefString(
            if (isNightTheme) PreferKey.dNThemeName else PreferKey.dThemeName
        )?.trim().orEmpty()
        if (themeName.isBlank()) return
        if (isBuiltinTheme(isNightTheme, themeName)) {
            val config = validatedConfig(builtinEntry(isNightTheme))
            ThemeConfig.applyConfig(context, config, switchNightMode = false, notify = false)
            return
        }
        val normalizedDirName = themeName.normalizeFileName()
        val directDir = localDir(isNightTheme, normalizedDirName)
        val entry = readPackage(directDir)?.let { pkg ->
            Entry(pkg, Source.LOCAL, localDir = directDir)
        } ?: loadLocal(isNightTheme).firstOrNull {
            it.dirName == normalizedDirName || it.packageInfo.name == themeName
        } ?: return
        val config = validatedConfig(entry)
        ThemeConfig.applyConfig(context, config, switchNightMode = false, notify = false)
    }

    private suspend fun restoreAppliedTheme(context: Context, isNightTheme: Boolean) {
        val themeName = context.getPrefString(
            if (isNightTheme) PreferKey.dNThemeName else PreferKey.dThemeName
        )?.trim().orEmpty()
        if (themeName.isBlank()) return
        if (isBuiltinTheme(isNightTheme, themeName)) {
            val config = validatedConfig(builtinEntry(isNightTheme))
            ThemeConfig.applyConfig(context, config, switchNightMode = false, notify = false)
            return
        }
        val normalizedDirName = themeName.normalizeFileName()
        val localEntry = loadLocal(isNightTheme).firstOrNull {
            it.dirName == normalizedDirName || it.packageInfo.name == themeName
        }
        val localConfig = localEntry?.let { entry ->
            runCatching { validatedConfig(entry) }.getOrElse {
                AppLog.put("restore theme local package invalid: $themeName\n${it.localizedMessage}", it)
                null
            }
        }
        if (localConfig != null) {
            ThemeConfig.applyConfig(context, localConfig, switchNightMode = false, notify = false)
            return
        }
        val remoteEntry = loadRemoteOrCache(isNightTheme).firstOrNull {
            it.dirName == normalizedDirName || it.packageInfo.name == themeName
        } ?: run {
            AppLog.put("restore theme package not found: $themeName")
            applyBuiltinTheme(context, isNightTheme)
            return
        }
        val downloadedEntry = runCatching { download(remoteEntry) }.getOrElse {
            AppLog.put("restore theme package download failed: $themeName\n${it.localizedMessage}", it)
            applyBuiltinTheme(context, isNightTheme)
            return
        }
        val remoteConfig = runCatching { validatedConfig(downloadedEntry) }.getOrElse {
            AppLog.put("restore theme package resource invalid: $themeName\n${it.localizedMessage}", it)
            applyBuiltinTheme(context, isNightTheme)
            return
        }
        ThemeConfig.applyConfig(context, remoteConfig, switchNightMode = false, notify = false)
    }

    private suspend fun applyBuiltinTheme(context: Context, isNightTheme: Boolean) {
        val config = validatedConfig(builtinEntry(isNightTheme))
        ThemeConfig.applyConfig(context, config, switchNightMode = false, notify = false)
    }

    private fun builtinEntry(isNightTheme: Boolean): Entry {
        val name = builtinName(isNightTheme)
        val config = ThemeConfig.Config(
            themeName = name,
            isNightTheme = isNightTheme,
            primaryColor = if (isNightTheme) "#455A64" else "#795548",
            accentColor = if (isNightTheme) "#FF8A65" else "#E53935",
            backgroundColor = if (isNightTheme) "#212121" else "#F5F5F5",
            bottomBackground = if (isNightTheme) "#303030" else "#EEEEEE",
            transparentNavBar = true,
            backgroundImgPath = null,
            backgroundImgBlur = 0,
            bookInfoBackgroundImgPath = null,
            panelBackgroundImgPath = null
        )
        return Entry(
            Package(
                name = name,
                dirName = builtinDirName(isNightTheme),
                isNightTheme = isNightTheme,
                updatedAt = 0L,
                config = config
            ),
            Source.BUILTIN
        )
    }

    private fun saveConfig(config: ThemeConfig.Config): Entry {
        val normalizedName = config.themeName.trim()
            .ifBlank { builtinName(config.isNightTheme) }
        val dirName = normalizedName.normalizeFileName()
        val dir = localDir(config.isNightTheme, dirName).apply {
            if (!exists()) mkdirs()
        }
        val namedConfig = config.copy(themeName = normalizedName)
        val packagedConfig = copyAssetsIntoPackage(namedConfig, dir, config.isNightTheme)
        val pkg = Package(
            name = normalizedName,
            dirName = dirName,
            isNightTheme = config.isNightTheme,
            updatedAt = System.currentTimeMillis(),
            config = packagedConfig
        )
        File(dir, packageFileName).writeText(GSON.toJson(pkg))
        ThemeConfig.addConfig(resolveConfigPaths(pkg, dir))
        return Entry(pkg, Source.LOCAL, localDir = dir)
    }

    private fun saveConfigReplacingLocal(config: ThemeConfig.Config): Entry {
        val normalizedName = config.themeName.trim()
            .ifBlank { builtinName(config.isNightTheme) }
        val dirName = normalizedName.normalizeFileName()
        val dir = localDir(config.isNightTheme, dirName)
        if (dir.exists()) {
            FileUtils.delete(dir, deleteRootDir = true)
        }
        removeThemeConfig(config.isNightTheme, normalizedName, dirName)
        return saveConfig(config.copy(themeName = normalizedName))
    }

    private fun removeThemeConfig(isNightTheme: Boolean, themeName: String, dirName: String) {
        val normalizedName = themeName.trim()
        val normalizedDirName = dirName.ifBlank { normalizedName.normalizeFileName() }
        val removed = ThemeConfig.configList.removeAll { config ->
            config.isNightTheme == isNightTheme &&
                (config.themeName == normalizedName || config.themeName.normalizeFileName() == normalizedDirName)
        }
        if (removed) {
            ThemeConfig.save()
        }
    }

    private fun loadLocal(isNightTheme: Boolean): List<Entry> {
        val typeDir = typeDir(isNightTheme)
        return typeDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                readPackage(dir)?.let { pkg ->
                    Entry(pkg, Source.LOCAL, localDir = dir)
                }
            }.orEmpty()
    }

    private fun isBuiltinDuplicate(entry: Entry): Boolean {
        return isBuiltinPackage(entry.packageInfo)
    }

    private fun isBuiltinPackage(pkg: Package): Boolean {
        return isBuiltinTheme(pkg.isNightTheme, pkg.name) || pkg.dirName == builtinDirName(pkg.isNightTheme)
    }

    private fun isBuiltinTheme(isNightTheme: Boolean, name: String): Boolean {
        return name == builtinName(isNightTheme) ||
            name == if (isNightTheme) "Built-in night" else "Built-in day"
    }

    private fun builtinName(isNightTheme: Boolean): String {
        return if (isNightTheme) builtinNightName else builtinDayName
    }

    private fun builtinDirName(isNightTheme: Boolean): String {
        return if (isNightTheme) builtinNightDirName else builtinDayDirName
    }

    private fun sortEntries(entries: List<Entry>): List<Entry> {
        return entries.sortedWith(
            compareBy<Entry> { it.source != Source.BUILTIN }
                .thenBy { it.source == Source.REMOTE }
                .thenByDescending { if (it.source == Source.REMOTE) it.remoteUpdatedAt else it.packageInfo.updatedAt }
                .thenBy { it.packageInfo.name }
                .thenBy { it.dirName }
        )
    }

    private suspend fun loadRemote(isNightTheme: Boolean, containerId: String? = null, scope: String? = null): List<Entry> {
        return AppCloudStorage.listThemePackages(isNightTheme, containerId, scope).mapNotNull { remoteDir ->
            val rawName = remoteZipBaseName(remoteDir.displayName)
            val dirName = rawName.normalizeFileName().ifBlank {
                AppLog.put("跳过异常远端主题包: ${remoteDir.displayName}")
                return@mapNotNull null
            }
            Entry(
                packageInfo = Package(
                    name = rawName.ifBlank { dirName },
                    dirName = dirName,
                    isNightTheme = isNightTheme,
                    updatedAt = remoteDir.lastModify,
                    config = null
                ),
                source = Source.REMOTE,
                remoteUpdatedAt = remoteDir.lastModify
            )
        }.dedupeRemoteEntries()
    }

    private fun remoteZipBaseName(displayName: String): String {
        val name = displayName.trim().trimEnd('/').trim()
        return if (name.endsWith(".zip", ignoreCase = true)) {
            name.dropLast(4).trim()
        } else {
            name
        }
    }

    private fun List<Entry>.dedupeRemoteEntries(): List<Entry> {
        return groupBy { it.dirName }.values.mapNotNull { entries ->
            entries.maxByOrNull { it.remoteUpdatedAt }
        }
    }

    private suspend fun loadRemoteOrCache(isNightTheme: Boolean, containerId: String? = null, scope: String? = null): List<Entry> {
        val cached = readRemoteCache(isNightTheme, containerId)
        return try {
            val remote = withTimeout(remoteListTimeoutMillis) {
                loadRemote(isNightTheme, containerId, scope)
            }
            if (remote.isNotEmpty() || cached.isEmpty()) {
                writeRemoteCache(isNightTheme, remote, containerId)
            }
            remote
        } catch (e: TimeoutCancellationException) {
            AppLog.put(
                "加载远端主题包列表超时: type=${AppCloudStorage.type}, container=${containerId.orEmpty()}, timeout=${remoteListTimeoutMillis}ms"
            )
            cached
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put(
                "加载远端主题包列表失败: type=${AppCloudStorage.type}, container=${containerId.orEmpty()}\n${e.localizedMessage}",
                e
            )
            cached
        }
    }

    private fun remoteCacheFile(isNightTheme: Boolean, containerId: String? = null): File {
        val mode = if (isNightTheme) "night" else "day"
        val suffix = containerId?.takeIf { it.isNotBlank() }?.normalizeFileName()?.let { "_$it" }.orEmpty()
        return remoteCacheDir.getFile("$mode$suffix.json")
    }

    private fun readRemoteCache(isNightTheme: Boolean, containerId: String? = null): List<Entry> {
        val file = remoteCacheFile(isNightTheme, containerId)
        if (!file.exists()) return emptyList()
        return GSON.fromJsonArray<Package>(file.readText()).getOrDefault(emptyList())
            .filter { it.isNightTheme == isNightTheme }
            .map { pkg ->
                Entry(pkg.copy(config = null), Source.REMOTE, remoteUpdatedAt = pkg.updatedAt)
            }
    }

    private fun writeRemoteCache(isNightTheme: Boolean, entries: List<Entry>, containerId: String? = null) {
        val packages = entries.map {
            it.packageInfo.copy(
                config = null,
                updatedAt = it.remoteUpdatedAt.takeIf { time -> time > 0L } ?: it.packageInfo.updatedAt
            )
        }
        remoteCacheFile(isNightTheme, containerId).writeTextIfChanged(GSON.toJson(packages))
    }

    private fun readPackage(dir: File): Package? {
        val file = File(dir, packageFileName)
        if (!file.exists()) return null
        return GSON.fromJsonObject<Package>(file.readText()).getOrNull()
    }

    private fun peekPackage(zipFile: File): Package {
        val unzipDir = tempDir.getFile("peek_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        return try {
            ZipUtils.unZipToPath(zipFile, unzipDir) { it.endsWith(packageFileName) }
            val packageFile = unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }
                ?: throw IllegalArgumentException(appCtx.getString(R.string.theme_config_file_missing))
            GSON.fromJsonObject<Package>(packageFile.readText()).getOrThrow()
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun importRed(file: File): List<Entry> {
        return when (detectRedPackageFormat(file)) {
            RedPackageFormat.RED04_ZIP -> importRedZip(file)
            RedPackageFormat.RED_GZIP_JSON,
            RedPackageFormat.RAW_GZIP_JSON -> importRedGzip(file)
            RedPackageFormat.RED10_PRIVATE -> {
                if (file.isArcRecoveryFile()) {
                    importRed10ArcRecoveryDetailed(file).themes
                } else {
                    throw IllegalArgumentException(appCtx.getString(R.string.theme_red_private_encrypted_unsupported))
                }
            }
            null -> throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
        }
    }

    private fun File.isArcRecoveryFile(): Boolean {
        return extension.equals("arc", ignoreCase = true)
    }

    private fun importRedGzip(file: File): List<Entry> {
        val redPackage = readRedThemePackage(file)
        if (redPackage.type != "theme" || redPackage.data.isEmpty()) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
        }
        val configs = redPackage.data.flatMap { item ->
            listOfNotNull(
                item.light
                    ?.takeIf { it.hasMeaningfulThemeContent(item.lightBackgroundImage) }
                    ?.toThemeConfig(item.name, false, item.lightBackgroundImage),
                item.dark
                    ?.takeIf { it.hasMeaningfulThemeContent(item.darkBackgroundImage) }
                    ?.toThemeConfig(item.name, true, item.darkBackgroundImage)
            )
        }
        if (configs.isEmpty()) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
        }
        return configs.map(::saveConfigReplacingLocal)
    }

    private fun detectRedPackageFormat(file: File): RedPackageFormat? {
        if (!file.isFile || file.length() < 2) return null
        return file.inputStream().use { input ->
            val header = ByteArray(8)
            val size = input.read(header)
            when {
                size >= 6 &&
                    header[0] == 'R'.code.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'D'.code.toByte() &&
                    header[3] == 4.toByte() &&
                    header[4] == 'P'.code.toByte() &&
                    header[5] == 'K'.code.toByte() -> RedPackageFormat.RED04_ZIP

                size >= 6 &&
                    header[0] == 'R'.code.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'D'.code.toByte() &&
                    header[3] == 0x10.toByte() -> RedPackageFormat.RED10_PRIVATE

                size >= 6 &&
                    header[0] == 'R'.code.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'D'.code.toByte() &&
                    header[4] == 0x1F.toByte() &&
                    header[5] == 0x8B.toByte() -> RedPackageFormat.RED_GZIP_JSON

                size >= 2 &&
                    header[0] == 0x1F.toByte() &&
                    header[1] == 0x8B.toByte() -> RedPackageFormat.RAW_GZIP_JSON

                else -> null
            }
        }
    }

    private fun importRedZip(file: File): List<Entry> {
        return importRedZipDetailed(file).themes
    }

    private fun importRed10ArcRecoveryDetailed(file: File): ThemeImportResult {
        val recoveryDir = tempDir.getFile("red10_arc_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        return try {
            val bytes = file.readBytes()
            validateRed10ArcHeader(bytes)
            val arcResources = readRed10ArcResources(bytes)
            val redTheme = arcResources.theme
            val images = scanPlainImageSegments(bytes)
            val lightBackground = recoverRed10BackgroundFromSchema(
                bytes = bytes,
                images = images,
                schema = arcResources.lightSchema,
                target = File(recoveryDir, "light_background")
            )
            val darkBackground = recoverRed10BackgroundFromSchema(
                bytes = bytes,
                images = images,
                schema = arcResources.darkSchema,
                target = File(recoveryDir, "dark_background")
            )
            val entries = listOfNotNull(
                redTheme.light
                    ?.takeIf { it.hasMeaningfulThemeContent(lightBackground?.absolutePath) }
                    ?.toThemeConfig(redTheme.name, false, inlineBackground = null)
                    ?.copy(backgroundImgPath = lightBackground?.absolutePath),
                redTheme.dark
                    ?.takeIf { it.hasMeaningfulThemeContent(darkBackground?.absolutePath) }
                    ?.toThemeConfig(redTheme.name, true, inlineBackground = null)
                    ?.copy(backgroundImgPath = darkBackground?.absolutePath)
            ).map(::saveConfigReplacingLocal)
            if (entries.isEmpty()) {
                throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
            }
            val navigationBars = recoverRed10NavigationBars(redTheme, images, bytes, recoveryDir)
            val coverCollections = recoverRed10CoverCollections(redTheme, images, bytes, recoveryDir)
            ThemeImportResult(
                sourceName = redTheme.name,
                themes = entries,
                navigationBars = navigationBars,
                coverCollections = coverCollections
            )
        } catch (e: Throwable) {
            if (e is IllegalArgumentException) throw e
            throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid), e)
        } finally {
            FileUtils.delete(recoveryDir, deleteRootDir = true)
        }
    }

    private fun validateRed10ArcHeader(bytes: ByteArray) {
        if (bytes.size < 12 ||
            bytes[0] != 'R'.code.toByte() ||
            bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'D'.code.toByte() ||
            bytes[3] != 0x10.toByte()
        ) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
        }
        val headerLength = ((bytes[4].toInt() and 0xFF) shl 24) or
            ((bytes[5].toInt() and 0xFF) shl 16) or
            ((bytes[6].toInt() and 0xFF) shl 8) or
            (bytes[7].toInt() and 0xFF)
        if (headerLength <= 0 || 8 + headerLength > bytes.size) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
        }
        val header = bytes.decodeToString(8, 8 + headerLength)
        if (!header.contains("\"resourceType\":\"themeBundle\"") ||
            !header.contains("\"payloadType\":\"directory\"") ||
            !header.contains("\"containerMode\":\"reedenPrivate\"") ||
            !header.contains("\"assetMode\":\"plain-sequential\"")
        ) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
        }
    }

    private fun readRed10ArcResources(bytes: ByteArray): Red10ArcResources {
        val scanSize = minOf(bytes.size, 4 * 1024 * 1024)
        val offset = bytes.size - scanSize
        val text = bytes.decodeToString(offset, bytes.size)
        val schemas = mutableListOf<Red10ColorSchema>()
        var theme: RedThemeV4? = null
        var start = text.indexOf("{\"name\"")
        while (start >= 0) {
            val end = findBalancedJsonEnd(text, start)
            if (end > start) {
                val candidate = text.substring(start, end)
                val themeCandidate = GSON.fromJsonObject<RedThemeV4>(candidate).getOrNull()
                if (themeCandidate != null &&
                    themeCandidate.name.isNotBlank() &&
                    (themeCandidate.light != null || themeCandidate.dark != null)
                ) {
                    theme = themeCandidate
                }
                val schema = GSON.fromJsonObject<Red10ColorSchema>(candidate).getOrNull()
                if (schema != null && schema.name.isNotBlank() && schema.backgroundImageUrl.isNotBlank()) {
                    schemas.add(schema)
                }
            }
            start = text.indexOf("{\"name\"", start + 1)
        }
        val redTheme = theme ?: throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
        val lightSchema = schemas.firstOrNull { !it.looksNightMode() } ?: schemas.firstOrNull()
        val darkSchema = schemas.firstOrNull { it.looksNightMode() } ?: schemas.drop(1).firstOrNull()
        return Red10ArcResources(redTheme, lightSchema, darkSchema)
    }

    private fun findBalancedJsonEnd(text: String, start: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val char = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return index + 1
                }
            }
        }
        return -1
    }

    private fun recoverRed10BackgroundFromSchema(
        bytes: ByteArray,
        images: List<PlainImageSegment>,
        schema: Red10ColorSchema?,
        target: File
    ): File? {
        schema ?: return null
        val imageId = schema.backgroundImageUrl
        if (imageId.isBlank() || images.isEmpty()) return null
        val idBytes = imageId.encodeToByteArray()
        val position = findAllBytePositions(bytes, idBytes)
            .minOrNull()
            ?: return null
        val segment = images.asSequence()
            .filter { it.end <= position }
            .map { it to position - it.end }
            .filter { it.second in 0..RED10_RESOURCE_REF_MAX_DISTANCE }
            .minByOrNull { it.second }
            ?.first
            ?: return null
        val file = File(target.parentFile, "${target.name}${segment.suffix}")
        writeImageSegment(bytes, segment, file)
        return file
    }

    private fun recoverRed10NavigationBars(
        redTheme: RedThemeV4,
        images: List<PlainImageSegment>,
        bytes: ByteArray,
        recoveryDir: File
    ): List<NavigationBarIconConfig.Entry> {
        if (redTheme.light?.navbarPackId.isNullOrBlank() && redTheme.dark?.navbarPackId.isNullOrBlank()) {
            return emptyList()
        }
        val iconGroup = findRed10NavigationIconGroup(images) ?: return emptyList()
        return listOfNotNull(
            importRed10NavigationBar(bytes, iconGroup, redTheme.name, false, recoveryDir),
            importRed10NavigationBar(bytes, iconGroup, redTheme.name, true, recoveryDir)
        )
    }

    private fun importRed10NavigationBar(
        bytes: ByteArray,
        iconGroup: List<PlainImageSegment>,
        themeName: String,
        isNightTheme: Boolean,
        recoveryDir: File
    ): NavigationBarIconConfig.Entry? {
        val packageDir = recoveryDir.getFile("nav_${if (isNightTheme) "night" else "day"}").apply { mkdirs() }
        val zipFile = recoveryDir.getFile("nav_${if (isNightTheme) "night" else "day"}.zip")
        return try {
            val itemMap = listOf("discovery", "bookshelf", "rss", "readRecord", "my", "ai")
            val icons = linkedMapOf<String, String>()
            iconGroup.take(itemMap.size).forEachIndexed { index, segment ->
                val itemKey = itemMap[index]
                val fileName = "${itemKey}_normal${segment.suffix}"
                val target = File(packageDir, fileName)
                writeImageSegment(bytes, segment, target)
                icons["${itemKey}_normal"] = fileName
                icons["${itemKey}_selected"] = fileName
            }
            if (icons.size < 5) return null
            val config = NavigationBarIconConfig.Config(
                name = "${themeName.ifBlank { "ARC" }} ${if (isNightTheme) "Night" else "Day"}",
                isNightMode = isNightTheme,
                layoutMode = "floating",
                effectMode = "glass",
                opacity = 72,
                icons = icons
            )
            File(packageDir, "navigation.json").writeText(GSON.toJson(config))
            if (!ZipUtils.zipFile(packageDir, zipFile)) return null
            NavigationBarIconConfig.importZip(zipFile)
        } finally {
            zipFile.delete()
        }
    }

    private fun recoverRed10CoverCollections(
        redTheme: RedThemeV4,
        images: List<PlainImageSegment>,
        bytes: ByteArray,
        recoveryDir: File
    ): List<CoverCollectionManager.Collection> {
        if (redTheme.light?.coverGalleryId.isNullOrBlank() && redTheme.dark?.coverGalleryId.isNullOrBlank()) {
            return emptyList()
        }
        val coverGroup = findRed10CoverImageGroup(images) ?: return emptyList()
        return listOfNotNull(
            importRed10CoverCollection(bytes, coverGroup, redTheme.name, false, recoveryDir),
            importRed10CoverCollection(bytes, coverGroup, redTheme.name, true, recoveryDir)
        )
    }

    private fun importRed10CoverCollection(
        bytes: ByteArray,
        coverGroup: List<PlainImageSegment>,
        themeName: String,
        isNightTheme: Boolean,
        recoveryDir: File
    ): CoverCollectionManager.Collection? {
        val packageDir = recoveryDir.getFile("cover_${if (isNightTheme) "night" else "day"}").apply { mkdirs() }
        val imagesDir = packageDir.getFile("images").apply { mkdirs() }
        val zipFile = recoveryDir.getFile("cover_${if (isNightTheme) "night" else "day"}.zip")
        return try {
            val imageRefs = coverGroup.mapIndexed { index, segment ->
                val fileName = "cover_${index + 1}${segment.suffix}"
                writeImageSegment(bytes, segment, File(imagesDir, fileName))
                "images/$fileName"
            }
            if (imageRefs.size < 3) return null
            val collection = CoverCollectionManager.Collection(
                id = UUID.randomUUID().toString(),
                name = "${themeName.ifBlank { "ARC" }} ${if (isNightTheme) "Night" else "Day"}",
                dirName = "${themeName}_${if (isNightTheme) "night" else "day"}_covers".normalizeFileName(),
                isNight = isNightTheme,
                images = imageRefs,
                updatedAt = System.currentTimeMillis()
            )
            File(packageDir, "collection.json").writeText(GSON.toJson(collection))
            if (!ZipUtils.zipFile(packageDir, zipFile)) return null
            kotlinx.coroutines.runBlocking {
                CoverCollectionManager.importZip(appCtx, zipFile, isNightTheme)
            }
        } finally {
            zipFile.delete()
        }
    }

    private fun findRed10CoverImageGroup(images: List<PlainImageSegment>): List<PlainImageSegment>? {
        return contiguousRed10Groups(images) {
            it.suffix == ".png" &&
                it.width >= 600 &&
                it.height >= 600 &&
                it.aspectRatio in 0.60f..0.95f
        }
            .filter { group -> group.size >= 3 }
            .maxByOrNull { it.size }
    }

    private fun findRed10NavigationIconGroup(images: List<PlainImageSegment>): List<PlainImageSegment>? {
        return contiguousRed10Groups(images) {
            it.suffix == ".png" &&
                it.width in 96..1500 &&
                it.height in 96..1500 &&
                it.aspectRatio in 0.75f..1.35f
            }
            .filter { group -> group.size >= 4 }
            .maxByOrNull { it.size }
            ?.take(6)
    }

    private fun contiguousRed10Groups(
        images: List<PlainImageSegment>,
        predicate: (PlainImageSegment) -> Boolean
    ): List<List<PlainImageSegment>> {
        val groups = mutableListOf<MutableList<PlainImageSegment>>()
        images.sortedBy { it.start }.filter(predicate).forEach { image ->
            val current = groups.lastOrNull()
            if (current != null && image.start - current.last().end in 0..RED10_RESOURCE_GROUP_MAX_GAP) {
                current.add(image)
            } else {
                groups.add(mutableListOf(image))
            }
        }
        return groups
    }

    private fun writeImageSegment(bytes: ByteArray, segment: PlainImageSegment, file: File) {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { output ->
            output.write(bytes, segment.start, segment.end - segment.start)
        }
    }

    private fun findAllBytePositions(bytes: ByteArray, pattern: ByteArray): List<Int> {
        if (pattern.isEmpty() || bytes.size < pattern.size) return emptyList()
        val positions = mutableListOf<Int>()
        var index = 0
        while (index <= bytes.size - pattern.size) {
            var matched = true
            for (offset in pattern.indices) {
                if (bytes[index + offset] != pattern[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) {
                positions.add(index)
                index += pattern.size
            } else {
                index++
            }
        }
        return positions
    }

    private fun scanPlainImageSegments(bytes: ByteArray): List<PlainImageSegment> {
        val segments = mutableListOf<PlainImageSegment>()
        var index = 0
        while (index < bytes.size - 12) {
            val segment = when {
                isPngAt(bytes, index) -> pngSegment(bytes, index)
                isJpegAt(bytes, index) -> jpegSegment(bytes, index)
                isWebpAt(bytes, index) -> riffSegment(bytes, index, ".webp")
                isGifAt(bytes, index) -> gifSegment(bytes, index)
                else -> null
            }
            if (segment != null && segment.end > segment.start) {
                segments.add(segment)
                index = segment.end
            } else {
                index++
            }
        }
        return segments
    }

    private fun isPngAt(bytes: ByteArray, index: Int): Boolean {
        return index + 8 < bytes.size &&
            bytes[index] == 0x89.toByte() &&
            bytes[index + 1] == 0x50.toByte() &&
            bytes[index + 2] == 0x4E.toByte() &&
            bytes[index + 3] == 0x47.toByte() &&
            bytes[index + 4] == 0x0D.toByte() &&
            bytes[index + 5] == 0x0A.toByte() &&
            bytes[index + 6] == 0x1A.toByte() &&
            bytes[index + 7] == 0x0A.toByte()
    }

    private fun pngSegment(bytes: ByteArray, start: Int): PlainImageSegment? {
        var index = start + 8
        while (index + 12 <= bytes.size) {
            val length = readIntBigEndian(bytes, index)
            if (length < 0 || index + 12 + length > bytes.size) return null
            val typeStart = index + 4
            val isEnd = bytes[typeStart] == 'I'.code.toByte() &&
                bytes[typeStart + 1] == 'E'.code.toByte() &&
                bytes[typeStart + 2] == 'N'.code.toByte() &&
                bytes[typeStart + 3] == 'D'.code.toByte()
            index += 12 + length
            if (isEnd) {
                return PlainImageSegment(
                    start = start,
                    end = index,
                    suffix = ".png",
                    width = readIntBigEndian(bytes, start + 16).takeIf { it > 0 } ?: 0,
                    height = readIntBigEndian(bytes, start + 20).takeIf { it > 0 } ?: 0
                )
            }
        }
        return null
    }

    private fun isJpegAt(bytes: ByteArray, index: Int): Boolean {
        return index + 2 < bytes.size &&
            bytes[index] == 0xFF.toByte() &&
            bytes[index + 1] == 0xD8.toByte() &&
            bytes[index + 2] == 0xFF.toByte()
    }

    private fun jpegSegment(bytes: ByteArray, start: Int): PlainImageSegment? {
        var index = start + 2
        while (index + 1 < bytes.size) {
            if (bytes[index] == 0xFF.toByte() && bytes[index + 1] == 0xD9.toByte()) {
                val end = index + 2
                val (width, height) = readJpegSize(bytes, start, end)
                return PlainImageSegment(start, end, ".jpg", width, height)
            }
            index++
        }
        return null
    }

    private fun isWebpAt(bytes: ByteArray, index: Int): Boolean {
        return index + 12 < bytes.size &&
            bytes[index] == 'R'.code.toByte() &&
            bytes[index + 1] == 'I'.code.toByte() &&
            bytes[index + 2] == 'F'.code.toByte() &&
            bytes[index + 3] == 'F'.code.toByte() &&
            bytes[index + 8] == 'W'.code.toByte() &&
            bytes[index + 9] == 'E'.code.toByte() &&
            bytes[index + 10] == 'B'.code.toByte() &&
            bytes[index + 11] == 'P'.code.toByte()
    }

    private fun riffSegment(bytes: ByteArray, start: Int, suffix: String): PlainImageSegment? {
        val length = readIntLittleEndian(bytes, start + 4)
        val end = start + 8 + length
        if (length <= 4 || end > bytes.size) return null
        val (width, height) = readWebpSize(bytes, start, end)
        return PlainImageSegment(start, end, suffix, width, height)
    }

    private fun isGifAt(bytes: ByteArray, index: Int): Boolean {
        return index + 6 < bytes.size &&
            bytes[index] == 'G'.code.toByte() &&
            bytes[index + 1] == 'I'.code.toByte() &&
            bytes[index + 2] == 'F'.code.toByte() &&
            bytes[index + 3] == '8'.code.toByte() &&
            (bytes[index + 4] == '7'.code.toByte() || bytes[index + 4] == '9'.code.toByte()) &&
            bytes[index + 5] == 'a'.code.toByte()
    }

    private fun gifSegment(bytes: ByteArray, start: Int): PlainImageSegment? {
        var index = start + 6
        while (index < bytes.size) {
            if (bytes[index] == 0x3B.toByte()) {
                val width = readUnsignedShortLittleEndian(bytes, start + 6)
                val height = readUnsignedShortLittleEndian(bytes, start + 8)
                return PlainImageSegment(start, index + 1, ".gif", width, height)
            }
            index++
        }
        return null
    }

    private fun readJpegSize(bytes: ByteArray, start: Int, end: Int): Pair<Int, Int> {
        var index = start + 2
        while (index + 9 < end) {
            if (bytes[index] != 0xFF.toByte()) {
                index++
                continue
            }
            while (index < end && bytes[index] == 0xFF.toByte()) index++
            if (index >= end) break
            val marker = bytes[index].toInt() and 0xFF
            index++
            if (marker == 0xD8 || marker == 0xD9 || marker == 0x01) continue
            if (index + 2 > end) break
            val segmentLength = readUnsignedShortBigEndian(bytes, index)
            if (segmentLength < 2 || index + segmentLength > end) break
            if (marker in setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF) &&
                index + 7 <= end
            ) {
                val height = readUnsignedShortBigEndian(bytes, index + 3)
                val width = readUnsignedShortBigEndian(bytes, index + 5)
                return width to height
            }
            index += segmentLength
        }
        return 0 to 0
    }

    private fun readWebpSize(bytes: ByteArray, start: Int, end: Int): Pair<Int, Int> {
        if (start + 30 > end) return 0 to 0
        return when (bytes.decodeToString(start + 12, start + 16)) {
            "VP8X" -> {
                val width = 1 + readUInt24LittleEndian(bytes, start + 24)
                val height = 1 + readUInt24LittleEndian(bytes, start + 27)
                width to height
            }

            "VP8 " -> {
                val frame = start + 20
                if (frame + 10 <= end) {
                    val width = readUnsignedShortLittleEndian(bytes, frame + 6) and 0x3FFF
                    val height = readUnsignedShortLittleEndian(bytes, frame + 8) and 0x3FFF
                    width to height
                } else {
                    0 to 0
                }
            }

            "VP8L" -> {
                val frame = start + 20
                if (frame + 5 <= end && bytes[frame] == 0x2F.toByte()) {
                    val bits = readIntLittleEndian(bytes, frame + 1)
                    val width = (bits and 0x3FFF) + 1
                    val height = ((bits shr 14) and 0x3FFF) + 1
                    width to height
                } else {
                    0 to 0
                }
            }

            else -> 0 to 0
        }
    }

    private fun readIntBigEndian(bytes: ByteArray, index: Int): Int {
        if (index + 4 > bytes.size) return -1
        return ((bytes[index].toInt() and 0xFF) shl 24) or
            ((bytes[index + 1].toInt() and 0xFF) shl 16) or
            ((bytes[index + 2].toInt() and 0xFF) shl 8) or
            (bytes[index + 3].toInt() and 0xFF)
    }

    private fun readIntLittleEndian(bytes: ByteArray, index: Int): Int {
        if (index + 4 > bytes.size) return -1
        return (bytes[index].toInt() and 0xFF) or
            ((bytes[index + 1].toInt() and 0xFF) shl 8) or
            ((bytes[index + 2].toInt() and 0xFF) shl 16) or
            ((bytes[index + 3].toInt() and 0xFF) shl 24)
    }

    private fun readUnsignedShortBigEndian(bytes: ByteArray, index: Int): Int {
        if (index + 2 > bytes.size) return -1
        return ((bytes[index].toInt() and 0xFF) shl 8) or
            (bytes[index + 1].toInt() and 0xFF)
    }

    private fun readUnsignedShortLittleEndian(bytes: ByteArray, index: Int): Int {
        if (index + 2 > bytes.size) return -1
        return (bytes[index].toInt() and 0xFF) or
            ((bytes[index + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUInt24LittleEndian(bytes: ByteArray, index: Int): Int {
        if (index + 3 > bytes.size) return 0
        return (bytes[index].toInt() and 0xFF) or
            ((bytes[index + 1].toInt() and 0xFF) shl 8) or
            ((bytes[index + 2].toInt() and 0xFF) shl 16)
    }

    private fun importRedZipDetailed(file: File): ThemeImportResult {
        val zipFile = tempDir.getFile("red_${System.currentTimeMillis()}.zip")
        val unzipDir = tempDir.getFile("red_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                if (input.read(header) != header.size) {
                    throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
                }
                FileOutputStream(zipFile).use { output -> input.copyTo(output) }
            }
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val target = File(unzipDir, entry.name)
                    target.canonicalPath.takeIf { it.startsWith(unzipDir.canonicalPath) }
                        ?: throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                }
            }
            val themeFile = File(unzipDir, packageFileName)
            val redTheme = GSON.fromJsonObject<RedThemeV4>(themeFile.readText()).getOrThrow()
            val entries = listOfNotNull(
                redTheme.light
                    ?.takeIf { it.hasMeaningfulThemeContent(File(unzipDir, "light/theme_bg.img").takeIf(File::isFile)?.absolutePath) }
                    ?.toThemeConfig(redTheme.name, false, unzipDir),
                redTheme.dark
                    ?.takeIf { it.hasMeaningfulThemeContent(File(unzipDir, "dark/theme_bg.img").takeIf(File::isFile)?.absolutePath) }
                    ?.toThemeConfig(redTheme.name, true, unzipDir)
            ).map(::saveConfigReplacingLocal)
            if (entries.isEmpty()) {
                throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
            }
            val navigationBars = listOfNotNull(
                importRedNavigationPack(unzipDir, redTheme.light, false),
                importRedNavigationPack(unzipDir, redTheme.dark, true)
            )
            val coverCollections = listOfNotNull(
                importRedCoverGallery(unzipDir, redTheme.light, false),
                importRedCoverGallery(unzipDir, redTheme.dark, true)
            )
            copyRedReaderSchema(unzipDir, redTheme.light)
            copyRedReaderSchema(unzipDir, redTheme.dark)
            ThemeImportResult(
                sourceName = redTheme.name,
                themes = entries,
                navigationBars = navigationBars,
                coverCollections = coverCollections
            )
        } catch (e: Throwable) {
            if (e is IllegalArgumentException) throw e
            throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid), e)
        } finally {
            zipFile.delete()
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun RedThemeColors.toThemeConfig(name: String, isNightTheme: Boolean, root: File): ThemeConfig.Config {
        val modeDir = if (isNightTheme) "dark" else "light"
        val background = File(root, "$modeDir/theme_bg.img")
            .takeIf { it.isFile }
            ?.absolutePath
        val panelBackground = resolveRedCardBackground(root)
        return toThemeConfig(name, isNightTheme).copy(
            backgroundImgPath = background,
            panelBackgroundImgPath = panelBackground,
            panelBackgroundScaleType = ThemeConfig.PANEL_BG_CROP,
            panelBorderColor = borderColor.normalizeArgbColorOrNull(),
            panelBorderAlpha = if (borderColor.isBlank()) null else 100,
            uiLayoutAlpha = cardColor.alphaPercentOrNull() ?: 100,
            dialogAlpha = 100
        )
    }

    private fun importRedNavigationPack(
        root: File,
        colors: RedThemeColors?,
        isNightTheme: Boolean
    ): NavigationBarIconConfig.Entry? {
        val packId = colors?.navbarPackId?.takeIf { it.isNotBlank() } ?: return null
        val sourceDir = File(root, "navbar_pack/$packId").takeIf { it.isDirectory } ?: return null
        val meta = File(sourceDir, "meta.json")
            .takeIf { it.isFile }
            ?.let { GSON.fromJsonObject<RedNameMeta>(it.readText()).getOrNull() }
        val packageDir = tempDir.getFile("red_nav_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        val zipFile = tempDir.getFile("red_nav_${System.currentTimeMillis()}.zip")
        try {
            val icons = linkedMapOf<String, String>()
            val itemMap = mapOf(
                "home" to "discovery",
                "bookshelf" to "bookshelf",
                "notes" to "rss",
                "statistics" to "readRecord",
                "settings" to "my"
            )
            itemMap.forEach { (redKey, targetKey) ->
                val source = File(sourceDir, "${redKey}_normal.png")
                if (source.isFile) {
                    val name = "${targetKey}_normal.png"
                    source.copyTo(File(packageDir, name), overwrite = true)
                    icons["${targetKey}_normal"] = name
                }
            }
            if (icons.isEmpty()) return null
            val config = NavigationBarIconConfig.Config(
                name = meta?.name?.ifBlank { null } ?: "RED Navigation",
                isNightMode = isNightTheme,
                layoutMode = "floating",
                effectMode = "glass",
                opacity = colors.cardColor.alphaPercentOrNull() ?: 72,
                icons = icons
            )
            File(packageDir, "navigation.json").writeText(GSON.toJson(config))
            ZipUtils.zipFile(packageDir, zipFile)
            return NavigationBarIconConfig.importZip(zipFile)
        } finally {
            zipFile.delete()
            FileUtils.delete(packageDir, deleteRootDir = true)
        }
    }

    private fun importRedCoverGallery(
        root: File,
        colors: RedThemeColors?,
        isNightTheme: Boolean
    ): CoverCollectionManager.Collection? {
        val galleryId = colors?.coverGalleryId?.takeIf { it.isNotBlank() } ?: return null
        val sourceDir = File(root, "cover_gallery/$galleryId").takeIf { it.isDirectory } ?: return null
        val meta = File(sourceDir, "meta.json")
            .takeIf { it.isFile }
            ?.let { GSON.fromJsonObject<RedNameMeta>(it.readText()).getOrNull() }
        val packageDir = tempDir.getFile("red_cover_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        val imagesDir = packageDir.getFile("images").apply { mkdirs() }
        val zipFile = tempDir.getFile("red_cover_${System.currentTimeMillis()}.zip")
        try {
            val images = sourceDir.listFiles()
                ?.filter { it.isFile && it.name != "meta.json" }
                ?.sortedBy { it.name }
                ?.mapIndexedNotNull { index, source ->
                    val target = File(imagesDir, "cover_${index + 1}.${source.extension.ifBlank { "png" }}")
                    source.copyTo(target, overwrite = true)
                    "images/${target.name}"
                }
                .orEmpty()
            if (images.isEmpty()) return null
            val collection = CoverCollectionManager.Collection(
                id = UUID.randomUUID().toString(),
                name = meta?.name?.ifBlank { null } ?: "RED Cover Gallery",
                dirName = (meta?.name?.ifBlank { null } ?: "RED Cover Gallery").normalizeFileName(),
                isNight = isNightTheme,
                images = images,
                updatedAt = System.currentTimeMillis()
            )
            File(packageDir, "collection.json").writeText(GSON.toJson(collection))
            ZipUtils.zipFile(packageDir, zipFile)
            return kotlinx.coroutines.runBlocking {
                CoverCollectionManager.importZip(appCtx, zipFile, isNightTheme)
            }
        } finally {
            zipFile.delete()
            FileUtils.delete(packageDir, deleteRootDir = true)
        }
    }

    private fun copyRedReaderSchema(root: File, light: RedThemeColors?) {
        val schemaId = light?.readerColorSchemaId?.takeIf { it.isNotBlank() } ?: return
        val sourceDir = File(root, "reader_schema/$schemaId").takeIf { it.isDirectory } ?: return
        val targetDir = rootDir.getFile(redReaderSchemaDirName).getFile(schemaId).apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        sourceDir.copyRecursively(targetDir, overwrite = true)
    }

    private fun String.alphaPercentOrNull(): Int? {
        val value = trim().removePrefix("#")
        if (value.length != 8) return null
        return value.take(2).toIntOrNull(16)
            ?.let { (it * 100f / 255f).toInt().coerceIn(0, 100) }
    }

    private fun RedThemeColors.hasMeaningfulThemeContent(backgroundPayload: String? = null): Boolean {
        return listOf(
            primaryColor,
            backgroundColor,
            foregroundColor,
            mutedForegroundColor,
            cardColor,
            cardForegroundColor,
            mutedColor,
            borderColor,
            accentColor,
            backgroundImage,
            backgroundPayload.orEmpty(),
            cardBackgroundImage,
            coverGalleryId,
            navbarPackId,
            readerColorSchemaId,
            searchFieldBackgroundColor,
            tabBackgroundColor,
            shelfColor
        ).any { it.isNotBlank() } ||
            cardShadow != null ||
            cardBackgroundBlur != null ||
            cardBorder ||
            switchBorder ||
            tabBorder ||
            searchFieldBorder
    }

    private fun RedThemeColors.resolveRedCardBackground(root: File): String? {
        val relativePath = cardBackgroundImage.takeIf { it.isNotBlank() } ?: return null
        val direct = File(root, relativePath)
        if (direct.isFile) return direct.absolutePath
        val fileName = relativePath.substringAfterLast('/')
        return root.walkTopDown().firstOrNull { it.isFile && it.name == fileName }?.absolutePath
    }

    private fun writeInlineRedImage(encoded: String, prefix: String, dir: File): String? {
        if (encoded.isBlank()) return null
        return runCatching {
            val decoded = Base64.decode(encoded, Base64.DEFAULT)
            val bytes = if (decoded.size >= 2 && decoded[0] == 0x1F.toByte() && decoded[1] == 0x8B.toByte()) {
                GZIPInputStream(ByteArrayInputStream(decoded)).use { it.readBytes() }
            } else {
                decoded
            }
            val suffix = detectImageSuffix(bytes)
            val target = dir.getFile("${prefix.normalizeFileName()}$suffix")
            FileOutputStream(target).use { it.write(bytes) }
            target.absolutePath
        }.getOrNull()
    }

    private fun detectImageSuffix(bytes: ByteArray): String {
        imageMagic.forEach { (magic, suffix) ->
            if (bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }) {
                return suffix
            }
        }
        return ".img"
    }

    private fun readRedThemePackage(file: File): RedThemePackage {
        val format = detectRedPackageFormat(file)
        return runCatching {
            file.inputStream().use { input ->
                when (format) {
                    RedPackageFormat.RED_GZIP_JSON -> {
                        val header = ByteArray(4)
                        if (input.read(header) != header.size) {
                            throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
                        }
                    }

                    RedPackageFormat.RAW_GZIP_JSON -> Unit
                    else -> throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
                }
                GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { reader ->
                    GSON.fromJsonObject<RedThemePackage>(reader.readText()).getOrThrow()
                }
            }
        }.getOrElse {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid), it)
        }
    }

    private fun RedThemeColors.toThemeConfig(
        name: String,
        isNightTheme: Boolean,
        inlineBackground: String? = null
    ): ThemeConfig.Config {
        val backgroundPath = inlineBackground?.let {
            writeInlineRedImage(
                encoded = it,
                prefix = "${name}_${if (isNightTheme) "night" else "day"}_background",
                dir = tempDir
            )
        }
        val panelPath = cardBackgroundImage.takeIf { it.isNotBlank() }?.let {
            resolveRedCardBackground(tempDir)
        }
        return ThemeConfig.Config(
            themeName = name.trim().ifBlank { "RED Theme" },
            isNightTheme = isNightTheme,
            primaryColor = primaryColor.normalizeArgbColor(default = if (isNightTheme) "#FFD97757" else "#FFD97757"),
            accentColor = accentColor.normalizeArgbColor(default = primaryColor.ifBlank { "#FFD97757" }),
            backgroundColor = backgroundColor.normalizeArgbColor(default = if (isNightTheme) "#FF1C1917" else "#FFFAF9F6"),
            bottomBackground = cardColor
                .ifBlank { mutedColor }
                .normalizeArgbColor(default = if (isNightTheme) "#FF312E2B" else "#FFEEEBE3"),
            transparentNavBar = false,
            backgroundImgPath = backgroundPath,
            backgroundImgBlur = 0,
            bookInfoBackgroundImgPath = null,
            panelBackgroundImgPath = panelPath,
            panelBackgroundScaleType = ThemeConfig.PANEL_BG_CROP,
            panelBorderColor = borderColor.normalizeArgbColorOrNull(),
            panelBorderAlpha = if (cardBorder || switchBorder) 100 else 0,
            uiCornerScale = cardShadow?.let { (1f + it.coerceIn(0, 4) * 0.08f).coerceIn(0f, 3f) } ?: 1f,
            uiLayoutAlpha = cardColor.alphaPercentOrNull() ?: 100,
            dialogAlpha = 100,
            cardColor = cardColor.normalizeArgbColorOrNull(),
            mutedColor = mutedColor.normalizeArgbColorOrNull(),
            searchFieldBackgroundColor = searchFieldBackgroundColor.normalizeArgbColorOrNull(),
            tabBackgroundColor = tabBackgroundColor.normalizeArgbColorOrNull(),
            shelfColor = shelfColor.normalizeArgbColorOrNull(),
            cardShadow = cardShadow?.coerceIn(0, 24),
            cardBackgroundBlur = cardBackgroundBlur?.coerceIn(0f, 25f),
            uiCornerSearchFollow = true,
            uiCornerReplyFollow = true,
            fontScale = null,
            uiFontPath = null,
            titleFontPath = null
        )
    }

    private fun String.normalizeArgbColor(default: String): String {
        return normalizeArgbColorOrNull() ?: default
    }

    private fun String.normalizeArgbColorOrNull(): String? {
        val value = trim()
        if (!value.startsWith("#")) return null
        val hex = value.drop(1)
        return when (hex.length) {
            6 -> "#FF${hex.uppercase()}"
            8 -> "#${hex.uppercase()}"
            else -> null
        }
    }

    private fun importZipInternal(zipFile: File, remoteUpdatedAt: Long): Entry {
        val unzipDir = tempDir.getFile("import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        ZipUtils.unZipToPath(zipFile, unzipDir)
        val packageFile = unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }
            ?: throw IllegalArgumentException(appCtx.getString(R.string.theme_config_file_missing))
        val pkg = GSON.fromJsonObject<Package>(packageFile.readText()).getOrThrow()
        val dirName = pkg.dirName.ifBlank { pkg.name.normalizeFileName() }
        val targetDir = localDir(pkg.isNightTheme, dirName)
        if (targetDir.exists()) {
            FileUtils.delete(targetDir, deleteRootDir = true)
        }
        targetDir.mkdirs()
        packageFile.parentFile?.copyRecursively(targetDir, overwrite = true)
        val restoredPackage = readPackage(targetDir) ?: pkg
        val targetPackage = if (remoteUpdatedAt == 0L) {
            restoredPackage.copy(updatedAt = System.currentTimeMillis())
        } else {
            restoredPackage
        }
        File(targetDir, packageFileName).writeText(GSON.toJson(targetPackage))
        ThemeConfig.addConfig(resolveConfigPaths(targetPackage, targetDir))
        return Entry(targetPackage, Source.LOCAL, localDir = targetDir, remoteUpdatedAt = remoteUpdatedAt)
    }

    private fun copyAssetsIntoPackage(
        config: ThemeConfig.Config,
        dir: File,
        isNightTheme: Boolean
    ): ThemeConfig.Config {
        val background = copyAsset(config.backgroundImgPath, dir, mainBackgroundPrefix)
        val bookInfo = copyAsset(
            config.bookInfoBackgroundImgPath
                ?: appCtx.getPrefString(if (isNightTheme) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage),
            dir,
            bookInfoBackgroundPrefix
        )
        val panelBackground = copyAsset(
            config.panelBackgroundImgPath
                ?: appCtx.getPrefString(if (isNightTheme) PreferKey.panelBgImageN else PreferKey.panelBgImage),
            dir,
            panelBackgroundPrefix
        )
        val uiFont = copyAsset(config.uiFontPath, dir, uiFontPrefix, keepOriginalName = true)
        val titleFont = copyAsset(config.titleFontPath, dir, titleFontPrefix, keepOriginalName = true)
        return config.copy(
            backgroundImgPath = background,
            bookInfoBackgroundImgPath = bookInfo,
            panelBackgroundImgPath = panelBackground,
            uiFontPath = uiFont,
            titleFontPath = titleFont
        )
    }

    private fun copyAsset(
        path: String?,
        dir: File,
        prefix: String,
        keepOriginalName: Boolean = false
    ): String? {
        if (path.isNullOrBlank()) {
            deletePackagedAssets(dir, prefix)
            return path
        }
        if (path.startsWith("http", ignoreCase = true)) {
            deletePackagedAssets(dir, prefix)
            return path
        }
        if (path.startsWith("content://", ignoreCase = true)) {
            return runCatching {
                val uri = Uri.parse(path)
                val name = DocumentFile.fromSingleUri(appCtx, uri)?.name.orEmpty()
                val target = File(dir, packageAssetName(prefix, name, keepOriginalName))
                appCtx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: return path
                deletePackagedAssets(dir, prefix, target)
                target.name
            }.getOrDefault(path)
        }
        val source = File(path)
        if (!source.exists()) {
            return findPackagedAssetByPrefix(dir, prefix)?.name ?: path
        }
        if (source.parentFile?.canonicalFile == dir.canonicalFile && source.name.startsWith(prefix)) {
            deletePackagedAssets(dir, prefix, source)
            return source.name
        }
        val target = File(dir, packageAssetName(prefix, source.name, keepOriginalName))
        if (source.canonicalFile == target.canonicalFile) {
            deletePackagedAssets(dir, prefix, target)
            return target.name
        }
        source.copyTo(target, overwrite = true)
        deletePackagedAssets(dir, prefix, target)
        return target.name
    }

    private fun deletePackagedAssets(dir: File, prefix: String, keepFile: File? = null) {
        val keepCanonical = keepFile?.takeIf { it.exists() }?.canonicalFile
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) }
            ?.filter { keepCanonical == null || it.canonicalFile != keepCanonical }
            ?.forEach { it.delete() }
    }

    private fun findPackagedAssetByPrefix(dir: File, prefix: String): File? {
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith(prefix) }
    }

    private fun packageAssetName(prefix: String, sourceName: String, keepOriginalName: Boolean): String {
        val sourceFile = File(sourceName)
        val suffix = ImageTypeUtils.preferredRasterExtension(sourceFile.takeIf { it.isFile }, "")
            .ifBlank { sourceName.substringAfterLast('.', "") }
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            .orEmpty()
        if (!keepOriginalName) {
            return "$prefix$suffix"
        }
        val normalizedName = sourceName.normalizeFileName()
        if (normalizedName.startsWith("$prefix.")) {
            return "$prefix$suffix"
        }
        val cleanName = normalizedName.removePrefix("${prefix}_")
        return if (cleanName.isBlank()) {
            "$prefix$suffix"
        } else {
            "${prefix}_${cleanName}"
        }
    }

    private suspend fun validatedConfig(entry: Entry): ThemeConfig.Config {
        val dir = entry.localDir ?: localDir(entry.packageInfo.isNightTheme, entry.dirName)
        if (entry.source != Source.BUILTIN && !File(dir, packageFileName).isFile) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_config_file_missing))
        }
        val config = resolveConfigPaths(entry.packageInfo, dir)
        return config.copy(
            backgroundImgPath = localResourcePath(config.backgroundImgPath, backgroundDir(config.isNightTheme)),
            bookInfoBackgroundImgPath = localResourcePath(
                config.bookInfoBackgroundImgPath,
                bookInfoBackgroundDir(config.isNightTheme)
            ),
            panelBackgroundImgPath = localResourcePath(config.panelBackgroundImgPath, panelBackgroundDir(config.isNightTheme)),
            uiFontPath = localResourcePath(config.uiFontPath),
            titleFontPath = localResourcePath(config.titleFontPath)
        )
    }

    private suspend fun localResourcePath(path: String?, remoteCacheDir: File? = null): String? {
        if (path.isNullOrBlank()) return path
        if (path.startsWith("http", ignoreCase = true)) {
            val cacheDir = remoteCacheDir ?: throw IllegalArgumentException(appCtx.getString(R.string.theme_resource_not_local))
            return downloadRemoteResource(path, cacheDir).absolutePath
        }
        val file = File(path)
        if (!isReadableOwnFile(file)) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_resource_not_readable, file.name.ifBlank { path }))
        }
        return path
    }

    private suspend fun downloadRemoteResource(path: String, dir: File): File {
        dir.mkdirs()
        val target = File(dir, remoteResourceName(path))
        if (isReadableOwnFile(target)) return target
        withTimeout(60_000L) {
            okHttpClient.newCallResponse(0) {
                url(path)
            }.use { response ->
                if (!response.isSuccessful) {
                    throw IllegalArgumentException(appCtx.getString(R.string.theme_resource_download_failed, response.code))
                }
                response.body.byteStream().use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
        return target
    }

    private fun remoteResourceName(path: String): String {
        val cleanPath = path.substringBefore('?').substringBefore('#')
        val suffix = cleanPath.substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it.length <= 8 }
            ?.let { ".$it" }
            .orEmpty()
        return Integer.toHexString(path.hashCode()) + suffix
    }

    private fun resolveConfigPaths(pkg: Package, dir: File): ThemeConfig.Config {
        val config = pkg.config ?: ThemeConfig.Config(
            themeName = pkg.name,
            isNightTheme = pkg.isNightTheme,
            primaryColor = "#795548",
            accentColor = "#E53935",
            backgroundColor = if (pkg.isNightTheme) "#212121" else "#F5F5F5",
            bottomBackground = if (pkg.isNightTheme) "#303030" else "#EEEEEE",
            transparentNavBar = true,
            backgroundImgPath = null,
            backgroundImgBlur = 0
        )
        return config.copy(
            themeName = pkg.name,
            isNightTheme = pkg.isNightTheme,
            backgroundImgPath = resolvePath(config.backgroundImgPath, dir),
            bookInfoBackgroundImgPath = resolvePath(config.bookInfoBackgroundImgPath, dir),
            panelBackgroundImgPath = resolvePath(config.panelBackgroundImgPath, dir),
            uiFontPath = resolvePath(config.uiFontPath, dir),
            titleFontPath = resolvePath(config.titleFontPath, dir)
        )
    }

    private fun resolvePath(path: String?, dir: File): String? {
        if (path.isNullOrBlank() || path.startsWith("http", ignoreCase = true)) return path
        val file = File(path)
        if (file.isAbsolute) {
            if (isReadableOwnFile(file)) return path
            findPackagedAsset(dir, file.name)?.let { return it.absolutePath }
            findPackagedAssetByPrefix(dir, file.name.substringBeforeLast('.', file.name))?.let {
                return it.absolutePath
            }
            return null
        }
        val packagedFile = File(dir, path)
        if (isReadableOwnFile(packagedFile)) return packagedFile.absolutePath
        findPackagedAsset(dir, file.name)?.let { return it.absolutePath }
        return packagedFile.absolutePath
    }

    private fun isReadableOwnFile(file: File): Boolean {
        if (!file.isFile) return false
        if (isOtherAppExternalDataPath(file.absolutePath)) return false
        return runCatching {
            FileInputStream(file).use { true }
        }.getOrDefault(false)
    }

    private fun isOtherAppExternalDataPath(path: String): Boolean {
        val marker = "/Android/data/"
        val normalized = path.replace('\\', '/')
        val start = normalized.indexOf(marker, ignoreCase = true)
        if (start < 0) return false
        val packageStart = start + marker.length
        val packageEnd = normalized.indexOf('/', packageStart).takeIf { it >= 0 } ?: normalized.length
        val ownerPackage = normalized.substring(packageStart, packageEnd)
        return ownerPackage.isNotBlank() && ownerPackage != appCtx.packageName
    }
    private fun backgroundDir(isNightTheme: Boolean): File {
        return appCtx.externalFiles.getFile(if (isNightTheme) PreferKey.bgImageN else PreferKey.bgImage)
    }

    private fun bookInfoBackgroundDir(isNightTheme: Boolean): File {
        return appCtx.externalFiles.getFile(if (isNightTheme) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage)
    }

    private fun panelBackgroundDir(isNightTheme: Boolean): File {
        return appCtx.externalFiles.getFile(if (isNightTheme) PreferKey.panelBgImageN else PreferKey.panelBgImage)
    }

    private fun findPackagedAsset(dir: File, fileName: String): File? {
        if (fileName.isBlank()) return null
        val lowerName = fileName.lowercase()
        return dir.walkTopDown().firstOrNull { file ->
            file.isFile && file.name.lowercase() == lowerName
        }
    }

    fun localDir(isNightTheme: Boolean, dirName: String): File {
        return typeDir(isNightTheme).getFile(dirName)
    }

    private val tempDir: File
        get() = rootDir.getFile("temp").apply {
            if (!exists()) mkdirs()
        }

    private val remoteCacheDir: File
        get() = rootDir.getFile("remote_cache").apply {
            if (!exists()) mkdirs()
        }

    private fun typeDir(isNightTheme: Boolean): File {
        return rootDir.getFile(if (isNightTheme) "night" else "day").apply {
            if (!exists()) mkdirs()
        }
    }

    data class Entry(
        val packageInfo: Package,
        val source: Source,
        val localDir: File? = null,
        val remoteUpdatedAt: Long = 0L
    ) {
        val dirName: String get() = packageInfo.dirName
    }

    data class ThemeImportResult(
        val sourceName: String = "",
        val themes: List<Entry> = emptyList(),
        val navigationBars: List<NavigationBarIconConfig.Entry> = emptyList(),
        val coverCollections: List<CoverCollectionManager.Collection> = emptyList()
    )

    @Keep
    data class Package(
        val name: String,
        val dirName: String,
        val isNightTheme: Boolean,
        val updatedAt: Long,
        val config: ThemeConfig.Config?
    )

    @Keep
    private data class RedThemeV4(
        val name: String = "",
        val light: RedThemeColors? = null,
        val dark: RedThemeColors? = null
    )

    @Keep
    private data class RedNameMeta(
        val name: String = ""
    )

    @Keep
    private data class RedThemePackage(
        val version: Int = 1,
        val type: String = "",
        val data: List<RedThemeItem> = emptyList()
    )

    @Keep
    private data class RedThemeItem(
        val name: String = "",
        val light: RedThemeColors? = null,
        val dark: RedThemeColors? = null,
        val lightBackgroundImage: String = "",
        val darkBackgroundImage: String = ""
    )

    @Keep
    private data class RedThemeColors(
        val primaryColor: String = "",
        val backgroundColor: String = "",
        val foregroundColor: String = "",
        val mutedForegroundColor: String = "",
        val cardColor: String = "",
        val cardForegroundColor: String = "",
        val popoverColor: String = "",
        val dialogBackgroundColor: String = "",
        val cardBackgroundImage: String = "",
        val mutedColor: String = "",
        val borderColor: String = "",
        val dividerColor: String = "",
        val accentColor: String = "",
        val backgroundImage: String = "",
        val backgroundImageFit: String = "",
        val backgroundImageOpacity: Float? = null,
        val coverGalleryId: String = "",
        val navbarPackId: String = "",
        val readerColorSchemaId: String = "",
        val cardShadow: Int? = null,
        val cardBackgroundBlur: Float? = null,
        val cardBorder: Boolean = false,
        val searchFieldBorder: Boolean = false,
        val searchFieldBackgroundColor: String = "",
        val switchBorder: Boolean = false,
        val tabBorder: Boolean = false,
        val tabBackgroundColor: String = "",
        val shelfColor: String = ""
    )

    private data class PlainImageSegment(
        val start: Int,
        val end: Int,
        val suffix: String,
        val width: Int = 0,
        val height: Int = 0
    ) {
        val aspectRatio: Float
            get() = if (height > 0) width.toFloat() / height.toFloat() else 0f
    }

    private data class Red10ArcResources(
        val theme: RedThemeV4,
        val lightSchema: Red10ColorSchema?,
        val darkSchema: Red10ColorSchema?
    )

    @Keep
    private data class Red10ColorSchema(
        val name: String = "",
        val backgroundImageUrl: String = "",
        val themeMode: String? = null
    ) {
        fun looksNightMode(): Boolean {
            val mode = themeMode.orEmpty()
            return mode.equals("dark", ignoreCase = true) ||
                mode.equals("night", ignoreCase = true) ||
                name.contains("夜") ||
                name.contains("dark", ignoreCase = true) ||
                name.contains("night", ignoreCase = true)
        }
    }

    private enum class RedPackageFormat {
        RED04_ZIP,
        RED10_PRIVATE,
        RED_GZIP_JSON,
        RAW_GZIP_JSON
    }

    enum class Source {
        BUILTIN,
        LOCAL,
        REMOTE,
        BOTH
    }
}
