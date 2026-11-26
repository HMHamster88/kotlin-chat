package org.kotlinchat.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.client.rtc.AudioDeviceList
import org.kotlinchat.client.settings.AppDensity
import org.kotlinchat.client.settings.AppTheme
import org.kotlinchat.client.settings.ClientSettings
import org.kotlinchat.protocol.Protocol
import kotlin.math.roundToInt

private enum class Confirm { FORGET_SERVER, FORGET_PIN, CLEAR_ROOM_HISTORY, CLEAR_ALL_HISTORY }

/** Full-screen settings on Android; the back arrow returns to the room or connect screen. */
@Composable
fun SettingsScreen(
    settings: ClientSettings,
    snapshot: ChatSnapshot,
    hasSavedPassword: Boolean,
    canRememberPassword: Boolean,
    version: String,
    audioDevices: AudioDeviceList,
    currentRoomId: String?,
    onRefreshDevices: () -> Unit,
    onSettingsChange: (ClientSettings) -> Unit,
    onAudioPreview: (microphone: Float, speaker: Float) -> Unit,
    onForgetServer: () -> Unit,
    onForgetPin: () -> Unit,
    onChangeServer: () -> Unit,
    onReconnect: () -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onClearHistory: (String?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    BackHandler { onClose() }

    confirm?.let { pending ->
        val title = when (pending) {
            Confirm.FORGET_SERVER -> "Forget saved server?"
            Confirm.FORGET_PIN -> "Forget pinned key?"
            Confirm.CLEAR_ROOM_HISTORY -> "Clear this room's history?"
            Confirm.CLEAR_ALL_HISTORY -> "Clear all chat history?"
        }
        val body = when (pending) {
            Confirm.FORGET_SERVER ->
                "Clears the address, auto-connect, audio choices and the remembered password. " +
                    "The pinned certificate stays trusted."
            Confirm.FORGET_PIN ->
                "The next connection will ask you to approve the server's certificate again."
            Confirm.CLEAR_ROOM_HISTORY ->
                "Deletes the messages stored on this device for ${currentRoomId ?: "the current room"}."
            Confirm.CLEAR_ALL_HISTORY ->
                "Deletes every locally stored room for this server. Messages on the server are not affected."
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = null
                        when (pending) {
                            Confirm.FORGET_SERVER -> onForgetServer()
                            Confirm.FORGET_PIN -> onForgetPin()
                            Confirm.CLEAR_ROOM_HISTORY -> onClearHistory(currentRoomId)
                            Confirm.CLEAR_ALL_HISTORY -> onClearHistory(null)
                        }
                    },
                ) {
                    Text(if (pending == Confirm.FORGET_SERVER || pending == Confirm.FORGET_PIN) "Forget" else "Clear")
                }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) {
                Icon(MdiIcons.arrowLeft, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Back")
            }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        HorizontalDivider()
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionTitle("Connection")
            Text(
                "${settings.host}:${settings.port} · ${if (settings.tls) "TLS" else "no TLS"}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Hint(if (snapshot.serverName.isNotBlank()) "connected as ${snapshot.serverName}" else "not connected")
            snapshot.serverFingerprint?.let { fingerprint ->
                Text(fingerprint, style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { copyToClipboard(context, fingerprint) }) {
                    Text("Copy fingerprint")
                }
            }
            ToggleRow(
                title = "Connect automatically on start",
                subtitle = "Needs a saved password.",
                checked = settings.autoConnect && settings.rememberPassword,
                enabled = settings.rememberPassword,
                onChange = { onSettingsChange(settings.copy(autoConnect = it && settings.rememberPassword)) },
            )
            ToggleRow(
                title = "Remember password",
                subtitle = when {
                    !canRememberPassword -> "No keystore here, so it cannot be saved safely."
                    hasSavedPassword -> "Saved in the Android keystore."
                    else -> "Saved in the Android keystore the next time you connect."
                },
                checked = settings.rememberPassword,
                enabled = canRememberPassword,
                onChange = {
                    onSettingsChange(
                        settings.copy(
                            rememberPassword = it,
                            autoConnect = settings.autoConnect && it,
                        ),
                    )
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onReconnect, enabled = hasSavedPassword) { Text("Reconnect") }
                OutlinedButton(onClick = onChangeServer) { Text("Change server…") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { confirm = Confirm.FORGET_SERVER }) { Text("Forget saved server") }
                TextButton(onClick = { confirm = Confirm.FORGET_PIN }) { Text("Forget pinned key") }
            }

            SettingsDivider()
            SectionTitle("Audio")
            val inputs = listOf<Pair<String?, String>>(null to "System default") +
                audioDevices.inputs.map { it.id to it.name }
            ChoiceRow(
                title = "Microphone",
                options = inputs,
                selected = settings.inputDeviceId,
                onSelect = { onSettingsChange(settings.copy(inputDeviceId = it)) },
            )
            Hint("Output uses the phone's audio route (speaker, earpiece or headset).")
            TextButton(onClick = onRefreshDevices) { Text("Refresh device list") }
            VolumeRow(
                title = "Microphone level",
                value = settings.microphoneVolume,
                onPreview = { onAudioPreview(it, settings.speakerVolume) },
                onCommit = { onSettingsChange(settings.copy(microphoneVolume = it)) },
            )
            VolumeRow(
                title = "Speaker level",
                value = settings.speakerVolume,
                onPreview = { onAudioPreview(settings.microphoneVolume, it) },
                onCommit = { onSettingsChange(settings.copy(speakerVolume = it)) },
            )
            Hint("Levels apply live while a call is running.")
            Hint("Screen sharing is available on the desktop client only.")

            SettingsDivider()
            SectionTitle("Chat")
            ToggleRow(
                title = "24-hour clock",
                checked = settings.clock24h,
                onChange = { onSettingsChange(settings.copy(clock24h = it)) },
            )
            ToggleRow(
                title = "Show timestamps",
                checked = settings.showTimestamps,
                onChange = { onSettingsChange(settings.copy(showTimestamps = it)) },
            )
            ToggleRow(
                title = "Group consecutive messages",
                checked = settings.groupMessages,
                onChange = { onSettingsChange(settings.copy(groupMessages = it)) },
            )
            ChoiceRow(
                title = "Message text size",
                options = listOf(0.85f to "Small", 1f to "Default", 1.15f to "Large"),
                selected = settings.textScale,
                onSelect = { onSettingsChange(settings.copy(textScale = it)) },
            )

            SettingsDivider()
            SectionTitle("History")
            ToggleRow(
                title = "Keep chat history on this device",
                subtitle = "Text messages only. Stored in the app's private files; file transfers are not kept.",
                checked = settings.keepHistory,
                onChange = { onSettingsChange(settings.copy(keepHistory = it)) },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { confirm = Confirm.CLEAR_ROOM_HISTORY },
                    enabled = currentRoomId != null,
                ) { Text("Clear this room") }
                TextButton(onClick = { confirm = Confirm.CLEAR_ALL_HISTORY }) {
                    Text("Clear all history")
                }
            }
            Hint("Turning history off stops new writes; use the buttons above to delete what is already stored.")

            SettingsDivider()
            SectionTitle("Files")
            Text("Downloads (MediaStore)", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { openDownloads(context) }) { Text("Open Downloads") }
            Hint("Received files go to the shared Downloads collection; nothing is stored on the server.")
            ToggleRow(
                title = "Open when finished",
                checked = settings.openDownloadedFiles,
                onChange = { onSettingsChange(settings.copy(openDownloadedFiles = it)) },
            )

            SettingsDivider()
            SectionTitle("Updates")
            Text("Current version: $version", style = MaterialTheme.typography.bodyMedium)
            ToggleRow(
                title = "Check at startup",
                checked = settings.checkForUpdates,
                onChange = { onSettingsChange(settings.copy(checkForUpdates = it)) },
            )
            OutlinedButton(onClick = onCheckUpdates) { Text("Check now") }
            settings.skippedVersion?.let { skipped ->
                Text("Version $skipped is skipped.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onSettingsChange(settings.copy(skippedVersion = null)) }) {
                    Text("Offer it again")
                }
            }

            SettingsDivider()
            SectionTitle("Appearance")
            ChoiceRow(
                title = "Theme",
                options = listOf(
                    AppTheme.SYSTEM to "Follow system",
                    AppTheme.LIGHT to "Light",
                    AppTheme.DARK to "Dark",
                ),
                selected = settings.theme,
                onSelect = { onSettingsChange(settings.copy(theme = it)) },
            )
            ChoiceRow(
                title = "Density",
                options = listOf(
                    AppDensity.COMFORTABLE to "Comfortable",
                    AppDensity.COMPACT to "Compact",
                ),
                selected = settings.density,
                onSelect = { onSettingsChange(settings.copy(density = it)) },
            )

            SettingsDivider()
            SectionTitle("About")
            Text("Kotlin Chat $version", style = MaterialTheme.typography.bodyMedium)
            Hint(
                "Protocol ${Protocol.VERSION} · Android ${Build.VERSION.RELEASE} · " +
                    "${Build.MANUFACTURER} ${Build.MODEL}",
            )
            Text(
                "${settings.host}:${settings.port} · server ${snapshot.serverName.ifBlank { "not connected" }}",
                style = MaterialTheme.typography.bodySmall,
            )
            snapshot.serverFingerprint?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
            OutlinedButton(onClick = onOpenReleasePage) { Text("Releases") }
            Button(
                onClick = {
                    copyToClipboard(
                        context,
                        buildString {
                            appendLine("Kotlin Chat $version")
                            appendLine("protocol ${Protocol.VERSION}")
                            appendLine("android ${Build.VERSION.RELEASE}")
                            appendLine("device ${Build.MANUFACTURER} ${Build.MODEL}")
                            appendLine("server ${settings.host}:${settings.port} tls=${settings.tls}")
                            appendLine("server name ${snapshot.serverName.ifBlank { "unknown" }}")
                            appendLine("fingerprint ${snapshot.serverFingerprint ?: "none"}")
                            append("update channel stable")
                        },
                    )
                },
            ) { Text("Copy diagnostics") }
            Hint("Diagnostics contain no password and no message content.")
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Hint(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: "System default"
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) { Text(selectedLabel, maxLines = 1) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            open = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VolumeRow(
    title: String,
    value: Float,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text("${(local * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = local.coerceIn(0f, 1f),
            onValueChange = {
                local = it
                onPreview(it)
            },
            onValueChangeFinished = { onCommit(local) },
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("Kotlin Chat diagnostics", text))
}

private fun openDownloads(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        Toast.makeText(context, "Open the Files app to see Downloads", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(MediaStore.Downloads.EXTERNAL_CONTENT_URI, "vnd.android.document/directory")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(intent) }.onFailure {
        Toast.makeText(context, "Open the Files app to see Downloads", Toast.LENGTH_SHORT).show()
    }
}
