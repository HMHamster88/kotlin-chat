package org.kotlinchat.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.kotlinchat.media.android.RemoteVideo
import org.kotlinchat.media.android.RemoteVideoView

/**
 * The room's screen-share panel. The decoded frames are drawn by a native
 * [android.view.SurfaceView] inside [RemoteVideoView], so this composable only handles the
 * header, the waiting state and the fullscreen viewer.
 */
@Composable
fun RemoteScreenPane(
    video: RemoteVideo?,
    sharerName: String,
    modifier: Modifier = Modifier,
) {
    var fullscreen by remember { mutableStateOf(false) }
    var firstFrame by remember(video) { mutableStateOf(false) }
    var resolution by remember(video) { mutableStateOf<Pair<Int, Int>?>(null) }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .clickable { fullscreen = true },
    ) {
        VideoSurface(
            video = video,
            modifier = Modifier.fillMaxSize(),
            onFirstFrame = { firstFrame = true },
            onResolution = { width, height -> resolution = width to height },
        )
        PresenterLabel(
            sharerName = sharerName,
            resolution = resolution,
            modifier = Modifier.align(Alignment.TopStart),
        )
        TextButton(
            onClick = { fullscreen = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)),
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        ) {
            Text("Fullscreen")
        }
        if (!firstFrame && video == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("connecting to the screen…", color = Color.White)
            }
        }
    }

    if (fullscreen) {
        FullscreenVideo(
            video = video,
            sharerName = sharerName,
            onDismiss = { fullscreen = false },
        )
    }
}

@Composable
private fun FullscreenVideo(
    video: RemoteVideo?,
    sharerName: String,
    onDismiss: () -> Unit,
) {
    var firstFrame by remember(video) { mutableStateOf(false) }
    var resolution by remember(video) { mutableStateOf<Pair<Int, Int>?>(null) }
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                VideoSurface(
                    video = video,
                    modifier = Modifier.fillMaxSize(),
                    onFirstFrame = { firstFrame = true },
                    onResolution = { width, height -> resolution = width to height },
                )
                PresenterLabel(
                    sharerName = sharerName,
                    resolution = resolution,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                )
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp)),
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                ) {
                    Text("Close")
                }
                if (!firstFrame && video == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("connecting to the screen…", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun PresenterLabel(
    sharerName: String,
    resolution: Pair<Int, Int>?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$sharerName is presenting",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        resolution?.let { (width, height) ->
            Text(
                text = "$width×$height",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.75f),
            )
        }
    }
}

/**
 * One `SurfaceViewRenderer` bound to [video]. Two of these can coexist (inline and fullscreen);
 * each renderer adds itself as a sink on the same decoded track.
 */
@Composable
private fun VideoSurface(
    video: RemoteVideo?,
    modifier: Modifier,
    onFirstFrame: () -> Unit,
    onResolution: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val view = remember { RemoteVideoView(context) }

    SideEffect {
        view.onFirstFrame = onFirstFrame
        view.onResolutionChanged = { width, height, _ -> onResolution(width, height) }
    }
    DisposableEffect(view) {
        onDispose { view.release() }
    }
    LaunchedEffect(video) {
        view.setVideo(video)
    }

    AndroidView(factory = { view }, modifier = modifier)
}
