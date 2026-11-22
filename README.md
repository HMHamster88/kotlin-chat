# kotlin-chat

Self-hosted chat with text, voice, screen sharing and peer-to-peer file transfer.
A Kotlin server acts as a control plane; media and files travel directly between clients.

- Design: [docs/PLAN.md](docs/PLAN.md)
- Wire protocol: [docs/PROTOCOL.md](docs/PROTOCOL.md)

## Status

**M2 — voice.** Mesh WebRTC audio on desktop (echo cancellation, noise suppression, AGC),
per-peer connection state and speaking indicators, mute, device selection, plus everything
from M1: pinned TLS, SCRAM authentication, rooms, presence, text chat, reconnect.

Not implemented yet: screen share, file transfer, Android client, local message history.

Known gaps: `AudioTrack.getSignalLevel()` returns 0 on Windows, so there is no local level
meter (remote levels and speaking indicators work). Cross-machine calls are untested in
automation; loopback and in-process mesh are covered by tests.

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
- `media-desktop` — native library/device smoke test, and two engines negotiating over a direct
  signal wire with RTP bytes measured in both directions.
- `integration` — real Netty server on a loopback port with the production client: two clients
  exchanging chat, room moves, wrong-password rejection, TLS pinning (accept and reject),
  reconnection after the server restarts, and two clients establishing voice with audio bytes
  flowing both ways.

Tests that need a microphone skip themselves when no capture device exists
(`Assumptions.assumeTrue`), so CI without audio still passes.

## Voice

Joining a room starts the microphone **muted**; press **Unmute** in the header to talk. The
sidebar shows a dot per member — it grows with their speech level, turns red on a failed peer,
and is hollow when they are self-muted. `(relay)` appears when a peer is reached through TURN.

Microphone and speaker devices are chosen on the connect screen and apply to the next
connection.
