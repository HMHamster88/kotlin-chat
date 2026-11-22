package org.kotlinchat.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.client.ServerEndpoint

@Composable
fun ConnectScreen(
    snapshot: ChatSnapshot,
    failure: String?,
    onConnect: (ServerEndpoint, String, String) -> Unit,
) {
    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("8443") }
    var tls by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("guest") }
    var password by remember { mutableStateOf("") }

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
        Spacer(Modifier.height(40.dp))
        Text("kotlin-chat", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Enter a server address and its password.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        Column(
            modifier = Modifier.widthIn(max = 420.dp),
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

            Button(
                onClick = {
                    onConnect(
                        ServerEndpoint(host = host, port = port.toInt(), tls = tls),
                        name,
                        password,
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
                text = "TLS is verified by pinned certificate. The password is never sent; " +
                    "clients prove knowledge of it with SCRAM.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
