package org.kotlinchat.client.settings

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories

/** Settings JSON: unknown fields from a newer build are ignored, defaults fill the rest. */
internal val settingsJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
    // An enum value written by a newer build must not reset the whole file on an older one.
    coerceInputValues = true
}

/**
 * Writes `settings.json` atomically: a truncated file after a crash would otherwise look like a
 * corrupt settings file. The contents are not secret; owner-only permissions are still applied.
 */
internal class JsonFileSettingsStore(private val path: Path) : ClientSettingsStore {

    override fun load(): ClientSettings {
        if (!Files.exists(path)) return ClientSettings()
        return runCatching {
            settingsJson.decodeFromString(ClientSettings.serializer(), String(Files.readAllBytes(path), Charsets.UTF_8))
        }.getOrElse {
            // A damaged file must never stop the app from starting; fall back to defaults.
            ClientSettings()
        }
    }

    override fun save(settings: ClientSettings) {
        path.toAbsolutePath().parent?.createDirectories()
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.write(temporary, settingsJson.encodeToString(ClientSettings.serializer(), settings).toByteArray(Charsets.UTF_8))
        restrictToOwner(temporary)
        runCatching { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            .getOrElse { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING) }
        restrictToOwner(path)
    }

    private fun restrictToOwner(target: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                target,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } // Windows has no POSIX permissions; the file holds no secrets.
    }
}

/** Used where no platform key store exists, so the client refuses to remember a password. */
internal object UnavailableSecretStore : SecretStore {
    override val available: Boolean = false
    override fun read(): String? = null
    override fun write(secret: String) = Unit
    override fun clear() = Unit
}
