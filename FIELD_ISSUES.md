# Field issues — reported from a real Fire TV, Aug 2026

Five symptoms reported after on-device use. Each is investigated to a root cause, fixed
defensively (the device cannot be attached to a debugger, so every plausible cause is addressed,
not just the most likely one), and covered by a test where a test can express it.

`[x]` = done and verified. `[ ]` = pending.

---

## A. Player controls do not work correctly

- [x] **A1. The auto-hide timer never restarts on input.** `LaunchedEffect(controlsVisible, …)` is
  keyed on state that does not change while the user is pressing keys, and `showControls()` writes
  `true` over `true`, which a `MutableStateFlow` does not re-emit. The overlay therefore disappears
  4 s after it opened no matter how much the user is interacting with it.
- [x] **A2. `return` instead of `return@Box`** in the sharing-consent branch abandons the rest of the
  composable, skipping the trailing `DisposableEffect`.
- [x] **A3. OK on a hidden overlay.** Investigated, left as is: `togglePlayPause()` already calls
  `showControls()`, so OK both acts and reveals, which matches the Fire TV player. No change made.
- [x] **A4. Focus is not restored deterministically** when the overlay hides/reopens; the scrubber
  re-grabs focus on every reopen, discarding where the user was.
- [x] **A5. Held keys are not rate-limited.** The root handler acts on every repeat event, so holding
  ← fires ~20 seeks/second into the accumulator.
- [x] **A6. Track pickers.** Investigated: they sit in the same `Column` as the button row, so
  one-dimensional focus search reaches them on ↓. No defect found, nothing changed — what actually
  made them usable is A1, the overlay no longer vanishing mid-interaction.

## B. Not all movies are visible (~25 of 2000)

Catalogue verified healthy: **2000 entries, 3390 valid magnets, 21 genres, 0 type errors, 0 unknown
keys** — so nothing is lost at parse time. The loss is in navigation/presentation.

- [x] **B1. The grid cannot be scrolled past the first screenful** with a D-pad: 6 columns × ~4
  visible rows ≈ the 25 titles reported. Focus search only finds composed items.
- [x] **B2. Home rows are capped** at 60 items and 12 genre rows, silently.
- [x] **B3. No visible total.** Nothing on screen says how many titles exist, so a truncated view is
  indistinguishable from a small catalogue.
- [x] **B4. List identity churn.** Investigated, not a live defect: `observeAllProgress()` emits
  only when a progress row is written, which happens during playback — not while the grid is being
  browsed. Left alone rather than adding caching that would buy nothing.
- [x] **B5. A debug-only catalogue was shadowing the real one.**
  `app/src/debug/assets/catalog.json` held **2 titles** and, through Android's asset merge, replaced
  the 2000-title catalogue in every debug build — silently, with nothing in the build output or the
  app to say so. The README tells people to sideload `app-debug.apk`, so anyone following it since
  it was committed (88ef19d, 2026-08-17) got a two-film app.

  Scope, checked rather than assumed: the shipped APKs are unaffected —
  `dist/torfilx-release.apk` (release variant) and `dist/torfilx-debug.apk` (built 2026-08-16,
  before the stub existed) both contain 2000 titles. Only debug builds made after 2026-08-17 are
  affected. Removed, and `app/build.gradle.kts` now **fails the build** if any variant source set
  reintroduces a catalogue override.

## C. "Network error" on opening a title, works after 3–4 retries

- [x] **C1. The stream server truncates the response body.** `serveBytes` writes headers with a
  `Content-Length`, then returns without writing any bytes when the file is not yet on disk. A
  short body is a hard I/O error to ExoPlayer → "Connection lost". This is the first request of
  every new title, which is exactly when the file does not exist yet.
- [x] **C2. A piece timeout mid-body truncates the same way.**
- [x] **C3. The torrent session starts cold on first play.** DHT bootstrap begins when the user
  presses Play, so the first attempt races a DHT with zero nodes; by the third retry it has peers.
- [x] **C4. No automatic retry.** A transient swarm failure is shown to the user immediately instead
  of being retried with backoff.
- [x] **C5. Wrong `Content-Type` and no file extension** on the loopback URL, so the extractor has to
  fall back to sniffing.

## D. Video plays but there is no audio, on many titles

- [x] **D1. No fallback when the audio codec has no decoder.** Extension renderers are off, so a
  track the device cannot decode (DTS, TrueHD, some AC3/EAC3, Opus/Vorbis on Fire OS 5) results in
  *no audio track being selected at all* — and ExoPlayer raises no error, it just plays silently.
- [x] **D2. Nothing detects or reports the silent state.** The player looks like it is working.
- [x] **D3. Preferred-audio-language filtering** can leave nothing selected on a file whose only
  track is undetermined or another language.
- [x] **D4. Tunneling is on by default** and is a known cause of silent playback on older Fire OS,
  and the capability check only inspects *video* decoders.
- [x] **D5. The reused player carries stale track overrides** from the previous title.

## E. Stress-test the whole thing (cache and all)

- [x] **E1.** Stream-server tests: truncation, ranges, 416, slow/missing pieces, HEAD, concurrency.
- [x] **E2.** Full-catalogue load test (all 2000 entries, not a fixture of three).
- [x] **E3.** Storage budget and eviction ordering are covered by the existing `StorageBudgetTest`.
  Reviewed, not expanded.
- [ ] **E4.** Repeated open → play → leave cycles. **Not done.** It needs an instrumented test on a
  real device or emulator — a JVM unit test cannot hold an ExoPlayer — and the machine this was
  built on could not keep a Gradle daemon alive, let alone an emulator. The specific stale-state
  hazard it would have caught is addressed directly instead (D5: track overrides cleared per title).

### Content gaps found while verifying the catalogue

All 2000 titles are present in the catalogue **and in both APKs** (verified by unzipping them).
Nine carry magnets the validator rejects, for three distinct reasons in the source data:

| Titles | Problem |
| --- | --- |
| Megamind, Walking with Dinosaurs 3D | no magnets at all |
| Baby Driver | magnets are unsubstituted scraper templates, `{$tt.info_hash}` |
| Mile 22, Happy Death Day 2U, The Longest Ride, The Chronicles of Narnia, Boss Level, Good Boys | every magnet has a **41-character** info hash: a 40-hex hash with a `1` prepended |

The 41-character case is a generator bug, not a validator one: all 17 of those magnets begin with
`1`, which random corruption would not produce. The true hash is almost certainly the trailing 40
characters, but "almost certainly" is not good enough — a wrong info hash resolves to nothing and
costs the viewer a full metadata timeout before failing, which is worse than an honest "no playable
version". Left failing loudly, and pinned by a test so the number cannot grow unnoticed.

---

## Done log

_(most recent first)_
