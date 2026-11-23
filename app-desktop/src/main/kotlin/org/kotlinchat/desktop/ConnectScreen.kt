package org.kotlinchat.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.client.ConnectionFailure
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.client.ServerEndpoint
import org.kotlinchat.client.rtc.AudioDeviceInfo
import org.kotlinchat.client.rtc.AudioDeviceSelection
import org.kotlinchat.client.settings.ClientSettings
import org.kotlinchat.client.settings.SavedConnection

/** Everything the connect screen collected, in one value the caller can act on. */
data class ConnectionRequest(
    val endpoint: ServerEndpoint,
    val name: String,
    val password: String,
    val devices: AudioDeviceSelection,
    val rememberPassword: Boolean,
    val autoConnect: Boolean,
) {
    fun toSettings(): ClientSettings = ClientSettings(
        host = endpoint.host,
        port = endpoint.port,
        tls = endpoint.tls,
        name = name,
        inputDeviceId = devices.inputId,
        outputDeviceId = devices.outputId,
        rememberPassword = rememberPassword,
        autoConnect = autoConnect && rememberPassword,
    )
}

@Composable
fun ConnectScreen(
    snapshot: ChatSnapshot,
    failure: ConnectionFailure?,
    inputDevices: List<AudioDeviceInfo>,
    outputDevices: List<AudioDeviceInfo>,
    checkingKey: Boolean,
    initial: SavedConnection,
    canRememberPassword: Boolean,
    onForgetSaved: () -> Unit,
    onForgetKey: (ServerEndpoint) -> Unit,
    onConnect: (ConnectionRequest) -> Unit,
) {
    var host by remember { mutableStateOf(initial.settings.host) }
    var port by remember { mutableStateOf(initial.settings.port.toString()) }
    var tls by remember { mutableStateOf(initial.settings.tls) }
    var name by remember { mutableStateOf(initial.settings.name.ifBlank { "guest" }) }
    var password by remember { mutableStateOf(initial.password.orEmpty()) }
    var inputId by remember { mutableStateOf(initial.settings.inputDeviceId) }
    var outputId by remember { mutableStateOf(initial.settings.outputDeviceId) }
    var remember by remember { mutableStateOf(initial.settings.rememberPassword) }
    var autoConnect by remember { mutableStateOf(initial.settings.autoConnect) }

    val busy = checkingKey ||
        snapshot.connection is ConnectionState.Connecting ||
        snapshot.connection is ConnectionState.Authenticating
    val canConnect = !busy &&
        host.isNotBlank() &&
        name.isNotBlank() &&
        password.isNotEmpty() &&
        (port.toIntOrNull() ?: 0) in 1..65535

    Column(
        // A short window must scroll rather than hide the lower fields.
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("kotlin-chat", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Enter a server address and its password.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier.widthIn(max = 440.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it.trim() },
                label = { Text("server address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = { Text("port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = tls, onCheckedChange = { tls = it })
                Text("TLS", style = MaterialTheme.typography.bodyMedium)
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(32) },
                label = { Text("display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("server password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevicePicker(
                    label = "mic",
                    devices = inputDevices,
                    selectedId = inputId,
                    modifier = Modifier.weight(1f),
                    onSelect = { inputId = it },
                )
                DevicePicker(
                    label = "speaker",
                    devices = outputDevices,
                    selectedId = outputId,
                    modifier = Modifier.weight(1f),
                    onSelect = { outputId = it },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = remember && canRememberPassword,
                    onCheckedChange = {
                        remember = it
                        if (!it) autoConnect = false
                    },
                    enabled = canRememberPassword,
                )
                Column {
                    Text("remember password on this device", style = MaterialTheme.typography.bodySmall)
                    Text(
                        if (canRememberPassword) {
                            "kept in the system key store, not in the settings file"
                        } else {
                            "no system key store here, so the password cannot be saved safely"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = autoConnect && remember,
                    onCheckedChange = { autoConnect = it },
                    enabled = remember,
                )
                Text("connect automatically when the app starts", style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    val request = ConnectionRequest(
                        endpoint = ServerEndpoint(host = host, port = port.toInt(), tls = tls),
                        name = name,
                        password = password,
                        devices = AudioDeviceSelection(inputId = inputId, outputId = outputId),
                        rememberPassword = remember,
                        autoConnect = autoConnect,
                    )
                    onConnect(request)
                },
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        checkingKey -> "Checking server key…"
                        busy -> "Connecting…"
                        else -> "Connect"
                    },
                )
            }

            if (failure != null) {
                Text(
                    text = failure.summary,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                failure.hint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                failure.detail?.let { detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = {
                        onForgetKey(ServerEndpoint(host = host, port = port.toIntOrNull() ?: 8443, tls = tls))
                    },
                ) {
                    Text("Forget server key")
                }
            } else {
                val state = snapshot.connection
                if (state is ConnectionState.Reconnecting) {
                    Text(
                        text = state.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.hint?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.detail?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onForgetKey(ServerEndpoint(host, port.toIntOrNull() ?: 8443, tls)) }) {
                        Text("Forget server key")
                    }
                } else if (state !is ConnectionState.Idle) {
                    Text(state.label, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (initial.settings != ClientSettings() || initial.password != null) {
                TextButton(
                    onClick = {
                        onForgetSaved()
                        host = ClientSettings().host
                        port = ClientSettings().port.toString()
                        tls = ClientSettings().tls
                        name = "guest"
                        password = ""
                        inputId = null
                        outputId = null
                        remember = false
                        autoConnect = false
                    },
                ) {
                    Text("Forget saved connection")
                }
            }

            snapshot.serverFingerprint?.let { fingerprint ->
                Text(
                    text = "server key $fingerprint",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = "The password is never sent; clients prove knowledge of it with SCRAM. " +
                    "TLS is verified by pinned certificate. Voice joins muted.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DevicePicker(
    label: String,
    devices: List<AudioDeviceInfo>,
    selectedId: String?,
    modifier: Modifier = Modifier,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = devices.firstOrNull { it.id == selectedId }?.name ?: "default"

    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "$label: $selectedName",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("System default") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        onSelect(device.id)
                        expanded = false
                    },
                )
            }
        }
    }
}
