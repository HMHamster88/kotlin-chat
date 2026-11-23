# App icon

The icon is a parchment tile with a black cast-iron pot, a bail handle and a wax-sealed
envelope. The pot keeps the "pot" identity; the envelope is the chat/message cue.

## Source

| File | Used for |
|---|---|
| `kotlin-chat-icon.svg` | full rounded-square tile: desktop icon PNG/ICO/ICNS, Android legacy PNGs, small-size rendering |
| `kotlin-chat-icon-round.svg` | circular legacy launcher icon (`ic_launcher_round`) |
| `kotlin-chat-icon-foreground.svg` | transparent Android adaptive foreground, already padded for the 66dp safe zone |

Keep the three files in sync when changing the mark. The parchment gradient is
`#F5E9CF → #E7D6B6 → #D5BE98`; the pot is `#1E1C1A` with a `#2A2724` rim, and the seal is
`#A63A2F`.

## Rendered assets

- `app-desktop/icons/kotlin-chat.ico` — multi-size Windows icon (16–256 px)
- `app-desktop/icons/kotlin-chat.png` — 512 px, used by Linux packaging
- `app-desktop/icons/kotlin-chat.icns` — macOS icon set
- `app-desktop/src/main/resources/icons/kotlin-chat.png` — in-app window icon
- `app-android/src/main/res/mipmap-*/ic_launcher.png` — legacy launcher icon
- `app-android/src/main/res/mipmap-*/ic_launcher_round.png` — legacy round icon
- `app-android/src/main/res/mipmap-*/ic_launcher_foreground.png` — adaptive foreground
- `app-android/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml` — adaptive icon definitions
- `app-android/src/main/res/drawable/ic_launcher_background.xml` — parchment backdrop

Rendered mockups stay under `build/` and are not tracked.
