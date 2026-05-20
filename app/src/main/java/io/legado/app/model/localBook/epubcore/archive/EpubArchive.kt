package io.legado.app.model.localBook.epubcore.archive

import java.io.Closeable

interface EpubArchive : Closeable {
    fun exists(path: String): Boolean
    fun list(): List<String>
    fun readBytes(path: String): ByteArray

    fun readText(path: String): String = readBytes(path).toString(Charsets.UTF_8)
}
