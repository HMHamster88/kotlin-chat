package org.kotlinchat.protocol

/** Envelope `type` values. */
object MessageType {
    // handshake
    const val HELLO = "hello"
    const val CHALLENGE = "challenge"
    const val AUTH = "auth"
    const val WELCOME = "welcome"

    // rooms
    const val ROOM_LIST = "room.list"
    const val ROOM_LIST_RESULT = "room.list.result"
    const val ROOM_JOIN = "room.join"
    const val ROOM_CREATE = "room.create"
    const val ROOM_LEAVE = "room.leave"
    const val ROOM_LEFT = "room.left"
    const val ROOM_STATE = "room.state"

    // presence
    const val MEMBER_JOINED = "member.joined"
    const val MEMBER_LEFT = "member.left"
    const val MEMBER_UPDATED = "member.updated"
    const val PRESENCE_UPDATE = "presence.update"

    // chat
    const val CHAT_SEND = "chat.send"
    const val CHAT_MESSAGE = "chat.message"

    // webrtc signaling
    const val RTC_SIGNAL = "rtc.signal"

    // files
    const val FILE_OFFER = "file.offer"
    const val FILE_ACCEPT = "file.accept"
    const val FILE_DECLINE = "file.decline"
    const val FILE_CANCEL = "file.cancel"

    // keepalive / control
    const val PING = "ping"
    const val PONG = "pong"
    const val ERROR = "error"
    const val SERVER_SHUTDOWN = "server.shutdown"

    /**
     * M0 development echo. The server returns the [HELLO] payload unchanged.
     * Removed in M1 when the real handshake lands.
     */
    const val ECHO = "echo"
}
