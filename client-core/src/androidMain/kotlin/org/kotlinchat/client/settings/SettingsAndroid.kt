package org.kotlinchat.client.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.kotlinchat.client.platform.AndroidPlatform
import java.nio.file.Paths
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.util.Base64

internal actual fun platformSettingsStore(): ClientSettingsStore {
    val context = AndroidPlatform.appContext
        ?: return object : ClientSettingsStore {
            override fun load(): ClientSettings = ClientSettings()
            override fun save(settings: ClientSettings) = Unit
        }
    return JsonFileSettingsStore(Paths.get(context.filesDir.absolutePath, "settings.json"))
}

internal actual fun platformSecretStore(): SecretStore {
    val context = AndroidPlatform.appContext ?: return UnavailableSecretStore
    return KeyStoreSecretStore(context)
}

/**
 * Keeps the password encrypted with an AES-256-GCM key that lives in the Android Keystore.
 * Only the ciphertext is written to preferences, so a backup of the app data is useless without
 * the device's key material.
 */
private class KeyStoreSecretStore(context: Context) : SecretStore {

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override val available: Boolean = runCatching { key() }.isSuccess

    override fun read(): String? {
        val stored = preferences.getString(KEY, null) ?: return null
        return runCatching {
            val separator = stored.indexOf('.')
            require(separator > 0) { "malformed secret" }
            val iv = Base64.decode(stored.substring(0, separator), Base64.NO_WRAP)
            val ciphertext = Base64.decode(stored.substring(separator + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun write(secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            "." + Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        preferences.edit().putString(KEY, encoded).apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "kotlin-chat-password"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFERENCES = "kotlin-chat-secret"
        const val KEY = "password"
    }
}
