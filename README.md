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

### Artwork repair (4.0)
- Every cover is graded: missing, low resolution (under 300px), or a blank placeholder.
- Bad covers are replaced from Cover Art Archive, matched to the right release through MusicBrainz and scored against the file's own title, artist, album and length. Only confident matches (80%+) apply on their own; anything less is offered as a choice.
- Per track or album: pick from up to nine candidate covers, use a picture from the gallery, or go back to the file's own cover. A fix applies to the whole album.
- "Repair artwork" in Settings works through the whole library in the background.
- Fixed covers reach the notification and lock screen, because the session's own bitmap loader decodes them in-process. Files are never modified.

### Lyrics (4.0)
- Synced lyrics in Now Playing: current line highlighted, the list follows playback, tap a line to seek, and ±0.5s offset nudges that are remembered per track.
- Sources in priority order: embedded synced (SYLT or LRC in the lyrics tag) → a `.lrc` beside the track → cache → LRCLIB synced → embedded plain → cached/online plain. Online results are validated against the track before they are kept, and plain lyrics are labelled "Not synced".

### Sound and audio path (4.0)
- 10-band EQ with presets, preamp, ReplayGain (track/album, with untagged fallback), limiter, bass and spatial — Android audio effects on the player's session, so they work with the 24-bit float output path too.
- Audio path sheet: source format → decoder → sound chain → Android mixer (with resampling shown) → the actual output device.
- Now Playing shows the real format under the artist ("FLAC · 24-bit · 96 kHz", marked Hi-Res when it is).
- Track details: codec, bit depth, sample rate, bitrate, tags, MusicBrainz IDs, ReplayGain, artwork and lyrics sources, and a MusicBrainz identify that can correct the title and artist as an ordinary edit.
- A built-in tag reader for ID3v2.2–2.4, FLAC, Ogg Vorbis/Opus, MP4/ALAC, WAV and AIFF.

Online lookups (MusicBrainz, Cover Art Archive, LRCLIB) are off until switched on in Settings, or used for one track by hand; only title, artist, album and length are sent.

### Organization
- Automatic classification of audio into pillars via a cascade: folder (Audiobooks/Podcasts/Music) → filename contains "podcast" → duration over 10 minutes = podcast, else music.
- Manual override: long-press any item to rename, set artist/host/author (field adapts to pillar), add optional details, and move it between Music / Podcasts / Audiobook. Overrides beat the automatic rules and persist across sessions and app updates (real DB migrations, no data loss). "Reset to automatic" clears an override.

### Browsing
- Home: editorial feed with a Continue row (real play history, most-recent-first, deduplicated) plus per-pillar shelves.
- Library: full filterable list (All / Music / Podcasts / Audiobooks / Video).
- Live search across the whole library.
- Dedicated Podcasts and Audiobooks tabs.

### Design and settings
- Original "three signals" brand mark with an animated splash.
- Editorial visual language: ink and cream, Fraunces serif over Inter sans, content-forward.
- Theme (Dark / Light / System) and text size (Compact / Default / Large).
- Edge-to-edge system bars in both themes.
- In-app About and Terms; hosted Privacy Policy.

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
