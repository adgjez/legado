package io.legado.app.utils

import android.net.Uri
import android.util.Base64

fun String.decodeBase64DataUrlBytes(): ByteArray? {
    val clean = trim()
        .trimMatchingDataUrlWrapper()
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
    if (!clean.startsWith("data:", ignoreCase = true)) return null
    val commaIndex = clean.indexOf(',')
    if (commaIndex < 0) return null
    val meta = clean.substring(0, commaIndex).lowercase()
    if (!meta.contains(";base64")) return null
    val rawPayload = clean.substring(commaIndex + 1)
        .trimMatchingDataUrlWrapper()
        .let { Uri.decode(it) }
        .filterNot { it.isWhitespace() }
    if (rawPayload.isBlank()) return null
    val paddedPayload = rawPayload.padBase64()
    return runCatching {
        Base64.decode(paddedPayload, Base64.DEFAULT)
    }.getOrElse {
        runCatching {
            Base64.decode(paddedPayload, Base64.URL_SAFE)
        }.getOrNull()
    }
}

private fun String.trimMatchingDataUrlWrapper(): String {
    var value = trim()
    while (value.isNotEmpty() && value.first() in charArrayOf('\'', '"')) {
        value = value.drop(1).trimStart()
    }
    while (value.isNotEmpty() && value.last() in charArrayOf('\'', '"', ')', ';')) {
        value = value.dropLast(1).trimEnd()
    }
    return value
}

private fun String.padBase64(): String {
    val remainder = length % 4
    return if (remainder == 0) this else this + "=".repeat(4 - remainder)
}
