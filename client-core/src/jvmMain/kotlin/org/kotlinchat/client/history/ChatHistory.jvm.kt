package org.kotlinchat.client.history

import org.kotlinchat.client.settings.settingsDirectory

internal actual fun platformChatHistoryStore(): ChatHistoryStore =
    JsonlChatHistoryStore(settingsDirectory().resolve("history"))
