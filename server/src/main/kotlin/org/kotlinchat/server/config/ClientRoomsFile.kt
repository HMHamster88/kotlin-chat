package org.kotlinchat.server.config

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories

/**
 * Client-created rooms, kept out of `server.yaml` so hand-written config and comments are never
 * rewritten by the server. A missing or damaged file simply means "no created rooms yet".
 */
object ClientRoomsFile {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private val serializer = ListSerializer(RoomConfig.serializer())

    fun load(path: Path): List<RoomConfig> {
        if (!Files.exists(path)) return emptyList()
        return runCatching { json.decodeFromString(serializer, Files.readString(path)) }
            .getOrDefault(emptyList())
    }

    fun save(path: Path, rooms: List<RoomConfig>) {
        path.toAbsolutePath().parent?.createDirectories()
        val temporary = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary, json.encodeToString(serializer, rooms))
        runCatching {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
