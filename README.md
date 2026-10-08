# Media

**All your media. One home.**

Media is a native Android media suite that brings your music, podcasts, audiobooks, and video into one calm, editorial home — so your library stops feeling scattered across a dozen apps. Everything plays locally from your device. No accounts, no tracking, no clutter.

---

## What it does

Media reads the audio and video already on your device and organizes it into four pillars:

- **Music** — your songs
- **Podcasts** — long-form spoken audio
- **Audiobooks** — books and long-form narration
- **Video** — everything the system recognizes as video, any format

It plays them with a proper background service (lock-screen and notification controls, playback that survives leaving the app), an editorial now-playing screen, live search, a browsable library, play history, and per-file manual organization.

---

## Features

### Playback
- Native background playback via Media3 `MediaSessionService` — audio continues when the app is closed, with notification and lock-screen controls.
- Editorial now-playing screen: art-forward layout, scrubber with time labels, and shuffle / repeat / playback-speed controls (1x, 1.25x, 1.5x, 2x).
- Notification and lock-screen artwork tracks the current song (via MediaStore album-art URIs).
- Synced play/pause state across cards, mini-player, and the full player.
- The mini-player is near-black glass: a hair off black with a whisper of the cover's colour, a hairline edge and a faint top light. Its progress line has no grey track behind it.
- Mini-player gestures: swipe left for the next song, right for the previous song, down to stop playback and close it, up (or tap) to open Now Playing. The pill follows the finger, so it shows what letting go will do. Its artwork and play control sit at mirrored insets.

### Artwork repair (4.0)
- Every cover is graded: missing, low resolution (under 300px), or a blank placeholder.
- Bad covers are replaced from Cover Art Archive, matched to the right release through MusicBrainz, then from Deezer and Apple Music (iTunes Search) when that comes up short. Store searches use the cleaned title and lead artist ("Laho (feat. X) [Official Video]" searches as "Laho"). Every candidate is scored against the file's own title, artist, album and length. Only confident matches (80%+) apply on their own, and a confident match with no picture falls through to the next instead of ending the attempt.
- Per track or album: pick from up to twelve candidate covers (each labelled with its source), use a picture from the gallery, or go back to the file's own cover.
- A fix spreads to the whole album only when it is a real album (`AlbumCoherence`): a proper name (not a site tag such as "Trendysongz.com"), one lead artist, and real track numbers when it has several tracks. Anything else MediaStore files under one album id is fixed track by track. Automatic fixes that were applied across such mixed "albums" are removed on the next library load; covers picked by hand stay.
- Every song shows its own picture. MediaStore's album-art uri (one picture per album id) is used only for real albums; other tracks use the file's own thumbnail (Android 10+) or embedded picture, so songs by different artists under one download tag no longer share a cover.
- "Repair artwork" in Settings works through the whole library in the background.
- Fixed covers reach the notification and lock screen, because the session's own bitmap loader decodes them in-process. Files are never modified.

### Lyrics (4.0)
- Synced lyrics in Now Playing: current line highlighted, the list follows playback, tap a line to seek, and ±0.5s offset nudges that are remembered per track.
- Sources in priority order: lyrics you added → embedded synced (SYLT or LRC in the lyrics tag) → a `.lrc` beside the track → a `.lrc` in your lyrics folder → cache → LRCLIB synced (exact, search, then search on the cleaned title and lead artist) → NetEase synced → embedded plain → cached/online plain (LRCLIB, NetEase, then lyrics.ovh). Online results are validated against the track before they are kept, and plain lyrics are labelled "Not synced".
- Add your own: the ⋯ button in the lyrics view (or the buttons when nothing is found) loads a `.lrc` or `.txt` from the phone, or takes pasted text. Timed LRC plays synced. Your lyrics outrank every other source until you remove them.
- Lyrics folder: Android 11+ hides other apps' `.lrc` files from a normal scan, so Settings → Lyrics folder lets you grant one folder. Every `.lrc` and `.txt` in it, however deep, is matched to songs by file name, "Artist - Title", "Title - Artist" or title.
- The lyrics view masks its edges into the backdrop, scales the sung line up, and on every tier softens lines away from it; on Premium and Ultra the sung line glows.

### Sound and audio path (4.0)
- 10-band EQ with presets, preamp, ReplayGain (track/album, with untagged fallback), limiter, bass and spatial — Android audio effects on the player's session, so they work with the 24-bit float output path too.
- Bass is a shelf folded into the EQ and shaped by the output: the full low end on headphones; on the phone speaker the 125–500 Hz range a small speaker can play, with 31 Hz trimmed. It follows headphones and Bluetooth connecting and disconnecting. (Android's BassBoost switches itself off on the speaker, which is why it is no longer used.)
- Spatial is Android's Virtualizer, which only works on headphones; the Sound screen says so from the live state.
- Saved Sound settings are loaded when the playback service starts, so the EQ is on from the first song after a restart.
- The ten sliders and the bass shelf are control points of one smooth curve (monotone cubic in log frequency: passes through every slider, never overshoots between them, flat past the ends; `EqCurve.kt`). Dynamics Processing renders it at 31 bands (1/3 octave) with 40ms frames for music, about 23Hz of resolution at 48kHz, so the 31Hz and 62Hz sliders are genuinely separate; video switches to 10ms frames so lip-sync holds. If a phone refuses that, it falls back to 10 bands, then to the classic Equalizer sampled from the same curve at the phone's own band centres. Only bands that changed are sent to the audio server.
- The limiter is a near-brickwall ceiling at -1dBFS (20:1, 1ms attack), so a +12dB boost stays under full scale.
- The Sound screen draws the response actually applied right now, for the current output, and readouts show half-dB steps exactly.
- Compatibility mode (Sound → Engine) switches from Dynamics Processing to Android's classic equaliser, for phones where Dynamics Processing attaches but does nothing. The screen shows which engine is running.
- Audio path sheet: source format → decoder → sound chain → Android mixer (with resampling shown) → the actual output device.
- Now Playing shows the real format under the artist ("FLAC · 24-bit · 96 kHz", marked Hi-Res when it is).
- Track details: codec, bit depth, sample rate, bitrate, tags, MusicBrainz IDs, ReplayGain, artwork and lyrics sources, and a MusicBrainz identify that can correct the title and artist as an ordinary edit.
- A built-in tag reader for ID3v2.2–2.4, FLAC, Ogg Vorbis/Opus, MP4/ALAC, WAV and AIFF.

Online lookups (MusicBrainz, Cover Art Archive, Deezer, Apple's iTunes Search, LRCLIB, NetEase, lyrics.ovh) are off until switched on in Settings, or used for one track by hand; only title, artist, album and length are sent. There is no Google image lookup: Google has no public image-search API, and scraping its pages is against its terms.

### Organization
- Automatic classification of audio into pillars via a cascade: folder (Audiobooks/Podcasts/Music) → filename contains "podcast" → duration over 10 minutes = podcast, else music.
- Manual override: long-press any item to rename, set artist/host/author (field adapts to pillar), add optional details, and move it between Music / Podcasts / Audiobook. Overrides beat the automatic rules and persist across sessions and app updates (real DB migrations, no data loss). "Reset to automatic" clears an override.

### Browsing
- Home: editorial feed with a Continue row (real play history, most-recent-first, deduplicated) plus per-pillar shelves.
- Library: full filterable list (All / Music / Podcasts / Audiobooks / Video).
- Live search across the whole library.
- Dedicated Podcasts and Audiobooks tabs.

### Display quality (Auto / Enhanced / Premium / Ultra)
Each tier is a complete design (gutters, artwork resolution, motion) plus a visual engine (`Backdrop.kt`, `CoverLight.kt`, `FluidLight.kt`). Auto picks the highest tier the device's RAM, CPU and panel support. In 4.8 every tier moved up one: the old Premium is now Enhanced, the old Ultra is now Premium, and Ultra is new.
- **Every tier:** colour from the cover. A Palette pass, tuned for a dark room, gives an accent (waveform, rings, lyric glow), a glow colour (the cover's coloured shadow) and up to four deep tones. Fine grain is overlaid on the backdrop to break 8-bit banding.
- **Cover light** (`CoverLight.kt`), every tier. Made once per song on a background thread and cached, shared by Now Playing and the rest of the app:
  - *Smooth backdrop:* the cover reduced to 20px (its colour, none of its detail), brought up to 128px and box-blurred three times. Stretched across the screen it is smooth light. The 20px texture stretched directly showed its pixels as soft steps (a circle came out an octagon), which the old per-frame 24–32dp blur never fully removed and Android 11 and below never blurred at all. The per-frame blur is gone.
  - *Edge light:* a 64px texture with the cover in the middle and each border pixel carried out to the edge, blurred and faded out. Drawn behind the cover at twice its size, the colour at each edge spills past it. It swells on the beat with Reactive artwork, and its strength is capped per cover so a white cover's halo never sits bright under the title.
  - *Measured scrim:* Now Playing measures each cover and dims a bright one more (dark theme: the brightest 15% down to luma 0.30; light theme: the darkest 15% up to 0.72), never lighter than its designed gradient.
- **Enhanced** (the old Premium): Now Playing sits on the cover as smooth coloured light; the backdrop drifts, with a light field of three pools of the cover's tones on seamless looping paths; Reactive artwork pulses the cover, sends shockwave rings off it on the beat and makes the backdrop breathe with the bass; lyrics go soft away from the sung line (real blur on Android 12+); 72-bar waveform.
- **Premium** (the old Ultra): parallax with depth — the cover tilts with the phone (game rotation vector), a specular sheen slides across it, and the backdrop moves the other way behind it as the deepest layer, the light field half as far; the backdrop also turns and the light field has four pools; Reactive artwork adds a flash of light that sweeps the cover on hard hits; the sung lyric line glows; the window asks for the panel's top refresh rate while Now Playing is open; 96-bar waveform.
- **Ultra** (new): fluid artwork on Android 13+ (`FluidLight.kt`). An AGSL shader samples the smoothed cover through a slowly moving two-layer warp field under one slow turn, so the cover's colours flow into one another like liquid, in Now Playing and across the whole app; with Reactive artwork the bass pushes the flow. Every frequency is a whole multiple of the loop's phase, so the three-minute loop has no seam. A fifth pool joins the light field, the waveform has 120 bars, and list artwork is larger. A shader the driver rejects turns the flow off and Ultra draws as Premium; below Android 13 it turns as Premium does.
- **The whole app takes the song's colour** (`AppBackdrop.kt`), on every tier. While a song plays, Home, Library, Playlists, Search, Settings, Sound, the album and artist pages and the bottom bar sit on the same cover light as Now Playing, through the same filter. The cover light is made once and the clocks run once; every screen draws that one state in window coordinates, so a screen opened over Home covers it exactly and the bottom bar continues the picture. It is calm: no beat, and the tier sets the motion (Enhanced drifts with the light field, Premium also turns, Ultra flows). Each screen's content sits in its own layer above this background, so the moving background redraws alone and the rows and text on top are not recorded again every frame. The scrim is measured per cover: in the dark theme the brightest 15% of the cover is dimmed to a fixed dark level, in the light theme the darkest 15% is lifted to a fixed light level, so text contrast is the same on a white cover and a navy one. When nothing is playing (stopped, or paused until the mini-player hides) the room fades back to the plain floor. Video keeps the plain floor.
- **Frame cost:** every drawing that changes each frame (the edge light, the beat rings, the waveform, the backdrop) has its own layer, so a beat redraws that drawing and not the player around it; the beat rings read the frame clock in the draw phase and no longer recompose every frame.
- **Sustained frame rate** (`Performance.kt`): on Auto, if frames keep dropping, the tier steps down. At Enhanced, which has no tier below, it lightens instead, one step at a time: the flow, turn and per-line lyric blur, then the light field, then the drift. Settings says when either has happened. A manual pick is never changed; Settings offers Auto instead.
- The backdrop's scrim never reaches black, and the app asks for no system scrim behind the navigation bar, so the colour runs to the bottom edge on gesture and 3-button navigation alike.
- Settings lists what the selected tier gives you.

### Design and settings
- Original "three signals" brand mark with an animated splash.
- Editorial visual language: ink and cream, Fraunces serif over Inter sans, content-forward.
- Theme (Dark / Light / System) and text size (Compact / Default / Large).
- Edge-to-edge system bars in both themes.
- In-app About and Terms; hosted Privacy Policy.
- Google Play In-App Review: the rating card is requested when Now Playing closes, and only after 20 qualifying plays over at least three days, at most every 120 days (Play applies its own quota too). Settings → About → "Rate Via" opens the Play listing rather than the in-app flow, as Google requires for buttons.
- Google Play In-App Updates (`InAppUpdate.kt`): when the app opens and Play has a newer version, Play's own sheet offers it. Accepted, it downloads in the background while music keeps playing, and a banner above the mini-player says when it is ready; Restart installs it (playback stops for the restart, so it only ever happens on that tap). Every update is offered the flexible way; only a release published with in-app update priority 4 or 5 (set through the Play Developer API) takes the full-screen immediate flow. Offered once per app open; "No thanks" holds that version back for three days, and a newer version is offered at once. Play answers only for installs it made, so debug and sideloaded builds never show it.
- Settings order: Appearance, Library (storage, rescan), Display quality, Playback, Sound, Artwork & lyrics, About.
- Dialogs that take text (new playlist, paste lyrics) are real dialog windows: above the mini-player and nav bar, Back closes only them, and they stay clear of the keyboard.
- The long-press sheet scrolls, with Edit info as a quick action at the top.

### Languages (4.7)
- English, Spanish, Portuguese (Brazil), French, German, Russian, Turkish, Indonesian, Hindi, Vietnamese and Japanese.
- Automatic by default. With nothing picked, Android resolves every string against the phone's own language list, so the app opens in the phone's language and follows it when it changes. A phone set to a language Via doesn't ship falls through its list to the next one Via does, then to English.
- Settings → Appearance → Language overrides that for Via alone, with each language listed in its own name. On Android 13+ the choice is the system's per-app language, so it also appears in Settings → Apps → Via → Language (the list comes from `res/xml/locales_config.xml`). On Android 12 and below the app stores the choice and applies it as each screen and service starts (`Language.kt`).
- Library placeholders ("Unknown artist", "Untitled", recorder titles, EQ preset names) are stored in English and translated only where they are shown, so matching, keys and saved choices never depend on the language. Recording dates are formatted in the chosen language.
- The Terms of Use stay in English, the governing text; the other languages show a one-line note saying so.
- Every language ships in the base APK (`bundle.language.enableSplit = false`). Play would otherwise install only the phone's own languages from the bundle, and a language picked in the app would have nothing to switch to.

### Privacy
The app collects nothing itself: no accounts, no analytics. Settings → Privacy has "Ad privacy choices" wherever Google's consent SDK says the law requires it (EEA, UK, Switzerland, and the US states configured in AdMob): Google's own form, to change or withdraw consent or opt out of sale/sharing. It also links to Android's advertising-ID settings and the privacy policy. All edits, history, and preferences stay in the app's private storage. The free version shows one adaptive banner in Settings from Google AdMob, requested only after Google's consent flow says ads may be requested (UMP `canRequestAds()`), sized to the card it sits in so it is never cropped, and paused with the app. A one-time purchase removes it. Policy source: `docs/privacy.html`; the app links to https://mebs.app/privacy/aura.

---

## Tech stack

- Kotlin, Jetpack Compose (Material 3)
- AndroidX Media3 (ExoPlayer, MediaSession)
- Room (overrides, play history), DataStore (preferences)
- Fraunces + Inter variable fonts
- Min SDK 24, Target/Compile SDK 36
- Gradle 8.9, AGP 8.7.2, Kotlin 2.0.20, KSP

---

## Building

Developed entirely in GitHub Codespaces (JDK 17, Android SDK cmdline-tools, platform 36).

Debug:
    ./gradlew assembleDebug

Release (R8 minified, signed, ARM64/ARM32 split, ~4MB):
    ./gradlew assembleRelease

Play bundle:
    ./gradlew bundleRelease

Signing is via keystore.properties (git-ignored, never committed).

### Release check

4.5 crashed at launch with a `VerifyError` in `HomeScaffold`. The method had
grown to need more than 256 Dalvik registers (the verifier names `v258`), and
past that limit the compiler moved an object reference with a plain `move`,
which ART rejects, so the class could not load. The build that crashed was
unminified: its trace keeps real class names and line numbers. Unminified
DEX keeps every local alive for the debugger, so the same code needs far more
registers there than in R8's release output. The big composables
(`HomeScaffold`, `PlayerSurface`) are now split into small ones.

`tools/dex_registers.py` reads the DEX in a build (a folder, an APK or an
AAB) and lists the methods with the most registers. It exits 1 if any method
of the app's own package is over 255:

    python3 tools/dex_registers.py app/build/outputs/apk/debug/app-arm64-v8a-debug.apk --package com.media.app
    python3 tools/dex_registers.py app/build/outputs/bundle/release/app-release.aab --package com.media.app

The `Release check` GitHub workflow builds both release (R8) and debug on
every push and runs this check on each. Run by hand (Actions, Release check,
Run workflow) it takes a commit to build, so any older version can be checked.

### Translations

English is `res/values/strings.xml`; each language is `res/values-xx/strings.xml` (`values-in` is Indonesian, Android's legacy code for it, and `values-pt` is Brazilian Portuguese). `tools/check_translations.py` checks every language against English: every string and plural present and nothing extra, exactly the same format placeholders, the plural forms each language's rules use, and escaping. It exits 1 on any problem:

    python3 tools/check_translations.py

The `Release check` workflow runs it on every push.

---

## Known limitations

- Notification artwork tracks the song correctly on standard Android and Samsung; some OEM skins (e.g. Tecno/HiOS) cache the notification bitmap and may not refresh per song — device-side behavior outside the app's control.
- Podcasts/audiobooks are local-first (classified from on-device audio), not an online RSS client.
- Video notification artwork is not yet handled like audio album art.
- Right-to-left languages (Arabic, Hebrew, Persian, Urdu) are not shipped: the layouts have not been mirrored and checked.

---

## License

All rights reserved. Personal project.
