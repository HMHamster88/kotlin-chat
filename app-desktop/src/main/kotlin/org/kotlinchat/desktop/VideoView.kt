package org.kotlinchat.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.kotlinchat.media.RemoteVideo
import org.kotlinchat.media.RemoteVideoView

/**
 * The room's screen-share pane. The decoded track is painted by a native Swing component at its
 * own resolution and frame rate; Compose only owns the header and layout.
 */
@Composable
fun RemoteVideoPane(
    video: RemoteVideo?,
    sharerName: String,
    modifier: Modifier = Modifier,
) {
    var firstFrame by remember(video) { mutableStateOf(false) }
    var resolution by remember(video) { mutableStateOf<Pair<Int, Int>?>(null) }
    val view = remember { RemoteVideoView() }

    SideEffect {
        view.onFirstFrame = { firstFrame = true }
        view.onResolution = { width, height -> resolution = width to height }
    }
    DisposableEffect(view) {
        onDispose { view.release() }
    }

    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${sharerName.ifBlank { "someone" }} is presenting",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            resolution?.let { (width, height) ->
                Text(
                    text = "$width×$height",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!firstFrame) {
                Text(
                    text = "waiting for the first frame…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SwingPanel(
            factory = { view },
            update = { it.setVideo(video) },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 4.dp),
            background = Color.Black,
        )
    }
}
