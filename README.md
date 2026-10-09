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
- Editorial now-playing screen: art-forward layout, scrubber with time labels, and shuffle / repeat / A-B loop / speed / sleep controls. Speed opens a sheet with 0.75x, 1x, 1.25x, 1.5x and 2x for everyone; Via Pro adds a fine slider and pitch (below).
- Notification and lock-screen artwork tracks the current song (via MediaStore album-art URIs).
- Synced play/pause state across cards, mini-player, and the full player.
- The mini-player is near-black glass: a hair off black with a whisper of the cover's colour, a hairline edge and a faint top light. Its progress line has no grey track behind it.
- Mini-player gestures: swipe left for the next song, right for the previous song, down to stop playback and close it, up (or tap) to open Now Playing. The pill follows the finger, so it shows what letting go will do. Its artwork and play control sit at mirrored insets.

### Via Pro (5.1)
One payment, kept for good, at the price set in the Play Console (shown from Play, in the buyer's currency; never written in the app). Pro is the Play product `remove_ads`: a product id can never be renamed, and keeping it means everyone who bought ad removal is Pro on update with nothing to restore. `Billing.kt` stays the source of truth: purchases are acknowledged, restored on every launch and revoked on a refund. The cached flag only stands in until Play answers; once Play has answered, only Play decides, so a phone where Play never answers cannot keep Pro from a stale cache.

- **Where it shows:** the PRO badge in the Home header (`ProBadge`): for free users a dark pill with a gold edge, crown and PRO in gold, the way to the Pro page; for owners solid gold with a dark crown, a gold glow and a sweep of light across it every few seconds, a status. Also a Via Pro row in Settings, and every locked control: tapping one opens the Pro page instead of doing nothing.
- **The Pro page** (`ProScreen.kt`): Free and Pro side by side, the buy button with Play's price, "One payment. No subscription.", and Restore purchase, which asks Play again.
- **Premium and Ultra display quality** are Pro's (`QualityLevel.forPro`). Without Pro the app draws Enhanced, whatever was picked and whatever Auto would choose; the pick is kept, so buying or restoring Pro brings it straight back. In Settings the two tiers show a crown and open the Pro page. The entitlement is seeded only from the real cached value, never an optimistic first frame, so a free user's app never flashes Ultra at launch.
- **What Pro adds** (`ProAudio.kt`, all inside the player in `PlaybackService`, so it works from the notification, lock screen and Bluetooth buttons too):
  - *A-B loop:* the A-B button on Now Playing marks A, then B, then lets go. A `PlayerMessage` at B is delivered by the player's own clock every time playback reaches B, so there is no polling and no drift. Repeats: endless, 3, 5 or 10, then playback moves on. The section shows on the scrubber; changing track ends the loop.
  - *Fine speed and pitch:* 0.5x to 3x in 0.05 steps, and pitch from -6 to +6 semitones independent of speed (slow a song down and keep its key).
  - *Skip silence*, Media3's silence skipping.
  - *Smart resume:* a recording longer than ten minutes, resumed after a pause of 30 seconds or more, steps back 5 seconds (10 after five minutes, 15 after an hour).
  - *Soft play and pause:* the session's player is wrapped (`ProPlayer`), so a pause fades out over 200ms and a play fades in over 260ms; the volume is put back exactly, so the sleep timer's own fade is undisturbed.
  - *Mono and balance:* an audio processor first in the player's chain mixes the channels per sample (16-bit and float stereo), live, with no restart.
  - No ads anywhere, and the crown.
- **Where Pro playback stops:** high-resolution files go to the float output untouched, and Media3 runs no audio processors on that path, so mono, balance and skip silence leave them alone; the Sound screen says so.
- The Pro settings live on the Sound screen (Pro playback) and in the speed sheet; free users see them with every switch off, and a tap opens the Pro page.

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

### Sound and audio path (4.0, Depth and Space 5.5)
- **Sound is for what you listen through.** Each output keeps its own tuning, remembered by the device's own name: the over-ears, the earbuds, the Bluetooth speaker and the car each have their own EQ, Depth, Space and device type. The Sound screen opens on the output playing right now (`OutputCard`), with Headphones / Speaker / Car guessed from the device (its Android type, and for Bluetooth words in its name like "Buds", "Flip" or a car maker) and corrected with one tap. The phone's own speaker plays the music as it is: no EQ, no Depth, no Space, and the screen says so instead of showing dead sliders. Loudness (preamp, ReplayGain, limiter) is about the files, so it is the same everywhere. A device seen for the first time starts from the single pre-5.5 settings, so nobody's EQ disappears on update.
- **Depth and Space are Via's own processing** (`SoundStage.kt`), run on the samples inside the player's audio sink (`SoundSink.kt`) before Media3 sees them. That is the one point every format passes: Media3 skips its own audio processors for 24-bit and float audio, so a processor there would have missed exactly the hi-res files. 16, 24 and 32-bit integer and float stereo are processed and written back in the same format (16-bit with TPDF dither while the stage works); mono, surround and passthrough go straight by. With both at zero, or on the phone speaker, the decoder's own buffer is handed on and not one sample is rewritten. The sink keeps Media3's contract for part-taken buffers by keeping the processed copy and mirroring what was taken onto the decoder's buffer.
- **Depth: deep bass that is felt, not boom.** A band boost under the bass, shaped per device type: headphones +9 dB centred near 40 Hz and reaching to 20 Hz, with 150–300 Hz slightly lower so the low end is tight; a Bluetooth speaker cannot play 40 Hz, so its boost sits at 60–120 Hz and the octave below is turned into its own 2nd and 3rd harmonics (about 13 dB under the tone), which the ear hears as the missing fundamental; a car cabin already lifts the lowest octave, so it gets +6 dB. The boost follows the music: a thin mix gets all of it, a heavy one half (by the bass level over 0.4 s), and each kick's attack is lifted up to 1.5 dB more than its sustain in the first 60 ms.
- **Space: the music in front of you, not inside your head.** On headphones the two channels become two speakers in a room. Each ear hears the near speaker plus the highs of the far one, and the far ear the lows of it, through a complementary one-pole pair at 700 Hz, so a centred voice sums back to itself exactly: no comb of notches (plain crossfeed puts an 11 dB notch at 1.5 kHz into every centred vocal; measured with noise in third-octave bands the centre stays within 0.9 dB at 100%). Then the room: eleven first reflections per speaker from an image-source model of a 5 x 6 x 2.8 m room, each reaching each ear with its own delay and level for its direction, the listener a little off centre so the two speakers' reflections do not stack; a four-line feedback delay network for a 0.4 s tail whose level is set at its input, so turning Space down lets it ring out; and the recording's own stereo ambience sent round the sides 12 ms late. Speakers and cars get a wider image instead: the stereo difference raised above 180 Hz, the centre untouched.
- **It never clips.** Whatever Depth adds is held under −0.15 dBFS by a 3 ms look-ahead limiter that only turns the added bass down, never the music under it; a second, wideband stage catches anything the room adds. A quarter of the boost comes off the overall level, so a loud master has room for it: on a master at −9 dBFS RMS, 40 Hz still comes out 5 dB above the mids.
- **Nothing clicks.** Every part runs on every sample while the stage works and only gains move, so turning Space up never reads a delay line that stopped being fed; switching the stage on or off crossfades over 20 ms; switching device type fades Depth out, rebuilds its filters in silence and fades it back.
- Tested off the device (the stage is plain Kotlin): off is bit-exact and zero-copy in all four formats; on matches the stage through part-taken buffers; Depth measures within 0.4 dB of the curve the Sound screen draws; steady bass comes out with −69 dB distortion; nothing exceeds the ceiling at 44.1, 48, 96 or 192 kHz with everything at 100%; the cost is about 1% of one desktop core at 48 kHz.
- 10-band EQ with presets, preamp, ReplayGain (track/album, with untagged fallback) and limiter are Android audio effects on the player's session, so they work with the 24-bit float output path too.
- Saved Sound settings are loaded when the playback service starts, so the EQ is on from the first song after a restart.
- The ten sliders are control points of one smooth curve (monotone cubic in log frequency: passes through every slider, never overshoots between them, flat past the ends; `EqCurve.kt`). Dynamics Processing renders it at 31 bands (1/3 octave) with 40ms frames for music, about 23Hz of resolution at 48kHz, so the 31Hz and 62Hz sliders are genuinely separate; video switches to 10ms frames so lip-sync holds. If a phone refuses that, it falls back to 10 bands, then to the classic Equalizer sampled from the same curve at the phone's own band centres. Only bands that changed are sent to the audio server.
- The limiter is a near-brickwall ceiling at -1dBFS (20:1, 1ms attack), so a +12dB boost stays under full scale.
- The Sound screen draws the response actually applied right now for the current output, the EQ and the shape Depth gives the low end together, and readouts show half-dB steps exactly.
- Compatibility mode (Sound → Engine) switches from Dynamics Processing to Android's classic equaliser, for phones where Dynamics Processing attaches but does nothing. The screen shows which engine is running.
- Audio path sheet: source format → decoder → sound chain (Depth, Space, gain, EQ, limiter, in signal order) → Android mixer (with resampling shown) → the actual output device.
- Now Playing shows the real format under the artist ("FLAC · 24-bit · 96 kHz", marked Hi-Res when it is).
- Track details: codec, bit depth, sample rate, bitrate, tags, MusicBrainz IDs, ReplayGain, artwork and lyrics sources, and a MusicBrainz identify that can correct the title and artist as an ordinary edit.
- A built-in tag reader for ID3v2.2–2.4, FLAC, Ogg Vorbis/Opus, MP4/ALAC, WAV and AIFF.

Online lookups (MusicBrainz, Cover Art Archive, Deezer, Apple's iTunes Search, LRCLIB, NetEase, lyrics.ovh) are off until switched on in Settings, or used for one track by hand; only title, artist, album and length are sent. There is no Google image lookup: Google has no public image-search API, and scraping its pages is against its terms.

### Organization
- Automatic classification of audio into pillars via a cascade: folder (Audiobooks/Podcasts/Music) → filename contains "podcast" → duration over 10 minutes = podcast, else music.
- Library scan on Android 8 and 9 reads each file's folder from its path. MediaStore's `RELATIVE_PATH` column only exists from Android 10, and asking for it on older versions threw `SQLiteException: no such column` and crashed the scan (4.9, decoded from the release's R8 mapping).
- Manual override: long-press any item to rename, set artist/host/author (field adapts to pillar), add optional details, and move it between Music / Podcasts / Audiobook. Overrides beat the automatic rules and persist across sessions and app updates (real DB migrations, no data loss). "Reset to automatic" clears an override.

### Browsing
- Every set of views or filters is one chip style (`Chips.kt`): Home's Music / Video / Podcasts (with an icon each), Library's Albums / Artists, Playlists' Mine / Smart, the Sound presets and the speed presets. The current one is a filled accent pill, the rest sit on a faint fill with a hairline edge; colours animate, a tap gives press feedback and the touch target is 48dp.
- Tracks without a cover get default artwork (`Artwork.kt`): a slate tile with a soft glow of the brand teal and a mark for what the file is, taken from Google's Material Rounded icons (the single note for music, the podcast mark, an open book for audiobooks, a microphone for recordings, a play triangle for video), the same set the Home chips use, so it stays sharp from a list row to Now Playing. The notification and lock screen get the same tile: `ArtworkBitmap.kt` runs the same drawing onto a bitmap, and the track's kind travels on its artwork uri.
- Home: editorial feed with a Continue row (real play history, most-recent-first, deduplicated) plus per-pillar shelves.
- Library: full filterable list (All / Music / Podcasts / Audiobooks / Video).
- Live search across the whole library.
- Dedicated Podcasts and Audiobooks tabs.

### Reactive artwork (5.2, beat-locked 5.6)
The idea is a reactive speaker: watch the woofer and you see the music. Until 5.2 the artwork saw one number, the bass energy twenty times a second, and every tier drew that same number a little differently, so the tiers looked alike.
- **What it hears** (`SoundShape.kt`, `EnvelopeAnalyzer.kt`): each music file is decoded once on first play and split into three bands fifty times a second (20ms): kick and bass below 110 Hz, snare and voices 250 Hz to 2.5 kHz, hi-hats and cymbals above 5 kHz. Every stage is a one-pole filter; a high-pass is the sample minus its own low-pass, which is exact, so stages cascade without the phase leak that subtracting two low-passes has. Hits are found per band by picking peaks of the rise against a threshold that follows the music, and must be at least 40% of the strongest hit within 0.6s, so what one drum leaks into another band drops out while a quiet verse keeps its hits. A snare-band hit on a kick's frame is the kick's beater click unless it is strong. On a synthetic loop (kick, snare on the off-beat, hats in quarters, a pad over it) every band finds exactly its own drum.
- **In sync** (5.3): the clock is the player's own position (`PlayerClock`), read on every frame. ExoPlayer derives it from the audio output's timestamps, so the output's latency is already in it; 30ms is added for the time a frame takes to reach the screen. Until 5.3 the position was read twice a second and guessed in between from the frame count; every read was already stale when the guess restarted on it, so the artwork drifted and snapped back and looked like a separate thing from the song. The analysis also skips the encoder's lead-in (an MP3's LAME delay, an AAC's priming), which the decoder returns and the player trims; counted, it put every hit 25-50ms late. Stored analyses carry a format tag, and older ones are redone on next play.
- **What it answers** (5.6, `BeatGrid.kt`). Up to 5.5 the cover answered every hit found in every band. Measured on four real tracks (hip-hop, electronic, pop, ragtime) that was 110 to 170 reactions a minute, only 19 to 66% of them on a beat, with 29 to 65% of the beats missed, and a light snare-band hit just before a heavy kick moved the cover twice within 100ms, 24 to 96 times per track. Now each track is listened to as a drummer would, once, when its shape loads:
  - *Tempo:* the three bands' onset strength, autocorrelated in 8-second windows each normalised on its own and averaged, with a gentle preference for 120 BPM (librosa's method; on the same four tracks the same tempo librosa finds, within 1 BPM).
  - *Beats:* Ellis's dynamic-programming beat tracker, as librosa runs it (the Kotlin port matches the Python prototype beat for beat).
  - *Moments:* a kick counts on a beat, on the half-beat, or, if strong, on a quarter (where the syncopated kicks of afrobeats, dancehall and reggaeton fall), and a strong kick counts anywhere, since the grid can be the wrong one: a 3-3-2 kick pattern reads as a faster tempo. Kicks are chosen first, strongest first, and nothing may react within 0.45 of a beat of one; then snares, on beats and half-beats, only where no kick is near. A snare-band hit's strength is measured against its own band, so a quiet one can score high; ranked against kicks it used to take the heavy kick's moment.
  - Result on the same tracks: never two reactions closer than 200ms. On a synthetic four-on-the-floor with a light hit 60ms before every kick, 5.5 reacted twice on 59 kicks and the new selection on none, answering 98% of the kicks; on a 3-3-2 pattern, with or without those light hits, 99%. A track with no steady beat falls back to its strongest kicks, a quarter of a second apart at least. The analysis takes about 10ms per two minutes of music off the main thread.
- **How it moves** (`BeatPulse.kt`): a moment fires on the frame playback crosses it, timed to the moment itself rather than the frame, so its pulse is already as far along as the sound. A kick is a fixed shape in time (`Pulse`): up in 30ms, then an exponential fall of a third of a beat (70 to 160ms), over before the next beat. 5.2 to 5.5 drove the cone with a lightly damped spring that every hit struck: it swung two or three times after each kick and wandered with the bass line between them, so the cover moved when nothing was hit, and a hit landing mid-swing looked late. Now a new kick always starts from a full attack, whatever the last one was doing, and between hits the cover is still.
- **Latency:** the Sound chain can hold the audio back beyond what the player's position knows: Dynamics Processing works a frame at a time (10 or 40ms) and Depth and Space look 3ms ahead. `SoundStatus.latencyMs` carries it (three quarters of a frame, so any error leaves the cover a touch early, which the eye forgives, never late) and the artwork draws that much later.
- **One system, one job per drum:**
  - *Kick = motion.* The cone. Its glow (the edge light), its shadow and the rings it sends out all read the same value, so they move as one body.
  - *Snare = light.* One flash across the cover, only in a moment no kick has taken.
  - *Hats = texture.* A fine shimmer that follows how busy the cymbals are: a level, not a hit, so it never reacts on its own; it gives way to the cone and the flash.
  - *Room.* The backdrop, the light field and the flow swell slowly with the bass over a bar or so: the ambience, never a hit.
- **Tiers add drums, not effects.** Enhanced shows the kick. Premium adds the snare, and the shadow that deepens with the cone. Ultra adds the hats and the cone's true deformation (`Phantom.kt`, Android 13+): a GPU shader re-samples the cover so the middle swells towards you on the kick while the rim stays put, a ripple runs out from the centre after each kick, and the hats make the surface tremble; it is lit from the top left by its own slope, so the swell reads as a shape, not a zoom. At rest the shader is removed. A driver that rejects it, or Android 12 and below, draws Ultra as Premium.
- Volume is still the power control: silent means still.

### Display quality (Auto / Enhanced / Premium / Ultra)
Enhanced is free; Premium and Ultra come with Via Pro.
Each tier is a complete design (artwork resolution, motion) plus a visual engine (`Backdrop.kt`, `CoverLight.kt`, `FluidLight.kt`). Auto picks the highest tier the device's RAM, CPU and panel support. In 4.8 every tier moved up one: the old Premium is now Enhanced, the old Ultra is now Premium, and Ultra is new. The side gutter is 16dp on every tier since 5.1 (it was 20-24dp), so lists and artwork run close to the screen edges.
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
- The icon is the iris (`tools/make_icon.py`, 5.7): a white disc cut into three identical blades closing around a play-shaped opening, a camera iris opening onto play, on a teal-to-blue tile lit from the top left. Each side of the play shape carries straight on past its corner and bends out to the rim, so the cuts and the opening are one continuous line and the blades' own edges make the play shape (until 5.6 each cut stopped just short of a corner, leaving a white bridge). One cut, rotated by 120 and 240 degrees, so the symmetry is exact; every blade corner is rounded the same, and outlines are kept within a quarter of a design unit, which keeps each vector to a few kilobytes. Every asset is generated from that one geometry: the adaptive launcher layers and the themed (monochrome) icon as vectors, sharp at every size and under every launcher mask; the status-bar icon (also Media3's notification icon); the tile in the Home header and About; the Android 7 PNGs; the Play listing icon; and the splash. On Android 12+ the splash animates: the iris starts closed (three curved lines meeting near the centre), turns a quarter turn into place and opens onto the play shape, each blade a true shape morph (the same blade at two opening sizes, resampled to the same points from the same point on the rim), over a lit disc that grows in under it; earlier versions show it still.
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
The app collects nothing itself: no accounts, no analytics. Settings → Privacy has "Ad privacy choices" wherever Google's consent SDK says the law requires it (EEA, UK, Switzerland, and the US states configured in AdMob): Google's own form, to change or withdraw consent or opt out of sale/sharing. It also links to Android's advertising-ID settings and the privacy policy. All edits, history, and preferences stay in the app's private storage. The free version shows one adaptive banner in Settings from Google AdMob, requested only after Google's consent flow says ads may be requested (UMP `canRequestAds()`), sized to the card it sits in so it is never cropped, and paused with the app. Via Pro, a one-time purchase, removes it. Policy source: `docs/privacy.html`; the app links to https://mebs.app/privacy/aura.

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

Icons: `python3 tools/make_icon.py` (needs shapely, skia-python, pillow and numpy) rewrites every icon asset from the iris's geometry; the design numbers are at the top of the script.

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

Each release build's R8 mapping is kept as a workflow artifact for 90 days.
A Play Console crash from a minified build names obfuscated classes
(`com.media.app.o6`); run the workflow by hand on that version's commit with
those names in `decode`, and the log prints what each one is in the source.

### Translations

English is `res/values/strings.xml`; each language is `res/values-xx/strings.xml` (`values-in` is Indonesian, Android's legacy code for it, and `values-pt` is Brazilian Portuguese). `tools/check_translations.py` checks every language against English: every string and plural present and nothing extra, exactly the same format placeholders, the plural forms each language's rules use, and escaping. It exits 1 on any problem:

    python3 tools/check_translations.py

The `Release check` workflow runs it on every push.

---

## Known limitations

- Notification artwork tracks the song correctly on standard Android and Samsung; some OEM skins (e.g. Tecno/HiOS) cache the notification bitmap and may not refresh per song — device-side behavior outside the app's control.
- Podcasts/audiobooks are local-first (classified from on-device audio), not an online RSS client.
- Video notification artwork is not yet handled like audio album art.
- Via Pro's mono, balance and skip silence do not act on high-resolution files (see Via Pro).
- Right-to-left languages (Arabic, Hebrew, Persian, Urdu) are not shipped: the layouts have not been mirrored and checked.

---

## License

All rights reserved. Personal project.
