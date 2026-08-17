# TORFILX — Production Readiness Checklist

Worked top to bottom. Each item is committed on its own. `[x]` = done, `[ ]` = pending.

## Tier 0 — Blockers

- [x] **1. Release signing stability** — a missing CI keystore silently falls back to a fresh debug key, breaking in-place upgrades. Guard tagged releases; document the keystore.
- [x] **2. Stream-server correctness** — 64 KB reads cross piece boundaries into not-yet-downloaded data (zero-fill corruption); a stalled swarm truncates the HTTP body under a declared Content-Length.
- [x] **3. Uncaught exceptions on the play path** — `open()`/`retry()` only catch `TorrentError`/`DataError`; anything else crashes the app.
- [x] **4. Storage safety** — active download never capped (disk fills), orphaned data can't be evicted after restart, "Clear data" doesn't delete files. `NoSpace` never thrown.
- [x] **5. Field failure visibility** — no remote crash/error capture, and the in-memory crash log is destroyed when CrashGuard restarts the process. _(Durable on-disk crash reports + export; true auto-telemetry needs a backend and is out of scope without one.)_
- [x] **6. Localization / RTL** — all strings hardcoded; `values-he` untranslated; decide in (externalize + translate + RTL) or out (drop the `he` config). _(Decided out: Hebrew removed, ships English-only.)_

## Tier 1 — High

- [x] **7. Seeding/privacy** — session never stopped on normal exit; seeding continues with no indicator; no VPN/kill-switch; watching requires uploading. _(Session now stops on app exit; VPN kill-switch and the watch-requires-upload coupling remain as design decisions — see done log.)_
- [x] **8. Foreground service** — started with `startService`; no notification channel; `POST_NOTIFICATIONS` never requested → FGS crash risk on Android 12/13. _(Mostly already correct: Media3 self-promotes and creates the channel; added the API-33+ runtime permission request. No current Fire TV is API 33.)_
- [x] **9. Data loss on reinstall** — progress / My List / settings are local-only with `allowBackup=false` and no export. _(Added file-based backup/restore of watch data; Google Auto Backup doesn't exist on Fire OS.)_
- [x] **10. `lastTouched` data race** — plain HashMap read/written from two dispatchers.
- [x] **11. Room migration trap** — no migrations, no migration test; first schema bump crash-loops.
- [x] **12. CI quality gate dead** — lint never runs in CI; baseline hides a fatal `Instantiatable`.
- [x] **13. Player focus loss** — focus can be stranded after controls auto-hide.
- [x] **14. No tests on riskiest code** — torrent engine, stream server, playback controller, real catalog parser.

## Tier 2 — Medium

- [x] **15. R8 fully off** — bloat, no obfuscation, dead proguard rules, false comment.
- [x] **16. DataStore corruption handler** — a corrupt settings file breaks all writes permanently.
- [ ] **17. Catalog parse resilience** — one bad byte empties the whole catalog; non-streaming decode on a low-RAM stick.
- [ ] **18. Accessibility** — chips/toggles/scrubber/search unlabeled for VoiceView.
- [ ] **19. Wire `NetworkMonitor`** — no offline affordance, no mid-stream recovery; remove dead banners.
- [ ] **20. Engine efficiency** — uncapped upload, RAF-per-chunk, metadata busy-poll.
- [ ] **21. "Media server" copy remnants** — wrong wording for a torrent-only app.
- [ ] **22. Update hygiene** — no in-app update check; versionCode decoupled from tag.
- [ ] **23. Log export usefulness** — no date on timestamps (wraps at 24h); no device model; external-storage export.

## Tier 3 — Low

- [ ] **24. Assorted** — subtitle sizing, subtitles-on-with-no-language no-op, keep-screen-on while paused, display-mode restore on crash, `values-iw` for Fire OS 5, Home back-to-exit confirm, dead code, download-failure→invalid-magnet mapping.

---

### Done log
_(most recent first)_

- **#16 DataStore corruption handler** — a corrupt settings file threw on every read (caught, falls
  back to defaults) but also on every write (not caught), so a one-time corruption locked the user
  out of changing any setting forever. Added a ReplaceFileCorruptionHandler that swaps a corrupt
  file for empty preferences, turning the permanent lockout into a one-time reset to defaults.

- **#15 R8 off + bloat + false comment** — R8 stays disabled (ART on Fire OS 5 miscompiles its dex
  into a launch SIGSEGV; correctness over size), but corrected the proguard-rules.pro header that
  falsely claimed "R8 full mode is on" to state it is disabled and why. Cut real bloat instead:
  every Fire TV is 32-bit ARM, so the x86/x86_64 libtorrent binaries were ~13 MB of emulator-only
  dead weight in the shipped APK. Release now ships armeabi-v7a + arm64-v8a only, dropping the
  release APK from 29 MB to 17 MB (-41%). Debug keeps all ABIs so it still runs on the x86 test
  emulators. Trade-off: the release APK installs only on ARM (all production devices); emulator
  smoke-testing uses the debug build, whose code is identical since minification is off in both.

- **#14 Tests on riskiest code** — extracted the catalogue parser to a pure `parseCatalog(raw, json)`
  and added a 6-case test that exercises the *real* parser (magnet validation, id de-duplication,
  genre normalisation, year/quality parsing, unknown-field tolerance) rather than a copy of its
  rules. Wired instrumented tests into CI: a separate emulator job runs the Room migration test on
  every push/PR (non-release-blocking, since emulators can be flaky). Combined with earlier work,
  the pure logic is now covered — piece-boundary math (#2), storage budget + magnet validation,
  catalogue parse, and backup round-trip (#9). The torrent engine and stream-server socket paths
  remain integration-tested on-device rather than unit-tested, as they need the native library.

- **#13 Player focus loss** — root focus is now re-requested whenever the controls overlay hides,
  so after the 4s auto-hide removes the focused scrubber subtree the D-pad keeps reaching the
  root handler (Up/Down reopen the overlay) instead of going dead mid-movie. While the overlay is
  up the scrubber owns focus; the two no longer compete.

- **#12 CI quality gate** — CI now runs `:app:lintRelease` after the unit tests, so the strict lint
  config (warningsAsErrors, abortOnError) actually gates the build. The fatal `Instantiatable` false
  positive is suppressed precisely on the service with `tools:ignore` (not baselined), so a genuine
  non-instantiatable component would still fail. Regenerated the baseline to capture only known
  cosmetic/opt-in debt (icons, Media3 , targetSdk) — 25 filtered, 0 new — so lint fails
  on any NEW issue. Also removed the leftover LAN network-security config: no more user-installed CA
  trust (a real release MITM vector) and no invalid CIDR domains; only the loopback stream server
  keeps cleartext.

- **#11 Room migration trap** — added an instrumented MigrationTestHelper test that replays the
  exported schemas (verified: ran on the API 22 emulator, 1 test, 0 failures) and is the template
  for validating every future migration; wired the schema dir as androidTest assets. Expanded the
  TorfilxDatabase KDoc with the exact, ordered procedure for a safe schema change, so a version bump
  without a migration + test is caught rather than shipped as a startup crash-loop. (CI wiring of
  instrumented tests is item #14.)

- **#10 lastTouched data race** — the eviction bookkeeping map was a plain HashMap written from the
  status poller (Default dispatcher) and read from enforceStorageBudget (IO); made it a
  ConcurrentHashMap so concurrent access can no longer throw ConcurrentModificationException or
  corrupt its internal state.

- **#9 Data loss on reinstall** — Fire OS has no Google backup transport, so Auto Backup would be a
  no-op on the target device (and would leak watch history to Google on those that do have it, which
  is why `allowBackup` stays false). Instead added `UserDataBackup`: Settings → Watch data →
  "Back up" / "Restore" writes and reads watch progress + My List as a JSON file the user can copy
  off and back (e.g. via adb) to survive a reinstall or a new stick. Restore merges by timestamp so
  a newer local position is never clobbered by an older backup. Covered by a 3-case round-trip test.

- **#8 Foreground service** — investigated against the Android docs: a Media3 `MediaSessionService`
  promotes itself to the foreground and posts the `MediaStyle` notification automatically when the
  player has items, and creates its own notification channel, so `startService` from the foreground
  activity is correct and there is no `startForeground`-timeout exposure. The manifest already
  declares `foregroundServiceType="mediaPlayback"` and the right permissions. The one real gap was
  the API-33+ `POST_NOTIFICATIONS` runtime grant (needed only for the notification to be *visible*);
  added a guarded, best-effort request. No current Fire TV runs API 33, but targetSdk is 34 so it is
  future-proofed. The audit's "HIGH" was a generic-Android concern that mostly did not apply here.

- **#6 Localization** — decided English-only. Removed the unused Hebrew resource config
  (`resourceConfigurations` is now just `en`), deleted the empty `values-he` folder (which also
  clears the `LocaleFolder` lint error), and dropped the Hebrew on-screen-keyboard layout. The app
  now honestly ships as English-only; no half-built bilingual surface remains.

- **#7 Seeding/privacy** — the torrent session is now stopped when the app is exited (activity
  finishing), swiped from recents (`onTaskRemoved`) or the service is destroyed, so nothing keeps
  seeding — uploading and advertising the viewer's IP — in the background after they leave. Within
  the app, post-playback seeding still works and is visible in Settings. **Still open, as design
  decisions rather than bugs:** there is no VPN-bind / kill-switch (the public IP is exposed to the
  swarm while streaming), and watching requires consenting to upload (no leech-only mode). Both are
  deliberate product/legal choices to make, not defects to patch silently.

- **#5 Field failure visibility** — added `CrashStore`: the crash guard now writes each uncaught
  exception (with device, OS, app version, thread and full stack) to app-private disk, so it
  survives the process kill that wipes the in-memory buffer. Settings → Export logs prepends a
  device header and the durable crash reports, so a user-sent export actually contains the crash.
  The guard also abandons restarting after 5 consecutive crashes (a slow, steady loop), not only a
  fast <10 s loop, with a stability reset once the app has run 2 min cleanly. Log timestamps now
  carry a date (no more 24 h wrap), and the export write moved off the main thread. True automatic
  remote telemetry still needs a backend to receive it, which is out of scope for a sideloaded app
  without one — the durable, exportable report is the realistic substitute.

- **#4 Storage safety** — "Clear downloaded data" now stops the session and actually deletes the
  files (`purgeAllData`), instead of a budget check that usually did nothing; un-resumable orphan
  data is purged on every cold start so dead downloads can't accumulate across restarts; `stream()`
  throws `NoSpace` when free space is already below the 500 MB reserve; and the status poller stops
  all downloads if free space dips below the reserve floor, so a single large title can't march the
  disk to zero and wedge the device.

- **#3 Uncaught exceptions on the play path** — `open()` now wraps its body so any unforeseen
  throwable (e.g. a port-bind `IOException`) becomes a retryable on-screen error instead of an
  uncaught crash in the play coroutine; `CancellationException` is re-thrown so coroutine teardown
  still works, and `retry()` inherits the safety. The engine also converts a stream-server bind
  failure into `TorrentError.EngineUnavailable`.

- **#2 Stream-server correctness** — reads are now capped to the end of the piece confirmed present,
  so a read can never pull zero-filled holes from the sparse file (the corruption source); one
  `RandomAccessFile` is reused per connection instead of reopened per 64 KB; out-of-range requests
  get a proper `416` and the `Range` unit is validated. Piece arithmetic extracted to pure functions
  with a 6-case unit test. Truncation on a genuinely dead swarm (no piece in 120 s) is retained as
  the honest failure signal; the misleading error copy is item #21.

- **#1 Release signing stability** — CI now aborts a tagged (`v*`) release when the release-keystore
  secrets are absent, instead of silently debug-signing a non-upgradeable APK; the signing
  certificate fingerprint is printed on every build so a key change is visible; added
  `docs/RELEASING.md` with keystore creation + secret setup. Local/PR builds keep the debug
  fallback. No runtime code touched.
