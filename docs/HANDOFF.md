# Handoff: Music Widget (Android)

Everything a new Claude Code session needs to continue this project. Read this file first.

## 1. Owner and how to work with them

- **Owner:** Ark (GitHub `ARKTHEOGEN`). Not a coder; enjoys discussing logic and algorithms. Aspiring Forward Deployed Engineer.
- **Style they asked for:** short, plain English, to the point. Granular step-by-step instructions. Teach the concept behind each step.
- **Rules they set:**
  - Never invent or assume when facts are unclear or conflict. Stop and ask.
  - Give the same answer when a question is asked again in a different way.
  - Build proper solutions on the shortest path, never quick patches (unless they ask for one).
  - Optimise for memory, battery and time.
- **Branch:** `claude/multi-service-music-widget-ayb15p`. Commit and push there. Don't open a pull request unless asked.

## 2. Goal

One home-screen widget on a **Pixel 7 Pro** (Android 17 beta, SDK 37) and a **Xiaomi Pad 7 Pro** (HyperOS) that controls **Apple Music, Spotify and YouTube Music**, with:
- a switch between the three apps
- swipe gestures, seek, next/previous, shuffle, repeat
- lyrics
- free resizing

Their inspiration videos couldn't be watched (YouTube was blocked from the cloud session), so they chose a "Material You" look: colours taken from the album art.

## 3. Decisions already made (don't re-ask)

| Topic | Decision |
|---|---|
| Gestures | Widgets accept taps only, so the widget has tap controls and tapping the art opens a **pop-up panel**. The panel holds all swipe, drag-to-seek and lyrics. |
| App switch | **Auto** follows whichever app is playing. Picking an app locks the widget to it and opens that app if it's closed. |
| Lyrics | **LRCLIB** (free, no key), matched by title, artist and length. |
| Look | Material You: album-art colour tint. |
| Next step | Run a **diagnostics test build** first, then build Feature 1 (handoff) on proven methods. |
| Feature 2 | Decide *after* the diagnostics results. |
| Handoff with no exact match | Play the **closest** version and show a "closest match" note. |
| Apps not installed | **Hide them everywhere** (widget chips, panel chips, setup screen, diagnostics test rows). Done in `344c054`. |
| Swipe on the widget itself | Owner wants it. Slide-bar test **failed** (see section 5). Next candidate: up-next card deck (StackView, vertical flick + tap to play). Not decided yet. |

## 4. What's built (status: compiles in CI; widget confirmed working on the Pixel)

v0.1 worked on the Pixel: after granting access, the widget showed the playing song. v0.2 adds diagnostics; it builds, but nobody has run it on a device yet.

| File (`app/src/main/java/com/arktheogen/musicwidget/`) | Job |
|---|---|
| `SessionHub.kt` | Reads media sessions (needs notification access). One `Tracker` per app caches the latest metadata and state, so reads cost no inter-process calls. Picks the session to show (Auto or manual). Batches updates 60 ms apart. Sends commands: shuffle and repeat use the standard command if allowed, else the app's own custom button. |
| `WidgetRenderer.kt` | 5 size-based layouts (tiny, bar, square, medium, large). Redraws only when a "signature" changes. Elapsed time is a `Chronometer` that the launcher ticks itself, so the app never wakes each second. |
| `PlayerPanelActivity.kt` | Pop-up player. Swipe art left/right = next/previous (art follows the finger). Double-tap = play/pause. Tap or swipe up = lyrics. Swipe down = close. Drag the bar to seek. Tap a lyric line to jump there. |
| `Lyrics.kt` | LRCLIB search, title clean-up ("(Official Video)", "feat."), LRC parsing, cache. |
| `Artwork.kt` | Album-art colours with Palette, darkened until white text is readable. |
| `Diagnostics.kt`, `DiagnosticsActivity.kt` | **v0.2 test build** (see section 6). |
| `Http.kt` | Shared HTTP GET helper. |
| `MediaListenerService.kt` | Notification listener; grants session access. Reads no notifications. |
| `SwitchActivity.kt` | Invisible screen that handles app-switch taps (only a visible screen may launch another app). |
| `MainActivity.kt` | Setup screen: access status, restricted-settings help, Xiaomi tips, add-widget button, Diagnostics button. |
| `MusicApps.kt` | The three package names. Add an app here to support it. |

- **Package / application ID:** `com.arktheogen.musicwidget`. minSdk 31, target/compile 35. AGP 8.9.1, Kotlin 2.1.20, Gradle 8.14.3 wrapper.
- **Dependencies:** `androidx.core`, `androidx.media` (compat controllers), `androidx.palette`. No Compose.
- **CI:** `.github/workflows/build.yml` builds a release APK on every push. If the `SIGNING_*` secrets are missing, it signs with that runner's throw-away debug key.
- **Passing runs:** #1 `41399240861` (v0.1) and #2 `41402027457` (v0.2 diagnostics).

## 5. Device facts learned the hard way

1. **Pixel has Advanced Protection on.** It blocked "Allow restricted settings". The owner turned it off briefly to finish setup.
2. **Wrong screen trap:** App info → Notifications says "This app doesn't send notifications" (greyed out). That is *not* the access screen. The correct path is the in-app **Grant notification access** button, or **Settings → Apps → Special app access → Device & app notifications → Music Widget**. On that screen: tap the greyed switch once, then App info → ⋮ → **Allow restricted settings**, then turn the switch on.
3. **Every CI build has a new signing key**, so each update needs an uninstall first, and access must be granted again. The permanent fix is in README → "Keep updates installable". It's still pending.
4. **Local setup on the owner's Mac (done 2026-11-02):** project at `~/Documents/AI Chats Vault/Claude/Music Widget`. Android SDK at `~/Library/Android/sdk` (path in git-ignored `local.properties`). Android Studio's bundled Java is 25, too new for Gradle 8.14, so builds use Homebrew **Java 21**: `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew assembleDebug`. Shell profile edits were blocked; set `JAVA_HOME` per command. `gh` is installed and signed in as ARKTHEOGEN, so pushes work. `adb` is not on PATH: use `~/Library/Android/sdk/platform-tools/adb`. The work network is slow for big downloads (first build took 8 min).
5. **Pixel serial `2B041FDH300BL5`.** Local debug build installed, notification access granted over adb. Installed players: Apple Music 7.0.0, YouTube Music 9.43.52. **Spotify is not installed.** Phone locale country reports **US** (affects the iTunes Search storefront in the Link test; owner may be in India — not yet confirmed).
6. **Widget swipe test (Slide test probe, removed after):** two `Switch` views styled as bars, using `setOnCheckedChangeResponse`. Result on the Pixel launcher: **the thumb can't be dragged**; a sideways drag changes the home-screen page. Only taps flip the switch. Conclusion: no horizontal gesture reaches a widget on the Pixel launcher.
7. Cloud sessions can't reach the owner's computer, and **Remote Control is disabled on their plan**. That's why this work moves to a local session.

## 6. Current task: run the diagnostics (v0.2)

**Purpose:** turn the unknowns into facts before building handoff.

**What the report shows, per app:**
- whether it accepts the play-from-search intent
- whether it opens its own song links
- per active session: allowed commands, the app's custom buttons, whether the **up-next queue is shared** (and its next 5 songs), all metadata keys, and extras

**Live tests (Diagnostics screen, one row per target app):**
- **Search:** if the target app is running and its session allows play-from-search, use that. Otherwise send the system `MEDIA_PLAY_FROM_SEARCH` intent.
- **Link:** iTunes Search (`itunes.apple.com/search`, country taken from the phone's locale) finds the Apple Music link. For Spotify or YouTube Music, Song.link (`api.song.link/v1-alpha.1/links`) maps it to theirs. The link is then opened in the target app.

Each test pauses the source app, waits up to 15 s for the target to play, grades the match as **EXACT / CLOSE / MISMATCH** (title, artist, length within ±3 s), then checks a seek to the same position (±4 s).

**Results so far (2026-11-02, YouTube Music → Apple Music only):**
- Static report: Apple Music and YouTube Music both accept the play-from-search intent and both open their own song links. Both sessions list `play_from_search`.
- **YouTube Music shares its up-next queue** (23 items readable). **Apple Music does not.** Feature 2 Option A is technically possible.
- **Search via Apple Music's session failed:** Apple Music was idle and frozen by Android (`state=NONE`, log shows "unfroze com.apple.android.music"); it ignored the command. A sleeping Apple Music must be launched, not just sent a session command. Retry with Apple Music force-stopped (so the intent path is used) is **pending**.
- Link test: **not run yet** (taps landed while the Search test was still running).
- Reverse direction (Apple Music → YouTube Music): not run yet.
- Retry: YouTube Music → Apple Music **Search worked** once Apple Music's player was loaded (session play-from-search, 3.1 s, EXACT, seek OK). **Link only opens the song page** (no playback). Session search to an idle Apple Music (`state=NONE`) is ignored.
- **Relay test (`RelayTest.kt`, `f6bb762`), 3 songs: works end to end.** Owner heard all three in Apple Music. Findings:
  - Picks start in Apple Music by session search in 0.5–6 s; 2 of 3 graded EXACT.
  - Song 2 race: our pick arrived after Apple Music's own autoplay had started its next song ("Money in the Bank"); our pick then replaced it. The test mistook the autoplay song for ours. Fix: confirm by title, and hand over earlier or pause Apple Music before its autoplay.
  - `skipToQueueItem` on a paused YouTube Music **makes it start playing** after 1.3–2.3 s; the test paused it. Need a quieter way to move its place.
  - YouTube Music's list **shrank** (16 → 13) with **no new picks** over 3 steps. Unknown whether it refills near the end of the list.

**Open questions the report must answer:**
1. Does **Apple Music on Android** accept play-from-search (intent or session)?
2. Does an Apple Music link **start playback**, or only open the song page?
3. Does **YouTube Music** accept play-from-search?
4. Does any app **share its up-next queue**? (This decides Feature 2, Option A.)
5. Does seek work right after a handoff in each app?

### Local session plan (with the Android SDK on the owner's computer)

**Prefer the real Pixel over USB.** An emulator may not run Apple Music (no hardware copy protection), Spotify Free limits picking songs on mobile, and the app versions differ. Use an emulator only to test our own logic.

1. On the Pixel: **Settings → About phone →** tap Build number 7 times. Then **Developer options → USB debugging** on. Plug in and approve. If USB debugging is blocked, Advanced Protection is the likely cause; ask the owner before changing it.
2. `adb devices` should list the phone.
3. Build locally: `./gradlew assembleDebug`. The local debug key stays the same across builds, which also fixes the uninstall-per-update problem for local work. The first switch from the CI build needs one uninstall: `adb uninstall com.arktheogen.musicwidget`.
4. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
5. Grant access without the settings screens:
   `adb shell cmd notification allow_listener com.arktheogen.musicwidget/com.arktheogen.musicwidget.MediaListenerService`
6. Raw session facts, no app needed (a good cross-check): `adb shell dumpsys media_session`
7. **Done (`ddbbe04`):** write the report and each test-log line to logcat (for example tag `MusicWidgetDiag`), so the session reads results with `adb logcat -s MusicWidgetDiag`. Debug builds export `DiagnosticsActivity`: `adb shell am start -n com.arktheogen.musicwidget/.DiagnosticsActivity`.
8. The owner plays a song in one app. Run Search and Link tests to each other app. Repeat with each app as the source. Collect the report.

## 7. After the diagnostics

**Feature 1: handoff (switch app, keep the song).**
Flow: identify the song (title, artist, length, position) → start it in the target app using the proven method, with fallback to the next one → verify the match → seek to the same position → pause the old app. On a CLOSE match, play it and show "closest match".

**Feature 2: "YouTube Music picks, Apple Music plays".** Decide with the owner after the results:
- **A. Mirror YouTube Music's shared queue.** Only possible if YouTube Music shares it. Expect gaps, and Apple Music's own autoplay will interfere.
- **B. Unofficial YouTube Music access.** Against YouTube's terms and fragile. **Not recommended.**
- **C. Our app owns the queue** and plays through Apple's official Android kit (MusicKit for Android). Needs an Apple Developer account (US$99/yr). Next songs come from an official source such as Last.fm similar tracks, not the owner's personal YouTube Music picks.

No official way exists to get personal YouTube Music recommendations into another app. Say so plainly.

## 8. Other pending items

- Signing-key setup (README) so CI builds upgrade in place.
- Shuffle and repeat behaviour per app is still unconfirmed on the devices.
- Xiaomi Pad: not tested yet. Needs Autostart on and Battery saver → No restrictions.
- Unit tests for `Lyrics.parseLrc`, `cleanTitle` and `cleanArtist`, and for the match grading.
