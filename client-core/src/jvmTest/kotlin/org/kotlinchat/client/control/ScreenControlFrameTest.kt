package org.kotlinchat.client.control

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenControlFrameTest {

    @Test
    fun `control frames round trip`() {
        val frames = listOf(
            ControlFrame.Request,
            ControlFrame.Grant(1800),
            ControlFrame.Deny("busy"),
            ControlFrame.Revoke("lease expired"),
            ControlFrame.Cancel,
            ControlFrame.Heartbeat,
            ControlFrame.Move(0, 65535),
            ControlFrame.Move(32768, 1),
            ControlFrame.Button(0, true),
            ControlFrame.Button(2, false),
            ControlFrame.Wheel(-240, false),
            ControlFrame.Wheel(120, true),
            ControlFrame.Key(65, true),
            ControlFrame.Key(524, false),
        )
        frames.forEach { frame ->
            assertEquals(frame, decodeControlFrame(encodeControlFrame(frame)))
        }
    }

    @Test
    fun `malformed control frames are ignored`() {
        assertNull(decodeControlFrame(ByteArray(0)))
        assertNull(decodeControlFrame(byteArrayOf(1, 2, 3)))
        assertNull(decodeControlFrame(encodeControlFrame(ControlFrame.Request).copyOf(5)))
        assertNull(decodeControlFrame(encodeControlFrame(ControlFrame.Grant(30)).copyOf(9)))
        assertNull(decodeControlFrame(encodeControlFrame(ControlFrame.Move(1, 2)) + 0))
        assertNull(decodeControlFrame(ByteArray(1024) { 7 }))
    }

    @Test
    fun `reason strings are bounded`() {
        val frame = ControlFrame.Deny("x".repeat(1000))
        val decoded = decodeControlFrame(encodeControlFrame(frame))
        assertEquals(ControlFrame.Deny::class, decoded!!::class)
        assertEquals(200, (decoded as ControlFrame.Deny).reason.length)
    }
}
