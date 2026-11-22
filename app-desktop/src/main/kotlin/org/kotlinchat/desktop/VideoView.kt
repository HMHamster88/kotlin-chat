package org.kotlinchat.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.kotlinchat.client.ScreenSnapshot
import org.kotlinchat.client.rtc.VideoFrameData
import org.jetbrains.skia.Image as SkiaImage

@Composable
fun RemoteVideoPane(
    screen: ScreenSnapshot,
    sharerName: String?,
    modifier: Modifier = Modifier,
) {
    val bitmap = rememberVideoBitmap(screen.remote)
    val frame = screen.remote

    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "${sharerName ?: "someone"} is presenting",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (frame != null) {
                Text(
                    text = "${frame.width}×${frame.height}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "screen share",
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 4.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "waiting for the first frame…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Frames arrive as RGBA bytes at ~5 fps. Skia is used directly because Compose has no
 * allocation-free path from raw pixels to an ImageBitmap on desktop.
 */
@Composable
private fun rememberVideoBitmap(frame: VideoFrameData?): ImageBitmap? = remember(frame) {
    frame?.let { data ->
        val info = ImageInfo(data.width, data.height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
        SkiaImage.makeRaster(info, data.rgba, data.width * 4).toComposeImageBitmap()
    }
}
