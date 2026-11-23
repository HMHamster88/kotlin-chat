package org.kotlinchat.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.kotlinchat.client.ServerKeyTrust

/** A connect that is waiting for the user to approve the server's certificate. */
data class PendingServerTrust(
    val request: ConnectionRequest,
    val trust: ServerKeyTrust,
)

/**
 * Trust on first use: the key is shown before anything is pinned, and a key that changed since
 * the last visit is called out instead of being accepted quietly.
 */
@Composable
fun TrustDialog(
    pending: PendingServerTrust,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val changed = pending.trust as? ServerKeyTrust.Changed
    val where = "${pending.request.endpoint.host}:${pending.request.endpoint.port}"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (changed != null) "Server key changed" else "New server key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (changed != null) {
                    Text(
                        "The key presented by $where does not match the one pinned last time. " +
                            "Only continue if you know the server was reinstalled or its certificate was replaced.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Fingerprint("pinned", changed.pinned)
                    Fingerprint("presented", changed.presented)
                } else {
                    Text(
                        "First contact with $where. Compare this key with the one the server printed " +
                            "when it started; trust it only if they match.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Fingerprint("server key", pending.trust.approvedFingerprint)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (changed != null) "Forget old key and trust" else "Trust and connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun Fingerprint(label: String, value: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        SelectionContainer {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
