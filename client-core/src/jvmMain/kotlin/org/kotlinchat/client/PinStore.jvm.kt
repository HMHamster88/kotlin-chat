package org.kotlinchat.client

import java.nio.file.Path
import java.nio.file.Paths

internal actual fun platformPinStore(): PinStore {
    val home = System.getProperty("user.home") ?: return MemoryPinStore()
    return FilePinStore(Paths.get(home, ".kotlin-chat", "pins.properties"))
}
