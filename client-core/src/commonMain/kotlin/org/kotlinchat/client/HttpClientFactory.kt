package org.kotlinchat.client

import io.ktor.client.HttpClient

/**
 * Creates a client that trusts exactly [pin] (a colon-separated SHA-256 certificate
 * fingerprint) when connecting over TLS. A null pin means "no TLS" and also disables
 * pinning for plain `ws://` endpoints.
 */
internal expect fun newHttpClient(pin: String?): HttpClient
