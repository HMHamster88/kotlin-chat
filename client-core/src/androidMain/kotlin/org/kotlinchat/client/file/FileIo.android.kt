package org.kotlinchat.client.file

import android.annotation.TargetApi
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.kotlinchat.client.platform.AndroidPlatform
import java.io.File
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A picked document arrives as a `content://` URI from the Storage Access Framework, which is
 * not a path on disk, so it is read through the content resolver instead.
 */
actual fun openFileSource(path: String): FileSource =
    if (path.startsWith("content://")) contentSource(path) else openSource(path)

actual fun openFileSink(name: String, directory: String?): FileSink = when {
    directory != null -> openSink(name, Paths.get(directory))
    // Shared Downloads is where a phone user looks for the file; it needs MediaStore on Q+.
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> mediaStoreSink(name)
    else -> openSink(name, defaultDownloadDirectory())
}

private fun contentSource(uriText: String): FileSource {
    val context = requireNotNull(AndroidPlatform.appContext) { "no application context" }
    val resolver = context.contentResolver
    val uri = Uri.parse(uriText)

    var displayName: String? = null
    var declaredSize = 0L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) declaredSize = cursor.getLong(sizeIndex)
            }
        }

    val name = sanitizeName(displayName ?: uri.lastPathSegment ?: "file")
    val stream: InputStream = requireNotNull(resolver.openInputStream(uri)) { "cannot open $uriText" }
        .buffered()

    return object : FileSource {
        override val name: String = name
        override val size: Long = declaredSize

        override suspend fun read(maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
            val buffer = ByteArray(maxBytes)
            val read = stream.read(buffer)
            when {
                read < 0 -> null
                read == maxBytes -> buffer
                else -> buffer.copyOf(read)
            }
        }

        override fun close() {
            runCatching { stream.close() }
        }
    }
}

/**
 * Writes into the shared Downloads collection. The row is pending until the hash has been
 * verified, so a failed or cancelled transfer never leaves a half-written file in Downloads.
 */
@TargetApi(Build.VERSION_CODES.Q)
private fun mediaStoreSink(rawName: String): FileSink {
    val context = requireNotNull(AndroidPlatform.appContext) { "no application context" }
    val resolver = context.contentResolver
    val name = sanitizeName(rawName)

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(name))
        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) {
        "cannot create a Downloads entry for $name"
    }
    val output = requireNotNull(resolver.openOutputStream(uri)) { "cannot write $uri" }
    var closed = false

    return object : FileSink {
        override suspend fun write(bytes: ByteArray, length: Int) = withContext(Dispatchers.IO) {
            output.write(bytes, 0, length)
        }

        override suspend fun commit(): String = withContext(Dispatchers.IO) {
            if (!closed) {
                output.flush()
                output.close()
                closed = true
            }
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            "Downloads/${displayNameOf(uri) ?: name}"
        }

        override suspend fun abort() = withContext(Dispatchers.IO) {
            if (!closed) {
                runCatching { output.close() }
                closed = true
            }
            runCatching { resolver.delete(uri, null, null) }
            Unit
        }
    }
}

private fun displayNameOf(uri: Uri): String? =
    AndroidPlatform.appContext?.contentResolver
        ?.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

private fun mimeTypeOf(name: String): String {
    val extension = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
}

/**
 * Scoped storage friendly fallback for Android 8-9: the app-specific external directory needs no
 * permission and is visible over adb/MTP.
 */
private fun defaultDownloadDirectory(): Path {
    val context = AndroidPlatform.appContext
    val dir = context?.getExternalFilesDir(null) ?: context?.filesDir
    val base = dir ?: File(System.getProperty("java.io.tmpdir"))
    return Paths.get(base.absolutePath, "Downloads")
}
