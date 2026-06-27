package io.legado.app.ui.main.ai

import androidx.annotation.Keep
import java.util.UUID

@Keep
data class AiAudioProviderConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: String = TYPE_OPENAI,
    val baseUrl: String = "",
    val apiKey: String = "",
    val headers: String = "",
    val model: String = "",
    val defaultParamsJson: String = "",
    val script: String = "",
    val submitEndpoint: String = "/audio/generations",
    val statusEndpoint: String = "/audio/generations/{id}",
    val pollIntervalMillisecond: Long = 5_000L,
    val timeoutMillisecond: Long = 300_000L,
    val costExpression: String = "",
    val jsLib: String = "",
    val loginUrl: String = "",
    val loginUi: String = "",
    val enabledCookieJar: Boolean = false,
    val order: Int = 0,
    val enabled: Boolean = true
) {
    fun displayName(): String = name.ifBlank { type }
    fun validTimeout(): Long = timeoutMillisecond.takeIf { it > 0L }?.coerceIn(60_000L, 600_000L) ?: 300_000L
    companion object {
        const val TYPE_OPENAI = "openai"
        const val TYPE_JS = "js"
    }
}
