package org.kotlinchat.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.kotlinchat.client.update.UpdateChecker
import org.kotlinchat.client.update.UpdateInfo
import org.kotlinchat.client.update.UpdatePlatform
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** What the banner should show. Downloads and swaps never run without a click. */
sealed interface UpdateState {
    data object Hidden : UpdateState
    data object Checking : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val received: Long, val total: Long) : UpdateState
    data class Ready(val info: UpdateInfo, val stagedApp: File) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Portable desktop updates. The app image is a directory, so the only thing needed is an
 * orderly directory swap after this process exits: download, verify, unpack, then let a small
 * PowerShell helper wait for the PID, rename the old directory aside and start the new one.
 *
 * The helper is also the rollback: if the new build is not alive after 20 seconds it puts the
 * previous directory back and starts that. A packaged app is required (jpackage sets
 * `jpackage.app-path`); a `gradlew run` build only offers the download page.
 */
class DesktopUpdateController(
    val currentVersion: String,
    private val scope: CoroutineScope,
    private val checker: UpdateChecker = UpdateChecker(currentVersion, UpdatePlatform.DESKTOP_WINDOWS),
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Hidden)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null

    private val baseDir: Path = System.getenv("LOCALAPPDATA")
        ?.let { Path.of(it, "kotlin-chat") }
        ?: Path.of(System.getProperty("user.home"), ".kotlin-chat")
    private val updatesDir: Path = baseDir.resolve("updates")
    private val marker: Path = updatesDir.resolve("pending-update")
    private val portable = PortableUpdate(updatesDir)

    /** Asks GitHub once; a failure stays silent because nobody asked for a progress dialog. */
    fun check(skippedVersion: String?) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            val info = checker.check()
            _state.value = when {
                info == null -> UpdateState.Hidden
                info.version == skippedVersion -> UpdateState.Hidden
                else -> UpdateState.Available(info)
            }
        }
    }

    /** Manual check: shows a failure instead of pretending nothing happened. */
    fun checkNow() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Checking
            val info = runCatching { checker.check() }
            _state.value = info.fold(
                onSuccess = { if (it == null) UpdateState.Hidden else UpdateState.Available(it) },
                onFailure = { UpdateState.Failed(it.message ?: "could not reach the update server") },
            )
        }
    }

    fun dismiss() {
        _state.value = UpdateState.Hidden
    }

    fun download() {
        val info = (_state.value as? UpdateState.Available)?.info ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(info, 0, info.asset.size)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    portable.downloadAndStage(info) { received, total ->
                        _state.value = UpdateState.Downloading(info, received, total)
                    }
                }
            }
            _state.value = result.fold(
                onSuccess = { UpdateState.Ready(info, it) },
                onFailure = { UpdateState.Failed(it.message ?: "the download failed", info) },
            )
        }
    }

    /** Starts the helper and asks the app to exit; the helper does the rest from disk. */
    fun installAndRestart(onExit: () -> Unit) {
        val ready = _state.value as? UpdateState.Ready ?: return
        val installDir = installDirectory()
        if (installDir == null) {
            _state.value = UpdateState.Failed(
                "This build was not started from the packaged app, so it cannot update itself. " +
                    "Download the release and replace the folder.",
                ready.info,
            )
            return
        }
        if (!canWriteNextTo(installDir)) {
            _state.value = UpdateState.Failed(
                "The install folder is not writable (it may be under Program Files). " +
                    "Download the release and replace the folder by hand.",
                ready.info,
            )
            return
        }

        val launched = runCatching {
            val script = writeHelper()
            ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-WindowStyle", "Hidden", "-File", script.toString(),
                "-WaitPid", ProcessHandle.current().pid().toString(),
                "-InstallDir", installDir.absolutePath,
                "-StagedDir", ready.stagedApp.absolutePath,
                "-ExeName", UPDATE_EXE_NAME,
                "-Marker", marker.toAbsolutePath().toString(),
            )
                .directory(updatesDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(updatesDir.resolve("update.log").toFile())
                .start()
        }
        launched.fold(
            onSuccess = { onExit() },
            onFailure = {
                _state.value = UpdateState.Failed(
                    "Could not start the update helper: ${it.message}",
                    ready.info,
                )
            },
        )
    }

    /** The helper drops a marker before relaunching; a healthy run removes it after ten seconds. */
    fun startupCleanup() {
        scope.launch {
            delay(10_000)
            runCatching { Files.deleteIfExists(marker) }
        }
    }

    fun close() {
        checker.close()
        job?.cancel()
    }

    private fun installDirectory(): File? {
        if (!System.getProperty("os.name").lowercase().contains("windows")) return null
        val appPath = System.getProperty("jpackage.app-path") ?: return null
        return runCatching { File(appPath).canonicalFile.parentFile }.getOrNull()
    }

    private fun canWriteNextTo(installDir: File): Boolean {
        val parent = installDir.parentFile ?: return false
        return runCatching {
            Files.createTempFile(parent.toPath(), ".kotlin-chat", ".tmp").toFile().delete()
        }.getOrDefault(false)
    }

    private fun writeHelper(): Path {
        Files.createDirectories(updatesDir)
        val script = updatesDir.resolve("update.ps1")
        Files.writeString(script, HELPER_SCRIPT)
        return script
    }

    private companion object {
        val HELPER_SCRIPT = """
            param(
              [Parameter(Mandatory=${'$'}true)][int]${'$'}WaitPid,
              [Parameter(Mandatory=${'$'}true)][string]${'$'}InstallDir,
              [Parameter(Mandatory=${'$'}true)][string]${'$'}StagedDir,
              [Parameter(Mandatory=${'$'}true)][string]${'$'}ExeName,
              [Parameter(Mandatory=${'$'}true)][string]${'$'}Marker
            )
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}deadline = (Get-Date).AddSeconds(60)
            while ((Get-Date) -lt ${'$'}deadline -and (Get-Process -Id ${'$'}WaitPid -ErrorAction SilentlyContinue)) {
              Start-Sleep -Milliseconds 500
            }
            ${'$'}old = "${'$'}{InstallDir}.old-${'$'}([DateTime]::Now.ToString('yyyyMMddHHmmss'))"
            ${'$'}swapped = ${'$'}false
            try {
              Move-Item -LiteralPath ${'$'}InstallDir -Destination ${'$'}old
              Move-Item -LiteralPath ${'$'}StagedDir -Destination ${'$'}InstallDir
              ${'$'}swapped = ${'$'}true
              Set-Content -LiteralPath ${'$'}Marker -Value ${'$'}old -NoNewline
              Start-Process -FilePath (Join-Path ${'$'}InstallDir ${'$'}ExeName) -WorkingDirectory ${'$'}InstallDir
              Start-Sleep -Seconds 20
              ${'$'}name = [IO.Path]::GetFileNameWithoutExtension(${'$'}ExeName)
              if (-not (Get-Process -Name ${'$'}name -ErrorAction SilentlyContinue)) {
                throw 'the new build exited during startup'
              }
              Remove-Item -LiteralPath ${'$'}old -Recurse -Force -ErrorAction SilentlyContinue
            } catch {
              # Any failure after the rename puts the previous directory back. Before the rename
              # there is nothing to restore, and the stale staged copy can simply be retried.
              if (${'$'}swapped -and (Test-Path -LiteralPath ${'$'}old)) {
                if (Test-Path -LiteralPath ${'$'}InstallDir) {
                  Remove-Item -LiteralPath ${'$'}InstallDir -Recurse -Force
                }
                Move-Item -LiteralPath ${'$'}old -Destination ${'$'}InstallDir
                Start-Process -FilePath (Join-Path ${'$'}InstallDir ${'$'}ExeName) -WorkingDirectory ${'$'}InstallDir
                Remove-Item -LiteralPath ${'$'}Marker -Force -ErrorAction SilentlyContinue
              }
            }
        """.trimIndent()
    }
}

/** The launcher inside a jpackage app image. */
internal const val UPDATE_EXE_NAME = "kotlin-chat.exe"

/**
 * Download, verify and unpack a release zip. Split from the controller so the archive handling
 * (checksum, zip-slip guard, locating the app root) can be tested without a window or a swap.
 */
internal class PortableUpdate(private val updatesDir: Path) {

    fun downloadAndStage(
        info: UpdateInfo,
        onProgress: (received: Long, total: Long) -> Unit,
    ): File {
        Files.createDirectories(updatesDir)
        val archive = updatesDir.resolve("kotlin-chat-${info.version}.zip")
        Files.deleteIfExists(archive)

        val client = OkHttpClient.Builder().followRedirects(true).build()
        try {
            val request = Request.Builder().url(info.asset.url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("the update server returned ${response.code}")
                val body = response.body ?: error("the update server sent an empty response")
                val total = body.contentLength().takeIf { it > 0 } ?: info.asset.size
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                body.byteStream().use { input ->
                    Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            received += read
                            onProgress(received, total)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(info.asset.sha256, ignoreCase = true)) {
                    Files.deleteIfExists(archive)
                    error("the download did not match its checksum; nothing was installed")
                }
            }

            val staging = updatesDir.resolve("stage-${info.version}")
            if (Files.exists(staging)) staging.toFile().deleteRecursively()
            Files.createDirectories(staging)
            unzip(archive, staging)
            Files.deleteIfExists(archive)
            return findAppRoot(staging)
                ?: error("the archive does not contain $UPDATE_EXE_NAME")
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun unzip(archive: Path, target: Path): Unit =
        ZipInputStream(Files.newInputStream(archive)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                val destination = target.resolve(entry.name).normalize()
                if (!destination.startsWith(target)) error("the archive contains an unsafe path")
                if (entry.isDirectory) {
                    Files.createDirectories(destination)
                } else {
                    Files.createDirectories(destination.parent)
                    Files.copy(input, destination)
                }
                input.closeEntry()
            }
        }

    /** The archive has one top-level directory (the jpackage app image). Accept either layout. */
    private fun findAppRoot(staging: Path): File? {
        if (Files.isRegularFile(staging.resolve(UPDATE_EXE_NAME))) return staging.toFile()
        Files.list(staging).use { children ->
            return children
                .filter { Files.isRegularFile(it.resolve(UPDATE_EXE_NAME)) }
                .findFirst()
                .orElse(null)
                ?.toFile()
        }
    }
}

@Composable
fun UpdateBanner(
    state: UpdateState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    onOpenPage: () -> Unit,
) {
    val info = when (state) {
        is UpdateState.Available -> state.info
        is UpdateState.Downloading -> state.info
        is UpdateState.Ready -> state.info
        is UpdateState.Failed -> state.info
        else -> null
    }
    if (info == null && state !is UpdateState.Failed) return

    Surface(
        color = when (state) {
            is UpdateState.Failed -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = when (state) {
                        is UpdateState.Failed -> "Update failed: ${state.message}"
                        is UpdateState.Ready -> "Version ${state.info.version} is ready to install"
                        is UpdateState.Downloading -> "Downloading ${state.info.version}…"
                        is UpdateState.Available -> "Version ${state.info.version} is available"
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                when (state) {
                    is UpdateState.Downloading -> {
                        val total = state.total.takeIf { it > 0 } ?: state.info.asset.size
                        if (total > 0) {
                            LinearProgressIndicator(
                                progress = { (state.received.toFloat() / total).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                        }
                    }

                    is UpdateState.Available, is UpdateState.Ready -> Text(
                        text = info?.asset?.size?.takeIf { it > 0 }?.let(::formatBytes).orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Unit
                }
            }
            when (state) {
                is UpdateState.Available -> {
                    Button(onClick = onDownload) { Text("Download") }
                    TextButton(onClick = onLater) { Text("Later") }
                    TextButton(onClick = onSkip) { Text("Skip") }
                }

                is UpdateState.Ready -> Button(onClick = onInstall) { Text("Restart now") }
                is UpdateState.Failed -> {
                    Button(onClick = onOpenPage) { Text("Download page") }
                    TextButton(onClick = onLater) { Text("Dismiss") }
                }

                else -> Unit
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 -> "%.0f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f kB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** Opens the release page in the user's browser; failures stay silent. */
fun openReleasePage(url: String?) {
    val target = url ?: "https://github.com/HMHamster88/kotlin-chat/releases/latest"
    runCatching {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(target))
    }
}
