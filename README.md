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
- Per track or album: pick from up to twelve candidate covers (each labelled with its source), use a picture from the gallery, or go back to the file's own cover. A fix applies to the whole album.
- "Repair artwork" in Settings works through the whole library in the background.
- Fixed covers reach the notification and lock screen, because the session's own bitmap loader decodes them in-process. Files are never modified.

### Lyrics (4.0)
- Synced lyrics in Now Playing: current line highlighted, the list follows playback, tap a line to seek, and ±0.5s offset nudges that are remembered per track.
- Sources in priority order: lyrics you added → embedded synced (SYLT or LRC in the lyrics tag) → a `.lrc` beside the track → a `.lrc` in your lyrics folder → cache → LRCLIB synced (exact, search, then search on the cleaned title and lead artist) → NetEase synced → embedded plain → cached/online plain (LRCLIB, NetEase, then lyrics.ovh). Online results are validated against the track before they are kept, and plain lyrics are labelled "Not synced".
- Add your own: the ⋯ button in the lyrics view (or the buttons when nothing is found) loads a `.lrc` or `.txt` from the phone, or takes pasted text. Timed LRC plays synced. Your lyrics outrank every other source until you remove them.
- Lyrics folder: Android 11+ hides other apps' `.lrc` files from a normal scan, so Settings → Lyrics folder lets you grant one folder. Every `.lrc` and `.txt` in it, however deep, is matched to songs by file name, "Artist - Title", "Title - Artist" or title.
- The lyrics view masks its edges into the backdrop, scales the sung line up, and on Premium and Ultra softens lines away from it; on Ultra the sung line glows.

### Sound and audio path (4.0)
- 10-band EQ with presets, preamp, ReplayGain (track/album, with untagged fallback), limiter, bass and spatial — Android audio effects on the player's session, so they work with the 24-bit float output path too.
- Bass is a shelf folded into the EQ and shaped by the output: the full low end on headphones; on the phone speaker the 125–500 Hz range a small speaker can play, with 31 Hz trimmed. It follows headphones and Bluetooth connecting and disconnecting. (Android's BassBoost switches itself off on the speaker, which is why it is no longer used.)
- Spatial is Android's Virtualizer, which only works on headphones; the Sound screen says so from the live state.
- Saved Sound settings are loaded when the playback service starts, so the EQ is on from the first song after a restart.
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
Each tier is a complete design (gutters, artwork resolution, motion) plus a visual engine (`Backdrop.kt`). Auto picks the highest tier the device's RAM, CPU and panel support, and steps down if frames start dropping.
- **Every tier:** colour from the cover. A Palette pass, tuned for a dark room, gives an accent (waveform, rings, lyric glow), a glow colour (a soft light behind the cover and its coloured shadow) and up to four deep tones. Fine grain is overlaid on the backdrop to break 8-bit banding.
- **Enhanced:** Now Playing sits on the cover itself, decoded at 20px and drawn full-screen with bilinear filtering (a blur on every Android version, one texture draw, no clock). With Reactive artwork on, the cover pulses and its glow swells on the beat.
- **Premium:** the backdrop slowly drifts, with a real RenderEffect blur on Android 12+, and a light field of three pools of the cover's tones drifts across it on seamless looping paths. Reactive artwork adds shockwave rings and the backdrop breathes with the bass; lyrics go soft away from the sung line.
- **Ultra:** the cover tilts with the phone (game rotation vector) and a specular sheen slides across it; the backdrop also turns and the light field has four pools that swell on the bass; Reactive artwork adds a flash of light that sweeps the cover on hard hits; the sung lyric line glows; the window asks for the panel's top refresh rate while Now Playing is open.
- The backdrop's scrim is only as dark as legibility needs and never reaches black, and the app asks for no system scrim behind the navigation bar, so the colour runs to the bottom edge on gesture and 3-button navigation alike.
- Settings lists what the selected tier gives you.

### Design and settings
- Original "three signals" brand mark with an animated splash.
- Editorial visual language: ink and cream, Fraunces serif over Inter sans, content-forward.
- Theme (Dark / Light / System) and text size (Compact / Default / Large).
- Edge-to-edge system bars in both themes.
- In-app About and Terms; hosted Privacy Policy.
- Settings order: Appearance, Library (storage, rescan), Display quality, Playback, Sound, Artwork & lyrics, About.
- Dialogs that take text (new playlist, paste lyrics) are real dialog windows: above the mini-player and nav bar, Back closes only them, and they stay clear of the keyboard.
- The long-press sheet scrolls, with Edit info as a quick action at the top.

### Privacy
No data collected. No accounts, analytics, ads, or tracking. All edits, history, and preferences stay in the app's private storage. Policy: https://bangscc10-dev.github.io/Media/privacy.html

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

---

## Known limitations

- Notification artwork tracks the song correctly on standard Android and Samsung; some OEM skins (e.g. Tecno/HiOS) cache the notification bitmap and may not refresh per song — device-side behavior outside the app's control.
- Podcasts/audiobooks are local-first (classified from on-device audio), not an online RSS client.
- Video notification artwork is not yet handled like audio album art.

---

## License

All rights reserved. Personal project.
