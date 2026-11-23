package org.kotlinchat.client.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The one server this client remembers. Holds no secrets: the password lives in the platform
 * key store (see [SecretStore]) and only when the user asked for it.
 */
@Serializable
data class ClientSettings(
    val schema: Int = 1,
    val host: String = "127.0.0.1",
    val port: Int = 8443,
    val tls: Boolean = true,
    /** Display name last used; blank means "use the app's default". */
    val name: String = "",
    @SerialName("inputDevice") val inputDeviceId: String? = null,
    @SerialName("outputDevice") val outputDeviceId: String? = null,
    /** Persist the password in the platform key store. */
    val rememberPassword: Boolean = false,
    /** Connect without asking when the app starts (requires a saved password). */
    val autoConnect: Boolean = false,
    /** Ask GitHub for a newer release shortly after start. */
    val checkForUpdates: Boolean = true,
    /** Set by "Skip this version", so the same release is not offered again. */
    val skippedVersion: String? = null,
) {
    /** Auto-connect needs something to authenticate with. */
    val canAutoConnect: Boolean get() = autoConnect && rememberPassword
}

/** What the connect screen starts from. */
data class SavedConnection(
    val settings: ClientSettings = ClientSettings(),
    val password: String? = null,
)

/** Persists [ClientSettings] as a small file; implementations must be safe against corruption. */
interface ClientSettingsStore {
    fun load(): ClientSettings
    fun save(settings: ClientSettings)
}

/**
 * A platform-protected slot for one secret. [available] is false where no OS key store exists,
 * in which case the client must not save a password at all rather than fall back to plaintext.
 */
interface SecretStore {
    val available: Boolean
    fun read(): String?
    fun write(secret: String)
    fun clear()
}

/**
 * Reads and writes the remembered connection: settings in a plain JSON file, the password in the
 * platform key store. Saving with a null password clears whatever was stored.
 */
class ConnectionMemory(
    private val settingsStore: ClientSettingsStore = platformSettingsStore(),
    private val secretStore: SecretStore = platformSecretStore(),
) {
    val canRememberPassword: Boolean get() = secretStore.available

    fun load(): SavedConnection {
        val settings = settingsStore.load()
        val password = if (settings.rememberPassword) {
            runCatching { secretStore.read() }.getOrNull()
        } else {
            null
        }
        return SavedConnection(settings, password)
    }

    /**
     * Writes [settings] and, when [password] is not null and remembering is on, the password.
     * Turning remembering off (or passing a null password) deletes the stored secret.
     */
    fun save(settings: ClientSettings, password: String?) {
        val remember = settings.rememberPassword && canRememberPassword
        settingsStore.save(settings.copy(rememberPassword = remember))
        if (remember && password != null) {
            runCatching { secretStore.write(password) }
        } else {
            runCatching { secretStore.clear() }
        }
    }

    /** Forgets the server entirely: settings back to defaults and no stored secret. */
    fun clear() {
        settingsStore.save(ClientSettings())
        runCatching { secretStore.clear() }
    }

    /**
     * Applies [transform] to the stored settings and saves them, keeping the saved password.
     * Used for preferences that are not about connecting (update checks), so the UI does not
     * have to carry the password around.
     */
    fun updateSettings(transform: (ClientSettings) -> ClientSettings): SavedConnection {
        val current = load()
        save(transform(current.settings), current.password)
        return load()
    }
}

internal expect fun platformSettingsStore(): ClientSettingsStore

internal expect fun platformSecretStore(): SecretStore
