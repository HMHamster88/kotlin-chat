package org.kotlinchat.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jetbrains.skia.Image as SkiaImage
import org.kotlinchat.client.file.isPreviewableImageName
import java.awt.Color as AwtColor
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

private const val PREVIEW_MAX_DIMENSION = 360
private const val PREVIEW_JPEG_QUALITY = 0.82f
private const val PREVIEW_MAX_BYTES = 96 * 1024
private const val PREVIEW_MAX_SOURCE_BYTES = 32L * 1024 * 1024
private const val PREVIEW_MAX_SOURCE_PIXELS = 24_000_000L

/** Builds the small JPEG sent over the data channel before a file is accepted. */
internal fun createImagePreview(path: String): ByteArray? {
    val file = File(path)
    if (!file.isFile || !isPreviewableImageName(file.name) || file.length() > PREVIEW_MAX_SOURCE_BYTES) {
        return null
    }
    return runCatching {
        val source = readBoundedImage(file) ?: return null
        val scale = PREVIEW_MAX_DIMENSION.toFloat() / maxOf(source.width, source.height)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.color = AwtColor.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }

        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpg").asSequence().firstOrNull() ?: return null
        val params = writer.defaultWriteParam
        if (params.canWriteCompressed()) {
            params.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
            params.compressionQuality = PREVIEW_JPEG_QUALITY
        }
        ImageIO.createImageOutputStream(output).use { stream ->
            writer.output = stream
            writer.write(null, javax.imageio.IIOImage(target, null, null), params)
        }
        writer.dispose()
        output.toByteArray().takeIf { it.size <= PREVIEW_MAX_BYTES }
    }.getOrNull()
}

private fun readBoundedImage(file: File): BufferedImage? {
    ImageIO.createImageInputStream(file)?.use { input ->
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return null
        val reader = readers.next()
        return try {
            reader.input = input
            val width = reader.getWidth(0)
            val height = reader.getHeight(0)
            if (width <= 0 || height <= 0) return null
            if (width.toLong() * height > PREVIEW_MAX_SOURCE_PIXELS) return null
            reader.read(0)
        } finally {
            reader.dispose()
        }
    }
    return null
}

/** Decodes a preview or a saved image; only bounded sizes reach the decoder. */
internal fun decodePreviewBitmap(bytes: ByteArray): ImageBitmap? = runCatching {
    // A malicious peer could send a tiny JPEG with huge dimensions; check the header first.
    val allowed = java.io.ByteArrayInputStream(bytes).use { input ->
        ImageIO.createImageInputStream(input)?.use { imageInput ->
            val readers = ImageIO.getImageReaders(imageInput)
            if (!readers.hasNext()) return null
            val reader = readers.next()
            try {
                reader.input = imageInput
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                width > 0 && height > 0 && width.toLong() * height <= PREVIEW_MAX_SOURCE_PIXELS
            } finally {
                reader.dispose()
            }
        }
    } ?: return null
    if (!allowed) return null
    SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

@Composable
internal fun FileImagePreview(
    preview: ByteArray,
    fileName: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val bitmap = remember(preview) { decodePreviewBitmap(preview) } ?: return
    Image(
        bitmap = bitmap,
        contentDescription = "Preview of $fileName",
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentScale = ContentScale.Fit,
    )
}

@Composable
internal fun ImagePreviewDialog(preview: ByteArray, fileName: String, onDismiss: () -> Unit) {
    val bitmap = remember(preview) { decodePreviewBitmap(preview) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.9f),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 8.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                HorizontalDivider()
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.Black).padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "Preview of $fileName",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    } else {
                        Text("This image cannot be displayed.", color = Color.White)
                    }
                }
            }
        }
    }
}
