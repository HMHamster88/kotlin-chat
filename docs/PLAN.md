# kotlin-chat — Design Plan

A self-hosted chat application with text, voice, screen sharing and peer-to-peer file transfer.
Clients connect to `host:port` with a single shared server password (Mumble-style), no accounts.

- **Server:** Kotlin (JVM)
- **Client:** Kotlin Multiplatform — Compose Multiplatform UI, Desktop (JVM) + Android for v1
- **Status:** M7 complete — desktop (voice, screen share, files) and Android (voice, text, files) with a trust-on-first-use prompt and `packageApp` producing the exe bundle and APK

---

## 1. Decision log

| Decision | Choice | Rationale |
|---|---|---|
| Client platforms (v1) | Desktop (Win/Linux/macOS) + Android | Screen share stays desktop-only; Android adds mobile voice and file transfer |
| Media topology | WebRTC **full mesh**, server is a control plane only | Keeps the Kotlin server small; no media/SFU code; media is E2E by construction |
| Room size | ≤ 8 members in a voice room (hard cap, configurable) | Mesh upload cost; see §6.2 |
| Text history | Client-side only (SQLDelight) | Server stores no user data |
| NAT fallback | TURN relay allowed, never persisted | ~10–20% of peer pairs cannot connect directly |
| Auth | Shared server password, SCRAM-SHA-256 over Argon2id | Password never transmits; server stores only a verifier |
| Identity | Client-generated Ed25519 keypair, TOFU cert pinning | Stable ID and file-sender verification without accounts |

### Out of scope for v1

- iOS and Web clients (protocol and module layout stay compatible)
- Server-side audio mixing / SFU (30-person rooms)
- Screen-share audio (system audio capture)
- Per-user roles, moderation, bans, persistent server-side history
- Group E2E encryption for text chat (text is relayed in plaintext over TLS; see §9.4)

---

## 2. Architecture

```
┌──────────────────────────┐        ┌──────────────────────────────────────┐
│ Desktop client (Compose) │        │  Kotlin server (Ktor + Netty)        │
│  UI · room store · WS    │◄──────►│  WSS :8443                           │
│  webrtc-java (JNI)       │  JSON  │   rooms · presence · text relay      │
└───────────┬──────────────┘        │   SCRAM auth · ICE/SDP signaling     │
            │                       │   TURN credential issuer             │
            │                       └────────────────┬─────────────────────┘
            │                                        │ short-lived TURN creds
            │  WebRTC mesh: DTLS-SRTP (voice, screen) │
            │  DataChannels (file bytes)             ▼
            │                              ┌───────────────────┐
            └──────► peer ◄──────► peer ◄──│ coturn (relay)    │
                     UDP 49152-65535       │ no persistence    │
                                           └───────────────────┘
```

**Invariants**

1. The server never carries media and never stores file bytes.
2. The server is authoritative for room membership and text ordering.
3. All peer-to-peer media/data is encrypted by DTLS-SRTP; a relayed connection is still
   encrypted end-to-end between peers, only the IP layer is proxied.
4. A failed peer connection degrades that peer only, never the room.

### 2.1 Why not a server-side mixer

Mumble mixes Opus audio server-side, which is excellent for 30-listener talk rooms but does not
extend to video or screen share, and it doubles the amount of media code we must own. Mesh gives
us voice + video + file transfer from one transport, at the cost of client upload bandwidth.

At 8 members: 7 upstream audio streams × ~32 kbps ≈ 224 kbps per client. Acceptable.
Screen share is the expensive part: ~2.5 Mbps per viewer at 1080p30.

---

## 3. Components

### 3.1 Server (`:server`)

Ktor 3.2 on Netty, coroutines, `kotlinx.serialization`. Single fat jar.

| Piece | Responsibility |
|---|---|
| `WsEndpoint` | accept upgrade, frame size limits, per-session outbound channel (never blocking) |
| `AuthService` | SCRAM-SHA-256 exchange against an Argon2id verifier file; per-IP rate limiting |
| `SessionRegistry` | sessionId ↔ connection, presence, display name, pubkey fingerprint |
| `RoomRegistry` | room definitions from config, membership, ordering, `Mutex` per room |
| `ChatRelay` | validates and fan-outs text messages, no persistence |
| `SignalingRelay` | forwards opaque SDP/ICE payloads between two sessions in the same room |
| `TurnService` | issues HMAC time-limited TURN credentials (TURN REST API format) |
| `AdminCli` | `--gen-config`, `--hash-password`, `--print-fingerprint` |

State is in memory. Room definitions come from config; optional `state.json` holds nothing but
room configuration.

### 3.2 Shared client core (`:client-core`, KMP)

- `ChatClient`: Ktor WebSocket client, reconnect with exponential backoff + jitter, outbound queue.
- `ConnectionStateMachine`: `Disconnected → Connecting → Authenticating → Ready → Reconnecting`.
- `RoomStore`: `StateFlow<RoomSnapshot>` for Compose.
- `MeshController`: owns `Map<sessionId, PeerLink>`, add/remove on roster deltas, perfect negotiation.
- `FileTransferService`: offer/accept/chunk/hash, driven by `DataChannel` abstraction.
- `MediaEngine` (`expect`): capture, playout, peer connections, screen capture, device enumeration.

### 3.3 Media implementations

| Target | Implementation | Notes |
|---|---|---|
| Desktop JVM | `dev.onvoid.webrtc:webrtc-java` 1.0.1 | JNI libwebrtc; audio devices with AEC/NS/AGC; `VideoDesktopSource` for screen and window capture; native bundles per OS/arch |
| Android | `com.shepeliev:webrtc-kmp` 0.125.11 | voice and data channel only; no video capture or rendering on Android |
| iOS (later) | `webrtc-kmp` | no screen capture; needs ReplayKit |
| Web (later) | `webrtc-kmp` JS/WasmJS | browser-native RTC |

`webrtc-kmp` has no JVM target, which is exactly why `MediaEngine` exists: the interface is in
`commonMain`, and the desktop actual wraps `webrtc-java`. Both expose the same browser-shaped
concepts (`PeerConnection`, `Track`, `DataChannel`), so the wrappers are mechanical.

**The library is a small third-party project (315★).** Everything it touches stays behind
`MediaEngine`; nothing else in the codebase imports it.

---

## 4. Module layout

```
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
protocol/            KMP (jvm, android, ios, js)   DTOs, error codes, version constants
client-core/         KMP                          ws client, state, mesh, files, MediaEngine
media-desktop/       JVM                          webrtc-java actual
media-android/       Android                      webrtc-kmp actual
server/              JVM                          Ktor app + tests
app-desktop/         JVM                          Compose Desktop application
app-android/         Android                      Compose application
docs/                                               this plan + protocol
```

Dependency rule: `app-* → client-core → protocol`, `server → protocol`.
`client-core` never depends on a media implementation; wiring happens in the app modules.

---

## 5. Protocol (summary)

Full specification in [`PROTOCOL.md`](./PROTOCOL.md).

- One WSS connection, JSON envelopes `{v, type, id?, payload}`.
- Pre-session: `hello → challenge → auth → welcome` (or `error`).
- Session: room listing/join, presence, chat, opaque `rtc.signal`, file offers.
- Version negotiation: server rejects `v` it does not support with `E_PROTO_VERSION`.
- Limits: 64 KiB frame, 4 KiB chat message, 5 chat msg/s, 50 signaling msg/s.

Voice/video negotiation details are deliberately *not* protocol concerns: the server forwards
`rtc.signal` payloads verbatim and never parses SDP.

---

## 6. Voice

### 6.1 Pipeline

Capture (48 kHz mono) → WebRTC APM (AEC, noise suppression, AGC) → Opus 32 kbps, 20 ms frames,
FEC on, DTX on → SRTP → peer. Reverse on receive, with the receiver's jitter buffer and PLC.

- Transmission modes: **voice activity** (default, threshold + hangover) or **push-to-talk**.
- Mute disables the track (stops packets) rather than zeroing samples.
- Speaking indicator: own level from local RMS, remote level from the decoded remote audio
  (an `AudioTrackSink` computes RMS over received frames), so it works even when RTCP level
  metadata is missing.
- Per-peer volume needs a custom render path: libwebrtc mixes every remote stream into the
  audio device module and exposes no per-track gain. It is deferred until the mixer milestone;
  per-peer *mute* would need the same machinery.
- Device selection: input/output device enumeration, applied on the next connection. libwebrtc
  opens playout on a device of its own choosing, and opening it earlier keeps the microphone
  from starting at all, so the desktop engine lets the factory set up audio and then moves
  playout to the chosen (or Windows default) device once the first peer connection exists.
  Only one audio device per process can own playout.

### 6.2 Membership cap

`maxUsersPerRoom = 8` by default, enforced by the server with `E_ROOM_FULL`. Text-only rooms are
uncapped. The cap is surfaced in the join UI, not discovered as an error.

### 6.3 Creating rooms from a client

Rooms come from `server.yaml` plus anything members create at runtime (`room.create`). Creation is
open to any authenticated member unless `allowClientRooms: false`, bounded by `maxRooms` (32 by
default), and the creator is moved into the new room in the same critical section that adds it, so
no client ever sees a room with no owner. Names are unique case-insensitively; the id is a slug of
the name with a numeric suffix on collision.

Client-created rooms live in `clientRoomsFile` (`config/rooms.json`) rather than `server.yaml`, so
the hand-written config and its comments are never rewritten. A room defined in `server.yaml` wins
over a saved copy with the same id. Created rooms have no delete path yet: they persist until the
file is edited.

---

## 7. Screen share

- **Source selection:** window or full screen, listed from `ScreenCapturer` and `WindowCapturer`.
  Screen sharing is desktop-only; the Android client does not capture or render video.
- **Codec:** VP8 negotiated at connection time, 10 fps, capped at 1280×720 for screen content.
- **No renegotiation:** an audio and a video transceiver are negotiated when a peer is added, and
  the video track exists but does not capture until sharing starts. Starting and stopping a
  share only starts/stops the capture source, so the SDP never changes.
- **Preview:** the receiver converts frames to RGBA, downscales to at most 640×360 and publishes
  at ~5 fps. Full-resolution frames are far too expensive to push through a StateFlow.
- **Linux note:** X11 works. Wayland has no portable capture path in `webrtc-java`; v1 treats
  Wayland as unsupported and says so in the picker (PipeWire portal support is a later task).
- Viewers get a "is presenting" banner above the messages; the member list shows `(presenting)`.

---

## 8. File transfer without server storage

1. Sender hashes the file (streaming SHA-256) and offers `file.offer {transferId, name, size, sha256}` to the room; the server relays metadata only.
2. Each receiver explicitly accepts; the server relays `file.accept` to the sender.
3. Bytes travel over the peer's pre-negotiated `files` data channel in 64 KiB chunks, with SCTP
   backpressure at 4 MiB. One transfer at a time per peer; multiple receivers each get their own
   upload.
4. The receiver writes to `.<name>.part`, hashes while writing, and renames only on a matching
   digest.
5. Cancel and decline travel over the control channel; the receiver deletes the partial file.

Constraints: 8 GiB per file (server-enforced), 255-character names, no resume (a reconnect
cancels in flight transfers). Incoming files land in the user's Downloads folder with a numeric
suffix on collision.

Platform notes:

- **Desktop:** the send dialog picks any file; incoming files are written to `.part` files and
  renamed into the chosen folder.
- **Android:** sending uses the Storage Access Framework, so the payload is a `content://` URI
  read through the content resolver. Incoming files go into the shared Downloads collection via
  MediaStore, kept `IS_PENDING` until the hash matches so a failed or cancelled transfer never
  leaves a visible half-written file. Android 8-9 fall back to the app-specific external
  directory (no permission needed).

---

## 9. Security

### 9.1 Transport

TLS 1.3 on the control channel. Self-signed certificate by default; the server prints its
SHA-256 fingerprint, and the client pins it on first connect (TOFU) with a Mumble-style prompt:
the key is displayed and must be approved before anything is stored. A changed fingerprint is a
hard block that shows both keys and requires **Forget server key** before the new one is accepted,
never a silent accept. Programmatic clients (`ChatClient.connect`) pin on first use, which is the
same contract without the dialog.

The TLS layer is given the host exactly as typed rather than the resolved address. Left alone it
reverse-resolves a LAN IP to a machine name and then demands that name in the certificate, which a
self-signed server cannot contain (`No server host: WIN-… in the server certificate`). With the
literal host, an IP address matches only IP SANs — and the default certificate has none — so the
pinned key is the sole authority, as intended. Hostnames are still checked against their DNS SANs
(`kotlin-chat`, `localhost` by default).

### 9.2 Server password

SCRAM-SHA-256 with an Argon2id-derived key (m=64 MiB, t=3, p=1):

```
authKey  = Argon2id(password, salt, m, t, p)
clientKey = HMAC-SHA256(authKey, "client key")
storedKey = SHA256(clientKey)
serverKey = HMAC-SHA256(authKey, "server key")
```

The verifier file holds `salt, storedKey, serverKey` with `0600` permissions. The client proves
knowledge without sending the password; the `serverSignature` returned by the server lets the
client detect a fake/hijacked endpoint. Failed attempts are rate limited per IP (3/min, then
backoff), compared in constant time.

### 9.3 Media and files

DTLS-SRTP is mandatory in WebRTC, so mesh media is end-to-end encrypted with no key management
on our side. Data channels inherit the same DTLS session.

### 9.4 Client-side storage

The client remembers exactly one server: address, port, TLS, display name, chosen audio devices,
and whether to connect on launch. That lives in `settings.json` under `~/.kotlin-chat/`
(desktop) or the app's `filesDir` (Android); it holds no secrets.

The server password is stored **only when the user ticks "remember password"**, and never in a
file this project writes in the clear:

| Platform | Secret store |
|---|---|
| Windows | DPAPI, encrypted for the current Windows user (`CryptProtectData`) |
| macOS | login keychain via `security add-generic-password` |
| Linux | Secret Service via `secret-tool` |
| Android | AES-GCM key in the Android Keystore; only the ciphertext is written |

If the platform store is unavailable the client refuses to save rather than falling back to
plaintext, and unticking the box deletes the saved secret. Auto-connect is allowed only while a
saved password exists, and a failed auto-connect still shows the classified reason plus a way to
stop retrying.

### 9.5 Known properties

- Text chat is readable by the server (it relays it) and by anyone on the TLS path.
- Display names are self-asserted and spoofable by design; the Ed25519 fingerprint is the
  stable identity shown in the member list.
- TURN relays bytes but does not store them.
- A malicious peer can send you a file with a misleading name; the hash is shown and can be
  compared out-of-band.

---

## 10. Server operations

```yaml
# config/server.yaml
serverName: kotlin-chat
motd: Welcome to the server
host: 0.0.0.0
port: 8443
verifierFile: config/verifier
maxUsersPerRoom: 8
maxChatBytes: 4096

tls:
  enabled: true
  keyStore: config/server.p12
  storePassword: changeit
  alias: kotlin-chat

rooms:
  - id: lobby
    name: Lobby
    voice: true
  - id: dev
    name: Dev
    voice: true

# optional
turn:
  urls: ["turn:example.org:3478?transport=udp"]
  secret: "<shared secret from coturn>"
```

Ports: `8443/tcp` (WSS), `3478/udp+tcp` + `49152-65535/udp` (coturn).

Lifecycle:

```
kotlin-chat-server init            # config + verifier + self-signed keystore
kotlin-chat-server hash-password   # rotate the shared password
kotlin-chat-server fingerprint     # print the certificate fingerprint
kotlin-chat-server serve           # default command
```

`init` prints the certificate fingerprint; that value is what clients pin. The verifier
file is written `0600` on POSIX and never contains the password.

---

## 11. Milestones

| # | Deliverable | Acceptance criteria | Est. |
|---|---|---|---|
| M0 | Gradle multi-module, wrapper, version catalog, protocol DTOs, WS echo, desktop shell | `./gradlew build` green; desktop connects and round-trips an envelope | 2–3 d |
| M1 | Auth, rooms, presence, text chat, reconnect | 2 clients chat through the server; wrong password rejected and rate limited; kill/restart server → clients reconnect in <10 s | 1–1.5 w |
| M2 | 1:1 voice on desktop | Two machines hear each other; mute stops packets; device pickers work; stats overlay shows RTT/jitter/loss | 2–3 w |
| M3 | N-way mesh + TURN | 4 clients in a room; join/leave churn leaves no stale peers; speaking indicators; TURN credentials issued and documented | 1 w |
| M4 | Screen share | Share a window or screen; the peer sees a live preview; starting and stopping twice never breaks the call | 1–1.5 w |
| M5 | File transfer | 1 GiB transfer with hash match; cancel mid-flight; same transfer succeeds over TURN | 1 w |
| M6 | Android client | Voice + text against the same server on a real device; foreground service keeps the call alive; no screen share on Android | 2 w |
| M6b | Android file transfer | Send a picked document and receive into Downloads on a real device, with the hash verified | 2 d |
| M7 | Hardening + packaging | TOFU UI; a runnable Windows `.exe` bundle (`createDistributable`) and a debug APK produced by one Gradle command | 3–5 d |
| M8 | Installers, docs and soak | MSI/dmg/deb installers; user and operations docs; 1 h soak test with 4 clients | 1 w |
| M8b | Remembered connection | One saved server (address, TLS, name, audio devices) plus an opt-in password in the OS key store; auto-connect is a setting; nothing is written in plaintext | 2–3 d |

Total: **~3–4 months** solo. M2 is the risk spike (JVM audio devices + NAT traversal).

Work order is a vertical slice: M0→M1 proves the control plane end to end; voice is added to a
working app rather than built in parallel with it.

---

## 12. Testing

| Level | Scope | Tooling |
|---|---|---|
| Unit | protocol codec, SCRAM vectors, room state transitions, rate limiter | `kotlin-test`, JUnit 5 |
| Integration | real Ktor server on an ephemeral port + N in-process WS clients | `testApplication`, `Turbine` |
| Media loop | two headless JVM clients on loopback, `iceServers = []`, sine-wave source | custom harness, CI on Windows+Linux |
| Transfer | 100 MiB over loopback DataChannel, hash assert, mid-flight cancel | same harness |
| Manual | echo/relay quality, device hot-plug, Wayland behaviour, network loss (`clumsy`) | checklist in `docs/` |

CI cannot validate TURN or Android audio; those stay manual with a written checklist.

---

## 13. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| `webrtc-kmp` abandonment | Android media breaks | isolate behind `MediaEngine`; desktop path independent |
| No native JVM WebRTC on some Linux/Wayland setups | screen share missing there | document limitation; PipeWire portal later |
| TURN not deployed | 10–20% of calls fail | ship coturn config + ephemeral creds; surface relay status in UI |
| Mesh bandwidth at 8 users + screen share | poor quality | hard cap, quality presets, `degradationPreference=balanced` |
| Self-signed TLS friction | users bypass checks | TOFU pinning plus a one-line fingerprint print on the server |
| Audio device bugs on JVM | M2 slips | build a device-matrix test early; allow manual device override |
| SCTP throughput on large files | slow transfers | chunk/backpressure tuning; document expected throughput |

---

## 14. Bootstrap notes

The machine has JDK 21 but no Gradle and no git repo yet. M0 starts with:

1. `git init`, add `.gitignore` (Gradle, IDEA, `local.properties`, `*.jks`).
2. Download a Gradle distribution once and generate the wrapper (`gradle wrapper --gradle-version 8.x`),
   then never rely on a system Gradle again.
3. Pin every version in `gradle/libs.versions.toml`; no dynamic versions.
4. Add ktlint + detekt from the first commit.
