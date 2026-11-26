package org.kotlinchat.client.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Theme choice; SYSTEM follows the OS setting and is the default. */
@Serializable
enum class AppTheme { SYSTEM, LIGHT, DARK }

/** Overall UI density; compact shrinks type and message padding. */
@Serializable
enum class AppDensity { COMFORTABLE, COMPACT }

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
    /** Room to rejoin after a restart; null starts in the lobby. */
    val lastRoomId: String? = null,
    /** Ask GitHub for a newer release shortly after start. */
    val checkForUpdates: Boolean = true,
    /** Set by "Skip this version", so the same release is not offered again. */
    val skippedVersion: String? = null,

    // ---------------------------------------------------------------- audio
    /** Start voice sessions muted; true reproduces the original behaviour. */
    val muteOnJoin: Boolean = true,
    /** Microphone input gain, 0..1. 1 is the device's normal level. */
    val microphoneVolume: Float = 1f,
    /** Speaker/playout gain, 0..1. 1 is the device's normal level. */
    val speakerVolume: Float = 1f,

    // ----------------------------------------------------------------- chat
    /** Desktop only: Enter sends, Shift+Enter inserts a newline. */
    val enterSends: Boolean = true,
    val clock24h: Boolean = true,
    val showTimestamps: Boolean = true,
    /** 0.85 small, 1 default, 1.15 large. */
    val textScale: Float = 1f,
    /** Collapse the repeated name/time for back-to-back messages by one sender. */
    val groupMessages: Boolean = true,
    /** Store text messages locally, per server and room. Off stops new writes but keeps old files. */
    val keepHistory: Boolean = true,

    // ---------------------------------------------------------------- files
    /** Desktop download directory; null means the user's Downloads folder. */
    val downloadDirectory: String? = null,
    /** Open a finished incoming file with the system handler. */
    val openDownloadedFiles: Boolean = false,

    // ----------------------------------------------------------- screen share
    /** Desktop defaults; the source is still picked for each share. */
    val shareMaxWidth: Int = 1280,
    val shareMaxHeight: Int = 720,
    val shareFps: Int = 10,
    /** Host-enforced maximum length of one remote-control grant, in minutes. */
    val remoteControlLeaseMinutes: Int = 30,

    // ------------------------------------------------------------ appearance
    val theme: AppTheme = AppTheme.SYSTEM,
    val density: AppDensity = AppDensity.COMFORTABLE,
    /**
     * Desktop only: draw the UI on the CPU instead of the GPU. The D3D path keeps a graphics
     * driver heap of its own (about 80 MB on Windows with a discrete GPU); software rendering
     * costs a little CPU instead. Applied at start-up.
     */
    val softwareRendering: Boolean = false,
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
