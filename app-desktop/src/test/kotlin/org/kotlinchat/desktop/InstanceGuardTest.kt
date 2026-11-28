package org.kotlinchat.desktop

import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class InstanceGuardTest {

    @Test
    fun `a second acquire activates the first instance and exits`(): Unit = runBlocking {
        val base = Random.nextInt(40_000, 47_000)
        val ports = base until base + 10
        val token = "test-${Random.nextLong()}"

        val first = InstanceGuard.acquire(token, ports)
        assertTrue(first is InstanceGuard.Result.Started, "first acquire: $first")
        val guard = assertIs<InstanceGuard.Result.Started>(first).guard
        try {
            val second = InstanceGuard.acquire(token, ports)
            assertTrue(second is InstanceGuard.Result.AlreadyRunning, "second acquire: $second")
            withTimeout(5_000) { guard.activations.first { it > 0 } }
        } finally {
            guard.close()
        }
    }

    @Test
    fun `a foreign service is not mistaken for an existing instance`(): Unit = runBlocking {
        val token = "test-${Random.nextLong()}"

        // Occupy one candidate with a plain socket that does not speak our greeting.
        bindForeign().use { foreign ->
            val result = InstanceGuard.acquire(token, foreign.localPort..foreign.localPort)
            assertTrue(result is InstanceGuard.Result.Failed, "foreign port: $result")
        }
    }

    @Test
    fun `a port that became free after startup does not start a duplicate`(): Unit = runBlocking {
        val ports = freePair()
        val token = "test-${Random.nextLong()}"
        val firstCandidate = InstanceGuard.candidatePorts(ports, token).first()

        // Start the guard while its first candidate is held by another service, so it binds the
        // other one. When that foreign service later disappears, a simple "bind the first free
        // port" scan would start a second instance instead of finding the first.
        val started = ServerSocket().use { foreign ->
            foreign.bind(InetSocketAddress("127.0.0.1", firstCandidate), 1)
            InstanceGuard.acquire(token, ports)
        }
        val guard = assertIs<InstanceGuard.Result.Started>(started).guard
        try {
            val second = InstanceGuard.acquire(token, ports)
            assertTrue(second is InstanceGuard.Result.AlreadyRunning, "second acquire: $second")
        } finally {
            guard.close()
        }
    }

    /** A bound loopback socket on a currently free port. */
    private fun bindForeign(): ServerSocket {
        repeat(50) {
            val port = Random.nextInt(40_000, 47_000)
            val socket = ServerSocket()
            val bound = runCatching {
                socket.bind(InetSocketAddress("127.0.0.1", port), 1)
            }.isSuccess
            if (bound) return socket
            runCatching { socket.close() }
        }
        error("could not bind a free loopback port")
    }

    /** A range of two adjacent loopback ports that are currently free. */
    private fun freePair(): IntRange {
        repeat(50) {
            val base = Random.nextInt(40_000, 47_000)
            val free = listOf(base, base + 1).all { port ->
                runCatching {
                    ServerSocket().use { socket ->
                        socket.bind(InetSocketAddress("127.0.0.1", port), 1)
                    }
                }.isSuccess
            }
            if (free) return base..(base + 1)
        }
        error("could not find two free loopback ports")
    }
}
