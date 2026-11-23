package org.kotlinchat.client.update

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.kotlinchat.client.newHttpClient

/** Where releases are announced: a CDN-backed redirect, no GitHub API quota or token involved. */
const val DEFAULT_UPDATE_MANIFEST_URL =
    "https://github.com/HMHamster88/kotlin-chat/releases/latest/download/latest.json"

/**
 * Reads [DEFAULT_UPDATE_MANIFEST_URL] and decides whether this build is out of date.
 *
 * All failures are silent by design: an offline laptop, a proxy that eats the request or a
 * malformed manifest must never turn into a startup error. The caller only sees "no update".
 */
class UpdateChecker(
    private val currentVersion: String,
    private val platform: UpdatePlatform,
    private val manifestUrl: String = DEFAULT_UPDATE_MANIFEST_URL,
    private val http: HttpClient = newHttpClient(pin = null, serverName = null),
) : AutoCloseable {

    private val json = Json { ignoreUnknownKeys = true }

    /** Null when up to date, offline, skipped by the manifest or carrying no asset for us. */
    suspend fun check(): UpdateInfo? {
        val manifest = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                val response = http.get(manifestUrl)
                if (!response.status.isSuccess()) return@runCatching null
                json.decodeFromString<UpdateManifest>(response.bodyAsText())
            }.getOrNull()
        } ?: return null

        if (compareVersions(manifest.version, currentVersion) <= 0) return null
        val asset = manifest.assetFor(platform) ?: return null
        return UpdateInfo(version = manifest.version, asset = asset, notesUrl = manifest.notesUrl)
    }

    override fun close() {
        runCatching { http.close() }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 10_000L
    }
}
