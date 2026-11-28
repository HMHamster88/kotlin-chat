package org.kotlinchat.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.JOptionPane
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.control.ScreenControlPhase
import org.kotlinchat.client.control.ScreenControlRole
import org.kotlinchat.client.notification.MessageNotifications
import org.kotlinchat.client.settings.ConnectionMemory
import org.kotlinchat.client.settings.SavedConnection
import org.kotlinchat.media.WebRtcDesktopEngine

fun main() {
    val instance = when (val result = InstanceGuard.acquire()) {
        is InstanceGuard.Result.Started -> result.guard
        InstanceGuard.Result.AlreadyRunning -> return
        is InstanceGuard.Result.Failed -> {
            runCatching {
                JOptionPane.showMessageDialog(
                    null,
                    result.message,
                    "Kotlin Chat",
                    JOptionPane.ERROR_MESSAGE,
                )
            }
            System.err.println(result.message)
            return
        }
    }

    val memory = ConnectionMemory()
    val initial = runCatching { memory.load() }.getOrDefault(SavedConnection())
    // Must be set before the first Skiko layer exists: the property is read once, when the
    // renderer for the window is created.
    if (initial.settings.softwareRendering) {
        System.setProperty("skiko.renderApi", "SOFTWARE")
    }
    runApp(memory, initial, instance)
}

private fun runApp(
    memory: ConnectionMemory,
    initial: SavedConnection,
    instance: InstanceGuard,
) = application {
    val engine = remember { WebRtcDesktopEngine() }
    val inputSink = remember { createRemoteInputSink() }
    val client = remember { ChatClient(engine, inputSink) }
    var saved by remember { mutableStateOf(initial) }
    var settingsOpen by remember { mutableStateOf(false) }
    var shareWindowOpen by remember { mutableStateOf(false) }
    var mainVisible by remember { mutableStateOf(true) }
    var mainFocused by remember { mutableStateOf(true) }
    var shareFocused by remember { mutableStateOf(false) }
    var focusRequest by remember { mutableStateOf(0) }
    var quitting by remember { mutableStateOf(false) }
    var trayHintShown by remember { mutableStateOf(false) }
    val traySupported = remember { isTraySupported }
    val trayState = rememberTrayState()
    val mainWindowState = rememberWindowState(width = 900.dp, height = 620.dp)
    val shareWindowState = rememberWindowState(
        width = 1000.dp,
        height = 650.dp,
        position = WindowPosition(Alignment.Center),
    )

    fun showApp() {
        mainWindowState.isMinimized = false
        mainVisible = true
        focusRequest++
    }

    fun quit() {
        if (quitting) return
        quitting = true
        runCatching { client.close() }
        runCatching { instance.close() }
        exitApplication()
    }

    // Any of our windows being active is enough to skip a notification. A hidden main window
    // is never active, even if AWT has not delivered its focus-lost event yet.
    val appActive = (mainVisible && (mainFocused || settingsOpen)) ||
        (shareWindowOpen && shareFocused)
    val currentAppActive by rememberUpdatedState(appActive)

    // A second launch of the app finds this instance's port and asks it to show up.
    LaunchedEffect(instance) {
        instance.activations.collect { count ->
            if (count > 0) showApp()
        }
    }

    // Explain the tray only once everything is actually out of sight. With the separate share
    // window open the app is still visible, so the reminder waits until that window is gone.
    LaunchedEffect(mainVisible, shareWindowOpen, traySupported) {
        if (traySupported && !mainVisible && !shareWindowOpen && !trayHintShown) {
            trayHintShown = true
            trayState.sendNotification(
                Notification(
                    "Kotlin Chat",
                    "Still running in the tray. Click the icon to reopen or quit.",
                    Notification.Type.Info,
                ),
            )
        }
    }

    // Live messages only: history replay is not on this flow, and the policy drops own echoes,
    // other rooms and anything that arrives while a Kotlin Chat window is active.
    LaunchedEffect(client) {
        client.incomingMessages.collect { message ->
            if (!traySupported) return@collect
            val notification = MessageNotifications.build(
                snapshot = client.snapshot.value,
                message = message,
                settings = saved.settings,
                appActive = currentAppActive,
            ) ?: return@collect
            trayState.sendNotification(
                Notification(notification.title, notification.body, Notification.Type.Info),
            )
        }
    }

    Window(
        onCloseRequest = {
            if (saved.settings.closeToTray && traySupported) {
                val control = client.snapshot.value.control
                mainVisible = false
                if (control.role == ScreenControlRole.HOST) {
                    val message = when (control.phase) {
                        ScreenControlPhase.REQUESTED ->
                            "A remote-control request is waiting. Open Kotlin Chat to respond."
                        ScreenControlPhase.ACTIVE ->
                            "Remote control is active. Open Kotlin Chat to stop it."
                        else -> null
                    }
                    if (message != null) {
                        trayState.sendNotification(
                            Notification("Kotlin Chat", message, Notification.Type.Warning),
                        )
                    }
                }
            } else {
                quit()
            }
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
        title = "Kotlin Chat",
        icon = painterResource("icons/kotlin-chat.png"),
        state = mainWindowState,
        visible = mainVisible,
    ) {
        DisposableEffect(window) {
            mainFocused = window.isFocused
            val listener = object : WindowAdapter() {
                override fun windowGainedFocus(e: WindowEvent?) {
                    mainFocused = true
                }

                override fun windowLostFocus(e: WindowEvent?) {
                    mainFocused = false
                }

                override fun windowIconified(e: WindowEvent?) {
                    mainFocused = false
                }

                override fun windowDeiconified(e: WindowEvent?) {
                    mainFocused = window.isFocused
                }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        LaunchedEffect(mainVisible, focusRequest) {
            if (mainVisible) {
                window.toFront()
                window.requestFocus()
            }
        }
        KotlinChatTheme(saved.settings) {
            ChatScreen(
                client = client,
                engine = engine,
                memory = memory,
                saved = saved,
                onSavedChange = { saved = it },
                settingsOpen = settingsOpen,
                onOpenSettings = { settingsOpen = true },
                onCloseSettings = { settingsOpen = false },
                shareDetached = shareWindowOpen,
                onOpenShareWindow = { shareWindowOpen = true },
                onCloseShareWindow = {
                    shareFocused = false
                    shareWindowOpen = false
                },
                onBringToFront = { showApp() },
                onExit = { quit() },
            )
        }
    }

    if (traySupported) {
        Tray(
            icon = painterResource("icons/tray.png"),
            state = trayState,
            tooltip = "Kotlin Chat",
            onAction = { showApp() },
        ) {
            Item("Open Kotlin Chat") { showApp() }
            Separator()
            Item("Quit") { quit() }
        }
    }

    if (shareWindowOpen) {
        Window(
            onCloseRequest = {
                shareFocused = false
                shareWindowOpen = false
            },
            state = shareWindowState,
            title = "Kotlin Chat — Screen share",
            icon = painterResource("icons/kotlin-chat.png"),
        ) {
            DisposableEffect(window) {
                shareFocused = window.isFocused
                val listener = object : WindowAdapter() {
                    override fun windowGainedFocus(e: WindowEvent?) {
                        shareFocused = true
                    }

                    override fun windowLostFocus(e: WindowEvent?) {
                        shareFocused = false
                    }

                    override fun windowIconified(e: WindowEvent?) {
                        shareFocused = false
                    }

                    override fun windowDeiconified(e: WindowEvent?) {
                        shareFocused = window.isFocused
                    }
                }
                window.addWindowFocusListener(listener)
                onDispose { window.removeWindowFocusListener(listener) }
            }
            KotlinChatTheme(saved.settings) {
                DetachedShareWindow(
                    client = client,
                    engine = engine,
                    onClose = {
                        shareFocused = false
                        shareWindowOpen = false
                    },
                )
            }
        }
    }
}
