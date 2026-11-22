package org.kotlinchat.client

import org.kotlinchat.client.platform.AndroidPlatform
import java.nio.file.Path
import java.nio.file.Paths

internal actual fun platformPinStore(): PinStore {
    val context = AndroidPlatform.appContext ?: return MemoryPinStore()
    return FilePinStore(Paths.get(context.filesDir.absolutePath, "pins.properties"))
}
