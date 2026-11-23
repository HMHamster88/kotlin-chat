package org.kotlinchat.client.settings

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsStoreTest {

    private fun tempPath() = Files.createTempDirectory("kotlin-chat-settings").resolve("settings.json")

    @Test
    fun `settings round trip and unknown fields are ignored`() {
        val path = tempPath()
        val store = JsonFileSettingsStore(path)
        val settings = ClientSettings(
            host = "chat.example",
            port = 9443,
            tls = false,
            name = "alice",
            inputDeviceId = "mic-1",
            outputDeviceId = null,
            rememberPassword = true,
            autoConnect = true,
        )
        store.save(settings)
        assertEquals(settings, store.load())

        // A newer build may add fields; an older one must still read the file.
        val withExtra = """
            {"schema":1,"host":"chat.example","port":9443,"tls":false,"name":"alice",
             "rememberPassword":true,"autoConnect":true,"future":"ignored"}
        """.trimIndent()
        Files.write(path, withExtra.toByteArray())
        assertEquals("alice", store.load().name)
        assertEquals(9443, store.load().port)
    }

    @Test
    fun `a corrupt file falls back to defaults`() {
        val path = tempPath()
        Files.createDirectories(path.parent)
        Files.write(path, "{ not json".toByteArray())
        assertEquals(ClientSettings(), JsonFileSettingsStore(path).load())
    }

    @Test
    fun `auto connect requires a remembered password`() {
        assertFalse(ClientSettings(autoConnect = true).canAutoConnect)
        assertTrue(ClientSettings(rememberPassword = true, autoConnect = true).canAutoConnect)
    }

    @Test
    fun `turning remembering off deletes the stored secret`() {
        val settings = FakeSettingsStore()
        val secret = FakeSecretStore()
        val memory = ConnectionMemory(settings, secret)

        memory.save(ClientSettings(host = "a", rememberPassword = true), "hunter2")
        assertEquals("hunter2", secret.value)
        assertTrue(settings.load().rememberPassword)

        memory.save(settings.load().copy(rememberPassword = false), "hunter2")
        assertNull(secret.value)
        assertFalse(settings.load().rememberPassword)
    }

    @Test
    fun `a platform without a key store never stores a password`() {
        val settings = FakeSettingsStore()
        val secret = FakeSecretStore(available = false)
        val memory = ConnectionMemory(settings, secret)

        assertFalse(memory.canRememberPassword)
        memory.save(ClientSettings(host = "a", rememberPassword = true, autoConnect = true), "hunter2")
        assertNull(secret.value)
        assertFalse(settings.load().rememberPassword)
        assertFalse(settings.load().canAutoConnect)
        assertNull(memory.load().password)
    }

    @Test
    fun `loading only returns a password when remembering is on`() {
        val settings = FakeSettingsStore()
        val secret = FakeSecretStore().apply { value = "hunter2" }

        settings.save(ClientSettings(rememberPassword = true))
        assertEquals("hunter2", ConnectionMemory(settings, secret).load().password)

        settings.save(ClientSettings(rememberPassword = false))
        assertNull(ConnectionMemory(settings, secret).load().password)
    }

    @Test
    fun `clearing forgets the server and the secret`() {
        val settings = FakeSettingsStore()
        val secret = FakeSecretStore()
        val memory = ConnectionMemory(settings, secret)
        memory.save(ClientSettings(host = "chat.example", rememberPassword = true), "hunter2")

        memory.clear()

        assertEquals(ClientSettings(), settings.load())
        assertNull(secret.value)
    }

    private class FakeSettingsStore : ClientSettingsStore {
        private var stored = ClientSettings()
        override fun load(): ClientSettings = stored
        override fun save(settings: ClientSettings) {
            stored = settings
        }
    }

    private class FakeSecretStore(override val available: Boolean = true) : SecretStore {
        var value: String? = null
        override fun read(): String? = value
        override fun write(secret: String) {
            value = secret
        }

        override fun clear() {
            value = null
        }
    }
}
