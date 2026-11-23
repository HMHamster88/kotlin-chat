package org.kotlinchat.server.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

@Serializable
data class TlsConfig(
    val enabled: Boolean = true,
    val keyStore: String = "config/server.p12",
    val storePassword: String = "changeit",
    val alias: String = "kotlin-chat",
)

@Serializable
data class RoomConfig(
    val id: String,
    val name: String,
    val topic: String = "",
    /** Voice rooms enforce [ServerConfig.maxUsersPerRoom]; text rooms are uncapped. */
    val voice: Boolean = true,
)

@Serializable
data class TurnConfig(
    val urls: List<String>,
    val secret: String,
    val ttlSeconds: Int = 3600,
)

@Serializable
data class ServerConfig(
    val serverName: String = "kotlin-chat",
    val motd: String = "",
    val host: String = "0.0.0.0",
    val port: Int = 8443,
    val verifierFile: String = "config/verifier",
    val maxUsersPerRoom: Int = 8,
    val maxChatBytes: Int = 4096,
    /** Lets authenticated members create rooms from their client. */
    val allowClientRooms: Boolean = true,
    /** Upper bound on the room table, config rooms included. */
    val maxRooms: Int = 32,
    /** Client-created rooms live here so they survive a restart. */
    val clientRoomsFile: String = "config/rooms.json",
    val tls: TlsConfig = TlsConfig(),
    val rooms: List<RoomConfig> = listOf(
        RoomConfig(id = "lobby", name = "Lobby", topic = "Say hello", voice = true),
    ),
    val turn: TurnConfig? = null,
) {
    companion object {
        fun load(path: Path): ServerConfig {
            require(Files.exists(path)) { "config file not found: $path (run `init` first)" }
            return Yaml.default.decodeFromString(serializer(), Files.readString(path))
        }

        fun writeDefault(path: Path) {
            path.toAbsolutePath().parent?.createDirectories()
            Files.writeString(path, DEFAULT_YAML)
        }
    }
}

/** Shipped by `init`; also the reference for every option. */
val DEFAULT_YAML: String = """
    # kotlin-chat server configuration
    serverName: kotlin-chat
    motd: Welcome to the server
    host: 0.0.0.0
    port: 8443
    verifierFile: config/verifier
    maxUsersPerRoom: 8
    maxChatBytes: 4096

    # Let members create rooms from the client. Created rooms are kept in
    # clientRoomsFile (JSON) so they survive a restart; server.yaml stays
    # the place for rooms you want to define by hand.
    allowClientRooms: true
    maxRooms: 32
    clientRoomsFile: config/rooms.json

    tls:
      enabled: true
      keyStore: config/server.p12
      storePassword: changeit
      alias: kotlin-chat

    # Removing TLS is for local development only: clients connecting over plain
    # ws:// cannot authenticate the server, so the password exchange is exposed.

    rooms:
      - id: lobby
        name: Lobby
        topic: Say hello
        voice: true
      - id: dev
        name: Dev
        topic: Implementation talk
        voice: true

    # Optional TURN relay for WebRTC (voice, screen share, file fallback).
    # turn:
    #   urls: ["turn:example.org:3478?transport=udp"]
    #   secret: shared-secret-from-coturn
    #   ttlSeconds: 3600
""".trimIndent()
