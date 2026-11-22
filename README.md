# kotlin-chat

Self-hosted chat with text, voice, screen sharing and peer-to-peer file transfer.
A Kotlin server acts as a control plane; media and files travel directly between clients.

- Design: [docs/PLAN.md](docs/PLAN.md)
- Wire protocol: [docs/PROTOCOL.md](docs/PROTOCOL.md)

## Status

**M0 — skeleton.** Gradle multi-module build, protocol DTOs, Ktor control channel with
echo/ping handling, and a Compose Desktop shell that connects and shows traffic.

Not implemented yet: auth, rooms, chat, voice, screen share, file transfer, TLS.

## Layout

| Module | Kind | Purpose |
|---|---|---|
| `protocol` | Kotlin Multiplatform | Envelope, DTOs, error codes, version constants |
| `client-core` | Kotlin Multiplatform | Ktor WebSocket client, connection state, incoming flow |
| `server` | JVM | Ktor + Netty control plane |
| `app-desktop` | JVM | Compose Desktop UI |
| `integration` | JVM (tests) | Real TCP end-to-end tests: server process + production client |

## Build

```powershell
.\gradlew.bat build
```

## Test

```powershell
.\gradlew.bat test
```

`protocol` covers envelope/DTO round-trips, `server` covers the session handler through Ktor's
test engine, and `integration` starts a real Netty server on an ephemeral port and drives it with
the production client.

## Run

Start the server (defaults to `127.0.0.1:8080`; override with `CHAT_HOST` / `CHAT_PORT`):

```powershell
.\gradlew.bat :server:run
```

Start the desktop client:

```powershell
.\gradlew.bat :app-desktop:run
```

Enter `127.0.0.1`, port `8080`, any name, then **Connect**. The server echoes the hello
payload and answers **Ping** with a pong.

The control channel is plain `ws://` in M0. TLS and the SCRAM handshake arrive with M1.
