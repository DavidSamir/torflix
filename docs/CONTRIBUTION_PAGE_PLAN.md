# "Your contribution" — design

**Status:** planned for the build *after* 0.2.4. 0.2.4 is the stable baseline.

## What this is for

Every viewer is already giving something back — the app uploads while it streams, and keeps seeding
what it has kept on disk. None of that is visible. The only place sharing appears today is a couple
of numbers in Settings that reset every time the app restarts, which is worse than nothing: it tells
someone who has seeded 40 GB that they have seeded 200 MB.

The page should let someone see, concretely and honestly, that they are part of what keeps these
films available: how much they have sent, to how many people, for which titles, and what is sitting
on their television right now helping someone else watch.

Two hard constraints, in priority order:

1. **Honest.** Every number is a real measurement or it is not shown. No estimates dressed as facts.
2. **Free.** It must cost nothing when the page is closed, and almost nothing when it is open. The
   floor device is a 2016 Fire TV Stick.

---

## What exists today, and what is missing

| Needed | Available now? |
| --- | --- |
| Bytes uploaded, per torrent | `TorrentStatus.totalUploadedBytes`, but **session-scoped** — libtorrent resets it on restart |
| Bytes uploaded, lifetime | ✗ nothing persists it |
| Which titles were seeded historically | ✗ lost the moment a torrent is evicted from disk |
| Which *parts* of a film are cached | `handle.pieces()` bitfield — available, never read |
| Peers served | `TorrentStatus.peers` is instantaneous only |
| Disk used / cap | `SharingStats.diskUsedBytes` / `diskCapBytes` ✓ |

So the work is mostly **accounting that survives restarts and eviction**, plus one new read
(the piece bitfield) and a screen.

---

## Data model

Two new Room tables. Room is at version 1 with destructive fallback off, so this is a real migration
(1 → 2) with a test, per the process documented on `TorfilxDatabase`.

### `contribution` — one row per title ever shared

| Column | Why |
| --- | --- |
| `infoHash` (PK) | survives eviction; the torrent may be long gone from disk |
| `title` | denormalised on purpose — the catalogue can change under us, and a contribution record must still be able to name what it was |
| `uploadedBytes`, `downloadedBytes` | lifetime totals, accumulated from deltas |
| `firstSharedAtMs`, `lastActiveAtMs` | "you have been seeding this for three weeks" |
| `sizeBytes` | to express upload as "×2.3 of the film" |
| `stillOnDisk` | distinguishes "seeding now" from "shared, since evicted" |

### `contribution_day` — one row per calendar day

`day` (epoch day, PK), `uploadedBytes`, `downloadedBytes`.

A rollup, not a sample log: bounded at **90 rows**, which is all the chart needs and is trivially
cheap to query. Older rows are pruned on write.

---

## Accounting

libtorrent's per-torrent counters are per *session*. Turning them into lifetime totals:

```
delta = if (current >= lastSeen) current - lastSeen else current   // session restarted
lifetime += delta
lastSeen  = current
```

`lastSeen` lives in memory, keyed by info hash. The `else` branch is the restart case: after a
restart libtorrent starts from zero, so the current value *is* the delta. This is the only correct
reading — treating it as a decrease and clamping to zero would silently discard everything shared
since the restart.

**Eviction** is handled by keying on info hash rather than on the torrent being present: the row
persists, `stillOnDisk` flips to false, and the totals stay.

**Consent.** Nothing is recorded while sharing is off — with upload throttled to nothing there is
nothing to record, and a contribution log for someone who has not consented to contribute would be
both wrong and slightly creepy.

---

## Performance budget

This is the part that decides whether the feature is worth having.

| Concern | Decision |
| --- | --- |
| Polling | **No new loop.** The engine already polls torrent status every 1 s while the session is up; the fold hooks into that existing tick |
| Work per tick | One subtraction and one map write per active torrent. Active torrents are capped at 4 by `MAX_ACTIVE_DOWNLOADS`/`MAX_ACTIVE_SEEDS`, so this is single-digit arithmetic |
| Disk writes | In-memory accumulation, flushed to Room **at most every 30 s**, and on app stop. A write per tick would be 86 400 writes a day on a device with slow flash |
| Piece bitfield | Read **only while the page is open**, at 1 Hz, and only for torrents still on disk |
| Piece map size | A 2 h film at 2 MB pieces is ~4 000 pieces. Downsampled to **≤ 120 buckets** before it ever reaches the UI — one bucket per drawn segment, so the UI never iterates thousands of booleans |
| Charts | Compose `Canvas`, drawn from ≤ 90 pre-aggregated values. No charting dependency, no bitmaps, no per-frame allocation |
| When closed | Zero. No flows collected, no bitfields read. The only residue is the fold, which was already happening |
| Where it runs | All aggregation on the IO dispatcher; the screen observes a single `StateFlow` of a ready-to-render model |

---

## The page

Reached from Settings → "Your contribution". Not on Home: it is a place you go, not something that
competes with watching a film.

**1 — The headline.** Total shared, in plain words, with the framing that this is a gift:
*"You've shared 41.2 GB — about 18 full films — with 1 240 people."*

**2 — Last 30 days.** A bar chart from `contribution_day`. Bars, not a line: daily totals are
discrete quantities and a line would imply interpolation between days that did not happen.

**3 — Right now.** Live upload rate, peers connected, which titles are seeding this second. This is
the only section that updates continuously, and only while visible.

**4 — Per title.** A focusable row per film: uploaded, ratio, how long it has been shared, and a
**piece strip** — a thin bar showing which parts of that film are on this television. This is the
literal answer to "what parts of which movie are cached on my TV", and it is the part that makes the
abstraction concrete: you can see the film as a physical thing you are holding a piece of.

**5 — Storage.** Used against the cap, and what eviction will take next — so the budget setting stops
being abstract.

### Tone

Thankful, not gamified. No badges, no streaks, no leaderboard. Someone keeping a 1921 Chaplin film
alive for strangers does not need a points score; they need to be told plainly that it happened.

### Accessibility

Every chart carries a text equivalent in its content description — a bar chart is invisible to
VoiceView otherwise, and this app already treats that as a defect.

---

## Privacy

The record is local, never leaves the device, and is included in "Clear data" — a per-title log of
what someone has seeded is exactly the sort of thing that must be erasable in one action. It is also
excluded from the backup export unless the user opts in.

---

## Order of work

1. Room migration 1 → 2, entities, DAO, exported schema, migration test.
2. Delta accounting as a **pure function**, unit-tested for the restart and eviction cases — it is
   the part that is impossible to verify by looking at a television.
3. Fold into the existing status tick; flush on a timer and on stop.
4. Piece-map read + downsample, with the bucket arithmetic unit-tested like `PieceMath` already is.
5. The screen: headline, chart, live, per-title, storage.
6. Wire "Clear data" to wipe it.

Steps 1–4 are the ones that must be right; 5 is replaceable.
