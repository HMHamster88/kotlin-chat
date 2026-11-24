package org.kotlinchat.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.client.rtc.AudioDeviceInfo
import org.kotlinchat.client.settings.AppDensity
import org.kotlinchat.client.settings.AppTheme
import org.kotlinchat.client.settings.ClientSettings
import org.kotlinchat.protocol.Protocol
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlin.math.roundToInt

private enum class SettingsSection(val label: String) {
    CONNECTION("Connection"),
    AUDIO("Audio & video"),
    CHAT("Chat"),
    FILES("Files"),
    UPDATES("Updates"),
    APPEARANCE("Appearance"),
    ABOUT("About"),
}

private enum class Confirmation { FORGET_SERVER, FORGET_PIN, CLEAR_ROOM_HISTORY, CLEAR_ALL_HISTORY }

/**
 * The desktop settings surface: a modal window with a left nav. Values save immediately; the only
 * confirmations are for discarding the saved server or the pinned certificate.
 */
@Composable
internal fun SettingsDialog(
    settings: ClientSettings,
    snapshot: ChatSnapshot,
    hasSavedPassword: Boolean,
    canRememberPassword: Boolean,
    version: String,
    inputDevices: List<AudioDeviceInfo>,
    outputDevices: List<AudioDeviceInfo>,
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
    onChooseDownloadFolder: () -> Unit,
    onOpenDownloads: () -> Unit,
    onClearHistory: (String?) -> Unit,
    onClose: () -> Unit,
) {
    var section by remember { mutableStateOf(SettingsSection.CONNECTION) }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }

    confirmation?.let { pending ->
        val title = when (pending) {
            Confirmation.FORGET_SERVER -> "Forget saved server?"
            Confirmation.FORGET_PIN -> "Forget pinned key?"
            Confirmation.CLEAR_ROOM_HISTORY -> "Clear this room's history?"
            Confirmation.CLEAR_ALL_HISTORY -> "Clear all chat history?"
        }
        val body = when (pending) {
            Confirmation.FORGET_SERVER ->
                "Clears the address, auto-connect, audio choices and the remembered password. " +
                    "The pinned certificate stays trusted."
            Confirmation.FORGET_PIN ->
                "The next connection will ask you to approve the server's certificate again. " +
                    "Only do this if you expected the key to change."
            Confirmation.CLEAR_ROOM_HISTORY ->
                "Deletes the messages stored on this device for ${currentRoomId ?: "the current room"}."
            Confirmation.CLEAR_ALL_HISTORY ->
                "Deletes every locally stored room for this server. Messages on the server are not affected."
        }
        AlertDialog(
            onDismissRequest = { confirmation = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmation = null
                        when (pending) {
                            Confirmation.FORGET_SERVER -> onForgetServer()
                            Confirmation.FORGET_PIN -> onForgetPin()
                            Confirmation.CLEAR_ROOM_HISTORY -> onClearHistory(currentRoomId)
                            Confirmation.CLEAR_ALL_HISTORY -> onClearHistory(null)
                        }
                    },
                ) {
                    Text(if (pending == Confirmation.FORGET_SERVER || pending == Confirmation.FORGET_PIN) "Forget" else "Clear")
                }
            },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("Cancel") } },
        )
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        KotlinChatTheme(settings) {
            Surface(
                modifier = Modifier.size(width = 780.dp, height = 600.dp),
                tonalElevation = 8.dp,
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Settings", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onClose) { Text("Close") }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        Column(
                            Modifier
                                .width(180.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 8.dp),
                        ) {
                            SettingsSection.entries.forEach { entry ->
                                TextButton(
                                    onClick = { section = entry },
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        containerColor = if (section == entry) {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
                                        },
                                    ),
                                ) {
                                    Text(
                                        text = entry.label,
                                        modifier = Modifier.fillMaxWidth(),
                                        textAlign = TextAlign.Start,
                                    )
                                }
                            }
                        }
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            when (section) {
                                SettingsSection.CONNECTION -> ConnectionSection(
                                    settings = settings,
                                    snapshot = snapshot,
                                    hasSavedPassword = hasSavedPassword,
                                    canRememberPassword = canRememberPassword,
                                    onChange = onSettingsChange,
                                    onReconnect = onReconnect,
                                    onChangeServer = onChangeServer,
                                    onForgetServer = { confirmation = Confirmation.FORGET_SERVER },
                                    onForgetPin = { confirmation = Confirmation.FORGET_PIN },
                                )

                                SettingsSection.AUDIO -> AudioSection(
                                    settings = settings,
                                    inputDevices = inputDevices,
                                    outputDevices = outputDevices,
                                    onRefreshDevices = onRefreshDevices,
                                    onChange = onSettingsChange,
                                    onAudioPreview = onAudioPreview,
                                )

                                SettingsSection.CHAT -> ChatSection(
                                    settings = settings,
                                    currentRoomId = currentRoomId,
                                    onChange = onSettingsChange,
                                    onClearRoom = { confirmation = Confirmation.CLEAR_ROOM_HISTORY },
                                    onClearAll = { confirmation = Confirmation.CLEAR_ALL_HISTORY },
                                )
                                SettingsSection.FILES -> FilesSection(
                                    settings = settings,
                                    onChange = onSettingsChange,
                                    onChooseDownloadFolder = onChooseDownloadFolder,
                                    onOpenDownloads = onOpenDownloads,
                                )

                                SettingsSection.UPDATES -> UpdatesSection(
                                    settings = settings,
                                    version = version,
                                    onChange = onSettingsChange,
                                    onCheckUpdates = onCheckUpdates,
                                )

                                SettingsSection.APPEARANCE -> AppearanceSection(settings, onSettingsChange)
                                SettingsSection.ABOUT -> AboutSection(
                                    settings = settings,
                                    snapshot = snapshot,
                                    version = version,
                                    onOpenReleasePage = onOpenReleasePage,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    subtitle: String? = null,
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
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) Hint(subtitle)
        }
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(selectedLabel, maxLines = 1)
            }
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

@Composable
private fun ConnectionSection(
    settings: ClientSettings,
    snapshot: ChatSnapshot,
    hasSavedPassword: Boolean,
    canRememberPassword: Boolean,
    onChange: (ClientSettings) -> Unit,
    onReconnect: () -> Unit,
    onChangeServer: () -> Unit,
    onForgetServer: () -> Unit,
    onForgetPin: () -> Unit,
) {
    SectionTitle("Connection")
    Text(
        "${settings.host}:${settings.port} · ${if (settings.tls) "TLS" else "no TLS"}",
        style = MaterialTheme.typography.bodyMedium,
    )
    Hint(
        when {
            snapshot.serverName.isNotBlank() -> "connected as ${snapshot.serverName}"
            else -> "not connected"
        },
    )

    val fingerprint = snapshot.serverFingerprint
    if (fingerprint != null) {
        Text("pinned key", style = MaterialTheme.typography.bodySmall)
        Text(
            fingerprint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { copyToClipboard(fingerprint) }) { Text("Copy fingerprint") }
    }

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    ToggleRow(
        title = "Connect automatically on start",
        subtitle = "Needs a saved password.",
        checked = settings.autoConnect && settings.rememberPassword,
        enabled = settings.rememberPassword,
        onChange = { onChange(settings.copy(autoConnect = it && settings.rememberPassword)) },
    )
    ToggleRow(
        title = "Remember password",
        subtitle = when {
            !canRememberPassword -> "No system key store here, so it cannot be saved safely."
            hasSavedPassword -> "Saved in the system key store."
            else -> "Saved in the system key store the next time you connect."
        },
        checked = settings.rememberPassword,
        enabled = canRememberPassword,
        onChange = {
            onChange(
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
        TextButton(onClick = onForgetServer) { Text("Forget saved server") }
        TextButton(onClick = onForgetPin) { Text("Forget pinned key") }
    }
}

@Composable
private fun AudioSection(
    settings: ClientSettings,
    inputDevices: List<AudioDeviceInfo>,
    outputDevices: List<AudioDeviceInfo>,
    onRefreshDevices: () -> Unit,
    onChange: (ClientSettings) -> Unit,
    onAudioPreview: (mic: Float, speaker: Float) -> Unit,
) {
    SectionTitle("Audio")
    val inputs = listOf<Pair<String?, String>>(null to "System default") +
        inputDevices.map { it.id to it.name }
    val outputs = listOf<Pair<String?, String>>(null to "System default") +
        outputDevices.map { it.id to it.name }
    ChoiceRow(
        title = "Microphone",
        options = inputs,
        selected = settings.inputDeviceId,
        onSelect = {
            onChange(settings.copy(inputDeviceId = it))
            onAudioPreview(settings.microphoneVolume, settings.speakerVolume)
        },
    )
    ChoiceRow(
        title = "Output",
        options = outputs,
        selected = settings.outputDeviceId,
        onSelect = {
            onChange(settings.copy(outputDeviceId = it))
            onAudioPreview(settings.microphoneVolume, settings.speakerVolume)
        },
    )
    TextButton(onClick = onRefreshDevices) { Text("Refresh device list") }

    VolumeRow(
        title = "Microphone level",
        value = settings.microphoneVolume,
        onPreview = { onAudioPreview(it, settings.speakerVolume) },
        onCommit = { onChange(settings.copy(microphoneVolume = it)) },
    )
    VolumeRow(
        title = "Speaker level",
        value = settings.speakerVolume,
        onPreview = { onAudioPreview(settings.microphoneVolume, it) },
        onCommit = { onChange(settings.copy(speakerVolume = it)) },
    )
    Hint("Levels apply live while a call is running.")

    ToggleRow(
        title = "Join muted",
        subtitle = "The microphone stays off until you unmute.",
        checked = settings.muteOnJoin,
        onChange = { onChange(settings.copy(muteOnJoin = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 4.dp))
    SectionTitle("Screen share")
    val presets = listOf(
        Triple("720p", 1280, 720),
        Triple("1080p", 1920, 1080),
        Triple("1440p", 2560, 1440),
    )
    ChoiceRow(
        title = "Resolution",
        options = presets.map { (label, width, height) -> (width to height) to label },
        selected = settings.shareMaxWidth to settings.shareMaxHeight,
        onSelect = { (width, height) ->
            onChange(settings.copy(shareMaxWidth = width, shareMaxHeight = height))
        },
    )
    ChoiceRow(
        title = "Frame rate",
        options = listOf(5, 10, 15, 30).map { it to "$it fps" },
        selected = settings.shareFps,
        onSelect = { onChange(settings.copy(shareFps = it)) },
    )
    Hint("These are defaults; the screen or window is still picked for each share.")
}

@Composable
private fun ChatSection(
    settings: ClientSettings,
    currentRoomId: String?,
    onChange: (ClientSettings) -> Unit,
    onClearRoom: () -> Unit,
    onClearAll: () -> Unit,
) {
    SectionTitle("Chat")
    ToggleRow(
        title = "Enter sends",
        subtitle = "Shift+Enter inserts a newline instead.",
        checked = settings.enterSends,
        onChange = { onChange(settings.copy(enterSends = it)) },
    )
    ToggleRow(
        title = "24-hour clock",
        checked = settings.clock24h,
        onChange = { onChange(settings.copy(clock24h = it)) },
    )
    ToggleRow(
        title = "Show timestamps",
        checked = settings.showTimestamps,
        onChange = { onChange(settings.copy(showTimestamps = it)) },
    )
    ToggleRow(
        title = "Group consecutive messages",
        subtitle = "Hides the repeated name and time for back-to-back messages.",
        checked = settings.groupMessages,
        onChange = { onChange(settings.copy(groupMessages = it)) },
    )
    ChoiceRow(
        title = "Message text size",
        options = listOf(0.85f to "Small", 1f to "Default", 1.15f to "Large"),
        selected = settings.textScale,
        onSelect = { onChange(settings.copy(textScale = it)) },
    )

    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    SectionTitle("History")
    ToggleRow(
        title = "Keep chat history on this device",
        subtitle = "Text messages only. Stored under ~/.kotlin-chat/history; file transfers are not kept.",
        checked = settings.keepHistory,
        onChange = { onChange(settings.copy(keepHistory = it)) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onClearRoom, enabled = currentRoomId != null) {
            Text("Clear this room")
        }
        TextButton(onClick = onClearAll) { Text("Clear all history") }
    }
    Hint("Turning history off stops new writes; use the buttons above to delete what is already stored.")
}

@Composable
private fun FilesSection(
    settings: ClientSettings,
    onChange: (ClientSettings) -> Unit,
    onChooseDownloadFolder: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    SectionTitle("Files")
    Text(
        settings.downloadDirectory ?: "System Downloads folder",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onChooseDownloadFolder) { Text("Choose folder…") }
        OutlinedButton(onClick = onOpenDownloads) { Text("Open") }
        TextButton(
            onClick = { onChange(settings.copy(downloadDirectory = null)) },
            enabled = settings.downloadDirectory != null,
        ) { Text("Reset") }
    }
    Hint("Received files are written here; nothing is stored on the server.")
    ToggleRow(
        title = "Open when finished",
        subtitle = "Hands a completed download to the system handler.",
        checked = settings.openDownloadedFiles,
        onChange = { onChange(settings.copy(openDownloadedFiles = it)) },
    )
}

@Composable
private fun UpdatesSection(
    settings: ClientSettings,
    version: String,
    onChange: (ClientSettings) -> Unit,
    onCheckUpdates: () -> Unit,
) {
    SectionTitle("Updates")
    Text("Current version: $version", style = MaterialTheme.typography.bodyMedium)
    ToggleRow(
        title = "Check at startup",
        checked = settings.checkForUpdates,
        onChange = { onChange(settings.copy(checkForUpdates = it)) },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onCheckUpdates) { Text("Check now") }
    }
    settings.skippedVersion?.let { skipped ->
        Text("Version $skipped is skipped.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onChange(settings.copy(skippedVersion = null)) }) {
            Text("Offer it again")
        }
    }
    Hint("Stable releases only. The manifest is fetched from the latest GitHub release.")
}

@Composable
private fun AppearanceSection(settings: ClientSettings, onChange: (ClientSettings) -> Unit) {
    SectionTitle("Appearance")
    ChoiceRow(
        title = "Theme",
        options = listOf(
            AppTheme.SYSTEM to "Follow system",
            AppTheme.LIGHT to "Light",
            AppTheme.DARK to "Dark",
        ),
        selected = settings.theme,
        onSelect = { onChange(settings.copy(theme = it)) },
    )
    ChoiceRow(
        title = "Density",
        options = listOf(
            AppDensity.COMFORTABLE to "Comfortable",
            AppDensity.COMPACT to "Compact",
        ),
        selected = settings.density,
        onSelect = { onChange(settings.copy(density = it)) },
    )
}

@Composable
private fun AboutSection(
    settings: ClientSettings,
    snapshot: ChatSnapshot,
    version: String,
    onOpenReleasePage: () -> Unit,
) {
    SectionTitle("About")
    Text("Kotlin Chat $version", style = MaterialTheme.typography.bodyMedium)
    Hint("Protocol ${Protocol.VERSION} · ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    Text(
        "${settings.host}:${settings.port} · server ${snapshot.serverName.ifBlank { "not connected" }}",
        style = MaterialTheme.typography.bodySmall,
    )
    snapshot.serverFingerprint?.let {
        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onOpenReleasePage) { Text("Releases") }
    }
    Button(
        onClick = {
            copyToClipboard(
                buildString {
                    appendLine("Kotlin Chat $version")
                    appendLine("protocol ${Protocol.VERSION}")
                    appendLine("os ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
                    appendLine("server ${settings.host}:${settings.port} tls=${settings.tls}")
                    appendLine("server name ${snapshot.serverName.ifBlank { "unknown" }}")
                    appendLine("fingerprint ${snapshot.serverFingerprint ?: "none"}")
                    appendLine("theme ${settings.theme} density ${settings.density}")
                    append("update channel stable")
                },
            )
        },
    ) { Text("Copy diagnostics") }
    Hint("Diagnostics contain no password and no message content.")
}

private fun copyToClipboard(text: String) {
    runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }
}
