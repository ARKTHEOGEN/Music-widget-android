# Music Widget (Android)

One home-screen widget for **Apple Music, Spotify and YouTube Music**, with an app switch,
swipe gestures, seek, shuffle, repeat and synced lyrics. Built for Pixel 7 Pro and
Xiaomi Pad 7 Pro (works on any Android 12+ device).

## What it does

| Where | Action |
|---|---|
| Widget | Tap play/pause, next, previous, shuffle, repeat, lyrics |
| Widget | Tap album art → opens the player panel |
| Widget | Tap the app icon (corner) → cycle Auto → Apple Music → Spotify → YT Music |
| Widget (large) | Tap Auto / an app icon → follow that app (opens it if closed) |
| Panel | Swipe art left/right → next/previous · double-tap → play/pause |
| Panel | Tap or swipe up on art → lyrics · swipe down → close |
| Panel | Drag the bar → seek · tap a lyric line → jump there |
| Panel | Long-press an app icon → open that app |

Resize the widget freely: it switches between 5 layouts (tiny, bar, square, medium, large).
**Auto** mode follows whichever app is playing; picking an app locks the widget to it.

## Install (no coding needed)

1. On GitHub, open **Actions** → latest **Build APK** run → download **music-widget-apk** (a zip).
2. Unzip it on your phone/tablet and tap the `.apk` file. Allow "install unknown apps" for your browser/files app when asked.
3. Open **Music Widget** and follow the screen:
   1. **Grant notification access.** If the switch is greyed out: *Open app info* → ⋮ (top right) → **Allow restricted settings**, then try again.
   2. **Xiaomi Pad only:** in App info turn **Autostart** on and set **Battery saver → No restrictions**.
4. Long-press the home screen → **Widgets** → **Music Widget** (or tap *Add widget to home screen*).

### Keep updates installable (one-time, recommended)

Android only lets an update install over the old app if both carry the same signing key.
Without your own key, each build uses a throw-away key and you must uninstall before
updating (and grant access again). To fix this once:

1. On a computer with Java: `keytool -genkeypair -v -keystore release.jks -alias musicwidget -keyalg RSA -keysize 2048 -validity 10000`
2. `base64 -w0 release.jks` (macOS: `base64 -i release.jks`) and copy the output.
3. GitHub repo → **Settings → Secrets and variables → Actions → New repository secret**, add:
   `SIGNING_KEYSTORE_BASE64` (step 2 output), `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` (`musicwidget`), `SIGNING_KEY_PASSWORD`.
4. Keep `release.jks` and the passwords safe. Lose them and you can't update the app.

## How it works (the short version)

- **No logins or developer keys.** Every music app reports "now playing" to Android
  through a standard *media session*. With notification access, the widget reads those
  sessions and sends commands back. One code path serves all three apps.
- **Widgets only accept taps.** That's an Android rule, so swipes and dragging live in
  the pop-up panel, which opens in one tap from the widget.
- **Battery:** the widget redraws only when something visible changes. The elapsed time
  is a `Chronometer`, which the launcher ticks by itself, so the app never wakes up each
  second. The panel's progress timer runs only while the panel is on screen.
- **Lyrics** come from [LRCLIB](https://lrclib.net) (free). The apps don't share theirs.
  Coverage is good but not complete.
- **Shuffle/repeat:** sent using the standard command if the app supports it, otherwise
  through the app's own shuffle/repeat button in its media controls. Dimmed icon = the app
  doesn't expose it.

## Project map

| File | Job |
|---|---|
| `SessionHub.kt` | Finds the music apps' sessions, picks one (Auto/manual), sends commands |
| `WidgetRenderer.kt` | Draws all widget sizes; skips redraws when nothing changed |
| `PlayerPanelActivity.kt` | Pop-up player: gestures, seek bar, synced lyrics |
| `Lyrics.kt` | LRCLIB lookup, title clean-up, LRC parsing |
| `Artwork.kt` | Album-art colours for the "Material You" tint |
| `MediaListenerService.kt` | Notification-access hook that grants session access |
| `SwitchActivity.kt` | Invisible screen that handles app-switch taps |
| `MainActivity.kt` | Setup screen |
