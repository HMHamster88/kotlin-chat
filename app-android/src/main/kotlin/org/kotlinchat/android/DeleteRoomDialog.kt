package org.kotlinchat.android

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import org.kotlinchat.protocol.RoomSummary

/** Confirms a room deletion; only empty, client-created rooms reach this dialog. */
@Composable
fun DeleteRoomDialog(
    room: RoomSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove room?") },
        text = {
            Text(
                "\"${room.name}\" will disappear for everyone. Chat history saved locally on each " +
                    "device is kept.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
