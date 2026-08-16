# MYFLIX — Fire TV Client: Engineering Plan

> Scope: **the Fire TV application only.** The local media server (scanning, metadata,
> transcoding, storage) is a separate project. This document defines only the contract the
> app *requires* from that server, so the app can be built against a fake/in-memory
> implementation first and swapped to the real server later.

---

## 0. Guiding decisions (read this first)

| Decision | Choice | Why |
|---|---|---|
| Platform | Native Android (Fire OS) app, Kotlin | Fire OS is Android; a WebView/React shell cannot do proper focus, tunneled 4K playback, audio passthrough, or MediaSession integration. |
| UI toolkit | Jetpack Compose + **`androidx.tv:tv-material`** (1.0 stable) | Purpose-built TV components (`Surface`, `Card`, `Carousel`, `NavigationDrawer`, `TabRow`, `ListItem`) with correct focus visuals and D-pad behaviour. |
| Lists | Standard `LazyRow`/`LazyColumn` from `compose.foundation` + `focusRestorer()`/`focusGroup()` | `tv-foundation`'s `TvLazyRow` is deprecated; foundation lists now support TV focus natively. Do not add `tv-foundation`. |
| Playback | **Media3** (`exoplayer`, `exoplayer-hls`, `session`, `ui`, `ui-compose`) | Only realistic choice. `MediaSession` is mandatory on Fire TV so Alexa/remote transport keys ("Alexa, pause") work. |
| Architecture | Single-activity, MVVM + unidirectional data flow, Hilt, Coroutines/Flow, kotlinx.serialization | Standard, testable, well-supported. |
| Persistence | Room (playback progress, My List, library cache) + DataStore (settings) | Must work when the server is briefly unreachable and must not lose progress. |
| Networking | OkHttp + Retrofit + Coil 3 | Boring and reliable. |
| Google Play Services | **None. Ever.** | Fire OS has no GMS. No Firebase, no Play Billing, no Cast SDK, no Play In-App Update. |
| Distribution | Sideload via `adb` (personal use). Amazon Appstore submission not planned. | Keeps the plan honest; but we still comply with Fire TV manifest requirements so it behaves correctly on the launcher. |
| Data first | Build the entire UI against a **`FakeMediaRepository`** with realistic data (100+ items, shows with 5+ seasons, long titles, missing artwork). | Nail Home → Browse → Details → Player on the real remote before any server work. |

---

## 1. Target hardware & OS matrix

Fire OS versions map to Android API levels. This drives `minSdk`, codec assumptions and performance budgets.

| Device | Fire OS | Android API | RAM | Video | Notes |
|---|---|---|---|---|---|
| Fire TV Stick (1st/2nd gen), Fire TV (1st/2nd gen box) | 5 | 22 | 1 GB | 1080p H.264 | **Not supported** (too slow for Compose, EOL). |
| Fire TV Stick 4K (2018), Fire TV Stick (3rd gen), Stick Lite, Cube 1st gen | 6 / 7 | 25 / 28 | 1.5 GB | 4K HEVC 10-bit, HDR10/HDR10+/DV (4K models) | Baseline performance target. |
| Fire TV Stick 4K Max (1st gen), Cube 2nd/3rd gen | 7 | 28 | 2 GB | + Wi-Fi 6 | |
| Fire TV Stick 4K / 4K Max (2nd gen, 2023), Fire TV 4-Series/Omni | 8 | 30 | 2 GB | + AV1 (4K Max 2nd gen) | Primary dev device. |

**Decisions**
- `minSdk = 25` (Fire OS 6+). `compileSdk`/`targetSdk` = latest stable (Fire OS 8 is API 30; targeting higher is fine — behaviour changes above 30 do not apply on-device but keep libraries happy).
- Performance budget: measure on a **Fire TV Stick 4K (2018)**; if it's smooth there it's smooth everywhere we support.
- Display: UI renders at 1080p → **960 × 540 dp** at xhdpi (Fire OS reports 320 dpi). Design in dp for that canvas; the system upscales to 4K.
- No touchscreen, no mouse, no keyboard assumed. Remote only (plus optional Bluetooth game controller — free with proper D-pad handling).

---

## 2. Project setup

### 2.1 Gradle / dependencies (verify latest stable at kickoff)
- AGP 8.x, Kotlin 2.x (K2, Compose compiler plugin), JDK 17.
- Compose BOM; `androidx.tv:tv-material:1.0.x`; `androidx.navigation:navigation-compose` (type-safe routes); `androidx.hilt`; `androidx.room` (KSP); `androidx.datastore`; `androidx.media3:*`; `io.coil-kt.coil3:coil-compose` + `coil-network-okhttp`; `com.squareup.retrofit2` + `converter-kotlinx-serialization`; `okhttp-logging-interceptor` (debug only).
- Static analysis: `ktlint` or `detekt`, Android Lint with `warningsAsErrors` for TV-relevant checks.
- R8 full mode + shrink resources for release; Baseline Profile generated from a Home→Details→Player journey (start-up on a Fire Stick is slow without it).

### 2.2 Module layout (pragmatic, not enterprise-cosplay)
```
:app                — Application, MainActivity, NavHost, Hilt entry points
:core:model         — pure Kotlin domain models
:core:data          — repositories, Room, DataStore, sync
:core:network       — Retrofit API, DTOs, mappers, OkHttp config
:core:ui            — theme, typography, TV components (FocusableCard, Row, Skeletons)
:core:player        — ExoPlayer factory, MediaSession service, track selection, progress reporter
:feature:home | :library | :details | :search | :mylist | :player | :settings
:testing            — FakeMediaRepository, fixtures, test rules
```
Rule: features never depend on each other; they depend on `core:*` and expose a NavGraph builder.

### 2.3 Manifest requirements (Fire TV specific)
```xml
<uses-feature android:name="android.hardware.touchscreen" android:required="false"/>
<uses-feature android:name="android.software.leanback"   android:required="false"/>
<uses-feature android:name="android.hardware.microphone" android:required="false"/>
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"/>
<uses-permission android:name="android.permission.WAKE_LOCK"/>

<application android:banner="@drawable/banner"   <!-- 320x180 px, required for launcher tile -->
             android:networkSecurityConfig="@xml/network_security_config"
             android:theme="@style/Theme.Myflix.Splash">
  <activity android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="landscape"
            android:configChanges="keyboard|keyboardHidden|navigation|screenSize|screenLayout|density|uiMode"
            android:launchMode="singleTask">
    <intent-filter>
      <action android:name="android.intent.action.MAIN"/>
      <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
      <category android:name="android.intent.category.LAUNCHER"/>  <!-- harmless, helps emulator -->
    </intent-filter>
  </activity>
  <service android:name=".player.PlaybackService"
           android:foregroundServiceType="mediaPlayback" android:exported="true">
    <intent-filter><action android:name="androidx.media3.session.MediaSessionService"/></intent-filter>
  </service>
</application>
```
- `network_security_config`: allow **cleartext only to RFC-1918 ranges / the configured server host** (LAN HTTP). If the server later provides HTTPS with a self-signed cert, support pinning a user-supplied CA — do **not** ship a trust-all `TrustManager`.
- Splash: use `androidx.core:core-splashscreen`; keep it dark; no animation longer than 500 ms.

---

## 3. Architecture

```
UI (Compose, tv-material)  ──events──▶  ViewModel (StateFlow<UiState>)  ──▶  UseCases
                                                                              │
                                                             Repository (single source of truth)
                                                        ┌────────────┴────────────┐
                                                   Room / DataStore           Retrofit API
                                                  (cache, progress,           (server contract §11)
                                                   my list, prefs)
```

- **UiState is a sealed hierarchy** per screen: `Loading | Content(...) | Error(kind, retry)`; never a bag of nullable fields.
- **Repositories are offline-tolerant**: read from Room first, refresh from network, emit both. Library cache TTL is short (server is local, so 5 min) but the last good snapshot is always shown when the server is down.
- **Progress writes are local-first**: write Room immediately, then enqueue a network sync (WorkManager with exponential backoff, coalesced per item). Progress must never be lost because the PC was asleep.
- **Every network call has a timeout** (connect 5 s, read 15 s) and a typed error (`ServerUnreachable`, `Unauthorized`, `NotFound`, `ServerError`, `Malformed`).
- Single `MainActivity`; the player is a full-screen destination in the same NavHost (simpler state sharing) backed by a `MediaSessionService` so Alexa/transport keys reach it.

---

## 4. TV design system (10-foot UI)

- **Canvas**: 960 × 540 dp. **Overscan-safe padding**: 48 dp horizontal, 27 dp vertical on all screen edges; nothing interactive or textual outside it.
- **Typography** (all sp): Display 48, Title 32, Heading 24, Body 20, Caption 16. Nothing below 14 sp anywhere. Line length ≤ ~60 characters for descriptions; clamp to 3 lines with ellipsis.
- **Colour**: background `#141414`, surfaces `#1F1F1F`/`#2A2A2A`, text `#E5E5E5` (never pure white — it clips on TVs), secondary text `#B3B3B3`, one accent colour of our own. Contrast ratio ≥ 4.5:1 for all text.
- **Focus indicator**: scale 1.08–1.10 + 3 dp border in accent/white + subtle glow, animated 150–200 ms. Focus must be visible **before** any secondary animation. Never rely on colour alone.
- **Cards**: poster 2:3 (150 × 225 dp base), landscape 16:9 (240 × 135 dp) for episodes/Continue Watching. Progress bar overlaid at bottom of card for in-progress items.
- **Rows**: title, then a `LazyRow` with `contentPadding` = overscan; the focused card is pivoted at ~10% from the leading edge (`BringIntoViewSpec` / pivot offsets), so the user always sees what is next.
- **Motion**: only focus scale, hero cross-fade (300 ms), row scroll, and screen transitions (fade/slide 200 ms). No parallax, no blur (`Modifier.blur` is expensive on Fire Sticks), no springy overshoot.
- **Images**: request the *exact* size we render (server exposes size variants, §11.4). Coil: memory cache 25% of heap, disk cache 256 MB, `RGB_565` for posters/backdrops (halves memory; banding is invisible on TVs), crossfade 150 ms, placeholder = solid surface colour, error = generated poster with title text (never a broken-image icon).
- **Sound**: none for focus (Fire TV launcher already makes clicks; users find double sounds annoying).
- **Accessibility**: every focusable has a `contentDescription` (VoiceView screen reader is on Fire TV); respect the system caption style (`CaptioningManager`) in the player.
- **RTL/localisation**: layout must mirror correctly for RTL locales (Hebrew/Arabic): rows scroll the other way and D-pad ← / → semantics follow layout direction automatically **only** if we never hard-code "left = previous". Strings in resources from day one; support `en` and `he` in V1.

---

## 5. Focus & remote-control model (the make-or-break part)

### 5.1 Key handling
| Key | Global | Player |
|---|---|---|
| D-pad ←↑↓→ | Move focus | Seek ±10 s (hold: accelerate 10→30→60 s), ↑ shows episode/track panel, ↓ shows timeline |
| Center/OK | Activate | Toggle controls; if controls visible → activate focused control; if hidden → play/pause |
| Back | Pop back stack; on Home root: **no exit-confirmation** (Fire TV convention: Back on root just goes to launcher) | 1st press hides controls; 2nd press exits player (progress saved) |
| Menu (☰) | Contextual options on focused card (Remove from Continue Watching, Add/Remove My List, Mark watched) | Audio/subtitle track picker |
| Play/Pause, Rewind, FF | Ignored outside player except Play/Pause on a focused card = start playback | Handled via MediaSession (so remote **and Alexa** both work) |
| Home / Alexa / Mic | System-owned; not interceptable. Playback must survive/resume correctly on return | same |

Implement with `Modifier.onPreviewKeyEvent` at the screen root only for player seek logic; everything else uses Compose focus traversal — do not hand-roll a focus manager.

### 5.2 Focus rules (all are acceptance criteria)
1. **Initial focus**: after Home content loads, focus lands on the hero "Play" button (or first Continue-Watching card if hero is absent). Never on the nav bar. Request focus **after** data arrives (`LaunchedEffect(state is Content)`), never during Loading — otherwise the request is lost.
2. **Restoration**: every `LazyRow`/`LazyColumn` uses `focusRestorer()` and stable `key = item.id`. Coming back from Details/Player returns focus to the exact card the user left. Nav-bar tab switch restores each tab's last focus.
3. **Data mutation while focused**: if the focused item is removed (e.g. Continue Watching entry deleted, or finished), focus moves to the next sibling, else previous, else the next row. If a row becomes empty it disappears **and** focus is moved first, then the row is removed (avoid focus falling to `null`, which makes the remote appear dead).
4. **Async row loading**: rows render as skeletons that are focusable placeholders sized like real cards so vertical navigation is stable while loading; skeleton→content swap must not steal or drop focus.
5. **Vertical navigation across rows** keeps the horizontal position by *column index* only when rows share card size; otherwise use nearest-edge default. Never trap focus in a row.
6. **Nav bar**: `NavigationDrawer` (collapsed icons, expands on focus) or top `TabRow`; choose one (recommend top tabs like the mock). Moving ↑ from the first row reaches the tabs; ↓ from tabs returns to the *last focused* card, not the first.
7. **Long lists**: `LazyRow` with `beyondBoundsItemCount = 2` and image prefetch of the next 5 items so fast scrolling doesn't show blanks; keep scroll speed constant on key-repeat (throttle to one item per 60–80 ms).
8. **Modals** (track picker, still-watching, error dialogs) trap focus and restore it on dismiss.
9. **No dead ends**: automated test walks the entire Home with random D-pad input for N steps and asserts something is always focused.

---

## 6. Screens

### 6.1 Home
- Top tab bar: Home · Movies · Shows · Search · My List · Settings (icon).
- **Hero**: `Carousel` (tv-material) of up to 5 featured items, auto-advance every 8 s **only while the hero is not focused**, stops permanently after user interaction. Content: backdrop, logo/title, meta line (year · runtime · rating · genres), 3-line synopsis, `Play`/`Resume`, `+ My List`, `More info`.
- Rows (each independently loading and failing; one failing row never blocks the screen): Continue Watching (hidden when empty), Recently Added, Movies, Shows, per-genre rows (server-provided ordering), My List, Because you watched X (if server supplies).
- Continue Watching card: 16:9 thumbnail, progress bar, "S2:E4 · 32 min left" or "1 h 12 m left", Menu → Remove.
- Card focus reveals a small info panel (title, year, runtime, rating) **below** the card, not a full-screen preview (keeps it cheap).
- Empty library state: friendly message + "Open Settings" button (server not configured / library empty).

### 6.2 Movies / Shows (browse)
- Grid (6 columns at 960 dp) with sticky sort/filter chips at top: Sort (Recently added · A–Z · Year · Rating), Genre, Watched/Unwatched. Chips are focusable, ↑ from first grid row reaches them.
- Server-side paging (`Paging 3`), 60 items/page; scroll position and focus survive process death via saved state.
- Alphabet fast-scroll rail on the right (optional V1.5).

### 6.3 Details — Movie
- Backdrop with gradient scrim; title/logo; meta line; synopsis (expandable via focus + OK → full text dialog); primary actions: `Play`/`Resume from 1:12:03`/`Play again`, `+/✓ My List`, `Mark watched`; secondary: Audio/Subtitle summary, cast row (if data), "More like this" row.
- Focus starts on the primary action.
- Live progress: returning from player updates the resume label immediately (state comes from Room `Flow`).

### 6.4 Details — Show
- Same header. Primary action logic (server-independent, decided in app from progress data):
  1. Episode in progress (2% < p < 95%) → `Resume S1:E3`.
  2. Otherwise → first **unwatched** episode in aired order (`Play S1:E1` / `Next: S2:E1`).
  3. All watched → `Play S1:E1 again`.
- Season selector: horizontal `TabRow` of seasons (Specials/season 0 = "Specials", displayed last). Episode list below as a vertical `LazyColumn` of 16:9 cards: number, title, runtime, synopsis (2 lines), progress bar, watched checkmark. Selecting a season restores that season's last-focused episode.
- Handles: missing episode metadata (fallback "Episode 7"), missing thumbnails (use show backdrop with number), 20+ seasons (tab row scrolls), single-season shows (hide selector).

### 6.5 Search
- Left: on-screen keyboard grid (A–Z, 0–9, space, backspace, clear; Hebrew layout toggle) — matches Netflix and avoids the flaky Fire TV IME overlay stealing focus. System IME allowed as fallback via long-press OK on the field.
- Right: results grid, updates with 300 ms debounce, minimum 2 characters, cancels in-flight requests, shows "No results for …" and recent searches when empty.
- Voice: the remote's mic is owned by Alexa system-wide; in-app voice search is **out of scope** (would need an Alexa Video Skill / Fire TV catalog integration).

### 6.6 My List
- Grid; empty state explains how to add. Order = most recently added first. Menu → Remove.

### 6.7 Settings
- Server URL (+ "Test connection" showing server name/version), API token, Preferred audio language, Preferred subtitle language / Off, Subtitles default on/off, Autoplay next episode on/off, Playback quality/transcode preference (Auto · Direct only · Cap 1080p), Frame-rate matching on/off, Tunneled playback on/off (debug escape hatch), Clear cache, About/version/logs export.
- Text entry via on-screen keyboard component shared with Search.

### 6.8 Player — see §7.

---

## 7. Player (Media3) — detailed spec

### 7.1 Setup
- `ExoPlayer.Builder` with:
  - Hardware decoders only in V1 (`DefaultRenderersFactory` default mode; no software-decoder extensions).
  - `setAudioAttributes(USAGE_MEDIA / CONTENT_TYPE_MOVIE, handleAudioFocus = true)`, `setHandleAudioBecomingNoisy(true)`.
  - `DefaultTrackSelector` params: preferred audio language from settings → else original; preferred text language; `setSelectUndeterminedTextLanguage`; forced subtitles always on; `setTunnelingEnabled(true)` on Fire OS 7+ (Amazon recommends tunneled playback for 4K/HDR; keep a settings toggle because some 3rd-party AVRs glitch).
  - `DefaultLoadControl`: buffer 15 s min / 50 s max, 2.5 s to start, 5 s after rebuffer (LAN is fast; keep memory low on 1.5 GB devices).
  - `setWakeMode(C.WAKE_MODE_LOCAL)` and `FLAG_KEEP_SCREEN_ON` **only** while player screen is visible & playing.
- Playback lives in `PlaybackService : MediaSessionService`, so:
  - remote transport keys and **Alexa** ("pause", "rewind 30 seconds", "next episode") work through `MediaSession` callbacks;
  - Fire TV's now-playing/foreground handling is correct when the user presses Home.
- Rendering: `PlayerSurface` (media3-ui-compose) inside the Compose screen; `SubtitleView` styled from `CaptioningManager` (user's system caption size/colour), with our own defaults when the system has none.

### 7.2 Media formats & capability
- Direct play (progressive HTTP with Range requests) for MP4/MKV/WebM containing codecs the device decodes; HLS (fMP4) when the server transcodes/remuxes. Both are `MediaItem`s; the choice is made from the server's `sources[]` (§11.5) using the app's **capability report**:
  - On first launch (and after a Fire OS update) enumerate `MediaCodecList` → supported video codecs/profiles/levels/max resolution (H.264, HEVC Main/Main10, VP9, AV1), HDR types from `Display.getHdrCapabilities()`, audio (AAC, AC3/E-AC3 passthrough via `AudioManager`/HDMI capabilities, DTS if reported), max display refresh modes.
  - Send `caps` with playback-info requests so the server chooses direct vs. transcode. **App never guesses**; if a direct-play attempt fails with a decoder/format error, it retries once with a transcode source and remembers that per item.
- Subtitles: sidecar tracks as `MediaItem.SubtitleConfiguration` (WebVTT UTF-8 **only** — server converts SRT/ASS/cp1255-encoded Hebrew SRTs to VTT). Embedded text tracks in MKV/MP4 are used as-is; PGS/VobSub bitmap subs are supported by ExoPlayer in Matroska but not in HLS — track picker greys them out with a reason.
- Chapters/markers from server (`intro`, `credits`, `recap`) power Skip Intro / Next Episode timing.

### 7.3 Display handling
- **Frame-rate matching** (Fire OS 7+): before `prepare()`, when the setting is on and the item's fps is known, pick the `Display.Mode` with the same resolution and best-matching refresh (23.976→23.976/24, 25→50/25, 29.97→59.94/60, fallback 60), set `window.attributes.preferredDisplayModeId`, wait for `onDisplayChanged` or 1.5 s (whichever first) with the screen black, then start. Reset to system default on player exit. Amazon's guidance: only switch when the difference is meaningful; never switch mid-playback.
- HDR: nothing to do besides tunneling + not forcing SDR surfaces; verify HDR10 and Dolby Vision profiles 5/8 on 4K Max; DV profile 7 (Blu-ray remux) is not decodable → capability report must exclude it so the server transcodes to HDR10.
- Aspect ratio: `RESIZE_MODE_FIT` default; user cycle Fit → Fill → Zoom via Menu.

### 7.4 Controls UI (overlay)
- Hidden by default 4 s after last key; any key shows it (D-pad seek acts immediately even when hidden).
- Layout: title (show: "S1:E3 · Episode name"), timeline with buffered/played, elapsed/remaining, buttons row: Play/Pause · Restart · Subtitles/Audio · Next episode (shows) · Episodes (shows) · Aspect · Speed (0.5–2× in 0.25 steps).
- Seeking: ←/→ = 10 s; held = accelerating; while seeking show target time and **preview thumbnail** if the server provides a sprite sheet (§11.5); actual seek executes 300 ms after the last key (debounced) — never seek per key event.
- Buffering: spinner appears only after 500 ms of `STATE_BUFFERING` (avoid flicker), plus "Slow connection" hint after 10 s with current bitrate.
- Skip Intro: appears at `intro.start`, focused by default (so OK skips), auto-hides at `intro.end` + 3 s.
- Next episode: at `credits.start` (or last 30 s) show "Next episode in 10 s" card with cancel; OK plays now, Back cancels countdown (stays in player), any D-pad moves focus to it. Autoplay respects the setting. Not shown for the last episode of the last season → show "Back to show" card.
- "Still watching?" prompt: after 3 consecutive autoplays **or** 3 hours continuous playback with no user input; pauses playback; if unanswered for 2 min, stops and saves progress.
- Errors: distinct messages for `NetworkError` (retry button, auto-retry 3× with backoff), `DecoderError/UnsupportedFormat` (retry with transcode → else "This file can't be played on this device"), `HttpDataSource 401/403` (re-auth), `404` (item removed → back to details with refresh). Errors never bounce the user to Home.

### 7.5 Progress & resume rules
- Report position: every 10 s while playing, on pause, on seek, on stop, on `onStop()` of the screen, on audio-focus loss, and in `MediaSession` `onTaskRemoved`. Local Room write is synchronous; server sync coalesced.
- Resume threshold: resume if 2% < position < 95% **and** position > 30 s and remaining > 30 s. Otherwise start from 0. Watched flag set at ≥ 90 %.
- Movies at ≥ 90 % leave Continue Watching; show episodes at ≥ 90 % advance the show's Continue Watching entry to the next episode (or remove if none).
- Server progress and local progress may conflict (watched on another client): **latest `updatedAt` wins**; app reconciles on Home refresh and shows the reconciled value.

### 7.6 Lifecycle & system edge cases
- Home button pressed → **pause and release surface** (video apps on TV do not background-play); keep service alive ≤ 30 s to allow quick return, then stop and save. Return restores at saved position without re-buffering the manifest if still alive.
- Audio focus loss transient (Alexa speaks) → pause; regain → resume only if we paused for it. Permanent loss → pause and keep.
- HDMI/display change (`onDisplayChanged`, HDCP) → surface recreation handled by Media3; verify no black screen after TV power-cycle.
- Screensaver/sleep: Fire TV sleeps after inactivity — `FLAG_KEEP_SCREEN_ON` only while playing; on pause > 15 min let the system sleep, save progress before.
- Process death mid-playback → on relaunch open Home; Continue Watching has the correct position from Room.
- Low memory (`onTrimMemory`) → clear Coil memory cache; player untouched.
- Network change (Wi-Fi drop) → auto-retry with backoff for 60 s before showing an error; resume from the same position when back.
- Config: activity handles all config changes (see manifest) so the player never restarts on density/uiMode changes.

---

## 8. Data layer

### 8.1 Room schema (v1)
- `library_items` (id, type, title, sortTitle, year, runtimeMs, rating, ageRating, overview, genres JSON, posterUrl, backdropUrl, logoUrl, addedAt, updatedAt, etag)
- `seasons` (id, showId, number, name, posterUrl, episodeCount)
- `episodes` (id, showId, seasonId, seasonNumber, episodeNumber, title, overview, runtimeMs, thumbUrl, airedAt)
- `progress` (itemId PK, positionMs, durationMs, watched, updatedAt, syncState)
- `my_list` (itemId PK, addedAt, syncState)
- `search_history` (query, at)
- Migrations are versioned from day one; destructive fallback only in debug builds.

### 8.2 Sync
- Library: `GET /library/items?since=<updatedAt>` delta refresh on app start, on Home refresh (Menu → Refresh), and every 5 min while foregrounded. `ETag`/`If-None-Match` on all GETs.
- Progress/My List: outbox table pattern with WorkManager `OneTimeWorkRequest` (unique per item, `REPLACE`), network-constrained, exponential backoff.
- Conflict policy documented in §7.5.

### 8.3 Settings (DataStore, typed)
serverUrl, apiToken (in `EncryptedSharedPreferences`), preferredAudioLang, preferredSubLang, subsDefaultOn, autoplayNext, qualityPreference, frameRateMatch, tunneling, lastCapabilityReportHash.

---

## 9. Performance budget (measured on Fire TV Stick 4K 2018)
- Cold start to interactive Home ≤ 2.0 s (with Baseline Profile and cached library), ≤ 4 s uncached.
- Home scroll: no frame > 16 ms during D-pad key-repeat (Perfetto / `FrameMetrics`).
- Details open ≤ 300 ms after OK.
- Player: first frame ≤ 1.5 s on LAN direct play; seek to first frame ≤ 1 s.
- Memory: steady-state heap < 120 MB on Home; no OOM on 4K backdrops (they're downsampled to 1280 px).
- Techniques: `key`s and `contentType` on all lazy items; no `Modifier.blur`; `remember`ed painters; avoid recomposing whole rows on progress ticks (progress via `derivedStateOf`/per-card flows); Coil `precision inexact`; images decoded off-main; StrictMode in debug; LeakCanary in debug.

---

## 10. Error handling & empty states (global inventory)
| Situation | UX |
|---|---|
| No server configured | Onboarding screen: enter URL/token, test, save. Skippable to browse a "demo" fake library in debug builds only. |
| Server unreachable at start, cache exists | Show cached Home + non-blocking banner "Can't reach server — showing saved library" with Retry. Playback attempts show a clear error. |
| Server unreachable, no cache | Full-screen error with Retry / Settings. |
| 401/403 | Go to Settings with token field focused; keep cache. |
| Item 404 (deleted on server) | Toast + pop back, trigger library refresh. |
| Row endpoint fails | Row shows compact "Couldn't load — Retry" placeholder; rest of Home works. |
| Empty library / empty row / no results / empty My List | Purpose-written copy + one action button, focusable. |
| Slow images | Skeleton then placeholder; never layout shift. |
| Playback errors | §7.4. |
| Clock skew (server timestamps) | Use server `Date` header offset for `updatedAt` comparisons. |
| Very long titles/overviews | Ellipsis, marquee only on focus (single line), full text on OK. |
| Duplicate keys / malformed JSON | DTO mapping is lenient (unknown fields ignored, missing optional → defaults); a single bad item is skipped and logged, never crashes the list. |

---

## 11. Server contract the app requires (defines the fake, not the server)

Versioned under `/api/v1`, JSON, `Authorization: Bearer <token>`, all responses include `updatedAt` ISO-8601 UTC, all lists paged (`?page=&pageSize=`) with `{ items, page, pageSize, total }`. All GETs support `ETag`.

- 11.1 `GET /server/info` → `{ name, version, apiVersion, features: [transcode, sprites, markers] }` (used by "Test connection" and feature-gating).
- 11.2 `GET /library/items?type=movie|show&sort=&genre=&watched=&since=` ; `GET /library/items/{id}` (movie or show with `seasons[]` summary) ; `GET /shows/{id}/seasons/{n}/episodes`.
- 11.3 `GET /home` → ordered `rows[]` of `{ id, title, type: continue|list, items[] }` so the server controls row order/genres; app treats `continue-watching` and `my-list` specially (they also come from local Room for instant display).
- 11.4 Images: `posterUrl`, `backdropUrl`, `logoUrl`, `thumbUrl` accept `?w=` (server resizes) — app requests 300/500 for posters, 1280 for backdrops, 480 for thumbs.
- 11.5 `GET /items/{id}/playback-info?caps=<capability report>` → `{ sources: [{ id, kind: direct|hls, url, container, videoCodec, audioCodecs[], width, height, fps, hdr, bitrate }], subtitles: [{ id, lang, label, url (vtt), forced, default }], audioTracks: [{ index, lang, label, channels, codec }], markers: { intro?: {start,end}, credits?: {start}, recap?: {start,end} }, spriteSheet?: { url, interval, cols, rows, thumbW, thumbH }, resume: { positionMs, durationMs } }`. App picks source per §7.2.
- 11.6 Progress: `PUT /progress/{itemId}` `{ positionMs, durationMs, watched, updatedAt }` ; `GET /progress?since=` ; `DELETE /progress/{itemId}` (remove from Continue Watching).
- 11.7 My List: `PUT|DELETE /my-list/{itemId}` ; `GET /my-list`.
- 11.8 `GET /search?q=&limit=` → items with `matchedOn` (title/actor/overview).
- 11.9 Errors: `{ error: { code, message } }` with proper HTTP status; app maps `code` to typed errors.

The `FakeMediaRepository` implements this same interface in-process (with artificial latency and injectable failures) so every screen and error path is exercised before the server exists.

---

## 12. Testing & quality
- **Unit**: ViewModels (Turbine for Flows), resume/next-episode logic, source selection, focus-target-on-removal algorithm, progress reconciliation, capability report parsing.
- **Compose UI tests** (instrumented, run on Android TV emulator 1080p): D-pad key injection (`performKeyInput`), focus assertions on every screen, back-stack + focus restoration, empty/error states, RTL snapshot.
- **Screenshot tests** (Paparazzi/Roborazzi) for cards, rows, hero, player overlay in en/he.
- **Player integration** on device: matrix of H.264 1080p / HEVC 4K / HEVC 10-bit HDR10 / DV P8 / AV1 / AAC / AC3 / E-AC3 / DTS, sidecar VTT, embedded subs, 23.976 fps frame-rate switch, seek storms, network cut, Alexa "pause", Home-and-return.
- **Manual TV pass** each milestone with a checklist: overscan, contrast, focus visibility on a real (non-monitor) TV, remote latency.
- **Debug tooling**: `adb connect <fire-tv-ip>:5555`; in-app debug drawer (Menu long-press on Settings) to toggle fake repo, latency, failure injection, show FPS/heap overlay, export logs. Crash reporting: none in V1 beyond persisted local logs (no GMS; Sentry is an option later since it doesn't need GMS).
- **CI** (GitHub Actions): lint, detekt, unit tests, assemble debug + release (signed with a CI keystore secret), UI tests on emulator nightly. `scripts/install.sh` = `adb connect … && adb install -r app-release.apk`.

---

## 13. Milestones (each ends with a demo on the real Fire Stick)

**M0 — Skeleton (1 wk)**: repo, modules, Hilt, theme, typography, NavHost, manifest, banner, splash, CI, install script. Home shows a static row of fake cards, focus works, Back exits.

**M1 — Focus & rows (1–2 wk)**: design system components (`FocusableCard`, `MediaRow`, `HeroCarousel`, skeletons), focus restoration, key-repeat scroll performance, overscan, focus tests, RTL check. Fake repository with 100+ items.

**M2 — Browse & details (2 wk)**: Home rows from fake `/home`, Movies/Shows grids with sort/filter + paging, Movie & Show details, season/episode lists, next-episode logic, My List (local), Continue Watching (local), all empty/error states.

**M3 — Player (2–3 wk)**: Media3 + MediaSession service, direct play of local test files over a scratch HTTP server, controls overlay, seek UX, tracks, subtitles, progress/resume, Skip Intro / Next episode / Still watching, lifecycle edge cases (§7.6), frame-rate matching, tunneling toggle.

**M4 — Search & settings (1 wk)**: on-screen keyboard, search results, settings screen, server URL/token, capability report, secure storage.

**M5 — Real server integration (1–2 wk)**: Retrofit implementation of §11, delta sync, outbox sync, conflict handling, transcode fallback, sprite thumbnails, markers.

**M6 — Hardening (1–2 wk)**: perf pass on Stick 4K 2018, Baseline Profile, memory, error copy, accessibility pass with VoiceView, codec matrix, release build + R8 rules, screenshot suite.

Definition of Done for every milestone: no dead-focus states, no crash in a 30-min monkey run with D-pad-only events (`adb shell monkey --pct-nav 100 …`), all new logic unit-tested, runs on Fire OS 6, 7 and 8 test devices.

---

## 14. Explicitly out of scope (V1)
Server implementation & metadata scraping · profiles · Amazon Appstore submission · Fire TV catalog/launcher integration & Alexa Video Skill (deep links from the Fire TV home row) · in-app voice search · downloads/offline · casting · picture-in-picture · phone/tablet layouts · trailers/autoplay previews · parental controls (age-rating shown, not enforced).

## 15. Open questions to settle before M3
1. HDMI-CEC / AVR setups in the household: does anything need DTS or TrueHD passthrough (affects capability report and server transcode rules)?
2. Should "watched" state be per-device or global (only matters once a second client exists — plan is global via server, local fallback).
3. Hebrew subtitle sources: confirm the server will normalise encodings to UTF-8 VTT; otherwise the app needs a charset-sniffing subtitle loader (avoidable — don't do it in the app).
