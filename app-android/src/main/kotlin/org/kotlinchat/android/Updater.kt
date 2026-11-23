package org.kotlinchat.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.io.File
import java.security.MessageDigest

/** Banner state for the Android updater; mirrors the desktop one, minus the directory swap. */
sealed interface AndroidUpdateState {
    data object Hidden : AndroidUpdateState
    data object Checking : AndroidUpdateState
    data class Available(val info: UpdateInfo) : AndroidUpdateState
    data class Downloading(val info: UpdateInfo, val received: Long, val total: Long) : AndroidUpdateState
    data class Ready(
        val info: UpdateInfo,
        val file: File,
        /** Set after the installer refused for lack of the "install unknown apps" grant. */
        val permissionHint: Boolean = false,
    ) : AndroidUpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : AndroidUpdateState
}

/**
 * Android cannot install silently without device-owner rights, so the flow stops at the system
 * installer: download, verify the checksum, then hand the APK over with a FileProvider. The APK
 * keeps the release signing key, so this is an in-place upgrade and settings/pins survive.
 */
class AndroidUpdateController(
    private val context: Context,
    private val scope: CoroutineScope,
    val currentVersion: String,
    private val checker: UpdateChecker = UpdateChecker(currentVersion, UpdatePlatform.ANDROID),
) {
    private val _state = MutableStateFlow<AndroidUpdateState>(AndroidUpdateState.Hidden)
    val state: StateFlow<AndroidUpdateState> = _state.asStateFlow()

    private var job: Job? = null

    private val updatesDir: File
        get() = File(context.cacheDir, "updates")

    fun check(skippedVersion: String?) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = AndroidUpdateState.Checking
            val info = checker.check()
            _state.value = when {
                info == null -> AndroidUpdateState.Hidden
                info.version == skippedVersion -> AndroidUpdateState.Hidden
                else -> AndroidUpdateState.Available(info)
            }
        }
    }

    fun dismiss() {
        _state.value = AndroidUpdateState.Hidden
    }

    fun download() {
        val info = (_state.value as? AndroidUpdateState.Available)?.info ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = AndroidUpdateState.Downloading(info, 0, info.asset.size)
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    downloadAndVerify(info) { received, total ->
                        _state.value = AndroidUpdateState.Downloading(info, received, total)
                    }
                }
            }
            _state.value = result.fold(
                onSuccess = { AndroidUpdateState.Ready(info, it) },
                onFailure = { AndroidUpdateState.Failed(it.message ?: "the download failed", info) },
            )
        }
    }

    /**
     * Opens the system installer. When this app may not install packages yet, the per-app
     * "install unknown apps" page is opened instead of failing silently; the banner then keeps
     * the downloaded APK so the user can tap Install again after enabling it.
     */
    fun install(): Boolean {
        val ready = _state.value as? AndroidUpdateState.Ready ?: return false
        if (!context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            _state.value = ready.copy(permissionHint = true)
            return false
        }

        return runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.updates",
                ready.file,
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        }.getOrElse {
            _state.value = AndroidUpdateState.Failed(
                "Could not open the installer: ${it.message}",
                ready.info,
            )
            false
        }
    }

    fun openReleasePage(url: String?) {
        val target = url ?: "https://github.com/HMHamster88/kotlin-chat/releases/latest"
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun close() {
        checker.close()
        job?.cancel()
    }

    private fun downloadAndVerify(
        info: UpdateInfo,
        onProgress: (received: Long, total: Long) -> Unit,
    ): File {
        val directory = updatesDir.apply { mkdirs() }
        val apk = File(directory, "kotlin-chat-${info.version}.apk")
        if (apk.exists()) apk.delete()

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
                    apk.outputStream().use { output ->
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
                    apk.delete()
                    error("the download did not match its checksum; nothing was installed")
                }
            }
            return apk
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}

@Composable
fun AndroidUpdateBanner(
    state: AndroidUpdateState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onLater: () -> Unit,
    onSkip: () -> Unit,
    onOpenPage: () -> Unit,
) {
    if (state is AndroidUpdateState.Hidden || state is AndroidUpdateState.Checking) return

    Surface(
        color = when (state) {
            is AndroidUpdateState.Failed -> MaterialTheme.colorScheme.errorContainer
            else -> MaterialTheme.colorScheme.secondaryContainer
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(
                text = when (state) {
                    is AndroidUpdateState.Failed -> "Update failed: ${state.message}"
                    is AndroidUpdateState.Ready -> if (state.permissionHint) {
                        "Allow \"install unknown apps\" for kotlin-chat, then tap Install"
                    } else {
                        "Version ${state.info.version} is downloaded"
                    }
                    is AndroidUpdateState.Downloading -> "Downloading ${state.info.version}…"
                    is AndroidUpdateState.Available -> "Version ${state.info.version} is available"
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
            )
            when (state) {
                is AndroidUpdateState.Downloading -> {
                    val total = state.total.takeIf { it > 0 } ?: state.info.asset.size
                    if (total > 0) {
                        LinearProgressIndicator(
                            progress = { (state.received.toFloat() / total).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }

                is AndroidUpdateState.Available -> BannerActions(
                    onDownload = onDownload,
                    onLater = onLater,
                    onSkip = onSkip,
                )

                is AndroidUpdateState.Ready -> BannerActions(
                    onInstall = onInstall,
                    onLater = onLater,
                )

                is AndroidUpdateState.Failed -> Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = onOpenPage) { Text("Releases") }
                    TextButton(onClick = onLater) { Text("Dismiss") }
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun BannerActions(
    onDownload: (() -> Unit)? = null,
    onInstall: (() -> Unit)? = null,
    onLater: () -> Unit,
    onSkip: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onDownload != null) Button(onClick = onDownload) { Text("Download") }
        if (onInstall != null) Button(onClick = onInstall) { Text("Install") }
        TextButton(onClick = onLater) { Text("Later") }
        if (onSkip != null) TextButton(onClick = onSkip) { Text("Skip") }
    }
}
