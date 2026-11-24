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
    val serverName: String = "Kotlin Chat",
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
        fun load(path: Path): ServerConfig = load(path, System::getenv)

        /**
         * [env] is injectable so the container-friendly TURN overrides can be tested. Keeping the
         * shared secret out of `server.yaml` matters: the file sits in a volume that gets backed
         * up and copied around, and the same secret also lives in coturn's configuration.
         */
        internal fun load(path: Path, env: (String) -> String?): ServerConfig {
            require(Files.exists(path)) { "config file not found: $path (run `init` first)" }
            val decoded = Yaml.default.decodeFromString(serializer(), Files.readString(path))
            return decoded.withTurnFromEnvironment(env)
        }

        fun writeDefault(path: Path) {
            path.toAbsolutePath().parent?.createDirectories()
            Files.writeString(path, DEFAULT_YAML)
        }
    }
}

/**
 * Reads TURN settings from the environment when the config file has none:
 * `CHAT_TURN_SECRET` plus either `CHAT_TURN_HOST` (from which `turn:`/`stun:` URLs are derived on
 * `CHAT_TURN_PORT`, default 3478) or explicit `CHAT_TURN_URLS` (comma separated).
 */
internal fun ServerConfig.withTurnFromEnvironment(env: (String) -> String?): ServerConfig {
    val secret = env("CHAT_TURN_SECRET")?.trim().orEmpty()
    val host = env("CHAT_TURN_HOST")?.trim().orEmpty()
    val explicit = env("CHAT_TURN_URLS")
        ?.split(',')
        ?.map(String::trim)
        ?.filter { it.isNotEmpty() }
        .orEmpty()

    val urls = explicit.ifEmpty {
        if (host.isEmpty()) {
            emptyList()
        } else {
            val port = env("CHAT_TURN_PORT")?.toIntOrNull() ?: 3478
            listOf("turn:$host:$port?transport=udp", "stun:$host:$port")
        }
    }
    if (urls.isEmpty() || secret.isEmpty()) return this

    return copy(
        turn = TurnConfig(
            urls = urls,
            secret = secret,
            ttlSeconds = env("CHAT_TURN_TTL")?.toIntOrNull() ?: turn?.ttlSeconds ?: 3600,
        ),
    )
}

/** Shipped by `init`; also the reference for every option. */
val DEFAULT_YAML: String = """
    # Kotlin Chat server configuration
    serverName: Kotlin Chat
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
