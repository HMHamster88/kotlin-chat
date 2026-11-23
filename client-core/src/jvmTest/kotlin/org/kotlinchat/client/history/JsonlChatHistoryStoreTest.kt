package org.kotlinchat.client.history

import org.kotlinchat.protocol.ChatMessage
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonlChatHistoryStoreTest {

    private fun message(id: String, room: String, ts: Long, text: String = "m$id") = ChatMessage(
        messageId = id,
        roomId = room,
        from = "s_alice",
        name = "alice",
        text = text,
        ts = ts,
    )

    private fun store() = JsonlChatHistoryStore(createTempDirectory("chat-history"))

    @Test
    fun `messages are separated by server and room and returned newest last`() {
        val store = store()
        repeat(4) { index ->
            store.append("server-a", "lobby", message("a$index", "lobby", index.toLong()))
        }
        store.append("server-b", "lobby", message("b0", "lobby", 100))
        store.append("server-a", "dev", message("d0", "dev", 200))

        assertEquals(listOf("a1", "a2", "a3"), store.load("server-a", "lobby", 3).map { it.messageId })
        assertEquals(listOf("b0"), store.load("server-b", "lobby", 10).map { it.messageId })
        assertEquals(listOf("d0"), store.load("server-a", "dev", 10).map { it.messageId })
        assertTrue(store.load("server-c", "lobby", 10).isEmpty())
    }

    @Test
    fun `loading the same message twice does not duplicate it`() {
        val store = store()
        store.append("s", "lobby", message("same", "lobby", 1))
        // A repeated delivery can happen around a reconnect; the file may contain it twice.
        val file = Files.walk(store.baseDir).filter { it.toString().endsWith(".jsonl") }.findFirst().get()
        val line = Files.readAllLines(file).first()
        Files.writeString(file, line + "\n", java.nio.file.StandardOpenOption.APPEND)

        assertEquals(listOf("same"), store.load("s", "lobby", 10).map { it.messageId })
    }

    @Test
    fun `a damaged line is skipped without losing the rest`() {
        val store = store()
        store.append("s", "lobby", message("one", "lobby", 1))
        store.append("s", "lobby", message("two", "lobby", 2))
        val file = Files.walk(store.baseDir).filter { it.toString().endsWith(".jsonl") }.findFirst().get()
        Files.writeString(file, "{ not json\n", java.nio.file.StandardOpenOption.APPEND)

        assertEquals(listOf("one", "two"), store.load("s", "lobby", 10).map { it.messageId })
    }

    @Test
    fun `loading compacts a room to the requested window`() {
        val store = store()
        repeat(25) { index ->
            store.append("s", "lobby", message("m$index", "lobby", index.toLong()))
        }
        val loaded = store.load("s", "lobby", 10)
        assertEquals(10, loaded.size)
        assertEquals("m15", loaded.first().messageId)
        assertEquals("m24", loaded.last().messageId)

        val file = Files.walk(store.baseDir).filter { it.toString().endsWith(".jsonl") }.findFirst().get()
        assertEquals(10, Files.readAllLines(file).size)
    }

    @Test
    fun `clear removes one room or the whole server`() {
        val store = store()
        store.append("s", "lobby", message("l", "lobby", 1))
        store.append("s", "dev", message("d", "dev", 2))

        store.clear("s", "lobby")
        assertTrue(store.load("s", "lobby", 10).isEmpty())
        assertEquals(1, store.load("s", "dev", 10).size)

        store.clear("s")
        assertTrue(store.load("s", "dev", 10).isEmpty())
    }


}
