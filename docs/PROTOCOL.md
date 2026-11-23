# kotlin-chat — Wire Protocol v1

Normative reference for the control channel. Media negotiation is carried opaquely inside
`rtc.signal`; this document does not constrain SDP.

- Transport: **WSS** (TLS 1.3), one connection per client, text frames only.
- Encoding: JSON, UTF-8, `kotlinx.serialization`.
- Keys are `camelCase`.
- Timestamps are epoch milliseconds, server clock for anything the server stamps.

---

## 1. Envelope

Every frame is one JSON object:

```json
{ "v": 1, "type": "chat.send", "id": "c-17", "replyTo": null, "payload": { } }
```

| Field | Type | Notes |
|---|---|---|
| `v` | int | Protocol version. Must be `1`; anything else is refused with `E_PROTO_VERSION`. |
| `type` | string | Message type, `namespace.action`. |
| `id` | string? | Optional client correlation id, echoed as `replyTo` by the server. |
| `payload` | object | Type-specific body, may be `{}`. |

Unknown `type` values are ignored (forward compatibility), but unknown fields inside a known type
are rejected with `E_BAD_PAYLOAD`. Maximum frame size is **64 KiB**.

---

## 2. Session lifecycle

```
client                                            server
  │  hello {protocol, name, pubkey}                 │
  ├────────────────────────────────────────────────►│
  │  challenge {nonce, kdf}                         │
  │◄────────────────────────────────────────────────┤
  │  auth {clientNonce, proof}                      │
  ├────────────────────────────────────────────────►│
  │  welcome {sessionId, rooms, turn, serverSig}    │
  │◄────────────────────────────────────────────────┤
  │  room.state (lobby)                             │
  │◄────────────────────────────────────────────────┤
```

Any failure before `welcome` is answered with `error` followed by a normal close.

### 2.1 `hello` (C→S)

```json
{ "protocol": 1, "name": "alice", "pubkey": "<base64 Ed25519 public key>",
  "capabilities": ["video-slot"] }
```

`capabilities` is advisory and server-validated against a known set. `video-slot` means the
client can create an audio *and* a video transceiver before offering, which is what decides who
offers in a pair (see §6.2).

`name` is 1–32 characters after trimming, no control characters, no leading/trailing space.
Collisions are resolved by the server appending ` (2)`, never rejected.

### 2.2 `challenge` (S→C)

```json
{ "nonce": "<base64, 16 bytes>",
  "kdf": { "alg": "argon2id", "salt": "<base64>", "m": 65536, "t": 3, "p": 1 } }
```

`salt` is stable for the lifetime of the verifier, so clients may cache the derived key.

### 2.3 `auth` (C→S)

```
authKey       = Argon2id(password, salt, m, t, p)          // 32 bytes
clientKey     = HMAC-SHA256(authKey, "client key")
storedKey     = SHA-256(clientKey)
serverKey     = HMAC-SHA256(authKey, "server key")

authMessage   = UTF8(name) ‖ 0x00 ‖ serverNonce ‖ 0x00 ‖ clientNonce

clientSig     = HMAC-SHA256(storedKey, authMessage)
clientProof   = clientKey XOR clientSig
serverSig     = HMAC-SHA256(serverKey, authMessage)
```

```json
{ "clientNonce": "<base64, 16 bytes>", "proof": "<base64, 32 bytes>" }
```

Server verifies `SHA-256(clientProof XOR HMAC(storedKey, authMessage)) == storedKey` in constant
time. On success it returns `serverSig` in `welcome`; the client **must** verify it and abort on
mismatch (this is what detects a substituted endpoint).

### 2.4 `welcome` (S→C)

```json
{ "sessionId": "s_7f3a…", "serverName": "home", "motd": "hi",
  "serverSignature": "<base64>",
  "rooms": [ { "id": "lobby", "name": "Lobby", "topic": "", "memberCount": 2, "cap": 8 } ],
  "turn": { "urls": ["turn:host:3478?transport=udp"], "username": "1763…:s_7f3a", "credential": "…", "ttlSeconds": 3600 },
  "limits": { "maxChatBytes": 4096, "maxMembersPerRoom": 8 } }
```

`turn` is absent when TURN is not configured — clients then use STUN/host candidates only.

---

## 3. Client → server

| Type | Payload | Effect |
|---|---|---|
| `room.list` | `{}` | returns `room.list.result` |
| `room.join` | `{roomId}` | leaves current room, joins `roomId`, returns `room.state` |
| `room.leave` | `{}` | returns to `lobby` |
| `room.create` | `{name, topic?, voice?}` | creates a room, moves the creator into it, broadcasts `room.list.result` to everyone, returns `room.state` |
| `chat.send` | `{text, clientMsgId}` | relayed to room as `chat.message` |
| `presence.update` | `{selfMute?, selfDeaf?, sharing?}` | broadcast as `member.updated` |
| `rtc.signal` | `{to, signal}` | relayed as `rtc.signal` to `to` |
| `file.offer` | `{transferId, name, size, sha256}` | relayed to room as `file.offer` |
| `file.accept` | `{transferId, to}` | relayed to the sender only |
| `file.decline` | `{transferId, to}` | relayed to the sender only |
| `file.cancel` | `{transferId, to?}` | relayed; `to` omitted means whole room |
| `ping` | `{ts}` | answers `pong` |

`text` is trimmed, must be non-empty and ≤ 4096 UTF-8 bytes. `clientMsgId` is echoed back to the
sender inside `chat.message` so optimistic UI can be reconciled.

### 3.1 `rtc.signal` payload

```json
{ "to": "s_9a1c…",
  "signal": { "kind": "offer" | "answer" | "ice" | "bye",
              "sdp": "v=0…",                          // offer/answer
              "candidate": "candidate:…", "sdpMid": "0", "sdpMLineIndex": 0 } }
```

The server checks only that `to` is a live session in the same room. SDP and candidates are
forwarded byte-for-byte, size-limited to 32 KiB. A target outside the sender's room is reported
as `E_BAD_TARGET` without revealing whether that session exists at all.

---

## 4. Server → client

| Type | Payload |
|---|---|
| `room.list.result` | `{rooms: [{id, name, topic, memberCount, cap}]}` |
| `room.state` | `{room, self, members}` — full snapshot, always authoritative |
| `member.joined` | `{member}` |
| `member.left` | `{sessionId, reason}` |
| `member.updated` | `{sessionId, selfMute, selfDeaf, sharing}` |
| `rtc.signal` | `{from, signal}` — relayed verbatim from another member |
| `chat.message` | `{messageId, roomId, from, name, text, ts, clientMsgId?}` |
| `file.offer` | `{from, transferId, name, size, sha256}` |
| `file.accept` | `{from, transferId}` |
| `file.decline` | `{from, transferId, reason?}` |
| `file.cancel` | `{from, transferId}` |
| `error` | `{code, message, retryable, fatal}` |
| `pong` | `{ts, serverTs}` |
| `server.shutdown` | `{inMs, reason}` |

`member` shape:

```json
{ "sessionId": "s_7f3a…", "name": "alice", "pubkeyFp": "SHA256:9c…",
  "joinedAt": 1763000000000, "selfMute": false, "selfDeaf": false, "sharing": false,
  "capabilities": ["video-slot"] }
```

`room.state` is sent on join, on any membership change (in addition to the delta), and on demand.
Clients may treat deltas as hints and the snapshot as truth.

---

## 5. Rooms and presence semantics

- A session is in exactly **one** room at a time. `room.join` implies leaving the previous one.
- `lobby` exists implicitly, is text-only in spirit, and is the post-auth default.
- Room definitions come from server config, plus any rooms members created from a client when the server allows it (`allowClientRooms`, `maxRooms`). Created rooms are written to `clientRoomsFile` (`config/rooms.json`) so they survive a restart; server.yaml stays hand-edited. A room id is derived from its name and made unique; names are unique case-insensitively.
- Voice rooms enforce `maxMembersPerRoom` (default 8) at join time: `E_ROOM_FULL`.
- `member.left` reasons: `leave`, `disconnect`, `kicked_timeout`, `server_shutdown`.
- Presence flags are advisory: the server does not verify that a client claiming `selfMute` is
  actually muted.

**Ordering invariant.** `welcome` and the joining session's first `room.state` are enqueued
while the server holds its room lock, before any `member.joined`/`member.left`/`chat.message`
for that session. A client therefore never receives a room delta that it has to apply before
it knows its room, and snapshots always dominate deltas.

---

## 6. Peer negotiation (mesh)

The server is unaware of this section; it only relays `rtc.signal`.

### 6.1 Topology rules

- One `RTCPeerConnection` per remote member, created when the member appears in `room.state`.
- Tracks: one audio (mic) always, one video (screen) while sharing.
- Data channels: one reliable, ordered channel labelled `files`, created by the offerer before
  the initial offer so it is negotiated with the media slots.
- On `member.left`, the local peer connection is closed and removed immediately.
- On `sharing` toggling, the track is added/removed and negotiation is re-run on the existing
  connection — no teardown.

### 6.2 One designated offerer

`polite = mySessionId > theirSessionId` (lexicographic, both sides derive the same answer).

The **impolite** peer creates the audio transceiver and sends the offer as soon as the peer
appears. The **polite** peer never offers: it waits, attaches its microphone to the transceiver
created by the incoming offer, flips it to `sendrecv`, and answers.

Because only one side can ever offer, glare cannot occur and no rollback path is needed. An
offer arriving at the impolite peer, or an answer at the polite peer, is dropped as a protocol
error.

The offerer is chosen by capability, not just by id: a client that can create both media slots
before negotiating advertises `video-slot` in `hello`, and that side offers whenever the other
side cannot. Android never sends video, so its answers keep the video m-line `inactive` and the
desktop offers. When both or neither advertise the capability, the larger session id is polite,
as before.

Two details that are easy to get wrong:

- The answerer must attach its microphone **after** `setRemoteDescription(offer)` and **before**
  `createAnswer()`. Pre-adding a transceiver produces a `recvonly` answer and therefore
  one-way audio.
- ICE candidates can arrive before the remote description (a candidate is emitted during
  `setLocalDescription`). The receiver buffers them until the description is applied.

M4 (screen share) adds a second track. That would normally force renegotiation, which is why
every peer connection instead negotiates **both** an audio and a video transceiver up front
(both `sendrecv`). The video transceiver carries a screen-capture track that does not capture
until the user starts sharing, so starting or stopping a share only starts or stops the capture
source — the SDP never changes. See §6.3.

### 6.3 Media slots

Both transceivers are created by the offerer and answered with `sendrecv` by the polite peer,
exactly as described above. A peer that joins a room therefore has a video slot with every
other member even if nobody is presenting; the cost is one idle m-line per peer.

Adding a genuinely dynamic media path (a lazily created data channel, a third track) would need
the full perfect-negotiation algorithm with rollback, replacing §6.2.

### 6.4 ICE

- Servers: from `welcome.turn`; no TURN means host/srflx only.
- Trickle ICE via `rtc.signal {kind:"ice"}`, truncated candidates dropped silently.
- The UI exposes connection state per peer: `new / connecting / direct / relayed / failed`.
- Relayed status is derived from the selected candidate pair type, not from guesswork.

---

## 7. File transfer flow

Captions are a client-side convention, not a `file.offer` field: a client that wants to describe a
file sends a normal `chat.send` with `clientMsgId = "file-caption:<transferId>"` and the caption as
the text. Newer clients attach it to the file bubble and hide the chat line; older clients simply
show it as a normal message, and the server needs no change.

```
sender                              receiver
  │ file.offer (via server, metadata only)
  ├─────────────────────────────────►│
  │            file.accept (via server)
  │◄─────────────────────────────────┤
  │  "files" DataChannel (open since negotiation)
  │  64 KiB chunks ──────────────────►│  SCTP backpressure at 4 MiB
  │                                  │  SHA-256 → rename .part
```

- `transferId` is generated by the sender and unique per sender. It is reused for every
  receiver that accepts the same offer; rows are distinguished by `(direction, transferId,
  peerId)`.
- Every peer connection has one reliable, ordered data channel labelled `files`, created by the
  offerer before the initial offer. No transfer renegotiates anything.
- The receiver must send `file.accept` before the sender starts writing bytes.
- One transfer at a time per peer: chunks carry no transfer header, so a second transfer to the
  same peer waits until the first finishes.
- Chunks are 64 KiB. The sender suspends while `bufferedAmount` exceeds 4 MiB and resumes on the
  channel's buffered-amount callbacks.
- The receiver writes to `.<name>.part`, hashes while writing, and only renames into place when
  the SHA-256 matches. A mismatch deletes the partial file and fails the transfer.
- Incoming files are saved to the user's Downloads folder, with a numeric suffix on collision.
- The control-channel `file.*` messages carry no file content.
- Both sides may `file.cancel` at any time; the receiver deletes the partial file. Declines are
  delivered to the sender only, with an optional `reason`.
- The server caps a single file at 8 GiB and 255 characters of name, and rate-limits offers.

---

## 8. Errors

```json
{ "code": "E_AUTH_FAILED", "message": "invalid password", "retryable": false, "fatal": true }
```

| Code | Fatal | Meaning |
|---|---|---|
| `E_PROTO_VERSION` | yes | server does not speak this protocol version |
| `E_BAD_PAYLOAD` | no | malformed or oversized field |
| `E_AUTH_REQUIRED` | yes | message sent before `welcome` |
| `E_AUTH_FAILED` | yes | bad proof |
| `E_AUTH_RATE_LIMIT` | yes | too many attempts from this IP |
| `E_NAME_INVALID` | yes | display name rejected |
| `E_ROOM_NOT_FOUND` | no | unknown `roomId` |
| `E_ROOM_FULL` | no | voice room at capacity |
| `E_NOT_IN_ROOM` | no | signaling to a session outside the room |
| `E_BAD_TARGET` | no | `to` is unknown or offline |
| `E_RATE_LIMIT` | no | per-session rate limit hit |
| `E_PAYLOAD_TOO_LARGE` | no | frame or field over the limit |
| `E_INTERNAL` | no | server bug; logged with a correlation id |
| `E_SHUTTING_DOWN` | yes | server restarting |

---

## 9. Limits and rate limits

| Limit | Value |
|---|---|
| Frame size | 64 KiB |
| Chat text | 4096 UTF-8 bytes |
| Signaling payload | 32 KiB |
| Display name | 32 chars |
| Room topic | 128 chars |
| Rooms per server | 64 |
| Members per voice room | 8 (configured) |
| Chat rate | 5 msg/s, burst 10 |
| Signaling rate | 50 msg/s sustained, 300 burst (ICE candidate spikes on join) |
| File offers | 20/min |
| Auth attempts | 3/min per IP, then 30 s lockout per failure |
| Heartbeat | server `ping` every 20 s; drop after 45 s without traffic |

Violations are answered with the corresponding error; repeated violations (`> 3×`) close the
connection with `E_RATE_LIMIT`.

---

## 10. Reconnection

- Client reconnects with exponential backoff: 0.5 s → 30 s, ±20% jitter, indefinite.
- Reconnect is a **fresh session**: new `sessionId`, re-auth, rejoin the last room, full roster
  resync, new peer connections.
- In-flight file transfers are cancelled, not resumed (v1).
- The server keeps no history: the client shows a "disconnected" gap marker in the local message
  store and resumes from there.
- Clients send `ping` immediately after `welcome` to validate the path end to end.

---

## 11. Compatibility

`v` is an integer that changes only for breaking changes; additive fields do not bump it.
Server advertises the range it accepts in `E_PROTO_VERSION`'s message, and clients surface
"server is too old/new" rather than retrying.
