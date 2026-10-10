package com.macroresearch.data

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.UUID

/** Sent only to the configured backend, never to public providers or personal model endpoints. */
data class BackendDeviceIdentity(val installationId: String, val androidIdHash: String)

/** Per-install identity, independent of backend credentials and excluded from backup/transfer. */
class DeviceIdentityStore internal constructor(
    private val readInstallationId: () -> String?,
    private val saveInstallationId: (String) -> Boolean,
    private val readAndroidId: () -> String?,
) {
    private val installationId: String by lazy {
        readInstallationId()?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?: UUID.randomUUID().toString().also {
                check(saveInstallationId(it)) { "Unable to persist installation identity" }
            }
    }

    fun identity(): BackendDeviceIdentity? {
        val androidId = readAndroidId()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        // App-specific domain separation: the raw ANDROID_ID is never sent or stored here.
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("macro-research:android-id:v1:$androidId".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return BackendDeviceIdentity(installationId, digest)
    }

    companion object {
        fun fromContext(context: Context): DeviceIdentityStore {
            val app = context.applicationContext
            val preferences = app.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
            return DeviceIdentityStore(
                readInstallationId = { preferences.getString("installation_uuid", null) },
                saveInstallationId = { preferences.edit().putString("installation_uuid", it).commit() },
                readAndroidId = { Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) },
            )
        }
    }
}
