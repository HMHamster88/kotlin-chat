# Settings menu

**Status: implemented in 0.4.0**, with the decisions in section 8: gear + `Ctrl+,`, a modal dialog
with a left nav on desktop, the full section table, live audio switching, and the system theme by
default. The microphone test and per-peer volume were left for later (see the audio notes).

Goal: one predictable place for every client preference, on both desktop and Android, without
turning the connect screen into a wall of checkboxes. Values are local to the device — there are
no accounts to sync them to.

Non-goals for v1: server-synced profiles, per-room overrides, notification centre, light/dark
accent palettes, translations, push-to-talk.

## What exists today

| Preference | Stored in | Changed today |
|---|---|---|
| Server host, port, TLS, display name | `ClientSettings` (JSON) | connect screen |
| Input/output audio device | `ClientSettings` | connect screen |
| Remember password, auto-connect | `ClientSettings` + OS key store | connect screen |
| Check for updates, skipped version | `ClientSettings` + update banner | connect screen / banner |
| Download directory | `FileTransferController.setDownloadDirectory` | no UI (default Downloads) |
| Screen-share size and frame rate | hardcoded in `startScreenShare(1280×720, 10 fps)` | no UI |
| Clock format, density, theme | hardcoded | — |

`ConnectionMemory` already loads/saves `ClientSettings` and keeps the password in DPAPI, Keychain,
Secret Service or the Android Keystore. The settings menu should use that, not a second store.

## 1. Entry point and shape

**Desktop.** A gear button in the room's top bar and in the corner of the connect screen, plus
`Ctrl+,` while a window has focus. It opens a modal dialog (about 760×560 dp) with a left nav and
a scrolling detail pane; `Esc` closes it. No native menu bar for v1, so the same mental model and
code serve both platforms.

**Android.** A gear in the room header and on the connect screen opens a full-screen settings
list with a top app bar; system back returns to the room. Sections are grouped in one scroll, each
row opens a picker or toggles inline. A dialog is the wrong shape on a phone — it fights the
keyboard and the notch.

```
Desktop                                      Android
┌─────────────────────────────────────┐      ┌─────────────────────┐
│ Settings                       [x]  │      │ ← Settings          │
├──────────┬──────────────────────────┤      ├─────────────────────┤
│Connection│ Server                   │      │ CHAT                │
│Audio     │ 89.125.70.83:8443  TLS   │      │  Enter sends      ▸ │
│Chat      │ key 9A:EC:…   [copy]     │      │  24-hour clock   [x] │
│Files     │ [Reconnect] [Change…]    │      │  Timestamps      [x] │
│Updates   │                          │      │                     │
│Appearance│ Auto-connect          [x]│      │ AUDIO               │
│About     │ Remember password     [x]│      │  Microphone      ▸  │
│          │ [Forget saved server]    │      │  Output          ▸  │
└──────────┴──────────────────────────┘      │  Mute on join    [x] │
                                             │                     │
                                             │ ABOUT               │
                                             │  Version 0.3.0      │
                                             └─────────────────────┘
```

Everything applies immediately; there is no Save button. Destructive actions (forget the saved
server, forget the pinned key, change server) ask first.

## 2. Sections

| Section | Contents | v1 |
|---|---|---|
| Connection | Server card, fingerprint, auto-connect, password state, change/forget server, forget pinned key | yes |
| Audio & video | Microphone, output, mute on join, microphone test, screen-share defaults (desktop) | yes |
| Chat | Enter-to-send, clock, timestamps, text size, message grouping | yes, small |
| Files | Download folder, open when finished (desktop) | yes |
| Updates | Version, check at startup, check now, skipped version, channel | yes |
| Appearance | Theme, density | theme yes |
| About | Version, server/protocol, diagnostics, links | yes |

### Connection

- **Server card**: `host:port`, TLS on/off, server name from `welcome`, current latency and
  connection state. Offline still shows the last remembered address.
- **Certificate**: short fingerprint with a **Copy** button and a note that the key is pinned by
  fingerprint (TOFU). **Forget pinned key** sits behind a confirmation and explains that the next
  connection will ask again — it does not disconnect now.
- **Auto-connect** and **Remember password** mirror `ClientSettings`. The password itself is never
  shown or edited here; the row says "saved in the system key store" or "not saved". Changing the
  password stays on the connect screen.
- **Change server…** confirms, disconnects, and returns to the connect screen with the current
  values prefilled. Editing the address in place and pressing Reconnect would hide the TLS/key
  decision, so it is deliberately not offered.
- **Forget saved server** clears the address, auto-connect flag, remembered password and audio
  choices, but leaves the pinned key (that is a separate, more destructive action).
- **Reconnect** is a convenience for the common "Wi-Fi changed" case.

### Audio & video

- **Microphone** and **Output** dropdowns come from `client.audioDevices()` (desktop: the
  MediaDevices lists, Android: `enumerateDevices`), with a "System default" entry and a refresh
  button. Android has no output list — it routes playout through AudioManager — so it shows that
  route instead.
- **Microphone and speaker levels**: 0–100 % sliders wired to the live session. Desktop maps them
  to the AudioDeviceModule's microphone/speaker volume; Android applies the microphone gain to the
  local audio track and the speaker level to every remote audio track. Both apply while a call is
  running.
- **Mute on join** (default on, matching the previous behaviour) is a stored preference; the join
  path reads it instead of the hardcoded `muted = true`.
- **Live switching**: selecting a device in a running call updates the session. Desktop changes
  the ADM capture/playout device; Android reopens the microphone and swaps the track into every
  peer connection with `replaceTrack`, so no renegotiation is needed.
- **Microphone test**: still deferred. It needs an engine call that meters the input without
  joining a room; rather than open a hidden voice session, the row is omitted for now.
- **Screen share (desktop only)**: default resolution (720p/1080p/1440p) and frame rate
  (5/10/15/30), passed to `startScreenShare`. The source is still picked per share; remembering a
  window ID is not meaningful across restarts. Android shows a one-line note that screen sharing
  is desktop-only.

### Chat

- **Enter sends** (desktop, default on). `Shift+Enter` always inserts a newline; Android keeps its
  explicit Send button.
- **24-hour clock** (default on) and **Show timestamps** (default on), used by `ChatFeed` on both
  platforms.
- **Message text size**: small / default / large, applied to the feed only.
- **Group consecutive messages** (default on), matching the existing grouping in `ChatFeed`.
- **History**: text messages are stored locally per server and room (see
  [HISTORY.md](HISTORY.md)). **Keep chat history on this device** stops new writes;
  **Clear this room** and **Clear all history** delete what is already stored.
- Notifications, sounds and unread badges are deliberately out of scope for v1.

### Files

- **Download folder** (desktop): directory picker, default `~/Downloads`, reset button, and a
  writability check before saving. Wired through a new `ChatClient.setDownloadDirectory`.
- **Open when finished** (default off): reuse the existing open-file action; never launch a file
  unattended.
- Android writes through MediaStore into Downloads; v1 shows that location and an **Open
  Downloads** button. "Ask where to save each time" via SAF is a later addition (it changes the
  transfer sink contract, which currently expects a directory).

### Updates

- Current version (desktop reads `-Dkotlinchat.version`, Android `BuildConfig.VERSION_NAME`).
- **Check at startup** (existing field) and **Check now**, wired to the already-present
  `DesktopUpdateController.checkNow()` equivalent so a manual check reports failures.
- When `skippedVersion` is set, show it with **Offer this version again**, which clears the field.
- Channel is stable-only; the row says where `latest.json` comes from and links to the release.

### Appearance

- **Theme**: system / light / dark. Applied at the application root so Compose's `MaterialTheme`
  is correct before the first frame; default "system".
- **Density**: comfortable / compact (row heights and message padding), default comfortable.
- Accent colours, fonts and animations are later.

### About

- App version, server name, protocol schema/version, platform and connection state.
- **Copy diagnostics**: the above plus the certificate fingerprint and update channel — no
  password, no message content. Useful when reporting a bug.
- Links: repository, releases, README, third-party notice (later).

## 3. Behaviour rules

- **Immediate apply.** Every row saves through `ConnectionMemory.updateSettings` and takes effect
  at once where the subsystem allows it, otherwise at the documented boundary (voice join,
  reconnect, next download, next share).
- **Defaults preserve 0.3.0.** A missing field decodes to the value the code used before, so an
  upgrade changes nothing until the user touches it.
- **Confirm the irreversible.** Forget server, forget pin, change server. Toggles and pickers
  never ask.
- **Validate early.** Port range, non-empty host, existing device, writable directory. Invalid
  input leaves the previous value in place and explains why.
- **Never surface secrets.** The password is not displayed, logged or copied by diagnostics.
- **Offline-friendly.** Opening settings does not require a connection; the server card simply
  shows "not connected".

## 4. Architecture

**Keep `ClientSettings` flat and additive.** A nested refactor would touch both connect screens,
`ConnectionRequest` and the tests for cosmetic gain. New fields with defaults:

```kotlin
val muteOnJoin: Boolean = true
val enterSends: Boolean = true
val clock24h: Boolean = true
val showTimestamps: Boolean = true
val textScale: Float = 1f
val groupMessages: Boolean = true
val theme: ThemeMode = ThemeMode.SYSTEM
val density: Density = Density.COMFORTABLE
val downloadDirectory: String? = null        // desktop only
val openDownloadedFiles: Boolean = false
val shareMaxWidth: Int = 1280
val shareMaxHeight: Int = 720
val shareFps: Int = 10
```

Enums are serialized by name; add `coerceInputValues = true` to `settingsJson` so an unknown value
from a newer build falls back to the default instead of resetting the whole file. The schema stays
backward compatible; there is no need to bump it for additive fields.

**State hoisting.** Load settings once at the application root (before `MaterialTheme`, which needs
the theme) and pass `settings` + `onChange` down. `MainActivity`/`Main.kt` already construct the
`ChatClient`; the settings object belongs beside it. `ChatScreen` and `ChatApp` stop owning a
`ConnectionMemory` load for the theme's sake and instead receive it.

**Wiring the values:**

| Setting | Plumbing |
|---|---|
| theme, density | root `MaterialTheme`, `LocalDensity` |
| clock, timestamps, text size, grouping | parameters to `ChatFeed` |
| mute on join | `ChatClient` join path / `setMicrophoneMuted` initial state |
| download folder, open when finished | new `ChatClient.setDownloadDirectory`; file feed action |
| share defaults | read when calling `startScreenShare` |
| update preferences | existing `UpdateChecker` / controller |
| connection rows | existing `ConnectionMemory`, pin store |

**No new dependencies.** `ClientSettings` already uses kotlinx.serialization; the UI uses the
Material 3 components both apps ship. Downloads/links use the existing platform helpers.

## 5. Storage and migration

- `settings.json` stays plain JSON with owner-only permissions; it contains no secrets.
- Adding fields is safe: old files load, new files are ignored by older builds apart from the
  enum caution above.
- The password remains in `SecretStore`; "remember password" off clears it.
- Device IDs are strings that can go stale. On load, a missing ID is shown as "unavailable" and
  the engine falls back to the system default; save only after the user picks a replacement.
- The download path is a local absolute path (desktop). Android ignores it.
- `schema` stays as-is unless a change needs data migration (none planned).

## 6. Phases

| Phase | Deliverable | Est. |
|---|---|---|
| M10a | Settings shell (gear, `Ctrl+,`, desktop dialog, Android screen), shared fields, theme, Connection + About + Updates sections | 2 d |
| M10b | Audio devices, mute on join, microphone test (if the engine supports metering), screen-share defaults | 2 d |
| M10c | Chat preferences, download folder picker, open-when-finished, density/text size | 2 d |
| M10d | Polish: confirmations, validation, copy-diagnostics, keyboard/focus, accessibility labels | 1 d |

M10a is useful on its own and is the structure every later row slots into. M10b's microphone test is
the only item that may slip if the engines cannot meter without joining.

## 7. Test plan

- Upgrade from 0.3.0: server, password, auto-connect and update preferences survive; defaults for
  every new field match the old behaviour.
- Change each setting, restart, verify it persisted on both platforms.
- Desktop: `Ctrl+,` opens and `Esc` closes; opening a second time shows the saved values.
- Android: rotate the device and background/restore while settings is open; state survives.
- Theme change applies without a restart and does not flash the wrong scheme on launch.
- Screen-share defaults are actually passed to `startScreenShare` (check the captured stream
  dimensions/fps).
- Download folder: pick, download a file, verify it lands there; make the folder read-only and see
  the explanation.
- Forget server: address, password, auto-connect and devices are cleared; pinned key remains.
- Forget pin: next connection asks for trust again.
- A stale audio device ID shows "unavailable" and falls back to default without crashing.
- Copy diagnostics contains no password or chat text.

## 8. Decisions

1. **Desktop entry point** — gear in the sidebar plus `Ctrl+,`.
2. **Shape** — modal dialog with a left nav.
3. **v1 scope** — the full table; implemented in 0.4.0.
4. **Live audio switching** — implemented: desktop input/output and Android microphone switch
   without rejoining; Android output follows the system route.
5. **Theme default** — follow the system. Microphone and speaker level sliders were added to the
   audio section afterwards and apply live.
