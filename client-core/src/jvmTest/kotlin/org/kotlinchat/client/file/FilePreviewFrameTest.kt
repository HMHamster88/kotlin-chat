package org.kotlinchat.client.file

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FilePreviewFrameTest {

    @Test
    fun `round trips a preview and leaves normal chunks untouched`() {
        val payload = ByteArray(512) { (it % 251).toByte() }
        val frame = assertNotNull(encodePreviewFrame("f_0123456789abcdef", payload))

        val decoded = assertNotNull(decodePreviewFrame(frame))
        assertEquals("f_0123456789abcdef", decoded.transferId)
        assertContentEquals(payload, decoded.payload)

        assertNull(decodePreviewFrame(payload))
    }

    @Test
    fun `rejects malformed and oversized preview messages`() {
        assertNull(encodePreviewFrame("", ByteArray(8)))
        assertNull(encodePreviewFrame("f_1", ByteArray(MAX_PREVIEW_BYTES + 1)))
        assertNull(decodePreviewFrame(byteArrayOf(1, 2, 3)))
        assertNull(decodePreviewFrame("not a preview at all".encodeToByteArray()))

        val frame = assertNotNull(encodePreviewFrame("f_1", ByteArray(16) { 7 }))
        assertNull(decodePreviewFrame(frame.copyOf(frame.size - 1)), "truncated payload")
        assertNull(decodePreviewFrame(frame + 1.toByte()), "trailing bytes")
    }
}
