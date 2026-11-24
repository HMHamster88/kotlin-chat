package org.kotlinchat.desktop

import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.io.File

/** Remembers the folder picked in this session so the next dialog starts there. */
private var lastFileDirectory: String? = null

/**
 * Opens the OS file chooser for "Send a file".
 *
 * `FileDialog` is the native dialog on Windows and macOS. On Linux AWT falls back to its own
 * dialog, but it still behaves better than a Swing component. The dialog is modal to the app
 * window, so Compose's window is used as its owner.
 */
internal fun chooseFileToSend(): File? {
    val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow as? Frame
    val dialog = FileDialog(owner, "Send a file", FileDialog.LOAD)
    lastFileDirectory?.let { dialog.directory = it }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    val chosen = File(dialog.directory, name)
    lastFileDirectory = chosen.parent
    return chosen
}

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

/** Opens the configured download folder, or the system Downloads folder. */
internal fun openDownloadsFolder(configured: String?): Boolean {
    val folder = configured?.let(::File) ?: File(System.getProperty("user.home"), "Downloads")
    if (!folder.isDirectory) return false
    return runCatching {
        require(Desktop.isDesktopSupported()) { "no desktop integration here" }
        Desktop.getDesktop().open(folder)
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
