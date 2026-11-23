# Client auto-update

**Status: implemented in 0.3.0**, with the decisions recorded in section 8: portable desktop
self-update, downloads only after an explicit click, SHA-256 checksums only, stable channel only.
The first release that can update itself is 0.3.0; a 0.2.x client has to be replaced by hand once.

Goal: a user who installed a client from GitHub Releases can move to the next version without
understanding zip files, adb or Gradle. Updates are never installed silently: the app says what is
available, the user decides, and nothing that fails a checksum or a signature is ever launched.

Non-goals for v1: delta updates, Play Store distribution, iOS, background silent installs.

## What we already have

- Release assets on every tag: `kotlin-chat-<version>-windows.zip`, `kotlin-chat-<version>.apk`,
  `SHA256SUMS` (`docs/PLAN.md`, `.github/workflows/release.yml`).
- Desktop ships as a **portable directory** (jpackage image) until M8 adds an MSI.
- The APK is signed with one project key (`app-android/debug.keystore`), so a newer build installs
  over an older one and keeps settings, the saved server and the pinned certificate.
- `ClientSettings` already persists per-client configuration, so preferences have a home.

## 1. One manifest instead of the GitHub API

The publish job writes `latest.json` next to the assets:

```json
{
  "schema": 1,
  "version": "0.3.0",
  "released": "2026-12-01T10:00:00Z",
  "notesUrl": "https://github.com/HMHamster88/kotlin-chat/releases/tag/v0.3.0",
  "desktop": {
    "windows": { "url": "https://…/kotlin-chat-0.3.0-windows.zip", "sha256": "…", "size": 114663711 }
  },
  "android": {
    "url": "https://…/kotlin-chat-0.3.0.apk", "sha256": "…", "size": 35092051 }
}
```

- Fetched from `https://github.com/HMHamster88/kotlin-chat/releases/latest/download/latest.json`:
  no GitHub API call, so no rate limit per IP and no token.
- `schema` lets a future client ignore fields it does not know.
- Only stable releases get `latest`; a later beta channel would use a differently named manifest.

## 2. Shared version check (`client-core`)

New `client-core/commonMain/.../update` package:

- `UpdateManifest` / `UpdateAsset` (`kotlinx.serialization`).
- `UpdateChecker` — one `GET`, 10 s timeout, returns `UpdateInfo(current, latest, asset, notesUrl)`
  or null when up to date / offline / manifest invalid. Version comparison is numeric
  (`0.10.0 > 0.9.9`), never lexicographic.
- No update logic in the UI layer; both apps share the check and the "is this newer" rule.

Settings additions in `ClientSettings`: `checkForUpdates: Boolean = true`,
`skippedVersion: String? = null`.

Runtime behaviour: check once shortly after start, and via a menu item (“Check for updates”),
never in a loop. Manifest/results are not cached beyond the session.

UI (both platforms): a dismissible banner in the room *and* on the connect screen:

> **Version 0.3.0 is available** (112 MB) — [Download] [Later] [Skip this version]

`Skip` writes `skippedVersion`; `Later` asks again on the next start; `Download` starts the
platform flow below and shows progress plus "Restart to update" / "Install".

## 3. Desktop (Windows first)

The bundle is a directory (`kotlin-chat.exe`, `runtime/`, `app/`), so the updater is a supervised
directory swap:

1. Download the new zip to `%LOCALAPPDATA%\kotlin-chat\updates\kotlin-chat-<version>.zip`,
   verifying `sha256` while streaming; abort on mismatch.
2. Unpack to `…\updates\stage-<version>\` and sanity-check that `kotlin-chat.exe` exists.
3. When the user picks "Restart to update", write a detached PowerShell helper and exit:
   - wait for the current PID to exit (bounded, 60 s);
   - rename the install directory to `<name>.old-<timestamp>`;
   - move the staged app directory into place;
   - start `kotlin-chat.exe` from the new directory;
   - after 20 s, delete the old directory if the new process is alive.
4. **Rollback:** the same helper restores and relaunches the previous directory if the new build
   exits during those 20 s (or if starting it throws). A `pending-update` marker is written before
   the relaunch and removed by the app after 10 s of healthy running; it remains as a breadcrumb
   if even the helper was killed mid-swap.
5. **Fallback:** if the install directory is not writable (extracted under `Program Files`, for
   example) or the app was started outside the packaged launcher (`gradlew run`), the banner offers
   "Download page" instead of a half-working update, and says why.

When M8 lands an MSI, auto-update switches to downloading the MSI and running
`msiexec /i … /qb` (elevation only at that point, Windows Installer handles upgrade and rollback).
The portable path stays for people who use the zip.

## 4. Android

Android does not allow silent APK installation unless the app is a device owner, so the flow ends
with the system installer:

1. Check the manifest; if newer and not skipped, show the banner.
2. Download to `cacheDir/updates/<version>.apk` (OkHttp, progress, sha256 verified).
3. `FileProvider` URI → `Intent(ACTION_VIEW)` with
   `application/vnd.android.package-archive`, `FLAG_GRANT_READ_URI_PERMISSION`, and
   `FLAG_ACTIVITY_NEW_TASK`.
4. The system installer asks for confirmation; if "install unknown apps" is disabled for
   kotlin-chat, open the per-app settings page with a short explanation.
5. The APK keeps its signing key, so this is an upgrade, not a reinstall: settings, saved password
   and pinned key survive. (Verified today for 0.2.0 → 0.2.1.)

Manifest changes: `Provider` entry for `FileProvider` (`res/xml/update_paths.xml`,
`cache-path`), and the `REQUEST_INSTALL_PACKAGES` permission. No new runtime permission.

Zero-code alternative worth documenting: Obtainium can track the GitHub repository and install new
releases itself; some users prefer that to another in-app updater.

## 5. Trust model

- HTTPS + the asset's sha256 from the same release catches corruption and truncated downloads, not
  a compromised repository: whoever can publish a release can publish a matching checksum.
- The honest upgrade paths are **Authenticode** for the Windows exe (code-signing certificate,
  ~$100–400/year, avoids SmartScreen warnings for users too) and a **private Android release key**
  in CI secrets (replacing the debug key; changes the signature, so one uninstall). Both are
  decisions for M9d, not blockers for a self-hosted setup used by a handful of people.
- Update checks reveal "someone runs this app" (and an IP) to GitHub. The toggle disables them.

## 6. Phases

| Phase | Deliverable | Est. |
|---|---|---|
| M9a | `latest.json` in CI, shared `UpdateChecker`, settings + banner on both clients | 1 d |
| M9b | Desktop download, staging, swap helper, pending/crash rollback, read-only fallback | 2–3 d |
| M9c | Android FileProvider flow, unknown-apps guidance, Obtainium note in the README | 1 d |
| M9d | Optional: Authenticode for the exe, release keystore for the APK, MSI auto-update after M8 | 1 d + certificate |

## 7. Test plan

- CI publishes a manifest whose entries match the uploaded assets and their checksums.
- Update 0.3.0 → the next release on Windows and Android, including "Skip this version" memory.
  (0.2.x clients predate the updater and need one manual install.)
- Corrupt a byte in the downloaded file: both clients must refuse it and say so.
- Cut network access mid-download: resume is not supported, the partial file is discarded.
- Desktop: make the install directory read-only → the UI offers the download page instead.
- Desktop: simulate a crash on start of the new version → the next launch restores the old one.
- Android: update from the released APK keeps the saved server, password and pinned key.

## 8. Decisions

1. **Desktop mechanism** — portable self-update, as implemented; the M8 MSI can later delegate to
   `msiexec`.
2. **Download policy** — only on an explicit click; nothing is prefetched in the background.
3. **Signing** — SHA-256 checksums from the same release for now. Authenticode and a private
   Android release key remain M9d.
4. **Channels** — stable only; only tag builds publish `latest.json`.
