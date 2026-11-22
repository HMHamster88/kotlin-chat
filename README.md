# kotlin-chat

Self-hosted chat with text, voice, screen sharing and peer-to-peer file transfer.
A Kotlin server acts as a control plane; media and files travel directly between clients.

- Design: [docs/PLAN.md](docs/PLAN.md)
- Wire protocol: [docs/PROTOCOL.md](docs/PROTOCOL.md)

## Status

**M1 — control plane.** TLS with pinned certificates, SCRAM-SHA-256 authentication against a
shared server password, rooms with presence, text chat, and automatic reconnect with backoff.

Not implemented yet: voice, screen share, file transfer, Android client, local message history.

## Layout

| Module | Kind | Purpose |
|---|---|---|
| `protocol` | Kotlin Multiplatform | Envelope, DTOs, error codes, SCRAM/crypto primitives |
| `client-core` | Kotlin Multiplatform | Ktor WebSocket client, handshake, snapshot state, reconnect, pin store |
| `server` | JVM | Ktor + Netty control plane, auth, rooms, config, TLS |
| `app-desktop` | JVM | Compose Desktop UI |
| `integration` | JVM (tests) | Real TCP end-to-end tests: server process + production client |

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
- `integration` — real Netty server on a loopback port with the production client: two clients
  exchanging chat, room moves, wrong-password rejection, TLS pinning (accept and reject), and
  reconnection after the server restarts.
