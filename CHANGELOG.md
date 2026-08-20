# Changelog

All notable changes to Matchbook over the past month, newest first.

## [0.7.7] — 2026-08-20

**Added: match times are now shown in each player's own timezone.**

Match dates and times in the GUIs (match list, match details, event log) were rendered in the server machine's timezone — UTC on a typical host — regardless of where the viewer actually lives. Matches are stored as absolute unix timestamps, so this is purely a display change: no saved data is touched, and two players viewing the same match each see it in their own local time.

- **`/mb timezone <zone>`** (alias `/mb tz`) sets a personal display timezone, remembered per player. US-friendly names are accepted — `eastern`, `central`, `mountain`, `pacific`, `alaska`, `hawaii`, `arizona`, and abbreviations like `est`/`cdt` — as well as any IANA id like `America/New_York`. Abbreviations map to real zones, so daylight saving stays correct year-round. `/mb timezone` alone shows the current setting; `/mb timezone reset` returns to the server default.
- **`display.timezone`** in config.yml sets the server-wide default (`server` = the machine's zone, the old behavior). Set it to e.g. `America/New_York` and only players outside that zone ever need the command. The new section is added to existing configs automatically (config-version 0.1.3).
- New permission `mb.command.timezone`, included in the default `mb.command.default` group.

**Fixed**

- **The match-details Back button always went to the viewer's own match history, page 0** — even when the match was opened from `/mb all` or from a later page of a history list. The details GUI (and the event log GUI behind it) now remembers where it was opened from, and Back returns there — same list, same page. Backing out of an event log also returns to the details page you left it from, and details opened directly via `/mb view` still fall back to your own history.

## [0.7.6] — 2026-08-11

Two field-reported bugs, both from trusting MBedwars' account of a match over Matchbook's own record of it.

**Fixed**

- **A team could be recorded with an extra player who never played that match** — a fifth member on a four-player team, all-zero stats, no events, sometimes someone who was spectating at the time. MBedwars' round-end roster includes `QuitPlayerMemory` entries, which are per-arena and outlive the round that created them; one left behind by an *earlier* round arrived still carrying its team and got bucketed into this round's winners or losers by it. Matchbook now tracks who actually played each round (round-start roster, plus anyone who joined or got a team while it ran) and drops participants on neither that roster nor the event log, with a `phantom_participant_dropped … not_on_round_roster` warning. Mid-round leavers and players who never killed, died, or broke a bed are unaffected.

- **A match that ended with several teams still standing recorded a winner instead of a tie** — a three-way tie came out as *1st, 2nd, 2nd, 4th*. Only one team had actually been eliminated; the rest ran to the time limit, and MBedwars' own tiebreak named a winner that Matchbook took at face value. More than one team standing at the end now means those teams **tied for 1st** regardless of what MBedwars announced (eliminated teams keep their earned placement; the override is logged). Set `match.multiple_survivors_are_a_tie: false` to keep the old behaviour. If MBedwars names a winner Matchbook doesn't have alive at all, the announced winner still stands.

- **A spectator present at round start could be promoted into the match roster**, undoing a correct classification made moments earlier — whether `Arena#getPlayers()` includes spectators varies across MBedwars builds. Round-start capture now leaves teamless viewers alone.

**Verified by execution**: a harness replays both reported matches from their exported event logs through the real session/lifecycle/document classes — 18/18. Each bug is reproduced from the real data first (the phantom fifth player; the exact 1,2,2,4 standings), then shown fixed with the rest of each match unchanged, plus guard rails for the three cases where the new rules deliberately stand down.

Already-recorded matches are not corrected retroactively.

## [0.7.5] — 2026-08-06

**Changed: match stats are now derived from the event log — the event log is the source of truth.**

Until now, the stats CSV was built from MBedwars' own per-round counters (cross-checked against matchbook's live event counters, taking the higher of the two). That path had several independent ways to go wrong — a counter not reset between back-to-back rounds on the same arena, a roster picking up players from the arena's *next* lobby, the max-merge hiding an over-count — and after two rounds of point fixes (0.7.1, 0.7.3) the same family of symptoms came back in the field: stats rows for players with zero recorded events, and final-kill counts the event log couldn't back up.

The event log never had these problems, so the exported stats now come from it directly:

- **Kills, final kills, deaths, final deaths, beds destroyed, and beds lost are counted from the match's own recorded events** (kill credits from the death rows attributing them, beds lost from which team's bed each `BED_BREAK` row names). The stats CSV and the events CSV can no longer disagree — one is computed from the other.
- **Wins, losses, placements, and ties are unchanged** — they were already derived from matchbook's own result/placement tracking, not from counters.
- **Custom tracked keys** an admin added (anything the event log has no record of) still come from the MBedwars counter snapshot, as before.
- **MBedwars' counters are demoted to a diagnostic cross-check.** They're still captured, and any disagreement with the event-derived value is recorded as a `stat_mismatch` warning inside the match document — so if the counters drift again, the evidence is preserved instead of exported.
- **Phantom participants are gone.** A teamless "player" the event log never mentions — the roster-capture artifact behind the white-wool rows with stats carried over from a previous round — is dropped from the match document entirely, with a `phantom_participant_dropped` warning as the paper trail.

Event log **recording** is untouched — same events, same rows, same pairing logic as 0.7.4 — with one addition: a death MBedwars flags as not counting toward stats now carries `stats_uncounted: true` on its row (new last column in events CSV exports). The row stays in the log either way; the flag is what lets the stats derivation skip exactly the deaths MBedwars would have skipped, so the two files always reconcile. Old match documents are untouched and render/export exactly as recorded.

**Fixed**

- **`plugin.yml` still declared version 0.7.3**, so matches recorded by 0.7.4 were stamped `matchbook_version: 0.7.3` and the update checker compared against the wrong running version. Both jar and plugin metadata now say 0.7.5.

**Verified by execution** against the compiled release (standalone harness driving the real session/document/codec classes): 39/39 checks — full derivation on a normal match (kills/finals/beds/placements/win-loss), a reproduction of both reported 0.7.3 corruptions (phantom roster row dropped with warning; final_kills over-count exported as the event-log value with a `stat_mismatch` warning), carried-over counter garbage zeroed for a teamed player with no events, uncounted-death exclusion with the kill still credited, legacy `PLAYER_KILL` rows counted, custom tracked-key passthrough, tie handling (tied teams get `ties`, the earlier-eliminated team keeps its loss), and YAML round-trip of the new flag.

## [0.7.4] — 2026-08-03

**Fixed: kill attribution now pairs kills to deaths exactly instead of guessing by time.**

MBedwars fires two events for every attributed death — the death itself and the kill — and matchbook has to glue them back together into one event-log row. Until now it did that by guessing: "same victim, within 10 seconds." That guess had real failure modes: a second kill on the same victim inside the window silently overwrote an unmatched first attribution (a kill counted in stats with no event row backing it — the mismatch reported against 0.7.3), and a fast respawn could merge a kill into the wrong one of two nearby deaths.

Both MBedwars events carry the same underlying Bukkit death event, so the two halves are now matched by that shared identity — exact, order-independent, no time window. Additionally:

- **If the kill event arrives first, it now writes the complete death row itself** (it carries the full death context), instead of parking the attribution and hoping the death event shows up to claim it.
- **The old victim+time matching survives only as a fallback** for the rare case where no shared Bukkit event is available, and a one-time console warning fires if identity pairing ever misses — so if the exact-pairing assumption is ever violated in the wild, it shows up in the console instead of in the data.
- **Kills that never match a death event now log a console warning at save time.** With exact pairing that situation always indicates a real capture gap, not a timing hiccup, so it is no longer silent.

> ### ⚠️ Match codes are now longer
>
> New match codes are **5+5 characters** — `8F3KQ-2JDXW` instead of `8F3K-Q2JD`.
>
> **Nothing you already have breaks.** Existing codes keep working everywhere (`/mb view`, `/mb export`, tab completion), no database or config change is needed, and only newly recorded matches use the longer format.
>
> Why: at 4+4, a server reaching 100k matches had a ~0.5% chance of two matches drawing the same code — which silently overwrote the older one. 5+5 makes that roughly 1 in 200,000.

Audit release. A full read of the codebase turned up eleven issues, all fixed here. Nothing that was already working changed, and the 0.7.2 placement fixes are unaffected.

**Fixed**

- **A failed save could lose the match on YAML storage.** The write error was logged and swallowed instead of thrown, so the retry-and-recovery-copy safety net never engaged on the default backend — a disk-full or permissions error discarded the match outright, and dropped it from `/mb matches` too. Failures now propagate, so YAML gets the same retries and `matches/failed/` recovery copy MySQL always had.
- **A tie with only one team in 1st recorded that team as having lost.** When MBedwars reports no winner and the `bedwars:wins` fallback is also missing, the match stayed marked `TIE` while a single team stood alone at 1st — producing records carrying `matchbook:1st_place` and `bedwars:loses: 1` on the same row. That result is now reconciled into the win it actually was. Genuine multi-team ties are left alone.
- **Match-code collisions silently overwrote the stored match.** Saving now refuses to overwrite a row belonging to a *different* match: the existing match stays intact, the new one lands in `matches/failed/` as YAML, and the console says exactly what happened. (See the note above for the change that makes a collision near-impossible in the first place.)
- **`/mb migrate` and `/mb view` froze the server.** Both did storage work on the main thread — migrate parsed every match document inline, and `view` scanned every match file on disk, twice for a code that doesn't exist. Both now run in the background; migrate also refuses to start while another is running.
- **The last kill of a match could go unrecorded.** Kills, deaths, bed breaks and team eliminations that MBedwars delivers asynchronously were handled a tick late, by which point the round had ended and they were discarded. They now stay attached to the round they actually happened in.
- **A failed MySQL startup leaked its connection pool**, holding connections for the rest of the server's uptime after quietly falling back to YAML.

**Changed**

- **`matches/failed/` is now quarantine.** Recovery copies no longer appear in `/mb all` or `/mb view`, so a record that storage rejected can't be mistaken for one that saved cleanly. The console message now spells out how to bring one back.

**Hardened**

- `saveMatch` rolls back and restores autocommit explicitly on a SQL failure instead of relying on the connection pool to clean up after it.
- Removed dead code and stale imports, and fixed a match-code splitting pattern that silently matched only literal spaces rather than all whitespace.

**Verified by execution** against the compiled release: 29/29 checks on the fixes above, plus a full re-run of the 0.7.2 placement suite (18/18).

## [0.7.2] — 2026-07-27

**Fixed: team placements skipped a rank — 3- and 4-team matches recording 1st, 3rd and 4th with no 2nd place.**

The cause was a *phantom team*. The team selector fires a team-change event in the pre-round lobby too, so a player who picked a team and then switched away left the abandoned team behind in the match's team list. That empty team then cost the standings twice over: it inflated the team count placements are calculated from (pushing every elimination down a rank), and it took a rank of its own. Because ranks are only ever recorded onto players, the rank it took landed on nobody and vanished — the missing 2nd place.

Three changes:

- The team count is frozen from the teams that actually have a player when the round starts, not from every team anyone was ever assigned to.
- Teams nobody finished the match on are discarded before placements are worked out, so an empty team can no longer hold a rank.
- As a backstop, final standings are collapsed to a contiguous 1..N ranking, so *any* drift in the team count can no longer surface as a gap. Finishing order is preserved — only the numbers are re-seated.

Genuine ties are unaffected: teams that tie for 1st keep a shared rank with the next rank skipping accordingly (1, 1, 3), and still get `matchbook:ties` rather than a false 1st-place credit.

**Verified by execution** against the compiled release — 18/18 checks, including a reproduction of the reported 1/3/4 bug on the old logic and its correction on the new.

## [0.7.1] — 2026-07-24

**Fixed: a recurring "empty team, stats carried over from the last time the map was played" bug — a different bug than the one already fixed in 0.6.9/0.6.10 with the same symptom.**
- Root cause: `RoundEnd` built the match's final participant list from `arena.getPlayers()` — the arena's *current* occupancy at the moment the handler ran. On arenas where the next round's players queue back in immediately, that live snapshot could already include players who had just joined the lobby for the *next* round and had no team yet. They'd get added to the ending match as a participant with an empty team, and the end-of-match live-stats fallback (safe only for players still "in the arena") would then read their per-round game-stats object before MBedwars reset it for their own upcoming round — i.e. their totals from the last time they actually played that map. This is a distinct mechanism from the 0.6.9 "ended session reused by the next round" bug (which is still fixed and unaffected); it reproduces the identical symptom through the participant-capture step instead.
- Fixed: `RoundEnd` handling now builds the participant roster from MBedwars' own `RoundEndEvent` winner/loser lists (including the offline `QuitPlayerMemory` buckets for anyone who already left) instead of re-querying live arena occupancy. This is the actual roster of who played the round that just ended, frozen at the moment it ended, regardless of who has since queued back into the same arena.
- Hardened as defense in depth: every event handler that mutates an in-progress match (kills, deaths, bed breaks, team changes, quits, team-eliminate, winning-team) now refuses to touch a session once it has recorded an end time. A finished match's data can take several seconds to fully snapshot and save (async stat callbacks under load); during that window any straggler event for that arena now belongs to whatever comes next, never to the match already being closed out and saved.

**Hardened: match stats now always start at 0, even if MBedwars' own counter doesn't.**
- Matchbook prefers MBedwars' per-round ("game") stats over its own start/end diffs, and previously trusted that counter's value at the end of the match as already relative to 0 for that round. That assumption is exactly what the bug above exploited: a player sitting in an arena's next lobby still holds their previous round's final numbers in that same counter until their own next `RoundStart` actually fires.
- Fixed: Matchbook now captures its own baseline reading of a player's game stats the moment they become a real participant in a match (round-start roster, a late join, or a mid-match team assignment), and reports that match's stats as a diff against it — never the raw counter value. A player whose counter wasn't actually reset when they joined can no longer show up with stats they didn't earn in this match. Harmless when MBedwars' counter is already zero (the normal case): diffing against a 0 baseline reproduces the exact same numbers as before.

## [0.7.0] — 2026-07-19

**Deaths and kill attribution are now one event row.**
- Previously every attributed death produced two separate rows — a `PLAYER_DEATH` for the victim and a `PLAYER_KILL` for the credited killer — and connecting them (e.g. "was this void death actually credited to anyone?") meant eyeballing adjacent rows. A death row now carries everything about that death: victim, how they died (`cause`, e.g. `VOID`), the responsible player MBedwars attributed it to (`killer_name`/`killer_uuid`/`killer_team`, empty when nobody was credited), and a new `kill_cause` column for how the killer contributed (e.g. `ENTITY_ATTACK` when they punched the victim into the void, `ENTITY_EXPLOSION` for TNT knockback).
- A void/fall death with empty killer columns is now unambiguously an unattributed environmental death.
- The in-game event log GUI shows the same merged line ("X [RED] was eliminated by Y [BLUE]", with the kill cause in the lore); the death and kill events arrive from MBedwars separately and in no guaranteed order, so Matchbook pairs them internally (amend-in-place when the kill lands second, parked-and-consumed when it lands first, within a 10s window keyed by victim so an attribution can never bleed into the victim's next death).
- **Backward compatible with existing databases:** old match documents keep their separate `PLAYER_DEATH`/`PLAYER_KILL` rows and render/export exactly as before — nothing is migrated or rewritten. `PLAYER_KILL` also remains as a rare fallback in new recordings when a kill can't be matched to a death row (e.g. no victim handle), so no kill is ever dropped.
- Events CSV exports gain the `kill_cause` column (last column; empty for legacy rows). Stat counting is unchanged — this only restructures the event log.
- Verified by execution against the compiled release (standalone harness driving the real lifecycle code): kill-then-death merge, death-then-kill amend-in-place, clean void death staying unattributed, unmatched-kill flush to a legacy row, stat counters unaffected, and YAML round-trip of the merged row — plus the full 0.6.8–0.6.10 regression suite, 28/28 passed.

## [0.6.10] — 2026-07-19

**Exports now show which Matchbook version recorded the match.**
- The Matchbook version is captured when the match session is created and persisted in the match document (`match.matchbook_version`), so it reflects the build that *recorded* the data — never the build that happens to be running when someone exports it later.
- All four CSV export variants print it in the header: single stats and single events exports get `# matchbook_version: <v>`; combined exports get `# matchbook_versions:` with one shared value, or `code=version` pairs when the matches were recorded by different builds.
- Matches saved by older builds (which never stored the field) export as `unknown`.

**Investigated: report of 12 final kills in a match with only 11 possible (`z8xy-y8hg`).**
- The event log shows only 10 credited final kills (plus one uncredited void death), yet the stats CSV shows 12: one player's entire row (9 kills / 4 deaths / 2 final kills) is a verbatim carry-over of their totals from the immediately preceding round (`z4qf-8rpf`) on the same arena — they recorded zero kills in `z8xy-y8hg`'s own event log. This is the back-to-back-round session-reuse corruption already fixed in 0.6.9; the affected matches were confirmed recorded on 0.6.7. Environmental kills are not double-credited — no code change needed.
- Since the reviewed exports predate the fixes, the 0.6.8/0.6.9 fixes were re-verified by execution against this release's compiled classes with a standalone harness driving the real lifecycle code: ended-session eviction (no stat/participant/event carry-over between back-to-back rounds), duplicate `RoundEnd` no-op, mid-match team overflow floored at 2nd, and a replay of the `z4qf-8rpf` tie scenario (eliminated teams keep 4th/3rd; only the genuinely tied teams get `matchbook:ties`) — 20/20 checks passed.

## [0.6.9] — 2026-07-17

Stability release. A full code audit was run with a deterministic simulation harness that drives the real match-lifecycle code (virtual scheduler, simulated players/arenas, injectable stat-callback lag) through 18 hostile scenarios — ties, ragequits, rejoins, team switches, duplicate events, arena restarts, storage outages, and multithreaded stress. Four bugs were reproduced and fixed, plus three more found by inspection.

**Fixed: back-to-back rounds could corrupt both matches' records.**
- Root cause: a finished round's save runs on a delayed chain (end-snapshot delay + async stat callbacks — potentially several seconds under DB lag). If the arena started its next round inside that window, the new round *reused* the ended session still sitting in memory: it inherited the old match's code, its players/events leaked into the old match's saved document, and when the old round's save finally completed it deleted the session out from under the new round — silently dropping the rest of the new round's kills/deaths/events.
- Fixed: an ended session is never reused — round start (and early joins/placeholder lookups) evict it and create a fresh session, while the in-flight save keeps its own reference and completes normally. Save-time session removal is now scoped (`remove(key, session)`) so a finalize running on an async thread can never delete a newer round's session. A match document whose duration would come out negative (the signature of the old merge corruption) is now rejected outright.

**Fixed: a duplicate `RoundEndEvent` double-counted placements.**
- MBedwars can re-fire `RoundEndEvent` under forced-stop conditions. Each firing ran the entire finalize chain again: the match saved twice and every player's `matchbook:*_place` counter was stamped twice (a winner would export with `1st_place = 2`). In YAML mode the second save overwrote the file with the double-stamped version.
- Fixed: `RoundEnd` is ignored if the session already has an end time recorded.

**Fixed: a team formed mid-match could steal the winner's 1st place.**
- Root cause: the placement denominator is (correctly) frozen at round start, but if reassignment/auto-balance created an extra team mid-match, more teams could be eliminated than the frozen count — and the placement formula clamped the overflow to **1st place**. The last-eliminated team then "tied" with the real winner, and the winner's players were exported with `matchbook:ties` instead of `matchbook:1st_place` despite the match result correctly saying they won.
- Fixed: an eliminated team's placement is floored at 2nd — an eliminated team can never have won.

**Fixed: quitting and quickly rejoining could freeze a player's stats at quit-time values.**
- Root cause: when a player quits and MBedwars provides no `QuitPlayerMemory`, their stats are captured via an async callback. If the player rejoined before that callback landed, the rejoin's stat-invalidation ran first and the stale quit-time snapshot was written *after* it — masking everything the player did post-rejoin (backstopped keys like kills/deaths survived via event counters; everything else stayed frozen).
- Fixed: quit-time captures carry a per-player generation stamp; a rejoin bumps the generation and a stale capture discards itself instead of writing.

**Fixed: tab-completing `/mb view` / `/mb export` could freeze the server.**
- Completion ran on the main thread on every keystroke, and building the suggestion list hit storage directly — in YAML mode that meant loading *every match file on disk*; in MySQL mode, blocking queries. With a few thousand recorded matches this was a hard main-thread stall.
- Fixed: completions are served from a per-sender cache refreshed in the background (30s TTL). Storage is never touched on the main thread; the very first keystroke after a cold cache may show no suggestions, which then appear on the next one.

**Fixed: two matches ending at once could drop an entry from a shared player's history.**
- The per-player match index (`users/<uuid>.yml`) was updated from async save threads with no locking — two matches finishing near-simultaneously with a shared player did a read-modify-write race, and one match could vanish from that player's `/mb matches` list. All index reads/writes now synchronize on a per-player lock.
- The index's 500-entry cap also now prunes the trimmed matches' `paths.*` mappings, which previously grew forever.

**Fixed: match-file name collisions in YAML mode.**
- Filenames were `<startUnix>-<arenaHash>.yml`, so two rounds of the same arena starting within the same second silently overwrote each other. The match code is now part of the filename (`<startUnix>-<arenaHash>-<matchcode>.yml`), making collisions impossible. Nothing parses the filename (lookups read `match.match_id` from file contents), so existing files remain fully readable.

**Minor:** the placeholder match-code cache now purges expired entries instead of only evicting on read (slow unbounded growth on high-traffic servers).

**Verified unaffected by simulation:** clean wins, elimination placement order, tie-by-time-limit (including partial ties where a third team still records a loss), tie→win correction from recorded win stats, leave/rejoin stat integrity, live elimination on ragequit, spectator exclusion, team-switch ghost cleanup, stat-lag timeouts (event backstop), stuck-match watchdog, shutdown flush with storage down (recovery file), and a 30-round multithreaded stress test with exact stat counts.

## [0.6.8] — 2026-07-16

**Tie placement fix: eliminated teams could get their real placement thrown out.**
- Root cause: at round end, tie detection scans every participating team for "still alive" using `alivePlayersByTeam`, falling back to reflection on the live MBedwars `Team` object when a team has no tracked alive-entry. That fallback could misreport an already-eliminated team as still alive, and its real placement (e.g. 3rd/4th) would then get force-overwritten with a false tie-for-1st — so a match with a genuine 2-team tie could end up with *every* team's players marked `matchbook:ties`, wiping out the eliminated teams' actual placement columns entirely. Confirmed against a reported match (`z4qf-8rpf`) where RED and YELLOW were cleanly eliminated via `TeamEliminateEvent` but still ended up tied in the exported CSV.
- Fixed: a team that already has a recorded placement is never reconsidered by the tie-detection scan. Non-tied teams now always keep their real placement; only the teams that genuinely tied show up in `matchbook:ties`.
- The match-details GUI now shows **"Placement: 0"** for tied players instead of silently omitting the line.

**Spectator/team-assignment race right after round start.**
- Team and spectator classification was read from MBedwars synchronously the instant a player's join event fires. In the first couple seconds after a round starts, MBedwars can still be settling that state — a genuine spectator could transiently read back a stale/leftover team (getting miscounted as a participant), and a real player could transiently read back no team at all. The same race affected the round-start roster snapshot, which is frozen once (`totalTeams()`) and never re-evaluated, so an undercount there was permanent for the rest of the match.
- Both the per-join classification and the round-start roster snapshot now wait a configurable delay (new `match.join_classify_delay_ticks`, default 60 ticks / 3s) before reading team/spectator state, giving MBedwars time to settle first.

**Pre-match join/leave events no longer logged.**
- `PLAYER_JOIN` / `PLAYER_LEAVE` / `SPECTATOR_JOIN` / `SPECTATOR_LEAVE` events are now only recorded once the match has actually started — lobby-phase joins and leaves (players queueing, spectators bouncing in and out before the round begins) no longer clutter the event log.

**"All Matches" browse view no longer shows misleading personal stats.**
- The admin/browse-all matches list reused the same item-lore builder as the personal match history view, so every match item showed *your* Kills/Final Kills/Final Deaths/Beds — meaningless (usually all zero) for matches you never played in. The All Matches view no longer includes any per-viewer stat lines; your personal match history is unaffected.

**Investigated:** a report that final kills cause minor local lag spikes. Traced through the full kill/death/team-elimination handling path — everything Matchbook itself does there is cheap in-memory map/set bookkeeping with no I/O, reflection, or blocking calls (the reflection-heavy code only runs once at round end). No concrete cause was found in this plugin's code; the spikes are more likely from MBedwars' own final-kill effects (bed-destroy particles/fireworks, camera work) rather than Matchbook.

## [0.6.7] — 2026-07-16

**Critical build fix: 0.6.6 crashed on MySQL storage with `NoClassDefFoundError: com/zaxxer/hikari/HikariConfig`.**
- Root cause: `build.gradle`'s shadow-jar config had the shaded (fat) jar and the plain `jar` task writing to the same output filename, and `./gradlew build`/`assemble` only ran the plain `jar` task — which excludes `implementation` dependencies (HikariCP, the MySQL driver). Any jar built with the standard `build` command was missing both, so `MySqlMatchRepository.init()` failed immediately on `HikariConfig`, and Matchbook failed to enable entirely on any server using `storage.type: mysql`.
- Fixed by disabling the plain `jar` task and wiring `assemble`/`build` to always produce the shaded jar instead. `./gradlew build` (or `shadowJar`) now always produces a complete, correct jar — confirmed by inspecting the built artifact for `HikariConfig.class` and the MySQL driver.
- No plugin behavior changed in this release — same code as 0.6.6, just a build/packaging fix. If you're running 0.6.6 with MySQL storage, upgrade to 0.6.7.

## [0.6.6] — 2026-07-16

**Hot-reloadable storage — no more restarting to change the database.**
- `/mb reload` now detects changes anywhere under `storage:` (switching `yaml`/`mysql`, MySQL host/port/credentials, pool settings, table prefix, ...) and reconnects the storage backend in the background, without a server restart.
- The new backend is built and validated (connected + health-checked) *before* it replaces the active one — a bad config change (typo'd password, unreachable host) fails the reload with a clear log message and leaves the previously-working backend untouched, instead of breaking storage until someone notices and restarts.
- The old backend is only shut down after the swap completes, so in-flight reads/writes are never disrupted mid-request.
- (`mode.hub` still requires a restart — it changes which event listeners get registered, not the storage backend.)

**Safer match persistence — no more silently losing a match to a DB hiccup.**
- Every match save (normal finish, aborted/forced-end, and shutdown flush) now retries up to 3 times before giving up, refetching the current storage backend on each attempt — so a save started during a brief outage can succeed on retry, including right after an admin fixes the config with `/mb reload`.
- If every retry still fails, Matchbook writes a local recovery copy to `matches/failed/<matchcode>.yml` instead of just logging an error and losing the data. Nothing to do at the time; move/re-import that file once storage is healthy again.

**Other error-handling fixes:**
- Fixed a silent failure at startup: if both the configured storage backend *and* its YAML fallback failed to initialize, this was previously swallowed with zero indication in the log — Matchbook would just silently be unable to save or read anything. Now logged clearly.
- Fixed a HikariCP connection-pool leak in `MySqlMatchRepository`: calling `init()` a second time on the same instance used to leave the previous connection pool open instead of closing it first.

## [0.6.5] — 2026-07-16

**Fixed: duplicate matches with an absurd running time.**
- Root cause: a match that never received a proper round end (e.g. MBedwars left the arena stuck reporting `RUNNING` indefinitely) would sit in memory until the server restarted, at which point shutdown-time flushing saved it with its real start time but `endUnix` = the moment the server stopped — producing a bogus extra match record for that arena with an hours/days-long duration.
- The per-match watchdog now proactively discards (never saves) any in-progress match that exceeds `match.max_duration_minutes` (default 180), independent of what the arena's reported status says. A shutdown-time check backstops this in case a match somehow slips past the watchdog.

**Ties now always exported as a column.**
- `/mb export` and combined exports now always include a `matchbook:ties` (`ties`) column, even when none of the exported matches ended in a tie — it simply reads back as `0`. Previously the column only appeared when at least one exported match had a tie, which made combining CSVs from multiple exports in a spreadsheet misaligned.
- Confirmed existing behavior is correct and unchanged: when a match ties, `bedwars:wins` is forced to `0` for the tied teams so a tie is never also counted as a win.

**Update checks.**
- Matchbook now checks GitHub Releases ([`BeekrBonkr/Matchbook-Releases`](https://github.com/BeekrBonkr/Matchbook-Releases)) for a newer version on startup and periodically thereafter (`update_check.interval_hours`, default 12h), and alerts online OPs in chat (plus a console log line) when one is found. New operators logging in later are also caught up.
- New config: `update_check.enabled` (default `true`) turns the whole feature off.

**Export autocomplete.**
- `/mb export <id1>,<id2>,...` tab-completion now keeps suggesting match ids after the first one, whether typed as `id1,id2` (no space) or `id1, id2` (space after the comma) — previously it only completed the very first id.

**Placement denominator fix.**
- `MatchSession.totalTeams()` could inflate above the round-start team count if a team picked up a player *after* the round started (e.g. an admin reassignment/auto-balance), which would silently shift the placement of every team eliminated after that point. Placement is now always computed against the number of teams that were actually participating at round start, frozen for the rest of the match — never the arena's full configured team roster, and never inflated by later changes.

**Hastebin export upload disabled (for now).**
- Removed from the command surface (`/mb test` no longer has an `--upload` flag; `/mb export` no longer auto-uploads) and from the README. The underlying `HasteUploader` and the command's private upload helpers are untouched so this can be re-enabled later.

**Config comments now survive upgrades.**
- Every setting in `config.yml` (including the ones added above) now has an explanatory comment, down to individual MySQL connection-pool keys.
- `ConfigUpdater` previously only merged in missing keys when `config-version` changed, and even then it rebuilt the file from raw values — silently dropping every comment (including the top-of-file banner) because Bukkit's config merge doesn't carry comments across a plain `.set()`. Comments were effectively gone forever after the first version-triggered merge.
- Fixed: the config is now synced against the packaged template on *every* startup (and on `/mb reload`), not just on a version bump. User-set values always win; comments (block, inline, and the top-of-file header) always come from the template, so wording fixes reach existing installs automatically and any config that already lost its comments gets them back. The file is only rewritten (and backed up) when something actually needed fixing.

**Merge fixup with hub/lobby mode (0.6.3, below):** the update-check join notification was moved out of `MatchbookListener` (which only registers when hub mode is off) into an always-registered listener in `MatchbookPlugin`, so operators on a hub/lobby server still get caught up on an available update.

## [0.6.3] — 2026-07-14

**Hub/lobby mode:**
- New `mode.hub` config option. When enabled, Matchbook registers no match-tracking listeners at all — it never creates a match session or writes a match to storage on that server. `/mb matches`, `/mb all`, `/mb view`, and `/mb export` keep working against the configured storage backend, so a hub server can browse/export a shared MySQL match history without ever attempting to record matches itself.
- Takes effect on server start/restart, same as `storage.type`; not hot-reloadable via `/mb reload`.
- Config version bumped to 0.0.10.

**Docs:** README updated with a "Hub / Lobby Mode" section and cross-referenced from "Multi-Server / Proxy Networks".

## [0.6.2] — 2026-07-14

**Placement accuracy fixes:**
- Team count used for placement math is now frozen right after round start instead of read live, so a very early elimination can no longer compute its rank against an undercounted, still-growing team set.
- A player quitting while their bed is already broken now triggers a live elimination check immediately, instead of only being caught by the round-end sweep (which appended missed eliminations in arbitrary, non-chronological order).
- Multiple teams landing in the round-end "ambiguous/partial info" bucket no longer collide on the same placement number (previously two different teams could both be stamped e.g. "4th place").
- `TeamEliminateEvent` is now rescheduled onto the main thread when fired asynchronously, matching the other elimination-related handlers (bed break, kill, death).

**Tracking correctness ("ghost alive" bugs):**
- A player who reconnects as a spectator after already being fatally eliminated no longer resurrects their team's alive status.
- Switching teams mid-match (auto-balance, admin reassignment) no longer leaves a stale "alive" entry behind on the old team, which could otherwise permanently block that team from being detected as eliminated.

**Event log:**
- Death and kill events now record a cause (Fall, Void, Entity Attack, Projectile, etc.) when MBedwars/Bukkit exposes one.
- Player-left events now distinguish a player who was already eliminated and spectating from one who quit while still active, in both the GUI and CSV export.

**Multi-server / proxy safety:**
- Matchbook now verifies an arena actually has a game world loaded on the local server before creating a match session. Fixes a bug where a hub server with no arenas of its own could create bogus match records for arenas it only knew about via MBedwars' network-wide remote-arena awareness — those matches never received a real end and stayed stuck in a "PLAYED" limbo state with zero stats until the server restarted.

**Docs:** README updated for the new event fields and a new "Multi-Server / Proxy Networks" section.

## [0.6.1] — 2026-07-14 (`8f8765b`)

- Ties are now scoped to the teams that actually tied for 1st — in a 3+ team match, a team eliminated earlier no longer gets `matchbook:ties` or a neutral win/loss just because two *other* teams ended up tied.
- `matchbook:ties` is now written from the same finalized-placement pass as `*_place` stats, computed after win inference so it can't disagree with the final result.
- `flushAll` gained a synchronous mode, used from `onDisable()` — previously, saves scheduled via the async Bukkit scheduler during shutdown were silently dropped because Bukkit disables the plugin before `onDisable()` runs, making `runTaskAsynchronously` throw immediately.
- YAML→MySQL and MySQL→YAML migration no longer abort the whole batch on one malformed/oversized record; failures are now counted and logged per-record instead.
- Migration dry-runs no longer run MySQL schema DDL, so previewing a migration can't mutate the database.
- Match History GUI now shows the actual result (winning team's color/name, or every tied team's color) instead of a generic green/red/yellow outcome color.
- Match Details GUI now groups players by team (fixed color order) sorted by final kills → kills, and colors each player by their team's real configured bed color instead of the enum default.
- Added a note that MySQL/MariaDB connections verify the server certificate by default, with the config key to disable it for self-signed certs.
- Fixed the documented install path (`plugins/MBedwars/add-ons/`, not `plugins/`).

## [0.5.1] — 2026-06-30 (`d4aee1a`)

- Replaced Pastebin upload with Hastebin (`POST /documents`, no auth required); removed `PastebinUploader`.
- `export_upload` config simplified to just `enabled` + `server` URL.
- `/mb export` now always saves locally regardless of upload settings; upload is purely additive.
- `/mb test --upload` replaces `/mb test --pastebin`.
- Config version bumped to 0.0.9.

## [0.5.0] — 2026-06-30 (`6b5d4e2`)

- **Event log added**: every join, leave, death, kill, bed break, team elimination, and spectator join/leave is now recorded with timestamps, stored in the match YAML, and exportable as a separate `_events.csv`.
- New `EventLogGui`: paginated 6-row inventory timeline with per-event-type icons and `+M:SS` time offsets, opened from a button in Match Details.
- `MatchesGui` and `MatchesDetailsGui` rewritten: outcome-colored items, new navigation icons, event-log entry point.
- Added `ConfigUpdater` for automatic, versioned config migration with backup on upgrade; `config.yml` rewritten with full section documentation (config version 0.0.8).
- Removed `PartyFollowService` and all party-follow-to-arena logic (reverted from the previous release).
- Gradle wrapper bumped 8.7 → 8.10.
- Added the project README (features, commands, permissions, configuration).

## [0.4.6] — 2026-06-29 (`bfdab86`, `be9c55d`)

- Version bump to 0.4.6.

## [0.4.x] — 2026-06-29 (`b5fa9cb`)

- Added `PartyFollowService` to auto-pull party members into an arena when their leader joins a lobby, with configurable `follow_leader_to_arena` / `follow_delay_ticks`.
- Ties tracked as a first-class stat (`matchbook:ties`); prevented MBedwars' own win/loss values from leaking through on tied matches.
- Wired `TeamEliminateEvent`, `SpectatorJoinArenaEvent`, and `PlayerTeamChangeEvent` for more reliable participant/elimination classification, replacing weaker reflection-based inference where MBedwars 5.x exposes direct API calls.
- Config version bumped to 0.0.7.

---

*Generated from `git log` and diff review.*
