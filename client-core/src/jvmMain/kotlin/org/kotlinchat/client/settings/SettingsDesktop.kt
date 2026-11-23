package org.kotlinchat.client.settings

import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories

private fun settingsDirectory(): Path {
    val home = System.getProperty("user.home")
    return if (home == null) {
        Paths.get(System.getProperty("java.io.tmpdir"), "kotlin-chat")
    } else {
        Paths.get(home, ".kotlin-chat")
    }.also { it.createDirectories() }
}

internal actual fun platformSettingsStore(): ClientSettingsStore =
    JsonFileSettingsStore(settingsDirectory().resolve("settings.json"))

/**
 * Windows uses DPAPI (the blob is tied to the current Windows user), macOS the login keychain,
 * Linux the Secret Service. Anywhere else the client simply refuses to save a password.
 */
internal actual fun platformSecretStore(): SecretStore {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val directory = settingsDirectory()
    return when {
        os.contains("win") -> DpapiSecretStore(directory.resolve("password.bin"))
        os.contains("mac") || os.contains("darwin") -> MacKeychainSecretStore()
        os.contains("linux") -> SecretServiceStore()
        else -> UnavailableSecretStore
    }
}

/** Encrypts for the current Windows user with DPAPI; another user or machine cannot decrypt it. */
private class DpapiSecretStore(private val path: Path) : SecretStore {

    override val available: Boolean = true

    override fun read(): String? {
        if (!Files.exists(path)) return null
        return runCatching {
            String(Crypt32Util.cryptUnprotectData(Files.readAllBytes(path)), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun write(secret: String) {
        path.toAbsolutePath().parent?.createDirectories()
        Files.write(path, Crypt32Util.cryptProtectData(secret.toByteArray(Charsets.UTF_8)))
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    override fun clear() {
        runCatching { Files.deleteIfExists(path) }
    }
}

/** The login keychain, through the `security` tool that ships with macOS. */
private class MacKeychainSecretStore : SecretStore {

    override val available: Boolean = true

    override fun read(): String? = run("security", "find-generic-password", "-s", SERVICE, "-w")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    override fun write(secret: String) {
        run("security", "add-generic-password", "-U", "-s", SERVICE, "-a", "kotlin-chat", "-w", secret)
    }

    override fun clear() {
        run("security", "delete-generic-password", "-s", SERVICE)
    }
}

/** The Secret Service, through libsecret's `secret-tool`. */
private class SecretServiceStore : SecretStore {

    override val available: Boolean = commandExists("secret-tool")

    override fun read(): String? = run("secret-tool", "lookup", "service", SERVICE)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    override fun write(secret: String) {
        run("secret-tool", "store", "--label=$SERVICE", "service", SERVICE, stdin = secret)
    }

    override fun clear() {
        run("secret-tool", "clear", "service", SERVICE)
    }
}

private const val SERVICE = "kotlin-chat"

private fun commandExists(command: String): Boolean = runCatching {
    ProcessBuilder("sh", "-c", "command -v $command").redirectErrorStream(true).start()
        .let { process ->
            process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0
        }
}.getOrDefault(false)

/** Runs a helper command; returns stdout on success and null on any failure. */
private fun run(vararg command: String, stdin: String? = null): String? = runCatching {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    process.outputStream.use { stream ->
        if (stdin != null) stream.write(stdin.toByteArray(Charsets.UTF_8))
    }
    val output = process.inputStream.bufferedReader().readText()
    if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) output else null
}.getOrNull()
