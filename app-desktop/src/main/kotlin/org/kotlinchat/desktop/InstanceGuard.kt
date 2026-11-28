package org.kotlinchat.desktop

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single-instance guard and activation channel that deliberately uses no lock file.
 *
 * The first process binds one loopback port from [DEFAULT_PORTS]; that bound socket is the
 * exclusive lock. A second process scans the same range, verifies our greeting/token, sends
 * `SHOW`, and exits. Ports used by unrelated programs are skipped, and a crashed process releases
 * its port with the process, so there is nothing to clean up.
 */
class InstanceGuard private constructor(
    private val server: ServerSocket,
    private val acceptThread: Thread,
    private val counter: AtomicInteger,
    private val flow: MutableStateFlow<Int>,
) : AutoCloseable {

    /** Increments every time another launch asks this instance to show its window. */
    val activations: StateFlow<Int> = flow.asStateFlow()

    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        runCatching { acceptThread.join(500) }
    }

    sealed interface Result {
        data class Started(val guard: InstanceGuard) : Result
        data object AlreadyRunning : Result
        data class Failed(val message: String) : Result
    }

    companion object {
        const val DEFAULT_PORT_START = 38100
        const val DEFAULT_PORT_COUNT = 40

        private val DEFAULT_PORTS = DEFAULT_PORT_START until DEFAULT_PORT_START + DEFAULT_PORT_COUNT
        private const val HANDSHAKE_PREFIX = "KC1"
        private const val SHOW = "SHOW"
        private val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")

        /**
         * Stable per-user marker. Two users on the same machine therefore do not activate each
         * other's instance even if they happen to use the same candidate port.
         */
        fun defaultToken(): String {
            val user = System.getProperty("user.name").orEmpty()
            val home = System.getProperty("user.home").orEmpty()
            return Integer.toHexString("$user|$home".hashCode())
        }

        fun acquire(
            token: String = defaultToken(),
            ports: IntRange = DEFAULT_PORTS,
        ): Result {
            require(!ports.isEmpty()) { "at least one candidate port is required" }
            val ordered = candidatePorts(ports, token)

            // An earlier launch may own a port that is not the first free one: a busy port at
            // start-up can become free later. Look for our greeting on every candidate before
            // claiming one, otherwise a second launch could bind the freed port and run twice.
            for (port in ordered) {
                if (tryActivate(port, token)) return Result.AlreadyRunning
            }

            for (port in ordered) {
                val server = runCatching {
                    ServerSocket().apply {
                        bind(InetSocketAddress(LOOPBACK, port), 8)
                    }
                }.getOrNull()
                if (server != null) {
                    return Result.Started(start(server, token))
                }
                // Another launch may have bound this port between the scan and our bind.
                if (tryActivate(port, token)) return Result.AlreadyRunning
            }
            return Result.Failed(
                "Kotlin Chat could not start: no free single-instance port in " +
                    "${ports.first}-${ports.last}. Another program may be using them.",
            )
        }

        private fun start(server: ServerSocket, token: String): InstanceGuard {
            val counter = AtomicInteger(0)
            val flow = MutableStateFlow(0)
            val thread = Thread(
                {
                    while (!server.isClosed) {
                        val client = runCatching { server.accept() }.getOrNull() ?: break
                        runCatching {
                            client.use { socket ->
                                socket.soTimeout = 1_000
                                val writer = socket.getOutputStream().bufferedWriter()
                                writer.write("$HANDSHAKE_PREFIX $token\n")
                                writer.flush()
                                if (socket.getInputStream().bufferedReader().readLine() == SHOW) {
                                    flow.value = counter.incrementAndGet()
                                }
                            }
                        }
                    }
                },
                "kotlin-chat-instance",
            ).apply {
                isDaemon = true
                start()
            }
            return InstanceGuard(server, thread, counter, flow)
        }

        private fun tryActivate(port: Int, token: String): Boolean {
            val socket = Socket()
            return try {
                socket.connect(InetSocketAddress(LOOPBACK, port), 250)
                socket.soTimeout = 500
                val reader = socket.getInputStream().bufferedReader()
                if (reader.readLine() != "$HANDSHAKE_PREFIX $token") return false
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write("$SHOW\n")
                writer.flush()
                true
            } catch (t: Throwable) {
                false
            } finally {
                runCatching { socket.close() }
            }
        }

        private fun orderedPorts(ports: IntRange, token: String): List<Int> {
            val all = ports.toList()
            val offset = (token.hashCode() and Int.MAX_VALUE) % all.size
            return all.drop(offset) + all.take(offset)
        }

        /** The order in which [acquire] probes ports; internal so tests can pick a target port. */
        internal fun candidatePorts(ports: IntRange, token: String): List<Int> = orderedPorts(ports, token)
    }
}
