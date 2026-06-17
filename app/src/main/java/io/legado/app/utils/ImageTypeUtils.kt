package io.legado.app.utils

import java.io.File

object ImageTypeUtils {

    fun isGif(file: File?): Boolean {
        if (file == null || !file.isFile) return false
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(6)
                if (input.read(header) != header.size) return@use false
                header.contentEquals(gif87Header) || header.contentEquals(gif89Header)
            }
        }.getOrDefault(false)
    }

    private val gif87Header = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61)
    private val gif89Header = byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)
}
