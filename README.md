# kotlin-chat

Self-hosted chat with text, voice, screen sharing and peer-to-peer file transfer.
A Kotlin server acts as a control plane; media and files travel directly between clients.

- Design: [docs/PLAN.md](docs/PLAN.md)
- Wire protocol: [docs/PROTOCOL.md](docs/PROTOCOL.md)

## Status

**M6 — Android client.** Text, rooms and voice from a phone against the same server, verified on
a real device (TECNO LJ8, Android 16) talking to the desktop over `adb reverse`: TLS pinning,
SCRAM handshake, room state, chat, and two-way audio through a foreground service that keeps the
call alive while the app is backgrounded. Screen sharing is a desktop feature; the Android
client neither shares nor renders remote video.

Plus everything from M1–M5 on desktop.

Not implemented yet: Android file transfer, local message history, per-peer volume.

Known gaps: transfers do not resume, incoming files go to Downloads, and files are capped at
8 GiB. The desktop shared view is a downscaled 640×360 preview at ~5 fps; Wayland capture is
unsupported. `AudioTrack.getSignalLevel()` returns 0 on Windows. The TURN relay path is
documented and its credentials are tested, but no coturn instance runs in automation.

## Layout

| Module | Kind | Purpose |
|---|---|---|
| `protocol` | Kotlin Multiplatform | Envelope, DTOs, error codes, SCRAM/crypto primitives |
| `client-core` | Kotlin Multiplatform | Ktor WebSocket client, handshake, snapshot state, reconnect, pin store, RTC seam |
| `media-desktop` | JVM | libwebrtc engine (webrtc-java): mic, peer connections, screen capture, stats |
| `media-android` | Android | webrtc-kmp engine: mic, peer connections, data channel |
| `server` | JVM | Ktor + Netty control plane, auth, rooms, config, TLS, signaling relay |
| `app-desktop` | JVM | Compose Desktop UI |
| `app-android` | Android | Compose UI, permissions, call foreground service |
| `integration` | JVM (tests) | Real TCP + real RTC end-to-end tests, plus an opt-in live-server smoke test |

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

## Android

Build and install the debug APK:

```powershell
.\gradlew.bat :app-android:assembleDebug
adb install -r app-android\build\outputs\apk\debug\app-android-debug.apk
```

To test against a server on the same machine, tunnel the port and connect to `127.0.0.1`:

```powershell
adb reverse tcp:8443 tcp:8443
```

The app asks for the microphone on first launch and needs Android 8.0+ (API 26). Joining a call
starts a microphone foreground service so the call survives backgrounding. The client pins the
server certificate on first connect exactly like the desktop client.

The Android client is voice and text only: no screen sharing and no video rendering. It answers
the desktop's video m-line as `inactive`, so the desktop does not waste bandwidth sending video
to a phone that cannot show it.

## Test

```powershell
.\gradlew.bat test
```

- `protocol` — envelope round-trips and SCRAM proofs (correct password verifies, wrong password
  and tampered auth messages do not).
- `server` — verifier file round-trip, per-IP auth throttling, room state machine, and a full
  handshake driven through Ktor's test engine.
- `media-desktop` — native library/device smoke test, two engines negotiating over a direct
  signal wire with RTP bytes measured in both directions, screen-share frames arriving then
  stopping when the share ends, and a 1 MiB data-channel payload arriving byte-for-byte.
- `integration` — real Netty server on a loopback port with the production client: two clients
  exchanging chat, room moves, wrong-password rejection, TLS pinning (accept and reject),
  reconnection after the server restarts, two clients establishing voice with audio bytes
  flowing both ways, screen share reaching the other client, a four-client mesh that then loses
  a member, and a 2 MiB file transferred between two clients and verified on disk.

There is also an opt-in live smoke test used to verify real hardware (phone ↔ desktop). It is
skipped unless the environment points at a running server:

```powershell
$env:CHAT_SMOKE_HOST="127.0.0.1"; $env:CHAT_SMOKE_PORT="8443"; $env:CHAT_SMOKE_PASSWORD="your-server-password"
.\gradlew.bat :integration:test --tests "org.kotlinchat.integration.LiveServerSmokeTest*"
```

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

### File sharing

**Send file** offers a file to the room. Everyone else sees the offer with an Accept/Decline
button; accepting starts a direct transfer over the peer's data channel. The receiver hashes the
bytes as they arrive and only keeps the file if the SHA-256 matches the offer.

Incoming files land in your Downloads folder (`~/.kotlin-chat` pins are elsewhere; the folder is
configurable in code via `ChatClient.setDownloadDirectory`). Transfers can be cancelled from
either side. Nothing is stored on the server, and a reconnect cancels whatever was in flight.

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
