package org.kotlinchat.protocol

/** Protocol-wide constants. See docs/PROTOCOL.md. */
object Protocol {
    const val VERSION = 1

    /** Maximum size of a single control frame. */
    const val MAX_FRAME_BYTES = 64 * 1024
}
