package org.kotlinchat.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/** Shared JSON configuration. Both sides must use identical settings. */
val ProtocolJson: Json = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    isLenient = false
}

@Serializable
data class Envelope(
    val v: Int = Protocol.VERSION,
    val type: String,
    val id: String? = null,
    val replyTo: String? = null,
    val payload: JsonObject = JsonObject(emptyMap()),
) {
    fun encode(): String = ProtocolJson.encodeToString(serializer(), this)

    companion object {
        fun of(type: String, id: String? = null, payload: JsonObject = JsonObject(emptyMap())): Envelope =
            Envelope(type = type, id = id, payload = payload)

        fun decode(text: String): Envelope = ProtocolJson.decodeFromString(serializer(), text)
    }
}

/** Serializes [value] into an envelope payload object. */
inline fun <reified T> payloadOf(value: T): JsonObject = ProtocolJson.encodeToJsonElement(value).jsonObject

/** Deserializes the envelope payload into [T]. */
inline fun <reified T> Envelope.decodePayload(): T = ProtocolJson.decodeFromJsonElement<T>(payload)
