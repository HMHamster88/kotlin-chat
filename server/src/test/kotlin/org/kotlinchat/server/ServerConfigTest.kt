package org.kotlinchat.server

import org.kotlinchat.server.config.ServerConfig
import org.kotlinchat.server.config.withTurnFromEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServerConfigTest {

    private val base = ServerConfig()

    private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
        val values = mapOf(*pairs)
        return { values[it] }
    }

    @Test
    fun `turn is derived from a host and a secret`() {
        val config = base.withTurnFromEnvironment(
            env("CHAT_TURN_HOST" to "vps.example.org", "CHAT_TURN_SECRET" to "s3cret"),
        )
        val turn = config.turn!!
        assertEquals(
            listOf("turn:vps.example.org:3478?transport=udp", "stun:vps.example.org:3478"),
            turn.urls,
        )
        assertEquals("s3cret", turn.secret)
        assertEquals(3600, turn.ttlSeconds)
    }

    @Test
    fun `explicit urls win over the host`() {
        val config = base.withTurnFromEnvironment(
            env(
                "CHAT_TURN_HOST" to "ignored",
                "CHAT_TURN_URLS" to "turn:one:3479, turn:two:3479?transport=tcp",
                "CHAT_TURN_SECRET" to "s3cret",
                "CHAT_TURN_TTL" to "60",
            ),
        )
        assertEquals(listOf("turn:one:3479", "turn:two:3479?transport=tcp"), config.turn!!.urls)
        assertEquals(60, config.turn!!.ttlSeconds)
    }

    @Test
    fun `a partial environment leaves the file alone`() {
        assertNull(base.withTurnFromEnvironment(env("CHAT_TURN_SECRET" to "s3cret")).turn)
        assertNull(base.withTurnFromEnvironment(env("CHAT_TURN_HOST" to "host")).turn)
        assertNull(base.withTurnFromEnvironment { null }.turn)
    }

    @Test
    fun `a file-level turn block survives when the environment is empty`() {
        val configured = base.copy(
            turn = org.kotlinchat.server.config.TurnConfig(
                urls = listOf("turn:file:3478"),
                secret = "file-secret",
            ),
        )
        assertEquals(configured.turn, configured.withTurnFromEnvironment { null }.turn)
    }
}
