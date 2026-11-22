package org.kotlinchat.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.protocol.Envelope
import org.kotlinchat.protocol.MessageType
import org.kotlinchat.protocol.Ping
import org.kotlinchat.protocol.payloadOf
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun ChatScreen(client: ChatClient) {
    val scope = rememberCoroutineScope()
    val state by client.state.collectAsState()

    var host by remember { mutableStateOf("127.0.0.1") }
    var port by remember { mutableStateOf("8080") }
    var name by remember { mutableStateOf("guest") }
    val traffic = remember { mutableStateListOf<String>() }

    LaunchedEffect(client) {
        client.incoming.collect { envelope ->
            traffic.add(0, "${clock()}  <-  ${describe(envelope)}")
        }
    }

    fun note(line: String) {
        traffic.add(0, "${clock()}  ${line}")
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("kotlin-chat", style = MaterialTheme.typography.headlineSmall)
        Text(
            "M0 control channel — connect, handshake, echo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("host") },
                singleLine = true,
                modifier = Modifier.weight(2f),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("port") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(32) },
                label = { Text("name") },
                singleLine = true,
                modifier = Modifier.weight(1.5f),
            )
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    val endpointPort = port.toIntOrNull() ?: 8080
                    scope.launch {
                        note("connecting to ${host.trim()}:$endpointPort")
                        val result = client.connect(
                            host = host.trim(),
                            port = endpointPort,
                            name = name.trim().ifBlank { "guest" },
                        )
                        if (result.isSuccess) {
                            note("control channel open, hello sent")
                        } else {
                            note("connect failed: ${result.exceptionOrNull()?.message ?: "unknown error"}")
                        }
                    }
                },
                enabled = state !is ConnectionState.Connected && state !is ConnectionState.Connecting,
            ) { Text("Connect") }

            OutlinedButton(
                onClick = {
                    note("disconnecting")
                    client.disconnect()
                },
                enabled = state is ConnectionState.Connected,
            ) { Text("Disconnect") }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        client.send(
                            MessageType.PING,
                            payloadOf(Ping(System.currentTimeMillis())),
                        )
                    }
                },
                enabled = state is ConnectionState.Connected,
            ) { Text("Ping") }

            TextButton(onClick = { traffic.clear() }) { Text("Clear") }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("status:", style = MaterialTheme.typography.labelMedium)
            Text(
                state.label,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor(state),
            )
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(traffic) { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun statusColor(state: ConnectionState): Color = when (state) {
    is ConnectionState.Connected -> Color(0xFF2E7D32)
    is ConnectionState.Failed -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun describe(envelope: Envelope): String {
    val target = envelope.replyTo?.let { " replyTo=$it" }.orEmpty()
    val body = envelope.payload.toString().let { if (it.length > 160) it.take(160) + "…" else it }
    return "${envelope.type}$target $body"
}

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private fun clock(): String = LocalTime.now().format(timeFormat)
