package com.macroresearch.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/** Where the app reads macro data from. */
enum class DataSourceMode {
    /** Direct providers, with the user's own AI key and optional HTTP proxy. */
    DIRECT,

    /** One self-hosted backend that owns the upstream feeds and the model key. */
    BACKEND,
    ;

    companion object {
        fun fromName(value: String?): DataSourceMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: DIRECT
    }
}

data class BackendSettings(
    val mode: DataSourceMode = DataSourceMode.DIRECT,
    val baseUrl: String = "",
    val tokenConfigured: Boolean = false,
    val verifiedVersion: String? = null,
    val lastVerifiedAt: Long = 0L,
    /** Explicit opt-in for a plain-HTTP endpoint on any address. */
    val allowCleartext: Boolean = false,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && tokenConfigured
}

/**
 * Data-source selection plus the backend endpoint and credential.
 *
 * The token is sealed with the Keystore, so a re-installed app on another device cannot read it,
 * and the address is a plain preference because the user can change it at any time.
 */
class BackendPreferences(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val secrets = SecureStore(context, PREFERENCES_NAME, KEY_ALIAS)
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<BackendSettings> = _settings.asStateFlow()

    fun token(): String? = secrets.get(KEY_TOKEN)

    fun setMode(mode: DataSourceMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
        _settings.value = _settings.value.copy(mode = mode)
    }

    /** Turns the plain-HTTP escape hatch on or off for an already-saved address. */
    fun setAllowCleartext(allowed: Boolean) {
        preferences.edit().putBoolean(KEY_ALLOW_CLEARTEXT, allowed).apply()
        _settings.value = _settings.value.copy(allowCleartext = allowed)
    }

    fun save(baseUrl: String, token: String) {
        val cleanUrl = normalizeBaseUrl(baseUrl, allowsCleartext = _settings.value.allowCleartext)
        val cleanToken = token.trim()
        if (cleanToken.isNotEmpty()) secrets.put(KEY_TOKEN, cleanToken)
        preferences.edit().putString(KEY_BASE_URL, cleanUrl).apply()
        _settings.value = loadSettings()
    }

    fun markVerified(version: String?) {
        preferences.edit()
            .putString(KEY_VERIFIED_VERSION, version.orEmpty())
            .putLong(KEY_VERIFIED_AT, System.currentTimeMillis())
            .apply()
        _settings.value = loadSettings()
    }

    /** Clears the verified marker when the address or token changes. */
    fun clearVerification() {
        preferences.edit()
            .remove(KEY_VERIFIED_VERSION)
            .remove(KEY_VERIFIED_AT)
            .apply()
        _settings.value = loadSettings()
    }

    fun clear() {
        secrets.remove(KEY_TOKEN)
        preferences.edit().clear().apply()
        _settings.value = BackendSettings()
    }

    private fun loadSettings(): BackendSettings = BackendSettings(
        mode = DataSourceMode.fromName(preferences.getString(KEY_MODE, null)),
        baseUrl = preferences.getString(KEY_BASE_URL, "").orEmpty(),
        tokenConfigured = token() != null,
        verifiedVersion = preferences.getString(KEY_VERIFIED_VERSION, null)?.takeIf { it.isNotBlank() },
        lastVerifiedAt = preferences.getLong(KEY_VERIFIED_AT, 0L),
        allowCleartext = preferences.getBoolean(KEY_ALLOW_CLEARTEXT, false),
    )

    companion object {
        private const val PREFERENCES_NAME = "data_source"
        private const val KEY_MODE = "mode"
        private const val KEY_BASE_URL = "backend_base_url"
        private const val KEY_TOKEN = "backend_token"
        private const val KEY_ALLOW_CLEARTEXT = "backend_allow_cleartext"
        private const val KEY_VERIFIED_VERSION = "backend_verified_version"
        private const val KEY_VERIFIED_AT = "backend_verified_at"
        private const val KEY_ALIAS = "macro_backend_token_v1"

        /**
         * Normalizes and validates the backend address.
         *
         * HTTPS is the default and is always accepted. Plain HTTP is available for any host only
         * after the user explicitly opts in, because the access token will travel unencrypted.
         */
        internal fun normalizeBaseUrl(value: String, allowsCleartext: Boolean = false): String {
            val normalized = value.trim().trimEnd('/')
            require(normalized.isNotEmpty()) { "Backend address is required" }
            val uri = runCatching { URI(normalized) }
                .getOrElse { throw IllegalArgumentException("Invalid backend address") }
            val scheme = uri.scheme?.lowercase()
            require(scheme == "https" || scheme == "http") {
                "Backend address must use http or https"
            }
            val host = uri.host
            require(!host.isNullOrBlank()) { "Backend address must include a host" }
            if (scheme == "http") {
                require(allowsCleartext) {
                    "Enable plain-HTTP access before using an http:// address"
                }
            }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
                "Backend address must not contain credentials, query, or fragment"
            }
            require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
                "Backend address must not contain a path"
            }
            return normalized
        }

        /** True when the address sends the access token over an unencrypted link. */
        fun usesPlainHttp(baseUrl: String): Boolean =
            baseUrl.trim().lowercase().startsWith("http://")

    }
}
