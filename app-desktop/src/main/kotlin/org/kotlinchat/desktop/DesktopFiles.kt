package org.kotlinchat.desktop

import java.awt.Desktop
import java.io.File

/** Opens a downloaded file with whatever the system associates with it. */
internal fun openDownloadedFile(path: String): Boolean {
    val file = File(path)
    if (!file.isFile) return false
    return runCatching {
        require(Desktop.isDesktopSupported()) { "no desktop integration here" }
        val desktop = Desktop.getDesktop()
        require(desktop.isSupported(Desktop.Action.OPEN)) { "opening files is not supported" }
        desktop.open(file)
        true
    }.getOrDefault(false)
}

/** Shows the file in the system file manager with the file itself selected. */
internal fun revealDownloadedFile(path: String): Boolean {
    val file = File(path)
    if (!file.exists()) return false
    return runCatching {
        ProcessBuilder(revealCommand(file.absolutePath)).start()
        true
    }.getOrDefault(false)
}

/** Kept separate so the per-OS invocation can be checked without launching a file manager. */
internal fun revealCommand(path: String): List<String> {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        // The comma belongs to the switch and the path follows as its own argument, which keeps
        // paths with spaces intact.
        os.contains("win") -> listOf("explorer.exe", "/select,", path)
        os.contains("mac") || os.contains("darwin") -> listOf("open", "-R", path)
        else -> listOf("xdg-open", File(path).parent ?: path)
    }
}
