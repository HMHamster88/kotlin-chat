package org.kotlinchat.client

/**
 * Turns a connection exception into something a user can act on. Platform-specific because the
 * exception types and messages differ per JVM implementation.
 */
internal expect fun classifyConnectionFailure(t: Throwable, endpoint: ServerEndpoint): ConnectionFailure
