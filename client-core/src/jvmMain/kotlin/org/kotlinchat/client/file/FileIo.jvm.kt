package org.kotlinchat.client.file

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

actual fun openFileSource(path: String): FileSource = openSource(path)

actual fun openFileSink(name: String, directory: String?): FileSink =
    openSink(name, Paths.get(directory ?: defaultDownloadDirectory()))

private fun defaultDownloadDirectory(): String {
    val home = System.getProperty("user.home")
    val downloads = Paths.get(home, "Downloads")
    return if (Files.isDirectory(downloads)) downloads.toString() else home
}
