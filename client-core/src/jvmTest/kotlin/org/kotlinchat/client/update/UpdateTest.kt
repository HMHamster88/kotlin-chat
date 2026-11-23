package org.kotlinchat.client.update

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `versions compare numerically, not as strings`() {
        assertTrue(compareVersions("0.10.0", "0.9.9") > 0)
        assertTrue(compareVersions("1.0.0", "0.99.99") > 0)
        assertEquals(0, compareVersions("0.2.1", "0.2.1"))
        assertTrue(compareVersions("0.2.1", "0.2.2") < 0)
    }

    @Test
    fun `short and pre-release versions are tolerated`() {
        assertEquals(0, compareVersions("1.2", "1.2.0"))
        assertTrue(compareVersions("0.3.0-beta1", "0.2.9") > 0)
    }

    @Test
    fun `manifest selects the asset for the platform`() {
        val manifest = json.decodeFromString<UpdateManifest>(
            """
            {
              "schema": 1,
              "version": "0.3.0",
              "notesUrl": "https://example.test/notes",
              "desktop": {
                "windows": {"url": "https://example.test/win.zip", "sha256": "aa", "size": 10}
              },
              "android": {"url": "https://example.test/app.apk", "sha256": "bb", "size": 20},
              "futureField": {"ignored": true}
            }
            """.trimIndent(),
        )

        assertEquals("0.3.0", manifest.version)
        assertEquals("https://example.test/win.zip", manifest.assetFor(UpdatePlatform.DESKTOP_WINDOWS)!!.url)
        assertEquals("https://example.test/app.apk", manifest.assetFor(UpdatePlatform.ANDROID)!!.url)
        assertEquals("https://example.test/notes", manifest.notesUrl)
    }

    @Test
    fun `a manifest without the current platform has no asset`() {
        val manifest = json.decodeFromString<UpdateManifest>(
            """{"schema": 1, "version": "0.3.0", "desktop": {}}""",
        )
        assertNull(manifest.assetFor(UpdatePlatform.DESKTOP_WINDOWS))
        assertNull(manifest.assetFor(UpdatePlatform.ANDROID))
    }
}
