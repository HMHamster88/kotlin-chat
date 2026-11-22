package org.kotlinchat.client.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** Shared by the JVM and Android actuals; only the default directory differs. */
internal fun openSource(path: String): FileSource {
    val file = Paths.get(path).toFile()
    require(file.isFile) { "not a file: $path" }
    val stream: InputStream = file.inputStream().buffered()

    return object : FileSource {
        override val name: String = file.name
        override val size: Long = file.length()

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

internal fun openSink(name: String, directory: Path): FileSink {
    Files.createDirectories(directory)

    val finalPath = uniquePath(directory, sanitizeName(name))
    val partialPath = directory.resolve(".${finalPath.fileName}.part")
    val output = Files.newOutputStream(partialPath)
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
            runCatching { Files.move(partialPath, finalPath, StandardCopyOption.ATOMIC_MOVE) }
                .getOrElse { Files.move(partialPath, finalPath, StandardCopyOption.REPLACE_EXISTING) }
            finalPath.toString()
        }

        override suspend fun abort() = withContext(Dispatchers.IO) {
            if (!closed) {
                runCatching { output.close() }
                closed = true
            }
            runCatching { Files.deleteIfExists(partialPath) }
            Unit
        }
    }
}

/** Strips path separators and characters Windows rejects, and never returns a dotfile. */
internal fun sanitizeName(raw: String): String {
    val cleaned = raw
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .map { if (it.isISOControl() || it in "\\/:*?\"<>|") '_' else it }
        .joinToString("")
        .trim()
        .trim('.')
        .take(200)

    return cleaned.ifBlank { "download" }
}

internal fun uniquePath(directory: Path, name: String): Path {
    var candidate = directory.resolve(name)
    if (!Files.exists(candidate)) return candidate

    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val extension = if (dot > 0) name.substring(dot) else ""

    var index = 1
    while (Files.exists(candidate)) {
        candidate = directory.resolve("$base ($index)$extension")
        index++
    }
    return candidate
}
