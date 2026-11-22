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
import androidx.compose.material3.Button
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
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.client.ServerEndpoint
import org.kotlinchat.client.rtc.AudioDeviceInfo
import org.kotlinchat.client.rtc.AudioDeviceSelection

@Composable
fun ConnectScreen(
    snapshot: ChatSnapshot,
    failure: String?,
    inputDevices: List<AudioDeviceInfo>,
    outputDevices: List<AudioDeviceInfo>,
    onForgetKey: (ServerEndpoint) -> Unit,
    onConnect: (ServerEndpoint, String, String, AudioDeviceSelection) -> Unit,
) {
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("8443") }
    var tls by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("guest") }
    var password by remember { mutableStateOf("") }
    var inputId by remember { mutableStateOf<String?>(null) }
    var outputId by remember { mutableStateOf<String?>(null) }

    val busy = snapshot.connection is ConnectionState.Connecting ||
        snapshot.connection is ConnectionState.Authenticating
    val canConnect = !busy &&
        host.isNotBlank() &&
        name.isNotBlank() &&
        password.isNotEmpty() &&
        (port.toIntOrNull() ?: 0) in 1..65535

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
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

            Button(
                onClick = {
                    onConnect(
                        ServerEndpoint(host = host, port = port.toInt(), tls = tls),
                        name,
                        password,
                        AudioDeviceSelection(inputId = inputId, outputId = outputId),
                    )
                },
                enabled = canConnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "Connecting…" else "Connect")
            }

            if (failure != null) {
                Text(
                    text = failure,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = {
                        onForgetKey(ServerEndpoint(host = host, port = port.toIntOrNull() ?: 8443, tls = tls))
                    },
                ) {
                    Text("Forget server key")
                }
            }

            val fingerprint = snapshot.serverFingerprint
            if (fingerprint != null) {
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
