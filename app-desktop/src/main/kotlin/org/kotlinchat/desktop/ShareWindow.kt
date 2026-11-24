package org.kotlinchat.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay
import org.kotlinchat.client.ChatClient
import org.kotlinchat.client.control.SCREEN_CONTROL_CAPABILITY
import org.kotlinchat.client.control.ScreenControlPhase
import org.kotlinchat.client.control.ScreenControlRole
import org.kotlinchat.media.VideoInput
import org.kotlinchat.media.WebRtcDesktopEngine

/**
 * Content of the secondary window opened by **Open in window**.
 *
 * The window follows the current remote presenter and closes itself when the share ends. It is
 * also the only control surface: the inline pane stays view-only. Closing the window while
 * controlling releases the session.
 */
@Composable
fun DetachedShareWindow(
    client: ChatClient,
    engine: WebRtcDesktopEngine,
    onClose: () -> Unit,
) {
    val snapshot by client.snapshot.collectAsState()
    val remoteVideos by engine.remoteVideos.collectAsState()
    val presenter = snapshot.members.firstOrNull {
        it.sharing && it.sessionId != snapshot.self?.sessionId
    }

    LaunchedEffect(presenter?.sessionId) {
        if (presenter == null) onClose()
    }

    if (presenter == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("The screen share has ended.")
        }
        return
    }

    val control = snapshot.control
    val active = control.role == ScreenControlRole.VIEWER && control.phase == ScreenControlPhase.ACTIVE
    val canRequest = !snapshot.screen.sharing &&
        presenter.capabilities.contains(SCREEN_CONTROL_CAPABILITY)
    val input = remember(client) {
        VideoInput(
            onPointer = { x, y -> client.sendScreenControlPointerMove(x, y) },
            onButton = { button, down -> client.sendScreenControlButton(button, down) },
            onWheel = { delta, horizontal -> client.sendScreenControlWheel(delta, horizontal) },
            onKey = { keyCode, down -> client.sendScreenControlKey(keyCode, down) },
        )
    }
    val latestControl by rememberUpdatedState(control)
    DisposableEffect(Unit) {
        onDispose {
            if (latestControl.role == ScreenControlRole.VIEWER) {
                when (latestControl.phase) {
                    ScreenControlPhase.ACTIVE -> client.releaseScreenControl()
                    ScreenControlPhase.REQUESTING -> client.cancelScreenControlRequest()
                    else -> Unit
                }
            }
        }
    }

    RemoteVideoPane(
        video = remoteVideos[presenter.sessionId],
        sharerName = presenter.name,
        modifier = Modifier.fillMaxSize(),
        onCloseWindow = onClose,
        input = if (active) input else null,
        headerActions = {
            when {
                control.role == ScreenControlRole.VIEWER &&
                    control.phase == ScreenControlPhase.REQUESTING -> {
                    Text(
                        text = "Waiting for approval…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { client.cancelScreenControlRequest() }) {
                        Text("Cancel")
                    }
                }

                active -> {
                    LeaseCountdown(control.expiresAtMillis)
                    TextButton(onClick = { client.releaseScreenControl() }) {
                        Text("Release")
                    }
                }

                else -> {
                    control.error?.let { error ->
                        Text(
                            text = error,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (canRequest) {
                        TextButton(
                            onClick = { client.requestScreenControl(presenter.sessionId) },
                        ) {
                            Text("Request control")
                        }
                    }
                }
            }
        },
    )
}

/** Shared countdown for the viewer and host banners. */
@Composable
internal fun LeaseCountdown(expiresAtMillis: Long?) {
    if (expiresAtMillis == null) return
    var remaining by remember(expiresAtMillis) {
        mutableStateOf(((expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0))
    }
    LaunchedEffect(expiresAtMillis) {
        while (true) {
            remaining = ((expiresAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            if (remaining <= 0) break
            delay(1_000)
        }
    }
    Text(
        text = "${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')} left",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
