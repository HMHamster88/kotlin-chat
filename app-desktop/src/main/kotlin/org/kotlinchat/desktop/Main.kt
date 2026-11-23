package org.kotlinchat.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.settings.ConnectionMemory
import org.kotlinchat.media.WebRtcDesktopEngine

fun main() = application {
    val client = remember { ChatClient(WebRtcDesktopEngine()) }
    val memory = remember { ConnectionMemory() }
    var saved by remember { mutableStateOf(memory.load()) }
    var settingsOpen by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = {
            client.close()
            exitApplication()
        },
        // Ctrl+, is the usual settings shortcut; Cmd+, on macOS. The handler is on the window so
        // it also works while a text field has focus.
        onPreviewKeyEvent = { event ->
            val settingsShortcut = event.type == KeyEventType.KeyDown &&
                (event.isCtrlPressed || event.isMetaPressed) &&
                event.key == Key.Comma
            if (settingsShortcut) {
                settingsOpen = true
                true
            } else {
                false
            }
        },
        title = "kotlin-chat",
        state = rememberWindowState(width = 900.dp, height = 620.dp),
    ) {
        KotlinChatTheme(saved.settings) {
            ChatScreen(
                client = client,
                memory = memory,
                saved = saved,
                onSavedChange = { saved = it },
                settingsOpen = settingsOpen,
                onOpenSettings = { settingsOpen = true },
                onCloseSettings = { settingsOpen = false },
                onExit = { exitApplication() },
            )
        }
    }
}
