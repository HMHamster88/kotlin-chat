package org.kotlinchat.client

import io.ktor.client.HttpClient

/**
 * Creates a client that trusts exactly [pin] (a colon-separated SHA-256 certificate
 * fingerprint) when connecting over TLS. A null pin means "no TLS" and also disables
 * pinning for plain `ws://` endpoints.
 *
 * [serverName] is the host the user typed. It is passed explicitly because the engine
 * otherwise reverse-resolves the address (a LAN IP can become a machine name), and the TLS
 * layer then demands that name in the certificate - which a self-signed server does not have.
 * With the literal host, an IP address finds no IP SAN and is accepted on the pin alone, which
 * is the trust model here.
 */
internal expect fun newHttpClient(pin: String?, serverName: String?): HttpClient
