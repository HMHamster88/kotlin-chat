package org.kotlinchat.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import org.kotlinchat.client.file.TransferProgress

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

private fun mimeTypeOf(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
}
