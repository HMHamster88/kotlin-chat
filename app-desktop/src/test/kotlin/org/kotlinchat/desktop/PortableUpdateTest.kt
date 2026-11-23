package org.kotlinchat.desktop

import com.sun.net.httpserver.HttpServer
import org.kotlinchat.client.update.UpdateAsset
import org.kotlinchat.client.update.UpdateInfo
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortableUpdateTest {

    @Test
    fun `a verified archive is unpacked to the app root`() {
        val root = createTempDirectory("portable-update")
        val archive = zip(
            mapOf(
                "kotlin-chat/kotlin-chat.exe" to byteArrayOf(1, 2, 3),
                "kotlin-chat/app/resources/readme.txt" to "hello".toByteArray(),
            ),
        )
        withServer(archive) { url ->
            val info = info(url, archive)
            val progress = mutableListOf<Long>()
            val app = PortableUpdate(root).downloadAndStage(info) { received, _ -> progress += received }

            assertTrue(Files.isRegularFile(app.toPath().resolve("kotlin-chat.exe")))
            assertTrue(Files.isRegularFile(app.toPath().resolve("app/resources/readme.txt")))
            assertTrue(progress.isNotEmpty())
            assertEquals(archive.size.toLong(), progress.last())
            assertFalse(Files.exists(root.resolve("kotlin-chat-${info.version}.zip")))
        }
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a checksum mismatch deletes the download and installs nothing`() {
        val root = createTempDirectory("portable-update")
        val archive = zip(mapOf("kotlin-chat/kotlin-chat.exe" to byteArrayOf(1, 2, 3)))
        withServer(archive) { url ->
            val base = info(url, archive)
            val info = base.copy(asset = base.asset.copy(sha256 = "00"))
            assertFailsWith<IllegalStateException> {
                PortableUpdate(root).downloadAndStage(info) { _, _ -> }
            }
            assertFalse(Files.exists(root.resolve("stage-${info.version}")))
            assertFalse(Files.exists(root.resolve("kotlin-chat-${info.version}.zip")))
        }
        root.toFile().deleteRecursively()
    }

    @Test
    fun `an entry that escapes the staging directory is rejected`() {
        val root = createTempDirectory("portable-update")
        val archive = zip(
            mapOf(
                "kotlin-chat/kotlin-chat.exe" to byteArrayOf(1, 2, 3),
                "../escaped.txt" to "nope".toByteArray(),
            ),
        )
        withServer(archive) { url ->
            val info = info(url, archive)
            assertFailsWith<IllegalStateException> {
                PortableUpdate(root).downloadAndStage(info) { _, _ -> }
            }
            assertFalse(Files.exists(root.resolve("escaped.txt")))
        }
        root.toFile().deleteRecursively()
    }

    private fun info(url: String, bytes: ByteArray): UpdateInfo = UpdateInfo(
        version = "9.9.9",
        asset = UpdateAsset(url = url, sha256 = sha256(bytes), size = bytes.size.toLong()),
        notesUrl = null,
    )

    private fun withServer(bytes: ByteArray, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/app.zip") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/app.zip")
        } finally {
            server.stop(0)
        }
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
