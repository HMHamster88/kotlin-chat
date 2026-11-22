package org.kotlinchat.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EnvelopeTest {

    @Test
    fun `round trips an envelope with a typed payload`() {
        val hello = Hello(name = "alice", pubkey = "AAAA")
        val envelope = Envelope.of(MessageType.HELLO, id = "c-1", payload = payloadOf(hello))

        val decoded = Envelope.decode(envelope.encode())

        assertEquals(Protocol.VERSION, decoded.v)
        assertEquals(MessageType.HELLO, decoded.type)
        assertEquals("c-1", decoded.id)
        assertNull(decoded.replyTo)
        assertEquals(hello, decoded.decodePayload<Hello>())
    }

    @Test
    fun `keeps unknown fields out of the payload contract`() {
        val text = """{"v":1,"type":"ping","payload":{"ts":7}}"""
        val envelope = Envelope.decode(text)
        assertEquals(7L, envelope.decodePayload<Ping>().ts)
    }

    @Test
    fun `encodes error codes using their wire names`() {
        val payload = payloadOf(ErrorPayload(ErrorCode.ROOM_FULL, "room at capacity", fatal = false))
        assertEquals("E_ROOM_FULL", payload["code"]?.toString()?.trim('"'))
    }
}
