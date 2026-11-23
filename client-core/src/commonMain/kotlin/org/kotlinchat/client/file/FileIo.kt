package org.kotlinchat.client.file

/** Reads a local file in bounded chunks. */
interface FileSource : AutoCloseable {
    val name: String
    val size: Long

    /** Returns the next chunk, or null at end of file. */
    suspend fun read(maxBytes: Int): ByteArray?
}

/**
 * A finished download. [display] is what the UI shows (a path, or "Downloads/name" on Android);
 * [openable] is the platform handle an "open" action needs, such as a content:// URI.
 */
data class SavedFile(val display: String, val openable: String?)

/** Writes an incoming file to a `.part` file and moves it into place on commit. */
interface FileSink {
    suspend fun write(bytes: ByteArray, length: Int)

    /** Closes and renames into a non-colliding final location. */
    suspend fun commit(): SavedFile

    /** Closes and deletes the partial file. */
    suspend fun abort()
}

expect fun openFileSource(path: String): FileSource

/** [directory] defaults to the user's Downloads folder. */
expect fun openFileSink(name: String, directory: String? = null): FileSink
