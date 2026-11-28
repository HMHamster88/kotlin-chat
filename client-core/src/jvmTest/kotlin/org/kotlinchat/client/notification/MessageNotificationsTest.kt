package org.kotlinchat.client.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.client.ConnectionState
import org.kotlinchat.client.settings.ClientSettings
import org.kotlinchat.protocol.ChatMessage
import org.kotlinchat.protocol.Member
import org.kotlinchat.protocol.RoomState
import org.kotlinchat.protocol.RoomSummary

class MessageNotificationsTest {

    private fun message(
        id: String = "m1",
        room: String = "lobby",
        from: String = "u2",
        name: String = "Alice",
        text: String = "hello",
    ) = ChatMessage(
        messageId = id,
        roomId = room,
        from = from,
        name = name,
        text = text,
        ts = 1_000,
    )

    private fun snapshot(roomId: String? = "lobby") = ChatSnapshot(
        connection = ConnectionState.Ready,
        room = roomId?.let {
            RoomState(
                room = RoomSummary(id = it, name = "Lobby"),
                self = Member(sessionId = "u1", name = "Me"),
                members = emptyList(),
            )
        },
        self = Member(sessionId = "u1", name = "Me"),
    )

    @Test
    fun `a message from someone else notifies when the app is inactive`() {
        val result = MessageNotifications.build(
            snapshot = snapshot(),
            message = message(),
            settings = ClientSettings(),
            appActive = false,
        )
        val notification = assertNotNull(result)
        assertEquals("Alice", notification.title)
        assertEquals("hello", notification.body)
    }

    @Test
    fun `own messages are skipped`() {
        assertNull(
            MessageNotifications.build(
                snapshot = snapshot(),
                message = message(from = "u1", name = "Me"),
                settings = ClientSettings(),
                appActive = false,
            ),
        )
    }

    @Test
    fun `messages for another room are skipped`() {
        assertNull(
            MessageNotifications.build(
                snapshot = snapshot(roomId = "dev"),
                message = message(room = "lobby"),
                settings = ClientSettings(),
                appActive = false,
            ),
        )
    }

    @Test
    fun `a roomless client is skipped`() {
        assertNull(
            MessageNotifications.build(
                snapshot = snapshot(roomId = null),
                message = message(),
                settings = ClientSettings(),
                appActive = false,
            ),
        )
    }

    @Test
    fun `an active app is skipped`() {
        assertNull(
            MessageNotifications.build(
                snapshot = snapshot(),
                message = message(),
                settings = ClientSettings(),
                appActive = true,
            ),
        )
    }

    @Test
    fun `the setting can turn notifications off`() {
        assertNull(
            MessageNotifications.build(
                snapshot = snapshot(),
                message = message(),
                settings = ClientSettings(notifyOnMessage = false),
                appActive = false,
            ),
        )
    }

    @Test
    fun `turning previews off keeps the sender but hides the text`() {
        val notification = assertNotNull(
            MessageNotifications.build(
                snapshot = snapshot(),
                message = message(text = "secret plans"),
                settings = ClientSettings(notifyPreview = false),
                appActive = false,
            ),
        )
        assertEquals("Alice", notification.title)
        assertEquals("New message", notification.body)
    }

    @Test
    fun `previews collapse whitespace and truncate safely`() {
        assertEquals("hello there", MessageNotifications.body("hello\n\n  there", true))

        val long = "a".repeat(300)
        val preview = MessageNotifications.body(long, true)
        assertTrue(preview.length <= 161, "preview length ${preview.length}")
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun `a blank message still produces a generic body`() {
        assertEquals("New message", MessageNotifications.body("   \n ", true))
        assertEquals("New message", MessageNotifications.body("", false))
    }
}
