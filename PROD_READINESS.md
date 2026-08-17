# TORFILX — Production Readiness Checklist

Worked top to bottom. Each item is committed on its own. `[x]` = done, `[ ]` = pending.

## Tier 0 — Blockers

- [x] **1. Release signing stability** — a missing CI keystore silently falls back to a fresh debug key, breaking in-place upgrades. Guard tagged releases; document the keystore.
- [x] **2. Stream-server correctness** — 64 KB reads cross piece boundaries into not-yet-downloaded data (zero-fill corruption); a stalled swarm truncates the HTTP body under a declared Content-Length.
- [ ] **3. Uncaught exceptions on the play path** — `open()`/`retry()` only catch `TorrentError`/`DataError`; anything else crashes the app.
- [ ] **4. Storage safety** — active download never capped (disk fills), orphaned data can't be evicted after restart, "Clear data" doesn't delete files. `NoSpace` never thrown.
- [ ] **5. Field failure visibility** — no remote crash/error capture, and the in-memory crash log is destroyed when CrashGuard restarts the process.
- [ ] **6. Localization / RTL** — all strings hardcoded; `values-he` untranslated; decide in (externalize + translate + RTL) or out (drop the `he` config).

## Tier 1 — High

- [ ] **7. Seeding/privacy** — session never stopped on normal exit; seeding continues with no indicator; no VPN/kill-switch; watching requires uploading.
- [ ] **8. Foreground service** — started with `startService`; no notification channel; `POST_NOTIFICATIONS` never requested → FGS crash risk on Android 12/13.
- [ ] **9. Data loss on reinstall** — progress / My List / settings are local-only with `allowBackup=false` and no export.
- [ ] **10. `lastTouched` data race** — plain HashMap read/written from two dispatchers.
- [ ] **11. Room migration trap** — no migrations, no migration test; first schema bump crash-loops.
- [ ] **12. CI quality gate dead** — lint never runs in CI; baseline hides a fatal `Instantiatable`.
- [ ] **13. Player focus loss** — focus can be stranded after controls auto-hide.
- [ ] **14. No tests on riskiest code** — torrent engine, stream server, playback controller, real catalog parser.

## Tier 2 — Medium

- [ ] **15. R8 fully off** — bloat, no obfuscation, dead proguard rules, false comment.
- [ ] **16. DataStore corruption handler** — a corrupt settings file breaks all writes permanently.
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
