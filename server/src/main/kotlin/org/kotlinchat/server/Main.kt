package org.kotlinchat.server

import io.ktor.server.engine.EngineSSLConnectorBuilder
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.kotlinchat.server.auth.PasswordVerifier
import org.kotlinchat.server.auth.VerifierFile
import org.kotlinchat.server.config.ServerConfig
import org.kotlinchat.server.core.ChatServer
import org.kotlinchat.server.net.Certificates
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private val defaultConfigPath: Path = Path.of("config", "server.yaml")

fun main(args: Array<String>) {
    val command = args.firstOrNull()?.takeIf { !it.startsWith("--") } ?: "serve"
    val options = parseOptions(
        if (args.firstOrNull()?.startsWith("--") == true) args.toList() else args.drop(1),
    )

    when (command) {
        "init" -> initServer(options)
        "hash-password" -> hashPassword(options)
        "fingerprint" -> printFingerprint(options)
        "serve" -> serve(options)
        "help", "-h", "--help" -> printUsage()
        else -> {
            System.err.println("unknown command: $command")
            printUsage()
            exitProcess(1)
        }
    }
}

// ---------------------------------------------------------------------------- commands

private fun initServer(options: Map<String, String>) {
    val configPath = options["config"]?.let(Path::of) ?: defaultConfigPath
    if (!Files.exists(configPath)) {
        ServerConfig.writeDefault(configPath)
        println("wrote $configPath")
    }
    val config = ServerConfig.load(configPath)

    val verifierPath = Path.of(config.verifierFile)
    if (!Files.exists(verifierPath)) {
        val password = options["password"] ?: readPassword("Server password: ")
        VerifierFile.save(verifierPath, PasswordVerifier.create(password))
        println("wrote $verifierPath")
    } else {
        println("kept existing $verifierPath")
    }

    if (config.tls.enabled) {
        val keyStorePath = Path.of(config.tls.keyStore)
        val storePassword = config.tls.storePassword.toCharArray()
        if (!Files.exists(keyStorePath)) {
            val keyStore = Certificates.createKeyStore(
                alias = config.tls.alias,
                password = storePassword,
                commonName = config.serverName,
                subjectAltNames = listOfNotNull(
                    config.serverName,
                    "localhost",
                    config.host.takeIf { it != "0.0.0.0" && it != "::" },
                ),
            )
            Certificates.store(keyStore, keyStorePath, storePassword)
            println("wrote $keyStorePath")
        }
        val keyStore = Certificates.loadKeyStore(keyStorePath, storePassword)
        println("server fingerprint: ${Certificates.fingerprint(keyStore, config.tls.alias)}")
        println("Clients pin this fingerprint on first connect; verify it out of band.")
    }

    println("next: kotlin-chat-server serve --config=$configPath")
}

private fun hashPassword(options: Map<String, String>) {
    val configPath = options["config"]?.let(Path::of) ?: defaultConfigPath
    val config = ServerConfig.load(configPath)
    val password = options["password"] ?: readPassword("New server password: ")
    val verifierPath = Path.of(config.verifierFile)
    VerifierFile.save(verifierPath, PasswordVerifier.create(password))
    println("wrote $verifierPath (all sessions will need the new password)")
}

private fun printFingerprint(options: Map<String, String>) {
    val config = ServerConfig.load(options["config"]?.let(Path::of) ?: defaultConfigPath)
    val keyStore = Certificates.loadKeyStore(
        Path.of(config.tls.keyStore),
        config.tls.storePassword.toCharArray(),
    )
    println(Certificates.fingerprint(keyStore, config.tls.alias))
}

private fun serve(options: Map<String, String>) {
    val configPath = options["config"]?.let(Path::of) ?: defaultConfigPath
    val config = ServerConfig.load(configPath)
    val verifier = VerifierFile.load(Path.of(config.verifierFile))
    val chat = ChatServer(config, verifier)

    println(
        "kotlin-chat '${config.serverName}' listening on ${config.host}:${config.port} " +
            "(tls=${config.tls.enabled}, rooms=${config.rooms.joinToString { it.id }})",
    )

    val server = if (config.tls.enabled) {
        val keyStorePassword = config.tls.storePassword.toCharArray()
        val keyStore = Certificates.loadKeyStore(Path.of(config.tls.keyStore), keyStorePassword)
        val connector = EngineSSLConnectorBuilder(
            keyStore,
            config.tls.alias,
            { keyStorePassword },
            { keyStorePassword },
        ).apply {
            host = config.host
            port = config.port
        }
        println("fingerprint: ${Certificates.fingerprint(keyStore, config.tls.alias)}")
        embeddedServer(
            factory = Netty,
            environment = applicationEnvironment(),
            configure = { connectors.add(connector) },
            module = { chat.install(this) },
        )
    } else {
        embeddedServer(Netty, port = config.port, host = config.host, module = { chat.install(this) })
    }

    server.start(wait = true)
}

// ---------------------------------------------------------------------------- helpers

private fun parseOptions(args: List<String>): Map<String, String> {
    val options = mutableMapOf<String, String>()
    var index = 0
    while (index < args.size) {
        val arg = args[index]
        if (!arg.startsWith("--")) {
            index++
            continue
        }
        val equals = arg.indexOf('=')
        if (equals > 0) {
            options[arg.substring(2, equals)] = arg.substring(equals + 1)
            index++
        } else {
            options[arg.substring(2)] = args.getOrNull(index + 1).orEmpty()
            index += 2
        }
    }
    return options
}

private fun readPassword(prompt: String): String {
    val console = System.console()
    if (console != null) {
        val chars = console.readPassword("%s", prompt) ?: return ""
        return String(chars)
    }
    print(prompt)
    System.out.flush()
    val line = readlnOrNull()?.trim().orEmpty()
    require(line.isNotEmpty()) { "password must not be empty" }
    return line
}

private fun printUsage() {
    println(
        """
        kotlin-chat-server <command> [options]

        commands:
          init             create config, password verifier and TLS keystore
          hash-password    replace the server password verifier
          fingerprint      print the TLS certificate fingerprint
          serve            run the server (default)

        options:
          --config <path>    config file (default: config/server.toml)
          --password <pw>    non-interactive password for init/hash-password
        """.trimIndent(),
    )
}
