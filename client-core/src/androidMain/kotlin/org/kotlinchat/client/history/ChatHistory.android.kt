package org.kotlinchat.client.history

import org.kotlinchat.client.platform.AndroidPlatform
import java.nio.file.Paths

internal actual fun platformChatHistoryStore(): ChatHistoryStore {
    val context = AndroidPlatform.appContext ?: return NoOpChatHistoryStore
    return JsonlChatHistoryStore(Paths.get(context.filesDir.absolutePath, "history"))
}
