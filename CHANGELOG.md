# Changelog

All notable changes to Matchbook over the past month, newest first.

## [0.6.4] — 2026-07-16

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
