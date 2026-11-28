package org.kotlinchat.client.notification

import org.kotlinchat.client.ChatSnapshot
import org.kotlinchat.client.settings.ClientSettings
import org.kotlinchat.protocol.ChatMessage

/** Title and body for one message notification, ready for the platform to display. */
data class MessageNotification(
    val title: String,
    val body: String,
)

/**
 * Decides whether a live chat line deserves a notification and formats its text.
 *
 * The platforms own the delivery (tray balloon on desktop, notification channel on Android);
 * keeping the decision here means both clients skip the same cases: own echo, another room,
 * history, and the room the user is already looking at.
 */
object MessageNotifications {

    private const val PREVIEW_LIMIT = 160

    fun build(
        snapshot: ChatSnapshot,
        message: ChatMessage,
        settings: ClientSettings,
        appActive: Boolean,
    ): MessageNotification? {
        if (!settings.notifyOnMessage || appActive) return null
        if (snapshot.room == null || snapshot.roomId != message.roomId) return null
        if (snapshot.self?.sessionId == message.from) return null
        return MessageNotification(
            title = message.name.trim().ifBlank { "Kotlin Chat" },
            body = body(message.text, settings.notifyPreview),
        )
    }

    /** One-line preview; a disabled preview still says a message arrived. */
    fun body(text: String, showPreview: Boolean): String {
        if (!showPreview) return "New message"
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return "New message"
        if (clean.length <= PREVIEW_LIMIT) return clean
        var end = PREVIEW_LIMIT
        // Never cut a surrogate pair in half (emoji, some scripts).
        if (clean[end - 1].isHighSurrogate()) end--
        return clean.substring(0, end).trimEnd() + "…"
    }
}
