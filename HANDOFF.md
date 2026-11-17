# Media — Project Handoff

Last updated: August 2026
Repo: https://github.com/bangscc10-dev/Media
Current HEAD: album-art notification fix (commit 2783173) + README

---

## 1. What this is

Media is a native Android media suite (Kotlin + Jetpack Compose + Media3) that
organizes on-device music, podcasts, audiobooks, and video into one editorial
home. Local-only, no accounts, no tracking. Built entirely in GitHub Codespaces
with no local Android Studio.

Package: com.media.app
App name: Media
Min SDK 24, Target/Compile SDK 36.

---

## 2. Architecture overview

Single-activity Compose app. All screens are Composable overlays toggled by
boolean state in HomeScaffold (MainActivity.kt) — there is no Navigation
component; screens are shown via `if (showX) { ScreenX() }` and dismissed by
BackHandlers.

Data flow:
  MediaStore (device files)
    -> MediaRepository (reads audio/video, classifies into pillars, applies overrides)
    -> AppMediaItem (the core data model)
    -> UI (shelves, lists, player)

Playback:
  PlaybackService (Media3 MediaSessionService, owns the real ExoPlayer)
    <- MediaController (in PlayerViewModel, a remote handle to the service)
    <- UI calls (play, pause, seek, shuffle, repeat, speed)

Persistence:
  Room (OverrideDb.kt): MediaOverride (manual edits) + PlayHistory (Continue row)
  DataStore (SettingsStore.kt): theme mode + font scale

---

## 3. File-by-file

MainActivity.kt      Root activity, HomeScaffold, all screen state + wiring,
                     BackHandlers, permission gate, edge-to-edge system bars,
                     the FullPlayer (now-playing) composable, MediaCard, shelves.
Theme.kt             Design tokens (Palette class), Dark + Light palettes,
                     MediaColors shim (composable getters), typography (Fraunces/
                     Inter with font scaling), MediaTheme.
MediaRepository.kt    MediaStore queries, the pillar classification cascade,
                     override application, album-art URI construction, caching.
PlayerViewModel.kt    MediaController lifecycle, PlayerState, position updates,
                     play() (builds MediaItems), playOrToggle, shuffle/repeat/
                     speed, 5-second play-history recording, updateCurrentMetadata.
PlaybackService.kt    Minimal Media3 MediaSessionService. Owns ExoPlayer + session.
CoverArt.kt          Composable: loads embedded art (MediaMetadataRetriever),
                     falls back to a deterministic tinted serif-letter tile.
OverrideDb.kt        Room DB v3. Entities: MediaOverride, PlayHistory. DAOs.
                     Migrations 1->2 (add play_history), 2->3 (add customArtPath,
                     currently unused — see section 6).
SettingsStore.kt      DataStore prefs: ThemeMode, fontScale, flows + setters.
SearchScreen.kt       Live filter across all audio + video.
LibraryScreen.kt      Filterable full list (accepts initialPillar for See-all).
PodcastsScreen.kt     2-col grid + empty state.
AudiobooksScreen.kt   2-col grid + empty state.
SettingsScreen.kt     Theme picker, text-size picker, rescan (with animation),
                     About/Terms/Privacy rows.
InfoScreens.kt        AboutScreen + TermsScreen (in-app text).
EditSheet.kt         Long-press modal: rename, adaptive artist/host/author field,
                     details, pillar picker, save, reset-to-auto.

res/font/            fraunces_variable.ttf, inter_variable.ttf (SIL OFL).
res/drawable/        ic_launcher_foreground (three signals), splash_signals +
                     splash_signals_animated (staggered rise).
res/values/          themes.xml (splash theme + Theme.Media), colors, strings,
                     splash_colors, ic_launcher_background (cream).
docs/privacy.html    Privacy policy, served via GitHub Pages.

---

## 4. The pillar classification cascade (MediaRepository.classifyAudio)

For each audio file, in order (first match wins):
  1. Path contains "audiobooks/"  -> AUDIOBOOK
  2. Path contains "podcasts/"    -> PODCAST
  3. Path contains "music/"       -> MUSIC
  4. Title contains "podcast"     -> PODCAST
  5. Duration > 10 minutes        -> PODCAST
  6. else                         -> MUSIC

Manual overrides (Room) sit ABOVE this — a user's saved pillar choice wins over
all heuristics. Video is always VIDEO, no rules.

---

## 5. Build & release

Prereqs in Codespace (installed once, may need reinstall on new Codespace):
  - JDK 17 (Temurin) at ~/.jdks, JAVA_HOME in ~/.bashrc
  - Android SDK cmdline-tools at ~/android-sdk, ANDROID_HOME in ~/.bashrc
  - platforms;android-36, build-tools;36.0.0, platform-tools

Commands:
  ./gradlew assembleDebug     -> debug APK (~20MB)
  ./gradlew assembleRelease   -> signed release APKs (~4MB, ARM64 + ARM32)
  ./gradlew bundleRelease     -> AAB for Play (NOT yet done — see remaining work)

Release config: R8 minify + resource shrink + ABI split (arm64-v8a, armeabi-v7a,
no universal). ProGuard keep-rules in app/proguard-rules.pro cover Media3, Room,
DataStore, SplashScreen, and data models.

Signing: keystore.properties -> media-release.keystore (alias "media").
BOTH are git-ignored and MUST NEVER be committed. The keystore was backed up as
base64 during development — VERIFY that backup exists off-Codespace. If the
keystore is lost, the app can never be updated on Play again.

The app uses Play App Signing: Google holds the app signing key, and the key
above is only the upload key, which Play Console can reset (App signing >
Upload key > Request reset). On 2026-11-17 the Codespace was out of quota, so
a reset was requested to a NEW upload key (alias "upload"). Once Google
confirms, only that key is accepted; the old "media" key no longer is.

GitHub Actions builds signed releases now (.github/workflows/release.yml,
Actions > Release > Run workflow, or push a tag like v6.0.3). It reads the
upload key from repository secrets KEYSTORE_BASE64, KEYSTORE_PASSWORD,
KEY_ALIAS and KEY_PASSWORD, writes keystore.properties on the runner, and
keeps the signed .aab and R8 mapping on the run page. Bump versionCode
before each Play upload.

Git note: GITHUB_TOKEN in Codespaces sometimes breaks pushes. Fix with
`unset GITHUB_TOKEN` before git commands, and gh auth is set up via
`gh auth login` + `gh auth setup-git` if pushes fall back to anonymous.

---

## 6. BUGS / UNRESOLVED

### 6.1 Notification artwork stale on some OEM skins (PARTIALLY FIXED)
Status: works on standard Android + Samsung, fails on Tecno/HiOS.
What works: notification/lock-screen art uses MediaStore album-art URIs
  (content://media/external/audio/albumart/{albumId}), set as artworkUri in
  PlayerViewModel.play(). On Samsung and standard Android the art correctly
  changes per song.
What fails: on Tecno (HiOS skin), the notification caches the first artwork
  bitmap and does not refresh when the song changes.
What we tried and REVERTED:
  - setArtworkData (raw bytes): made working art go blank. Reverted.
  - FileProvider content:// URIs from extracted art: URI permission issues.
  - Forcing a metadata re-apply in onMediaItemTransition (replaceMediaItem):
    CRASHED the app (re-entrant transition loop). Reverted.
Conclusion: this is a device-side OEM caching bug. The current code is the
  correct standard implementation. Do NOT re-attempt the transition-refresh
  hack — it crashes. If revisited, the safe direction is testing whether a
  MediaLibrarySession (vs plain MediaSession) or a custom notification via
  DefaultMediaNotificationProvider behaves differently on HiOS.

### 6.2 Upload custom cover art (STARTED, NOT SHIPPED)
Status: fully built once, then REVERTED because notification delivery failed.
What was built (all reverted): DB migration 2->3 adding customArtPath column
  (this migration IS still in OverrideDb.kt and harmless — the column exists but
  nothing writes to it), a system photo picker in EditSheet, copying picked
  images to filesDir/covers/<id>.jpg, CoverArt preferring the custom file, and
  repository applying customArtPath as artworkUri.
Why reverted: in-app display worked, but the custom cover did not appear on the
  notification/lock screen — a file:// URI to app-private storage can't be read
  by the system notification process. The fix (untried) is to serve custom
  covers through a FileProvider content:// URI with proper read grants, and
  verify Media3 forwards the grant to the session. Build the delivery FIRST,
  verify on the notification, THEN rebuild the picker UI.
Note: the customArtPath DB column already exists (migration 2->3 shipped in
  commit history is NOT present — the migration was in the reverted work, so
  the current DB is at whatever version the last commit has. VERIFY the current
  DB version in OverrideDb.kt before adding more migrations.)

### 6.3 Video notification artwork
Video items have no artworkUri (album-art URIs are audio-only). Video shows no
notification thumbnail. Would need MediaStore video thumbnail URIs.

---

## 7. REMAINING WORK (not bugs — unbuilt)

Ship prep:
  - Build the AAB (./gradlew bundleRelease) — Play requires it, never done.
  - Play Console listing: store description (use "All your media. One home."),
    screenshots, feature graphic, category (Music & Audio), content rating.
  - Final full-device shakeout of the release build across every screen.
  - Confirm keystore backup exists off-Codespace.

Deferred features:
  - Upload custom cover art (see 6.2).
  - Real RSS podcast subscriptions (currently local-first only).
  - Video thumbnails in notification (see 6.3).
  - "See all" exists; verify all shelves wire to it.
  - Details field shows on cards only when set (shipped) — no detail view screen.

---

## 8. Design decisions / rationale (so they aren't undone)

- Name is "Media" — deliberately chosen despite generic-ness; the Play Store
  slot was open. Package com.media.app is generic and may need changing before
  Play submission if it collides.
- Editorial identity: ink (#0B0B0F) + warm cream (#F4EFE6), Fraunces serif for
  display/headers, Inter for body. Accent purple (#7C5CFF) used ONLY for active
  state and progress — never decorative. This restraint is intentional; do not
  add decorative color.
- Content is the color: album art carries the palette, chrome recedes.
- No Navigation component by design — boolean overlays + BackHandlers. Simple,
  but means back-stack behavior is manual (see the BackHandler chain in
  HomeScaffold; order matters — topmost overlay closes first).
- Real DB migrations always (no destructive fallback) so user edits survive
  updates.
- Classification thresholds (10 min) tuned for 2026 music/podcast norms.

---

## 9. Known gotchas for the next developer

- MediaColors.X are @Composable getters (theme-aware) — can't be used outside
  composable scope. If you need a color in non-composable code, read the Palette
  directly.
- The data class is AppMediaItem, aliased because it collides with Media3's own
  MediaItem. Keep the alias (ExoMediaItem) in PlayerViewModel.
- MediaType (AUDIO/VIDEO) enum name once collided with a Typography val named
  MediaType — the typography is now MediaTypography. Don't reintroduce the clash.
- Fraunces/Inter are variable fonts; Compose loads one default weight.
- enableEdgeToEdge + isNavigationBarContrastEnforced=false is what kills the
  white nav strip. Don't remove either.
- The heredoc/python-patch workflow was used throughout; str_replace-style edits
  must match exact whitespace.

