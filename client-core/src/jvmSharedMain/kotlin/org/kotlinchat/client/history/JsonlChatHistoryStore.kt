package org.kotlinchat.client.history

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.kotlinchat.protocol.ChatMessage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createDirectories

/**
 * Append-only JSON Lines, one message per line. A damaged line is skipped, so a crash during a
 * write can lose at most that message. Loading periodically compacts the file to the newest
 * [limit] messages.
 */
internal class JsonlChatHistoryStore(internal val baseDir: Path) : ChatHistoryStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()

    override fun load(serverKey: String, roomId: String, limit: Int): List<ChatMessage> =
        synchronized(lock) {
            if (limit <= 0) return emptyList()
            val file = fileFor(serverKey, roomId)
            if (!Files.isRegularFile(file)) return emptyList()

            val decoded = runCatching {
                Files.readAllLines(file, StandardCharsets.UTF_8).mapNotNull { line ->
                    if (line.isBlank()) null
                    else runCatching { json.decodeFromString<ChatMessage>(line) }.getOrNull()
                }
            }.getOrDefault(emptyList())

            val relevant = decoded
                .filter { it.roomId == roomId }
                .distinctBy { it.messageId }
                .sortedBy { it.ts }

            if (relevant.size > limit * 2) {
                runCatching { rewrite(file, relevant.takeLast(limit)) }
            }
            relevant.takeLast(limit)
        }

    override fun append(serverKey: String, roomId: String, message: ChatMessage) {
        synchronized(lock) {
            val file = fileFor(serverKey, roomId)
            runCatching {
                Files.createDirectories(file.parent)
                Files.write(
                    file,
                    (json.encodeToString(message) + "\n").toByteArray(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.WRITE,
                )
                restrictToOwner(file)
            }
        }
    }

    override fun clear(serverKey: String, roomId: String?) {
        synchronized(lock) {
            val serverDir = baseDir.resolve(safeName(serverKey))
            runCatching {
                if (roomId == null) {
                    serverDir.toFile().deleteRecursively()
                } else {
                    Files.deleteIfExists(serverDir.resolve("${safeName(roomId)}.jsonl"))
                    // Keep the tree tidy, but never remove the shared base directory itself.
                    if (Files.isDirectory(serverDir) && Files.list(serverDir).use { !it.findAny().isPresent }) {
                        Files.deleteIfExists(serverDir)
                    }
                }
            }
        }
    }

    private fun fileFor(serverKey: String, roomId: String): Path =
        baseDir.resolve(safeName(serverKey)).resolve("${safeName(roomId)}.jsonl")

    private fun rewrite(file: Path, messages: List<ChatMessage>) {
        val temporary = file.resolveSibling("${file.fileName}.tmp")
        Files.createDirectories(file.parent)
        val content = messages.joinToString(separator = "\n", postfix = "\n") { json.encodeToString(it) }
        Files.write(temporary, content.toByteArray(StandardCharsets.UTF_8))
        runCatching {
            Files.move(
                temporary,
                file,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
        }
        restrictToOwner(file)
    }

    /**
     * A readable file name that cannot escape [baseDir]. The short hash keeps two room names that
     * sanitize to the same text from sharing a file.
     */
    private fun safeName(value: String): String {
        val cleaned = value
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_' }
            .joinToString("")
            .take(48)
            .ifBlank { "x" }
        val suffix = value.hashCode().toUInt().toString(16)
        return "$cleaned-$suffix"
    }

    private fun restrictToOwner(file: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                file,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        } // Windows has no POSIX permissions and the file is already under the user's profile.
    }
}
