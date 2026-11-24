package org.kotlinchat.media

import dev.onvoid.webrtc.media.FourCC
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoBufferConverter
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewConversionTest {

    /**
     * The old conversion used `FourCC.RGBA`, which webrtc-java writes as A,B,G,R: red stayed
     * red, but green turned magenta and blue turned yellow. Green and blue distinguish the
     * byte orders, so both are checked here.
     */
    @Test
    fun `preview conversion writes RGBA bytes, not ABGR`() {
        assertColor(y = 145, u = 54, v = 34, red = 0, green = 255, blue = 0)
        assertColor(y = 41, u = 240, v = 110, red = 0, green = 0, blue = 255)
    }

    @Test
    fun `I420 copy is tightly packed for the GL uploader`() {
        val buffer = NativeI420Buffer.allocate(4, 4)
        try {
            val y = buffer.dataY
            val u = buffer.dataU
            val v = buffer.dataV
            for (i in 0 until 16) y.put(i, 10.toByte())
            for (i in 0 until 4) {
                u.put(i, 20.toByte())
                v.put(i, 30.toByte())
            }
            val out = ByteArray(16 + 4 + 4)
            VideoBufferConverter.convertFromI420(buffer, out, FourCC.I420)
            assertEquals(10, out[0].toInt() and 0xff)
            assertEquals(20, out[16].toInt() and 0xff)
            assertEquals(30, out[20].toInt() and 0xff)
        } finally {
            buffer.release()
        }
    }

    private fun assertColor(y: Int, u: Int, v: Int, red: Int, green: Int, blue: Int) {
        val buffer = NativeI420Buffer.allocate(4, 4)
        try {
            val dataY = buffer.dataY
            val dataU = buffer.dataU
            val dataV = buffer.dataV
            for (i in 0 until 16) dataY.put(i, y.toByte())
            for (i in 0 until 4) {
                dataU.put(i, u.toByte())
                dataV.put(i, v.toByte())
            }

            val rgba = convertI420ToRgba(buffer, 4, 4)
            val actualRed = rgba[0].toInt() and 0xff
            val actualGreen = rgba[1].toInt() and 0xff
            val actualBlue = rgba[2].toInt() and 0xff
            assertTrue(abs(actualRed - red) <= 4, "red=$actualRed")
            assertTrue(abs(actualGreen - green) <= 4, "green=$actualGreen")
            assertTrue(abs(actualBlue - blue) <= 4, "blue=$actualBlue")
            assertEquals(255, rgba[3].toInt() and 0xff, "alpha")
        } finally {
            buffer.release()
        }
    }
}
