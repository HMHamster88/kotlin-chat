package org.kotlinchat.client.file

import org.kotlinchat.client.platform.AndroidPlatform
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

actual fun openFileSource(path: String): FileSource = openSource(path)

actual fun openFileSink(name: String, directory: String?): FileSink =
    openSink(name, directory?.let(Path::of) ?: defaultDownloadDirectory())

/**
 * Scoped storage friendly: the app-specific external directory needs no permission and is
 * visible over adb/MTP. Writing to the shared Downloads folder would require MediaStore.
 */
private fun defaultDownloadDirectory(): Path {
    val context = AndroidPlatform.appContext
    val dir = context?.getExternalFilesDir(null) ?: context?.filesDir
    val base = dir ?: File(System.getProperty("java.io.tmpdir"))
    return Paths.get(base.absolutePath, "Downloads")
}
