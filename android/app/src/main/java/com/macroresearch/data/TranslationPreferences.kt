package com.macroresearch.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

data class TranslationSettings(
    val configured: Boolean,
    val baseUrl: String,
    val model: String,
)

/** Stores the user-supplied translation credential encrypted by Android Keystore. */
class TranslationPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secrets = SecureStore(context, PREFERENCES_NAME, KEY_ALIAS)
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<TranslationSettings> = _settings.asStateFlow()

    fun apiKey(): String? = secrets.get(KEY_API_KEY)

    fun save(apiKey: String, baseUrl: String, model: String) {
        val cleanKey = apiKey.trim()
        require(cleanKey.isNotEmpty()) { "API key is required" }
        val cleanUrl = normalizeBaseUrl(baseUrl)
        val cleanModel = model.trim()
        require(cleanModel.isNotEmpty()) { "Model is required" }

        secrets.put(KEY_API_KEY, cleanKey)
        preferences.edit()
            .putString(KEY_BASE_URL, cleanUrl)
            .putString(KEY_MODEL, cleanModel)
            .apply()
        _settings.value = TranslationSettings(true, cleanUrl, cleanModel)
    }

    fun updateProvider(baseUrl: String, model: String) {
        check(apiKey() != null) { "Save an API key first" }
        val cleanUrl = normalizeBaseUrl(baseUrl)
        val cleanModel = model.trim()
        require(cleanModel.isNotEmpty()) { "Model is required" }
        preferences.edit()
            .putString(KEY_BASE_URL, cleanUrl)
            .putString(KEY_MODEL, cleanModel)
            .apply()
        _settings.value = TranslationSettings(true, cleanUrl, cleanModel)
    }

    fun clear() {
        secrets.remove(KEY_API_KEY)
        preferences.edit().clear().apply()
        _settings.value = TranslationSettings(false, DEFAULT_BASE_URL, DEFAULT_MODEL)
    }

    private fun loadSettings(): TranslationSettings {
        val baseUrl = preferences.getString(KEY_BASE_URL, DEFAULT_BASE_URL).orEmpty()
            .ifBlank { DEFAULT_BASE_URL }
        val model = preferences.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty()
            .ifBlank { DEFAULT_MODEL }
        return TranslationSettings(apiKey() != null, baseUrl, model)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "gpt-4o-mini"

        private const val PREFERENCES_NAME = "translation_credentials"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_MODEL = "model"
        private const val KEY_ALIAS = "macro_translation_api_key_v1"

        internal fun normalizeBaseUrl(value: String): String {
            val normalized = value.trim().trimEnd('/')
            val uri = runCatching { URI(normalized) }
                .getOrElse { throw IllegalArgumentException("Invalid API endpoint") }
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                "API endpoint must use HTTPS"
            }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "API endpoint must not contain credentials, query, or fragment"
            }
            return normalized
        }
    }
}
