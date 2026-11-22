package org.kotlinchat.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.kotlinchat.client.ChatClient

fun main() = application {
    val client = remember { ChatClient() }

    Window(
        onCloseRequest = {
            client.close()
            exitApplication()
        },
        title = "kotlin-chat",
        state = rememberWindowState(width = 900.dp, height = 620.dp),
    ) {
        MaterialTheme {
            ChatScreen(client)
        }
    }
}
