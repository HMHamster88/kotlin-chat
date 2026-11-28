package org.kotlinchat.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import org.kotlinchat.client.file.TransferProgress

/** A file picked from the document picker, before it is offered to the room. */
data class PickedFile(val uri: String, val name: String, val size: Long?)

fun describePickedFile(context: Context, uri: Uri): PickedFile {
    var name: String? = null
    var size: Long? = null
    runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) name = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
    }
    return PickedFile(
        uri = uri.toString(),
        name = name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "file",
        size = size,
    )
}

/**
 * Hands a finished download to whatever app can display it. Downloads live in MediaStore, so the
 * handle is a content:// URI and the receiving app gets a read grant with the intent.
 */
fun openDownloadedFile(context: Context, transfer: TransferProgress) {
    val uri = transfer.savedUri?.takeIf { it.startsWith("content://") }?.let(Uri::parse) ?: return
    val mime = context.contentResolver.getType(uri) ?: mimeTypeOf(transfer.fileName)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, transfer.fileName))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open ${transfer.fileName}", Toast.LENGTH_SHORT).show()
    }
}

/** Opens an http(s) message link in whatever browser the phone has. */
fun openExternalLink(context: Context, url: String) {
    if (!url.startsWith("http://", ignoreCase = true) &&
        !url.startsWith("https://", ignoreCase = true)
    ) {
        return
    }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this link", Toast.LENGTH_SHORT).show()
    }
}

private fun mimeTypeOf(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
}
