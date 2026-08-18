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

## Device support

Scope as of **August 2026**. The build's floor is deliberately low — `minSdk 22`, ARM-only release
ABIs, no Google Play Services anywhere in the graph, `leanback` declared optional — so the APK
installs on nearly anything Android-based with an HDMI port. Distribution is sideload-only; the app
is on no store.

| Constraint | Value | Why |
| --- | --- | --- |
| `minSdk` / `targetSdk` / `compileSdk` | 22 / 34 / 36 | API 22 is Fire OS 5, the oldest live Fire TV stick |
| Release ABIs | `armeabi-v7a` + `arm64-v8a` | every Fire TV is 32-bit ARM; arm64 covers Android TV boxes. `x86`/`x86_64` are debug-only (emulator) |
| Play Services | none | nothing depends on GMS, so uncertified and AOSP boxes work as-is |
| `android.software.leanback` | `required="false"` | installs on non-leanback Fire OS builds and on TV emulators |
| Signing | v1 + v2 + v3 | Fire OS 5 rejects a v2-only APK ("problem parsing the package") |

### The ten Android TV systems that matter, and where we stand

Ordered by installed base / OS share. Vendors do not publish per-brand Android TV unit counts, so
rows 3–10 are ranked on share and shipment evidence rather than exact numbers.

| # | System | OS base / API | Scale | Runs the release APK? |
| --- | --- | --- | --- | --- |
| 1 | **Fire TV sticks & cubes** — Fire OS 5/6/7/8 | Android 5.1 / 7.1 / 9 / 11 → API 22/25/28/30, all `armeabi-v7a` | 250M+ Fire TVs sold | ✅ **Proven** — the only tier tested on real hardware, all four API levels (see `TESTING_PLAN.md`) |
| 2 | **Fire TV smart TVs** — Omni, Insignia, Toshiba, Panasonic; Fire OS 8/14/16 | Android 11 / 14 / 16 → API 30/34/36 | Fire TV ≈ 17% of the US streaming-OS market | ✅ Expected, untested. Fire OS 16 is new — see the 16 KB note below |
| 3 | **Google TV built into sets** — TCL, Hisense, Sony Bravia, Philips, Sharp | Android 12 → 14 (TCL's 2026 line ships 14) | Android TV + Google TV: 300M monthly-active devices | ✅ Expected, untested |
| 4 | **Google's own players** — Google TV Streamer 4K, Chromecast with Google TV | Android 14 / Android 12→14 | Google TV is the #3 US streaming OS, ≈14% | ✅ Expected, untested |
| 5 | **Walmart onn. Google TV** — 4K Pro, 4K Plus | Android 12 → 14, Amlogic ARM | the US budget-volume box | ✅ Expected, untested |
| 6 | **Classic Android TV sets** — Sony, Philips, TCL 2016–2022 | Android TV 8–11 | most of the pre-Google-TV installed base | ✅ Expected — far above the API 22 floor |
| 7 | **NVIDIA Shield TV / Pro** | Android TV 11, Tegra X1+ (arm64) | still the enthusiast standard in 2026 | ✅ Expected — best-case hardware for torrent streaming |
| 8 | **Xiaomi TV Box S 3rd Gen / TV Stick** | Google TV 14, Amlogic S905X5M (arm64) | the main international box | ✅ Expected, untested |
| 9 | **Operator-tier boxes** — Airtel, Jio, Deutsche Telekom, Bouygues, pay-TV | Android TV 9–11 | large carrier-subsidised base | ⚠️ **Conditional** — the OS is fine, but many are locked: no unknown-sources, no ADB. Blocked by the operator, not by us |
| 10 | **Generic AOSP boxes** — MECOOL, X96, H96, Ugoos | Android 9–14, Amlogic ARM, no GMS | large grey-market volume | ✅ Expected — the zero-GMS design is exactly what makes these work |
| — | **Amazon Vega OS** — Fire TV Stick 4K Select (2025), Fire TV Stick HD (2026) | **Linux, not Android** | every *future* Fire TV Stick | ❌ **Impossible** — cannot run an APK at all; no sideloading, no Downloader |

**Nine of the ten run the current release APK**: one proven on hardware, seven expected but never
tested on a device, and one (operator boxes) gated by the operator's lock rather than by anything in
this build. Vega OS is listed as an eleventh row on purpose — it is not an Android TV system, but it
is where Amazon is moving all new hardware, so omitting it would overstate the app's reach.

### Watch items

1. **Vega OS erosion.** Every future Fire TV Stick is Vega. Our best-tested platform is a *shrinking*
   installed base, and reaching new Amazon hardware would mean a rewrite against Amazon's Kepler SDK,
   not a port.
2. **Google's sideload lockdown.** Developer verification (identity + a $25 registration) begins
   September 2026 in Brazil, Indonesia, Singapore and Thailand, expanding through 2027, and covers
   certified Google TV devices. Rows 3–8 are the growth path and they are what this hits.
3. **16 KB page sizes.** `libtorrent4j` is pinned to 1.2.3.0 — the only build whose native symbols
   resolve on API 22 — and it is almost certainly not 16 KB-aligned. Harmless on Fire TV (4 KB,
   32-bit) but a latent `.so` load failure on Android 15/16 arm64, i.e. Fire OS 16 and the newest
   Google TV sets. Verify before claiming row 2.
4. **x86 is release-stripped.** An x86 Android TV box or ChromeOS will not run a release build; the
   debug variant still does.

Sources for the market figures, which age quickly:
[Fire OS overview](https://developer.amazon.com/docs/fire-tv/fire-os-overview.html) ·
[Fire OS 16](https://www.aftvnews.com/android-16-is-coming-to-fire-tv-smart-tvs-via-newly-revealed-fire-os-16/) ·
[Vega OS has no sideloading](https://www.aftvnews.com/these-are-the-fire-tvs-that-dont-support-sideloading-or-downloader-due-to-vega-os-replacing-fire-os/) ·
[all future sticks are Vega](https://9to5google.com/2026/04/17/amazon-fire-tv-android-vega-switch/) ·
[250M Fire TVs sold](https://www.thurrott.com/smart-tech/smart-home/313174/amazon-has-now-sold-over-250-million-fire-tv-devices) ·
[Google TV at 300M devices](https://www.androidheadlines.com/2026/05/google-tv-300-million-devices-growth-2026.html) ·
[Google TV is the #3 US streaming OS](https://9to5google.com/2026/07/29/google-tv-is-reportedly-the-3-streaming-os-in-the-us/) ·
[smart TV OS share](https://www.sammobile.com/news/despite-samsungs-tv-dominance-tizen-os-trails-roku-fire-tv-and-google-tv/) ·
[Google TV brands](https://www.flatpanelshd.com/guide.php?subaction=showfull&id=1721907871) ·
[Android 14 for TV](https://www.androidauthority.com/android-tv-14-features-3362883/) ·
[Xiaomi TV Box S 3rd Gen](https://www.mi.com/global/product/xiaomi-tv-box-s-3rd-gen/specs/) ·
[sideloading restrictions](https://www.androidauthority.com/how-android-sideloading-restrictions-may-work-3595355/) ·
[16 KB page sizes](https://developer.android.com/guide/practices/page-sizes)

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
