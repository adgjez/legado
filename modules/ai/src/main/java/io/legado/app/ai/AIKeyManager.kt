package io.legado.app.ai

import android.content.Context
import android.content.SharedPreferences

/**
 * API Key 管理器，本地存储
 */
object AIKeyManager {
    private const val PREFS_NAME = "legado_ai_prefs"
    private const val KEY_PROVIDER = "ai_provider"
    private const val KEY_API_KEY = "ai_api_key"
    private const val KEY_BASE_URL = "ai_base_url"
    private const val KEY_MODEL = "ai_model"
    private const val KEY_STYLE = "ai_style"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadConfig(context: Context): AIConfig {
        val p = prefs(context)
        return AIConfig(
            provider = try {
                AIProvider.valueOf(p.getString(KEY_PROVIDER, AIProvider.OPENAI.name)!!)
            } catch (_: Exception) {
                AIProvider.OPENAI
            },
            apiKey = p.getString(KEY_API_KEY, "") ?: "",
            baseUrl = p.getString(KEY_BASE_URL, "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
            model = p.getString(KEY_MODEL, "dall-e-3") ?: "dall-e-3",
            style = p.getString(KEY_STYLE, "vivid") ?: "vivid"
        )
    }

    fun saveConfig(context: Context, config: AIConfig) {
        prefs(context).edit().apply {
            putString(KEY_PROVIDER, config.provider.name)
            putString(KEY_API_KEY, config.apiKey)
            putString(KEY_BASE_URL, config.baseUrl)
            putString(KEY_MODEL, config.model)
            putString(KEY_STYLE, config.style)
            apply()
        }
    }

    fun hasApiKey(context: Context): Boolean =
        prefs(context).getString(KEY_API_KEY, "").orEmpty().isNotBlank()
}