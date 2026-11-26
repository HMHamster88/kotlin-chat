# kotlin-chat

Self-hosted chat with text, voice, screen sharing and peer-to-peer file transfer.
A Kotlin server acts as a control plane; media and files travel directly between clients.

- Design: [docs/PLAN.md](docs/PLAN.md)
- Wire protocol: [docs/PROTOCOL.md](docs/PROTOCOL.md)
- Client auto-update: [docs/UPDATES.md](docs/UPDATES.md)
- Settings menu: [docs/SETTINGS.md](docs/SETTINGS.md)
- Chat history: [docs/HISTORY.md](docs/HISTORY.md)

## Contents

- [Status](#status)
- [Layout](#layout)
- [Build](#build)
- [Package](#package)
- [Releases](#releases)
  - [Clients](#clients)
- [First run](#first-run)
- [Run](#run)
  - [Local development without TLS](#local-development-without-tls)
- [Install on a VPS](#install-on-a-vps)
  - [Docker (recommended)](#docker-recommended)
    - [Without the Compose plugin](#without-the-compose-plugin)
  - [Without Docker (systemd)](#without-docker-systemd)
- [Android](#android)
- [Test](#test)
- [Voice](#voice)
  - [Screen sharing](#screen-sharing)
  - [File sharing](#file-sharing)
  - [TURN relay](#turn-relay)

## Status

**M7 complete — hardening and packaging.** Desktop covers voice, screen sharing and
peer-to-peer files; Android covers voice, text, file transfer and viewing screen shares, all
verified on a real device (TECNO LJ8, Android 16) talking to the desktop over `adb reverse`.
`./gradlew packageApp` builds the runnable desktop bundle and the APK.

TLS uses trust on first use: on first contact the server key is shown and must be approved before
anything is pinned, and a key that changed since the last visit is refused until the old pin is
forgotten.

Not implemented yet: local message history, per-peer volume, platform installers (M8).

Known gaps: transfers do not resume, and files are capped at 8 GiB. The desktop shared view is a
downscaled 640×360 preview at ~5 fps; Wayland capture is unsupported. `AudioTrack.getSignalLevel()`
returns 0 on Windows. The TURN relay path is documented and its credentials are tested, but no
coturn instance runs in automation.

## Layout

| Module | Kind | Purpose |
|---|---|---|
| `protocol` | Kotlin Multiplatform | Envelope, DTOs, error codes, SCRAM/crypto primitives |
| `client-core` | Kotlin Multiplatform | Ktor WebSocket client, handshake, snapshot state, reconnect, pin store, RTC seam |
| `media-desktop` | JVM | libwebrtc engine (webrtc-java): mic, peer connections, screen capture, OpenGL YUV presentation |
| `media-android` | Android | webrtc-kmp engine: mic, peer connections, data channel, hardware video rendering |
| `server` | JVM | Ktor + Netty control plane, auth, rooms, config, TLS, signaling relay |
| `app-desktop` | JVM | Compose Desktop UI |
| `app-android` | Android | Compose UI, permissions, call foreground service |
| `integration` | JVM (tests) | Real TCP + real RTC end-to-end tests, plus an opt-in live-server smoke test |

## Build

```powershell
.\gradlew.bat build
```

## Package

```powershell
.\gradlew.bat packageApp
```

Writes two ready-to-use artifacts to `build/dist`:

| File | What it is |
|---|---|
| `kotlin-chat-<version>-<os>.zip` | portable desktop app image; unzip and run `kotlin-chat/kotlin-chat.exe` (or the launcher for this OS) |
| `kotlin-chat-<version>.apk` | installable Android debug build (`adb install -r …`) |

The version comes from `app.version` in `gradle.properties`. Platform installers (MSI/dmg/deb)
are M8; this task builds the runnable `.exe` bundle and the APK. Close the desktop app before
packaging, or Windows keeps a handle on the files being replaced.

## Releases

CI builds everything once and publishes it, so neither a VPS nor a client machine needs a
toolchain. Tag a revision (keep `app.version` in `gradle.properties` in step with the tag):

```bash
git tag v0.3.0
git push origin v0.3.0
```

`.github/workflows/release.yml` then publishes, from that single tag:

| Artifact | Where | Used by |
|---|---|---|
| `ghcr.io/hmhamster88/kotlin-chat-server:0.3.0` and `:latest`, `linux/amd64` + `linux/arm64` | GitHub Container Registry | `docker compose pull`, `docker run` |
| `kotlin-chat-server.tar.gz`, `kotlin-chat-server-0.3.0.tar.gz` | GitHub Releases | bare-metal/systemd server, [stable link](https://github.com/HMHamster88/kotlin-chat/releases/latest/download/kotlin-chat-server.tar.gz) |
| `kotlin-chat-0.3.0-windows.zip` | GitHub Releases | Windows desktop client |
| `kotlin-chat-0.3.0.apk` | GitHub Releases | Android client |
| `latest.json` | GitHub Releases (`releases/latest/download/latest.json`) | the clients' update check |
| `SHA256SUMS` | GitHub Releases | integrity check for all of the above |

They are built in parallel: the server and APK on Linux, the desktop bundle on Windows (jpackage
only produces a bundle for the OS it runs on). Run the workflow by hand (Actions → **Release** →
*Run workflow*) to publish `:edge` and the client builds as workflow artifacts without cutting a
release.

The server image is public here, so servers pull it anonymously. If a pull is ever denied
(`unauthorized`), the package was switched to private: make it public in profile →
**Packages** → `kotlin-chat-server` → *Change visibility*, or log in there with a token that has
`read:packages`.

### Clients

- **Windows**: download `kotlin-chat-<version>-windows.zip`, unzip anywhere and run
  `kotlin-chat\kotlin-chat.exe`. No installer yet (that is M8); the bundle is portable.
- **Android**: download `kotlin-chat-<version>.apk` and open it (or `adb install -r …`). Every
  build — CI and local — is signed with the same project debug key, so a newer release installs
  over the previous one instead of asking you to uninstall.
- **macOS / Linux desktop**: no bundle is published yet; build it on that machine with
  `./gradlew packageApp` (jpackage emits a bundle for the host OS).

Clients keep themselves current. Shortly after start (if "check for updates" is left on) they fetch
`latest.json` from the latest release and show a banner when a newer version exists: Windows
downloads the zip, verifies its SHA-256, unpacks a staging copy and swaps it in on the next
restart (the previous directory is kept until the new build stays up); Android downloads the APK
and opens the system installer. Nothing is fetched or installed until you click Download, and
"Skip this version" remembers the choice. See [docs/UPDATES.md](docs/UPDATES.md).

The gear in the sidebar (or `Ctrl+,`) opens settings: server and pinned key, audio devices with
microphone/speaker levels, chat display, download folder, updates, theme/density, an optional
software UI renderer that trades a little CPU for noticeably lower memory (restart to apply), and
a copyable diagnostics block. Android has the same sections as a full screen. See
[docs/SETTINGS.md](docs/SETTINGS.md).

Files are attached with the paperclip in the message composer. The text field becomes an optional caption; sending with an empty caption is fine, Telegram-style. Captions require no server change and old clients simply see the caption as a normal message.

Text messages are kept locally, per server and room, so a restart does not wipe the conversation.
History settings live under **Chat → History** and can be turned off or cleared per room/all; file
transfers are not stored. See [docs/HISTORY.md](docs/HISTORY.md).

Messages are shown as bubbles grouped by sender, with colored sender names, a day separator and
the timestamp inside the bubble. Message text can be selected for copy on desktop and Android;
Android also long-presses a message to copy all of it, and desktop has **Copy message** in the
right-click menu.

The APK uses the Android debug key (`app-android/debug.keystore`, alias `androiddebugkey`). That
key is public by design and fine for sideloading, but it must be replaced with a private release
key before ever publishing to a store.

To build the server image locally instead: `docker build -t ghcr.io/hmhamster88/kotlin-chat-server:latest .`
(it runs Gradle inside the image and wants ~2 GB of RAM, which is why the VPS instructions pull).

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

Enter `127.0.0.1`, port `8443`, a display name and the server password. If the connection does not
come up, the screen says what happened ("nothing is listening on host:port", "wrong server
password", "no answer from host:port", ...) plus one thing to try, so a wrong address or a blocked
port does not look like a dead app. On first connect the
client shows the server fingerprint and asks you to approve it, comparing it with what the server
printed at startup; only then is the key pinned. If the certificate ever changes, the connection
fails with an explicit "certificate changed" prompt that shows both keys instead of silently
trusting the new one. Choose **Forget server key** on the connect screen to accept the new
certificate deliberately (this is what you need after re-running `init` or moving the server).

Rooms come from `config/server.yaml`. Anyone with the server password can also create one from the
client — **+** next to **ROOMS** on desktop, **+** in the room bar on Android. Created rooms are
stored in `config/rooms.json` and survive a restart, so `server.yaml` stays hand-edited;
`allowClientRooms: false` disables the feature and `maxRooms` bounds the total.

Empty rooms created from a client can be removed by anyone with the server password: use the trash
icon beside the room (desktop sidebar and the Android room chips) or long-press its chip on
Android. Rooms defined in `server.yaml` and the lobby cannot be removed, and a room must have no
members first.

Connecting enters a room automatically — the last one you used, or the lobby. There is no separate
room picker: switch rooms from the desktop sidebar or the Android room chips. Entering a room does
**not** start voice; press the phone button in the header to join the call (the microphone and
speaker come on unmuted), press the phone-off button to hang up and stay in the room. The
microphone and speaker buttons appear only during a call. Calls are per room, so switching rooms
ends the current one. The last room is remembered across launches.

The connect screen remembers one server: address, port, TLS, display name and your audio devices.
Tick **remember password** to keep the password in the OS key store (DPAPI on Windows, the login
keychain on macOS, Secret Service on Linux, the Android keystore on a phone) — it never goes into
the settings file, and unticking deletes it. Tick **connect automatically when the app starts** to
join without asking; that needs a saved password and an already-pinned server key. **Forget saved
connection** clears both.

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

## Install on a VPS

The server is one JVM process whose only state is `config/`: the password verifier, the TLS key
store, and any rooms members created. Media and files never touch it, so a 1 vCPU / 512 MB box is
enough; the trade-off is that a relay ([TURN](#turn-relay)) may be needed for strict NATs.

You need: a Linux host with Docker or JDK 21, and **TCP 8443** open to your clients. A domain name
is optional (the certificate is pinned by fingerprint, not validated by a CA), and a TLS
terminating proxy in front of the server is not supported — it would present a different
certificate than the one clients pinned.

### Docker (recommended)

The server image is built by CI (see [Releases](#releases)), so nothing is compiled here.

The quickest path on a fresh Debian/Ubuntu VPS is the install script. It installs Docker when it
is missing, clones the server into `/opt/kotlin-chat`, writes a `.env` with a generated password
and relay secret, opens the ports in `ufw`/`firewalld` when they are active, starts the stack and
prints the password and certificate fingerprint:

```bash
curl -fsSL https://raw.githubusercontent.com/HMHamster88/kotlin-chat/master/deploy/vps-install.sh | sudo bash
```

To update later, fetch the latest source and image with the second script — it keeps `.env`, the
password verifier and the certificate, and upgrades Docker Engine along the way (`--no-docker`
skips that part):

```bash
curl -fsSL https://raw.githubusercontent.com/HMHamster88/kotlin-chat/master/deploy/vps-update.sh | sudo bash
```

Or do it by hand:

```bash
git clone https://github.com/HMHamster88/kotlin-chat.git
cd kotlin-chat
printf 'CHAT_PASSWORD=%s\n' "$(openssl rand -base64 24)" > .env   # the shared server password
# Internet calls (home vs mobile data) also need the relay; see the next step.
printf 'TURN_HOST=%s\nTURN_SECRET=%s\n' "203.0.113.7" "$(openssl rand -base64 32)" >> .env
docker compose pull
docker compose -f docker-compose.yml -f docker-compose.turn.yml up -d
docker compose logs chat | grep -i fingerprint
```

Replace `203.0.113.7` with the VPS's public address (the same one clients type).

The first start writes `config/` into the `chat-config` volume (verifier, key store, created
rooms); later starts just serve it, so `CHAT_PASSWORD` is only read once and can then be removed
from `.env`. The server runs as an unprivileged user and exposes a Docker healthcheck.

**Why the relay:** media and files are peer-to-peer, so two peers behind NAT have no path to each
other without a TURN server — text keeps working (it goes through the server), while voice, screen
sharing and file transfer stall. The overlay runs coturn with the shared secret from `.env`, and
the server hands each session short-lived credentials. Only if both peers are on the same LAN can
you skip it:

```bash
docker compose up -d        # chat only; LAN-only voice/files
```

If the pull fails with `denied` or `unauthorized`, the GHCR package is still private: make it
public in profile → **Packages** → `kotlin-chat-server` → *Change visibility*, or authenticate the
VPS once with a token that has `read:packages`:

```bash
echo "$GHCR_TOKEN" | docker login ghcr.io -u HMHamster88 --password-stdin
```

```bash
sudo ufw allow 8443/tcp        # signaling + text
sudo ufw allow 3478/tcp        # TURN (only needed with the relay)
sudo ufw allow 3478/udp
sudo ufw allow 49160:49200/udp # TURN relay range, matches docker-compose.turn.yml
```

Connect a client with the VPS address, port `8443`, TLS on, and the password from `.env`. The
client shows the fingerprint and asks you to approve it — compare it with the one in the logs, then
trust it once. Every later connection is verified against that pinned key.

Update to a newer release — the update script above does this and keeps the configuration. By hand
(pin a version with `CHAT_VERSION=0.4.0` in `.env` if you prefer):

```bash
git pull && docker compose pull \
  && docker compose -f docker-compose.yml -f docker-compose.turn.yml up -d
```

Back up (and restore) the server state — the verifier and certificate are the only irreplaceable
files:

```bash
docker volume ls | grep chat-config      # e.g. kotlin-chat_chat-config
docker run --rm -v kotlin-chat_chat-config:/config -v "$PWD":/backup alpine \
  tar czf /backup/kotlin-chat-config.tgz -C /config .
```

`docker compose down` stops the server and keeps the volume; `docker compose down -v` deletes the
password and certificate too.

#### Without the Compose plugin

If `docker compose` reports `unknown shorthand flag: 'd' in -d`, the Compose v2 plugin is missing
(the command itself is fine). The package name depends on how Docker was installed:

| Docker installed from | Command |
|---|---|
| Docker's own repository (`docker-ce`) | `sudo apt install docker-compose-plugin` |
| Ubuntu's `docker.io`, Ubuntu 24.04+ | `sudo apt install docker-compose-v2` |
| Nothing above matches | install the plugin binary, below |

```bash
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -SL "https://github.com/docker/compose/releases/latest/download/docker-compose-linux-$(uname -m)" \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version
```

`uname -m` prints `x86_64` or `aarch64`, which are exactly the release asset names. No Docker
restart is needed; the CLI picks the plugin up from that directory.

If you would rather not install it, the prebuilt image runs with plain Docker too:

```bash
docker volume create chat-config
docker pull ghcr.io/hmhamster88/kotlin-chat-server:latest
docker run -d --name kotlin-chat --restart unless-stopped \
  -p 8443:8443 \
  -e CHAT_PASSWORD='pick-a-long-password' \
  -v chat-config:/app/config \
  ghcr.io/hmhamster88/kotlin-chat-server:latest
docker logs kotlin-chat | grep -i fingerprint
```

Updating that container: `docker pull ghcr.io/hmhamster88/kotlin-chat-server:latest`, then
`docker rm -f kotlin-chat` and repeat the `docker run` without `CHAT_PASSWORD` — the volume
already holds the password and certificate, so the entrypoint only serves.

### Without Docker (systemd)

No build step: take the server archive from the latest [release](#releases).

```bash
sudo apt install -y openjdk-21-jre-headless curl

sudo useradd --system --home /opt/kotlin-chat --shell /usr/sbin/nologin kotlin-chat
sudo mkdir -p /opt/kotlin-chat
curl -fL -o /tmp/kotlin-chat-server.tar.gz \
  https://github.com/HMHamster88/kotlin-chat/releases/latest/download/kotlin-chat-server.tar.gz
sudo tar -xzf /tmp/kotlin-chat-server.tar.gz -C /opt/kotlin-chat     # → /opt/kotlin-chat/server
sudo chown -R kotlin-chat /opt/kotlin-chat
cd /opt/kotlin-chat
sudo -u kotlin-chat ./server/bin/server init     # asks for the password, prints the fingerprint
```

Updating: stop the service, download and extract the new archive over `/opt/kotlin-chat/server`
(the `config/` directory is untouched), then start it again.

Create `/etc/systemd/system/kotlin-chat.service`:

```ini
[Unit]
Description=kotlin-chat server
After=network-online.target

[Service]
User=kotlin-chat
WorkingDirectory=/opt/kotlin-chat
ExecStart=/opt/kotlin-chat/server/bin/server serve
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable --now kotlin-chat
sudo journalctl -u kotlin-chat -n 20        # fingerprint and room list on start
```

The process resolves `config/` relative to its working directory, so `WorkingDirectory` matters;
`server.bat hash-password` and `server fingerprint` are run the same way (`sudo -u kotlin-chat
./server/bin/server ...` from `/opt/kotlin-chat`). Set `SERVER_OPTS` (Docker) or `JAVA_OPTS`
(systemd `Environment=`) if you want a smaller heap than the JVM default.

## Android

Build and install the debug APK:

```powershell
.\gradlew.bat :app-android:assembleDebug
adb install -r app-android\build\outputs\apk\debug\app-android-debug.apk
```

`.\gradlew.bat packageApp` does the same thing and drops a version-named copy into `build/dist`.

To test against a server on the same machine, tunnel the port and connect to `127.0.0.1`:

```powershell
adb reverse tcp:8443 tcp:8443
```

The app asks for the microphone on first launch and needs Android 8.0+ (API 26). Joining a call
starts a microphone foreground service so the call survives backgrounding. The client shows the
server key and asks before pinning it, exactly like the desktop client.

The Android client covers voice, text, file transfer and watching desktop screen shares:
**Send file** opens the system picker, incoming offers are accepted in the room, finished downloads
land in the phone's Downloads collection, and a presenting desktop appears above the messages.
Android answers the desktop's video m-line as `recvonly`, so it receives the share without ever
sending video itself.

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

Entering a room only joins the chat; press the **phone** button in the header to start a call. It
joins **unmuted** and shows the microphone and speaker buttons: mute the microphone, or silence
incoming audio without changing your microphone. Press **phone-off** to leave the call and stay in
the room. The room's media mesh keeps running without a call, so files and screen sharing still
work; on Android, joining or leaving a call briefly rebuilds that mesh. The sidebar shows a dot per
member — it grows with their speech level and turns red on a failed peer. A phone icon marks
members who are in the call; a crossed microphone appears only for a member who is in the call and
has muted the microphone, so a member who simply has not joined voice no longer looks muted.
`(relay)` appears when a peer is reached through TURN.

Microphone and speaker devices are chosen on the connect screen and apply to the next
connection.

### Screen sharing

**Share screen** in the header lists your monitors and windows. The viewer sees a pane above the
messages with the sharer's name and a live preview; the member list shows `(presenting)`.

Desktop paints the decoded track through an OpenGL fragment shader that converts the three I420
planes to RGB on the GPU, at the sender's resolution and frame rate. If OpenGL is unavailable it
falls back to a software ABGR canvas; `-Dkotlinchat.videoRenderer=software` forces that fallback.
**Open in window** moves the share into a resizable separate window that follows the presenter
and closes when the share ends; **Show in chat** moves it back. Desktop viewers can request
remote control from that window: the presenter gets a consent dialog, and while a session is
active a banner with a countdown and **Stop** is shown. One controller is allowed at a time,
the host's **Remote control lease** setting caps a session (30 minutes by default, 5–60 in
Settings), and only `Alt+F4` is filtered — other shortcuts, including the Windows key, reach the
presenter. Control is available for whole-screen shares only; window shares stay view-only.
Android renders with the hardware `SurfaceViewRenderer` from libwebrtc; tap **Fullscreen** for a
larger view. Only desktop clients can present, and only Windows hosts can accept control.

The video path is negotiated with every peer when they join — before anyone shares — so
starting and stopping a share never renegotiates the call. Only one share is shown at a time:
if several people present, the pane follows the first one that has the `sharing` presence flag.

### File sharing

**Send file** offers a file to the room. Everyone else sees the offer with an Accept/Decline
button; accepting starts a direct transfer over the peer's data channel. The receiver hashes the
bytes as they arrive and only keeps the file if the SHA-256 matches the offer.

Image offers include a small preview before the receiver accepts it. The thumbnail travels
peer-to-peer over the same data channel, never through the server, and can be tapped to open a
larger view. The full file is still only downloaded after **Accept**.

Incoming files land in your Downloads folder (`~/.kotlin-chat` pins are elsewhere; the folder is
configurable in code via `ChatClient.setDownloadDirectory`). Transfers can be cancelled from
either side. Nothing is stored on the server, and a reconnect cancels whatever was in flight.

Failed links are retried with an ICE restart (up to three times, growing backoff) by the peer
that owns the offer, so a transient network change or a NAT rebind recovers without leaving the
room.

### TURN relay

Media and files are peer-to-peer, so two peers behind NAT have no path to each other without a
relay. Text keeps working (it goes through the server); voice, screen sharing and file transfer
stall at "connecting" until one side has a public address. The Docker install in
[Install on a VPS](#install-on-a-vps) runs coturn for this; here is what it does, and the
non-Docker equivalent.

With Compose (Linux, host networking):

| `.env` | Meaning |
|---|---|
| `TURN_HOST` | public address clients use for the relay |
| `TURN_SECRET` | shared secret between the server and coturn; never sent to clients |

```bash
printf 'TURN_HOST=%s\nTURN_SECRET=%s\n' "203.0.113.7" "$(openssl rand -base64 32)" >> .env
docker compose -f docker-compose.yml -f docker-compose.turn.yml up -d
```

The server reads `CHAT_TURN_HOST`/`CHAT_TURN_SECRET` from its environment (`CHAT_TURN_PORT`, or
`CHAT_TURN_URLS` for full control) and puts `turn:` and `stun:` URLs into every session's welcome.

Without Docker, install coturn with [`deploy/coturn/turnserver.conf`](deploy/coturn/turnserver.conf)
(set `static-auth-secret`) or configure `config/server.yaml` by hand:

```yaml
turn:
  urls: ["turn:your.host:3478?transport=udp", "stun:your.host:3478"]
  secret: "<same value>"
  ttlSeconds: 3600
```

Either way each session receives short-lived credentials (`expiry:sessionId` plus an HMAC-SHA1
password, coturn's REST scheme). A client whose server offers no relay says so in the room:
"no STUN/TURN: peers behind NAT cannot connect". The relay only forwards encrypted DTLS-SRTP
packets; media stays end-to-end and nothing is stored.
