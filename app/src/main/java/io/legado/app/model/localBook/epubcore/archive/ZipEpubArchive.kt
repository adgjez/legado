package io.legado.app.model.localBook.epubcore.archive

import java.io.File
import java.util.zip.ZipFile

class ZipEpubArchive(file: File) : EpubArchive {

    private val zipFile = ZipFile(file)
    private val entries: Map<String, String> = zipFile.entries().asSequence()
        .filterNot { it.isDirectory }
        .associate { EpubPath.normalize(it.name) to it.name }

    override fun exists(path: String): Boolean = entries.containsKey(EpubPath.normalize(path))

    override fun list(): List<String> = entries.keys.toList()

    override fun readBytes(path: String): ByteArray {
        val normalized = EpubPath.normalize(path)
        val entryName = entries[normalized] ?: error("EPUB entry not found: $path")
        val entry = zipFile.getEntry(entryName) ?: error("EPUB entry not found: $path")
        return zipFile.getInputStream(entry).use { it.readBytes() }
    }

    override fun close() {
        zipFile.close()
    }
}
