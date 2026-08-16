# TORFILX

A Netflix-style Fire TV app whose entire library is a **bundled catalogue of public-domain films**, played over BitTorrent. There is no media server and no backend.
played over BitTorrent. There is no media server and no backend: the app ships the catalogue, streams
each title from its magnet link, and (with the viewer's consent) shares what it has downloaded.

## What it does

- **10-foot UI** built with Jetpack Compose + `androidx.tv:tv-material`: hero banner, focus-scaled
  rows, details, on-screen-keyboard search, My List, Settings — all driven by the D-pad alone.
- **Torrent streaming, not downloading**: only the video file is fetched, pieces are prioritised in
  play order with deadlines, and a loopback HTTP server feeds ExoPlayer while the download continues.
  Seeking into a not-yet-downloaded part re-prioritises and buffers instead of failing.
- **Explicit sharing consent**: off by default. The first attempt to play explains that streaming
  uploads to others and exposes your IP, with a real "Not now".
- **Storage budget**: never uses more than a configurable share of *free* space (default 50%, always
  keeping a 500 MB reserve), evicting oldest-touched titles first and never the one playing.
- **Local watch state**: Continue Watching, resume positions and My List live in Room on the device.

## Requirements

- JDK 17 or 21 (Android Studio's bundled JBR works)
- Android SDK with platform 37 and build-tools; `local.properties` pointing at it (`sdk.dir=...`)
- A Fire TV / Android TV device or emulator (arm64-v8a, armeabi-v7a or x86_64)

## Build and run

```bash
./gradlew :app:assembleDebug          # build
./gradlew test                        # unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.torfilx.tv.debug/com.torfilx.tv.MainActivity
```

Sideloading onto a Fire TV Stick:

```bash
adb connect <fire-tv-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## The catalogue

`core/data/src/main/assets/catalog.json` is the single source of titles:

```json
[
  {
    "title": "The Kid",
    "year": "1921",
    "image_url": "https://…/poster.jpg",
    "overview": "optional",
    "genres": ["Comedy"],
    "runtimeMinutes": 68,
    "magnets": [{ "quality": "720p", "magnet": "magnet:?xt=urn:btih:…" }]
  }
]
```

Magnets are validated when the file loads: an entry whose `xt=urn:btih:` is not a 40-character hex
(or 32-character base32) info hash is dropped with a log line rather than failing later in the
player. Only content that may be freely redistributed belongs here — seeding *is* redistribution.

## Module layout

```
:app                 single activity, navigation, Hilt entry points
:core:model          domain types + pure rules (resume thresholds, source selection)
:core:common         typed errors, dispatchers, clock, logging (with export buffer)
:core:ui             theme, focus behaviour, cards/rows/hero/keyboard/consent dialog
:core:data           catalogue loader, Room (progress, My List, search history), settings
:core:torrent        libtorrent4j engine, sequential streaming, loopback HTTP bridge, budget
:core:player         ExoPlayer factory, MediaSession service, playback state machine
:feature:*           home, library, details, search, player, settings
:core:testing        fixtures and fakes
```

## Notes for a real Fire TV

- Display frame-rate matching (24/50/60 Hz) is on by default; turn it off in Settings if your TV
  handles mode switches badly.
- Tunneled playback is enabled where the device reports support (Amazon's recommendation for 4K/HDR)
  and can be disabled in Settings if an AV receiver glitches.
- Media keys and Alexa transport commands work through `MediaSession`.

## Legal and content policy

**This project is provided for personal and educational use.** It is a BitTorrent client with a TV
interface: the application ships no media, hosts nothing, and operates no tracker or index service.
Every title it shows comes from `core/data/src/main/assets/catalog.json`, which is supplied by
whoever builds the app.

The catalogue committed to this repository is intended to hold **public-domain films** — works whose
copyright has expired and which may be freely copied and redistributed (for example Chaplin's *The
Kid*, 1921, and *The Gold Rush*, 1925).

Two things follow from how BitTorrent works, and they are worth stating plainly:

- Streaming a title over BitTorrent **also uploads it** to everyone else in that swarm. Downloading
  is receiving; seeding is *distributing*. Distributing a copyrighted film without permission is
  unlawful in most jurisdictions, and an "educational purposes" notice in a README does not change
  that.
- Your home IP address is visible to every other peer sharing the same title.

Because of this, sharing is **off by default** and cannot start until it has been explicitly enabled
through the consent screen, and it can be turned off again at any time in Settings.

Whoever populates `catalog.json` and builds or distributes the resulting APK is responsible for
ensuring they have the right to copy and redistribute those files. The maintainers of this codebase
are not responsible for catalogues assembled by third parties.
