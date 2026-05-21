package io.legado.app.help.config

import androidx.annotation.Keep
import io.legado.app.constant.PreferKey
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
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
    const val DEFAULT_COLOR = "#FF0000"
    const val MIN_SIZE_SCALE = 0.5f
    const val MAX_SIZE_SCALE = 1.5f
    private const val packageFileName = "bubble.json"
    private const val defaultBubblePath =
        "M44 48 Q48 48 48 44 L48 20 Q48 16 44 16 L20 16 Q16 16 16 20 L16 24 S16 28 10 30 Q6 32 10 34 Q16 36 16 38 L16 44 Q16 48 20 48 Z"

    val rootDir: File
        get() = appCtx.externalFiles.getFile("bubblePackages")

    private val tempDir: File
        get() = rootDir.getFile("temp").apply { mkdirs() }

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
    }

    fun currentEntry(): Entry {
        val dirName = activeDirName()
        if (dirName == BUILTIN_DIR_NAME) return builtinEntry()
        return readEntry(localDir(dirName)) ?: builtinEntry()
    }

    suspend fun loadEntries(): List<Entry> = withContext(IO) {
        listOf(builtinEntry()) + loadLocal().sortedWith(
            compareByDescending<Entry> { it.config.updatedAt }
                .thenBy { it.config.name }
                .thenBy { it.dirName }
        )
    }

    fun addOrUpdate(config: Config, oldEntry: Entry? = null): Entry {
        val normalized = normalizeConfig(config)
        val name = normalized.name.trim().ifBlank { "段评气泡" }
        val keepOldDir = oldEntry != null &&
            oldEntry.dirName.isNotBlank() &&
            oldEntry.dirName != BUILTIN_DIR_NAME &&
            oldEntry.source != Source.REMOTE
        val dirName = if (keepOldDir) {
            oldEntry!!.dirName
        } else {
            normalized.dirName.ifBlank { name.normalizeFileName() }
                .ifBlank { "bubble_${System.currentTimeMillis()}" }
        }
        if (!keepOldDir && readEntry(localDir(dirName)) != null) {
            throw IllegalArgumentException("已存在同名气泡")
        }
        val dir = localDir(dirName).apply { mkdirs() }
        val next = normalized.copy(
            name = name,
            dirName = dirName,
            updatedAt = System.currentTimeMillis()
        )
        File(dir, packageFileName).writeText(GSON.toJson(next))
        return Entry(next, Source.LOCAL, dirName, localDir = dir)
    }

    fun copyBuiltin(): Entry {
        val base = builtinConfig().copy(name = "内置段评气泡 副本")
        return addOrUpdate(base.copy(dirName = "builtin_default_copy"))
    }

    fun deleteLocal(entry: Entry) {
        if (entry.source == Source.BUILTIN || entry.dirName == BUILTIN_DIR_NAME) return
        FileUtils.delete(entry.localDir ?: localDir(entry.dirName), deleteRootDir = true)
        if (activeDirName() == entry.dirName) {
            appCtx.putPrefString(PreferKey.paragraphBubblePackage, BUILTIN_DIR_NAME)
        }
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
              <path d="$defaultBubblePath" fill="${'$'}{color}"/>
              <text x="32" y="39" text-anchor="middle" font-size="14" font-weight="600" fill="#FFFFFF">${'$'}{num}</text>
            </svg>
        """.trimIndent()
    }
}
