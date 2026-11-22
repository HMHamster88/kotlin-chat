# kotlin-chat

Self-hosted chat with text, voice, screen sharing and peer-to-peer file transfer.
A Kotlin server acts as a control plane; media and files travel directly between clients.

- Design: [docs/PLAN.md](docs/PLAN.md)
- Wire protocol: [docs/PROTOCOL.md](docs/PROTOCOL.md)

## Status

**M4 — screen share.** Share a screen or window into a pre-negotiated video slot; the peer sees
a live preview at ~5 fps, the member list marks who is presenting, and starting or stopping a
share never renegotiates the call. Plus mesh voice, N-way rooms, TURN, TLS, SCRAM and text chat.

Not implemented yet: file transfer, Android client, local message history, per-peer volume.

Known gaps: the shared view is a downscaled 640×360 preview at ~5 fps (fine for slides, not for
video); Wayland capture is unsupported by the underlying library; `AudioTrack.getSignalLevel()`
returns 0 on Windows so there is no local level meter. The TURN relay path is documented and its
credentials are tested, but no coturn instance runs in automation. Cross-machine calls are
untested here.

## Layout

| Module | Kind | Purpose |
|---|---|---|
| `protocol` | Kotlin Multiplatform | Envelope, DTOs, error codes, SCRAM/crypto primitives |
| `client-core` | Kotlin Multiplatform | Ktor WebSocket client, handshake, snapshot state, reconnect, pin store, RTC seam |
| `media-desktop` | JVM | libwebrtc engine (webrtc-java): mic, peer connections, stats |
| `server` | JVM | Ktor + Netty control plane, auth, rooms, config, TLS, signaling relay |
| `app-desktop` | JVM | Compose Desktop UI |
| `integration` | JVM (tests) | Real TCP + real RTC end-to-end tests |

## Build

```powershell
.\gradlew.bat build
```

## First run

```powershell
.\gradlew.bat :server:installDist
.\server\build\install\server\bin\server.bat init
```

`init` asks for a server password and writes three files under `config/` (gitignored):

| File | Contents |
|---|---|
| `config/server.yaml` | server config, rooms, TLS settings |
| `config/verifier` | Argon2id + SHA-256 SCRAM verifier (never the password) |
| `config/server.p12` | self-signed ECDSA certificate and key |

It prints the certificate fingerprint. That is what clients pin on first connect.

## Run

```powershell
.\server\build\install\server\bin\server.bat serve
.\gradlew.bat :app-desktop:run
```

Enter `127.0.0.1`, port `8443`, a display name and the server password. On first connect the
client records the server fingerprint; if the certificate ever changes, the connection fails
with an explicit "certificate changed" error instead of silently trusting the new key.

Other server commands:

```powershell
server.bat fingerprint       # show the pin clients should see
server.bat hash-password     # rotate the shared password
server.bat help
```

### Local development without TLS

Set `tls.enabled: false` in `config/server.yaml`, set `port: 8080`, and untick **TLS** in the
client. Authentication still happens, but the channel is unencrypted: never do this on a
network you do not control.

## Test

```powershell
.\gradlew.bat test
```

- `protocol` — envelope round-trips and SCRAM proofs (correct password verifies, wrong password
  and tampered auth messages do not).
- `server` — verifier file round-trip, per-IP auth throttling, room state machine, and a full
  handshake driven through Ktor's test engine.
- `media-desktop` — native library/device smoke test, two engines negotiating over a direct
  signal wire with RTP bytes measured in both directions, and screen-share frames arriving then
  stopping when the share ends.
- `integration` — real Netty server on a loopback port with the production client: two clients
  exchanging chat, room moves, wrong-password rejection, TLS pinning (accept and reject),
  reconnection after the server restarts, two clients establishing voice with audio bytes
  flowing both ways, screen share reaching the other client, and a four-client mesh that then
  loses a member.

Tests that need a microphone skip themselves when no capture device exists
(`Assumptions.assumeTrue`), so CI without audio still passes.

## Voice

Joining a room starts the microphone **muted**; press **Unmute** in the header to talk. The
sidebar shows a dot per member — it grows with their speech level, turns red on a failed peer,
and is hollow when they are self-muted. `(relay)` appears when a peer is reached through TURN.

Microphone and speaker devices are chosen on the connect screen and apply to the next
connection.

### Screen sharing

**Share screen** in the header lists your monitors and windows. The viewer sees a pane above the
messages with the sharer's name and a live preview; the member list shows `(presenting)`.

The video path is negotiated with every peer when they join — before anyone shares — so
starting and stopping a share never renegotiates the call. Only one share is shown at a time:
if several people present, the pane follows the first one that has the `sharing` presence flag.

Failed links are retried with an ICE restart (up to three times, growing backoff) by the peer
that owns the offer, so a transient network change or a NAT rebind recovers without leaving the
room.

### TURN relay

Direct connections fail for some NAT pairs (symmetric NAT, strict corporate firewalls). Run
coturn and hand both sides a relay:

1. Install coturn and start it with [`deploy/coturn/turnserver.conf`](deploy/coturn/turnserver.conf);
   set `static-auth-secret` to a long random string.
2. Use the same secret in `config/server.yaml`:

   ```yaml
   turn:
     urls: ["turn:your.host:3478?transport=udp"]
     secret: "<same value>"
     ttlSeconds: 3600
   ```

3. Restart the server. Each session receives short-lived credentials in `welcome`
   (`expiry:sessionId` plus an HMAC-SHA1 password, coturn's REST scheme).

The relay only forwards encrypted DTLS-SRTP packets; media is still end-to-end between peers
and nothing is stored.
