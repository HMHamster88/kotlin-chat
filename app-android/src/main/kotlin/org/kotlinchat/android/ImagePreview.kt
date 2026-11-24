package org.kotlinchat.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.kotlinchat.client.file.isPreviewableImageName
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

private const val PREVIEW_MAX_DIMENSION = 360
private const val PREVIEW_MAX_BYTES = 96 * 1024
private const val PREVIEW_MAX_SOURCE_PIXELS = 24_000_000L

/** Builds the small JPEG sent over the data channel before a file is accepted. */
fun createImagePreview(context: Context, uri: String, fileName: String): ByteArray? {
    if (!isPreviewableImageName(fileName)) return null
    return runCatching {
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth.toLong() * bounds.outHeight > PREVIEW_MAX_SOURCE_PIXELS) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > PREVIEW_MAX_DIMENSION * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = openStream(context, parsed)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        val rotated = applyExifRotation(context, parsed, decoded)
        if (rotated !== decoded) decoded.recycle()
        val scaled = scaleDown(rotated, PREVIEW_MAX_DIMENSION)
        if (scaled !== rotated) rotated.recycle()

        try {
            compressPreview(scaled)
        } finally {
            scaled.recycle()
        }
    }.getOrNull()
}

private fun compressPreview(bitmap: Bitmap): ByteArray? {
    var current = bitmap
    try {
        for (quality in intArrayOf(82, 65, 50)) {
            val output = ByteArrayOutputStream()
            current.compress(Bitmap.CompressFormat.JPEG, quality, output)
            val bytes = output.toByteArray()
            if (bytes.size <= PREVIEW_MAX_BYTES) return bytes
        }
        val smaller = scaleDown(current, PREVIEW_MAX_DIMENSION / 2)
        if (smaller !== current) {
            current = smaller
            val output = ByteArrayOutputStream()
            smaller.compress(Bitmap.CompressFormat.JPEG, 70, output)
            return output.toByteArray().takeIf { it.size <= PREVIEW_MAX_BYTES }
        }
        return null
    } finally {
        if (current !== bitmap) current.recycle()
    }
}

private fun openStream(context: Context, uri: Uri): InputStream? =
    if (uri.scheme == "content") {
        context.contentResolver.openInputStream(uri)
    } else {
        File(uri.path ?: uri.toString()).inputStream()
    }

private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
    val orientation = runCatching {
        openStream(context, uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
        else -> return bitmap
    }
    return runCatching { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true) }
        .getOrDefault(bitmap)
}

private fun scaleDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
    val largest = maxOf(bitmap.width, bitmap.height)
    if (largest <= maxDimension) return bitmap
    val scale = maxDimension.toFloat() / largest
    return Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * scale).toInt().coerceAtLeast(1),
        (bitmap.height * scale).toInt().coerceAtLeast(1),
        true,
    )
}

/** Decodes a thumbnail or saved image at its encoded size; the sender already bounded it. */
fun decodePreviewBitmap(bytes: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    if (bounds.outWidth.toLong() * bounds.outHeight > PREVIEW_MAX_SOURCE_PIXELS) return null

    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
}.getOrNull()

@Composable
fun FileImagePreview(
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
fun ImagePreviewDialog(preview: ByteArray, fileName: String, onDismiss: () -> Unit) {
    val bitmap = remember(preview) { decodePreviewBitmap(preview) }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
            shape = RoundedCornerShape(12.dp),
            tonalElevation = 8.dp,
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
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
