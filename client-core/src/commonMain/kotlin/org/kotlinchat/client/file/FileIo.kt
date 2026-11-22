package org.kotlinchat.client.file

/** Reads a local file in bounded chunks. */
interface FileSource : AutoCloseable {
    val name: String
    val size: Long

    /** Returns the next chunk, or null at end of file. */
    suspend fun read(maxBytes: Int): ByteArray?
}

/** Writes an incoming file to a `.part` file and moves it into place on commit. */
interface FileSink {
    suspend fun write(bytes: ByteArray, length: Int)

    /** Closes and renames into a non-colliding final path; returns that path. */
    suspend fun commit(): String

    /** Closes and deletes the partial file. */
    suspend fun abort()
}

expect fun openFileSource(path: String): FileSource

/** [directory] defaults to the user's Downloads folder. */
expect fun openFileSink(name: String, directory: String? = null): FileSink
