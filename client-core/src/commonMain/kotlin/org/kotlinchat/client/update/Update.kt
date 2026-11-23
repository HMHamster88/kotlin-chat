package org.kotlinchat.client.update

import kotlinx.serialization.Serializable

/**
 * `latest.json`, published next to the release assets. One document for every client and
 * platform: no GitHub API call, so no rate limit, no token, and a CDN that does the work.
 */
@Serializable
data class UpdateManifest(
    val schema: Int = 1,
    val version: String,
    val released: String? = null,
    val notesUrl: String? = null,
    val desktop: DesktopAssets? = null,
    val android: UpdateAsset? = null,
)

@Serializable
data class DesktopAssets(
    val windows: UpdateAsset? = null,
)

@Serializable
data class UpdateAsset(
    val url: String,
    val sha256: String,
    val size: Long = 0,
)

enum class UpdatePlatform { DESKTOP_WINDOWS, ANDROID }

/** What the UI needs to offer an update; never contains the payload itself. */
data class UpdateInfo(
    val version: String,
    val asset: UpdateAsset,
    val notesUrl: String?,
)

/** Raised for a download that does not match the manifest; the file is deleted before this. */
class UpdateException(message: String) : Exception(message)

/**
 * Compares dotted versions numerically, so `0.10.0` is newer than `0.9.9`. Anything after a `-`
 * (a pre-release suffix) is ignored: only stable releases are published to the manifest.
 */
fun compareVersions(a: String, b: String): Int {
    fun parts(value: String) = value.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val left = parts(a)
    val right = parts(b)
    for (index in 0 until maxOf(left.size, right.size)) {
        val difference = (left.getOrNull(index) ?: 0) - (right.getOrNull(index) ?: 0)
        if (difference != 0) return if (difference > 0) 1 else -1
    }
    return 0
}

/** The asset this client would install, if the manifest carries one for it. */
fun UpdateManifest.assetFor(platform: UpdatePlatform): UpdateAsset? = when (platform) {
    UpdatePlatform.DESKTOP_WINDOWS -> desktop?.windows
    UpdatePlatform.ANDROID -> android
}
