# Local chat history

**Status: implemented.** Text messages are kept on the device that received them, per server and
room. The server still stores no messages and never replays them; nothing is synchronized between
clients.

## What is stored

Each line is a `ChatMessage`:

- message id, room id, sender session id and display name
- text and the server timestamp

Messages sent by this client are included because the server broadcasts them back to everyone.
File offers and transfers are **not** persisted: after a restart the feed shows the text that was
stored, but not the file bubbles.

## Where

| Platform | Directory |
|---|---|
| Desktop | `~/.kotlin-chat/history/<server>/<room>.jsonl` |
| Android | `filesDir/history/<server>/<room>.jsonl` (app-private storage) |

`<server>` is the remembered endpoint, `host:port`; `<room>` is the room id (`lobby`, `dev`, or a
client-created slug). Names are sanitized and carry a short hash so two unusual room names cannot
share a file.

## Retention and durability

- One JSON object per line, UTF-8. A crash can lose at most the line being written.
- A damaged line is skipped on load; it never prevents the rest from loading.
- Loading keeps the newest **500** messages and compacts a file once it exceeds twice that window.
- Files are owner-only where the platform supports POSIX permissions; Android storage is already
  private to the app.

## Settings

**Settings → Chat → History**:

- **Keep chat history on this device** — on by default. Turning it off stops new writes; it does not
  delete existing files.
- **Clear this room** — deletes only the current room's file and clears its feed.
- **Clear all history** — deletes every room for the remembered server. This works while
  disconnected too, because the endpoint comes from the saved connection.

## Privacy

- History never reaches the server and is not part of the protocol.
- The files are not separately encrypted: desktop relies on the user profile and file permissions,
  Android on app-private storage. Someone with access to the unlocked account/device can read them.
- There is no cross-device sync; each client keeps its own copy.

## Implementation

- `client-core/.../history/ChatHistory.kt` — store interface and platform factory.
- `client-core/.../history/JsonlChatHistoryStore.kt` — append/load/compact/clear, filename
  sanitizing and permissions.
- `ChatClient` loads on `ROOM_STATE` (only when the room changes), appends on `CHAT_MESSAGE`, and
  exposes `setHistoryEnabled` / `clearHistory`.
- Tests: `JsonlChatHistoryStoreTest` (isolation, dedupe, damaged lines, compaction, clear) and the
  integration test that reconnects a new client and restores the message.

Possible later work: persist file-transfer summaries, an age-based retention option, or encrypting
the history with an OS-held key.
