package io.legado.app.help.config

import androidx.annotation.Keep
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppCloudStorage
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File

object BubblePackageManager {

    const val BUILTIN_DIR_NAME = "builtin_default"
    const val DEFAULT_EMPHASIS_COLOR = "#FF0000"
    const val DEFAULT_NORMAL_COLOR = "#808080"
    const val DEFAULT_COLOR = DEFAULT_EMPHASIS_COLOR
    const val MIN_SIZE_SCALE = 0.5f
    const val MAX_SIZE_SCALE = 1.5f
    private const val packageFileName = "bubble.json"
    private const val defaultBubblePath =
        "M44 48 Q48 48 48 44 L48 20 Q48 16 44 16 L20 16 Q16 16 16 20 L16 24 S16 28 10 30 Q6 32 10 34 Q16 36 16 38 L16 44 Q16 48 20 48 Z"
    @Volatile
    private var cachedEntry: Entry? = null
    @Volatile
    private var cachedDirName: String? = null

    val rootDir: File
        get() = appCtx.externalFiles.getFile("bubblePackages")

    private val tempDir: File
        get() = rootDir.getFile("temp").apply { mkdirs() }

    private val remoteCacheDir: File
        get() = rootDir.getFile("remote_cache").apply { mkdirs() }

    @Keep
    private data class RemoteCache(
        val name: String,
        val dirName: String,
        val updatedAt: Long
    )

    @Keep
    data class Config(
        var name: String,
        var dirName: String = "",
        var svgTemplate: String = "",
        var sizeScale: Float = 1f,
        var dayNormalColor: String? = null,
        var dayEmphasisColor: String? = null,
        var nightNormalColor: String? = null,
        var nightEmphasisColor: String? = null,
        var updatedAt: Long = System.currentTimeMillis()
    )

    data class Entry(
        val config: Config,
        val source: Source,
        val dirName: String,
        val localDir: File? = null,
        val remoteUpdatedAt: Long = 0L
    )

    enum class Source { BUILTIN, LOCAL, REMOTE, BOTH }

    fun builtinConfig(): Config {
        return Config(
            name = "内置段评气泡",
            dirName = BUILTIN_DIR_NAME,
            svgTemplate = defaultSvgTemplate(),
            sizeScale = 1f,
            dayNormalColor = DEFAULT_NORMAL_COLOR,
            dayEmphasisColor = DEFAULT_EMPHASIS_COLOR,
            nightNormalColor = DEFAULT_NORMAL_COLOR,
            nightEmphasisColor = DEFAULT_EMPHASIS_COLOR,
            updatedAt = 0L
        )
    }

    fun builtinEntry(): Entry {
        return Entry(
            config = builtinConfig(),
            source = Source.BUILTIN,
            dirName = BUILTIN_DIR_NAME
        )
    }

    fun activeDirName(): String {
        return appCtx.getPrefString(PreferKey.paragraphBubblePackage, BUILTIN_DIR_NAME)
            ?.ifBlank { BUILTIN_DIR_NAME }
            ?: BUILTIN_DIR_NAME
    }

    fun apply(entry: Entry) {
        appCtx.putPrefString(PreferKey.paragraphBubblePackage, entry.dirName)
        invalidateCurrentEntry()
    }

    fun currentEntry(): Entry {
        val dirName = activeDirName()
        cachedEntry?.takeIf { cachedDirName == dirName }?.let { return it }
        val entry = if (dirName == BUILTIN_DIR_NAME) {
            builtinEntry()
        } else {
            readEntry(localDir(dirName)) ?: builtinEntry()
        }
        cachedDirName = dirName
        cachedEntry = entry
        return entry
    }

    fun invalidateCurrentEntry() {
        cachedEntry = null
        cachedDirName = null
    }

    suspend fun loadEntries(containerId: String? = null, scope: String? = null): List<Entry> = withContext(IO) {
        val local = loadLocal().associateBy { it.dirName }
        val remote = loadRemoteOrCache(containerId, scope).associateBy { it.dirName }
        val merged = (local.keys + remote.keys).mapNotNull { key ->
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
        listOf(builtinEntry()) + merged.sortedWith(
            compareByDescending<Entry> { it.config.updatedAt }
                .thenByDescending { it.remoteUpdatedAt }
                .thenBy { it.config.name }
                .thenBy { it.dirName }
        )
    }

    fun addOrUpdate(config: Config, oldEntry: Entry? = null): Entry {
        val normalized = normalizeConfig(config)
        val name = normalized.name.trim().ifBlank { "段评气泡" }
        val editableOldEntry = oldEntry?.takeIf {
            it.dirName.isNotBlank() &&
                it.dirName != BUILTIN_DIR_NAME &&
                it.source != Source.REMOTE
        }
        val dirName = if (editableOldEntry != null) {
            editableOldEntry.dirName
        } else {
            val baseDirName = normalized.dirName.ifBlank { name.normalizeFileName() }
                .ifBlank { "bubble_${System.currentTimeMillis()}" }
            uniqueDirName(baseDirName)
        }
        val dir = localDir(dirName).apply { mkdirs() }
        val next = normalized.copy(
            name = name,
            dirName = dirName,
            updatedAt = System.currentTimeMillis()
        )
        File(dir, packageFileName).writeText(GSON.toJson(next))
        invalidateCurrentEntry()
        return Entry(next, Source.LOCAL, dirName, localDir = dir)
    }

    fun deleteLocal(entry: Entry) {
        if (entry.source == Source.BUILTIN || entry.dirName == BUILTIN_DIR_NAME) return
        FileUtils.delete(entry.localDir ?: localDir(entry.dirName), deleteRootDir = true)
        if (activeDirName() == entry.dirName) {
            appCtx.putPrefString(PreferKey.paragraphBubblePackage, BUILTIN_DIR_NAME)
        }
        invalidateCurrentEntry()
    }

    suspend fun exportZip(entry: Entry): File = withContext(IO) {
        if (entry.source == Source.BUILTIN) {
            throw IllegalArgumentException("内置气泡不可导出")
        }
        val dir = entry.localDir ?: localDir(entry.dirName)
        val zipFile = tempDir.getFile("${entry.dirName}.zip")
        if (zipFile.exists()) zipFile.delete()
        if (!ZipUtils.zipFile(dir, zipFile) || !zipFile.exists() || zipFile.length() <= 0L) {
            throw IllegalStateException("气泡导出失败")
        }
        zipFile
    }

    suspend fun upload(entry: Entry, containerId: String? = null, scope: String? = null) = withContext(IO) {
        if (entry.source == Source.BUILTIN) return@withContext
        AppCloudStorage.uploadBubblePackage(entry.dirName, exportZip(entry), containerId, scope)
    }

    suspend fun download(entry: Entry, containerId: String? = null, scope: String? = null): Entry = withContext(IO) {
        val zipFile = tempDir.getFile("${entry.dirName}.zip")
        AppCloudStorage.downloadBubblePackage(entry.dirName, zipFile, containerId, scope)
        importZipInternal(zipFile, overwrite = true, remoteUpdatedAt = entry.remoteUpdatedAt)
            .copy(source = Source.BOTH, remoteUpdatedAt = entry.remoteUpdatedAt)
    }

    suspend fun deleteRemote(entry: Entry, containerId: String? = null, scope: String? = null) = withContext(IO) {
        if (entry.source == Source.BUILTIN) return@withContext
        AppCloudStorage.deleteBubblePackage(entry.dirName, containerId, scope)
    }

    suspend fun importZip(zipFile: File): Entry = withContext(IO) {
        importZipInternal(zipFile, overwrite = false, remoteUpdatedAt = 0L)
    }

    private fun importZipInternal(zipFile: File, overwrite: Boolean, remoteUpdatedAt: Long): Entry {
        val unzipDir = tempDir.getFile("import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        return try {
            ZipUtils.unZipToPath(zipFile, unzipDir)
            val packageFile = unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }
                ?: throw IllegalArgumentException("气泡配置不存在")
            val raw = GSON.fromJsonObject<Config>(packageFile.readText()).getOrThrow()
            val config = normalizeConfig(raw)
            val baseDirName = config.dirName.ifBlank { config.name.normalizeFileName() }
                .ifBlank { "bubble_${System.currentTimeMillis()}" }
            val dirName = if (overwrite) baseDirName else uniqueDirName(baseDirName)
            val targetDir = localDir(dirName)
            if (targetDir.exists()) FileUtils.delete(targetDir, deleteRootDir = true)
            targetDir.mkdirs()
            packageFile.parentFile?.copyRecursively(targetDir, overwrite = true)
            val next = config.copy(
                dirName = dirName,
                updatedAt = if (remoteUpdatedAt > 0L) config.updatedAt else System.currentTimeMillis()
            )
            File(targetDir, packageFileName).writeText(GSON.toJson(next))
            invalidateCurrentEntry()
            Entry(next, Source.LOCAL, dirName, localDir = targetDir, remoteUpdatedAt = remoteUpdatedAt)
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun loadLocal(): List<Entry> {
        if (!rootDir.exists()) return emptyList()
        return rootDir.listFiles()
            ?.filter { it.isDirectory && it.name !in setOf("temp", "remote_cache", BUILTIN_DIR_NAME) }
            ?.mapNotNull(::readEntry)
            .orEmpty()
    }

    private suspend fun loadRemoteOrCache(containerId: String? = null, scope: String? = null): List<Entry> {
        val cached = readRemoteCache(containerId)
        return runCatching {
            AppCloudStorage.listBubblePackages(containerId, scope).map { remoteFile ->
                val dirName = remoteFile.displayName.trimEnd('/').removeSuffix(".zip")
                Entry(
                    config = Config(
                        name = dirName,
                        dirName = dirName,
                        updatedAt = remoteFile.lastModify,
                        svgTemplate = defaultSvgTemplate()
                    ),
                    source = Source.REMOTE,
                    dirName = dirName,
                    remoteUpdatedAt = remoteFile.lastModify
                )
            }
        }.onSuccess { remote ->
            if (remote.isNotEmpty() || cached.isEmpty()) {
                writeRemoteCache(remote, containerId)
            }
        }.getOrElse {
            cached
        }
    }

    private fun readRemoteCache(containerId: String? = null): List<Entry> {
        val file = remoteCacheFile(containerId)
        if (!file.exists()) return emptyList()
        return GSON.fromJsonArray<RemoteCache>(file.readText()).getOrDefault(emptyList()).map {
            Entry(
                config = Config(
                    name = it.name,
                    dirName = it.dirName,
                    updatedAt = it.updatedAt,
                    svgTemplate = defaultSvgTemplate()
                ),
                source = Source.REMOTE,
                dirName = it.dirName,
                remoteUpdatedAt = it.updatedAt
            )
        }
    }

    private fun writeRemoteCache(entries: List<Entry>, containerId: String? = null) {
        val cache = entries.map {
            RemoteCache(
                name = it.config.name,
                dirName = it.dirName,
                updatedAt = it.remoteUpdatedAt.takeIf { time -> time > 0L } ?: it.config.updatedAt
            )
        }
        remoteCacheFile(containerId).writeText(GSON.toJson(cache))
    }

    private fun remoteCacheFile(containerId: String? = null): File {
        val suffix = containerId?.takeIf { it.isNotBlank() }?.normalizeFileName()?.let { "_$it" }.orEmpty()
        return remoteCacheDir.getFile("bubble$suffix.json")
    }

    private fun readEntry(dir: File): Entry? {
        val file = File(dir, packageFileName)
        if (!file.exists()) return null
        val config = GSON.fromJsonObject<Config>(file.readText()).getOrNull()
            ?.let(::normalizeConfig)
            ?: return null
        val dirName = config.dirName.ifBlank { dir.name }
        return Entry(config.copy(dirName = dirName), Source.LOCAL, dirName, localDir = dir)
    }

    private fun normalizeConfig(config: Config): Config {
        val size = config.sizeScale.takeIf { it.isFinite() } ?: 1f
        return config.copy(
            name = config.name.trim().ifBlank { "段评气泡" },
            svgTemplate = config.svgTemplate.ifBlank { defaultSvgTemplate() },
            sizeScale = size.coerceIn(MIN_SIZE_SCALE, MAX_SIZE_SCALE),
            dayNormalColor = normalizeColorOrBlank(config.dayNormalColor),
            dayEmphasisColor = normalizeColorOrBlank(config.dayEmphasisColor),
            nightNormalColor = normalizeColorOrBlank(config.nightNormalColor),
            nightEmphasisColor = normalizeColorOrBlank(config.nightEmphasisColor)
        )
    }

    private fun normalizeColorOrBlank(value: String?): String? {
        val color = value?.trim().orEmpty()
        if (color.isBlank()) return null
        return if (color.startsWith("#")) color else "#$color"
    }

    private fun uniqueDirName(preferred: String): String {
        val clean = preferred.normalizeFileName().ifBlank { "bubble_${System.currentTimeMillis()}" }
        if (!localDir(clean).exists()) return clean
        for (index in 2..999) {
            val candidate = "${clean}_$index"
            if (!localDir(candidate).exists()) return candidate
        }
        return "${clean}_${System.currentTimeMillis()}"
    }

    private fun localDir(dirName: String): File = rootDir.getFile(dirName)

    private fun defaultSvgTemplate(): String {
        return """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
              <path d="$defaultBubblePath" fill="none" stroke="${'$'}{color}" stroke-width="3.2" stroke-linejoin="round" stroke-linecap="round"/>
              <text x="32" y="32" dy=".35em" text-anchor="middle" font-family="sans-serif" font-size="15" font-weight="600" fill="${'$'}{color}">${'$'}{num}</text>
            </svg>
        """.trimIndent()
    }
}
