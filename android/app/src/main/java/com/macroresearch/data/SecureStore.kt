package com.macroresearch.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Values sealed with an Android Keystore AES-GCM key.
 *
 * The ciphertext and its IV live in SharedPreferences; the key never leaves the Keystore, so a
 * copied preferences file is useless on another device. Used for the user's own AI key and for
 * the backend access token.
 */
class SecureStore(
    context: Context,
    preferencesName: String,
    private val keyAlias: String,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun get(name: String): String? {
        val encrypted = preferences.getString("$name$CIPHERTEXT_SUFFIX", null) ?: return null
        val iv = preferences.getString("$name$IV_SUFFIX", null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrElse {
            // A Keystore key can be invalidated by a device reset or a lock-screen change:
            // drop the unusable ciphertext instead of failing every later read.
            remove(name)
            null
        }?.takeIf { it.isNotBlank() }
    }

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString("$name$CIPHERTEXT_SUFFIX", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("$name$IV_SUFFIX", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun remove(name: String) {
        preferences.edit()
            .remove("$name$CIPHERTEXT_SUFFIX")
            .remove("$name$IV_SUFFIX")
            .apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val CIPHERTEXT_SUFFIX = "_ciphertext"
        const val IV_SUFFIX = "_iv"
    }
}
