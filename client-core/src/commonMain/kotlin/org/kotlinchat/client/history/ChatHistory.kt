package org.kotlinchat.client.history

import org.kotlinchat.protocol.ChatMessage

/**
 * Local chat history. Messages are stored per server and room on this device; the server never
 * sees them and nothing is replayed from it. Implementations must tolerate a missing or damaged
 * file by returning what they can read.
 */
interface ChatHistoryStore {
    /** The newest [limit] messages for this room, oldest first. */
    fun load(serverKey: String, roomId: String, limit: Int): List<ChatMessage>

    fun append(serverKey: String, roomId: String, message: ChatMessage)

    /** Deletes one room's file, or every room for this server when [roomId] is null. */
    fun clear(serverKey: String, roomId: String? = null)
}

/** Used where no application storage is available yet (for example before Android's context). */
internal object NoOpChatHistoryStore : ChatHistoryStore {
    override fun load(serverKey: String, roomId: String, limit: Int): List<ChatMessage> = emptyList()
    override fun append(serverKey: String, roomId: String, message: ChatMessage) = Unit
    override fun clear(serverKey: String, roomId: String?) = Unit
}

internal expect fun platformChatHistoryStore(): ChatHistoryStore
