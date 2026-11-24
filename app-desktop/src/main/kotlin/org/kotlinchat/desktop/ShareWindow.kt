package org.kotlinchat.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.kotlinchat.client.ChatClient
import org.kotlinchat.media.WebRtcDesktopEngine

/**
 * Content of the secondary window opened by **Open in window**.
 *
 * The window follows the current remote presenter and closes itself when the share ends. The
 * native video surface fills the window, so resizing it changes the displayed size without
 * touching the stream.
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

    RemoteVideoPane(
        video = remoteVideos[presenter.sessionId],
        sharerName = presenter.name,
        modifier = Modifier.fillMaxSize(),
        onCloseWindow = onClose,
    )
}
