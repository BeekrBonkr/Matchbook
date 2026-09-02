# Matchbook

<div align="center">

<a href="https://ko-fi.com/bkrbnkr"><img alt="Support me on Ko-fi" src="https://img.shields.io/badge/Ko--fi-buy_me_a_coffee-FF5E5B?style=for-the-badge&logo=kofi&logoColor=white"></a>

</div>

**Persistent match history for [MBedwars](https://www.spigotmc.org/resources/mbedwars.82729/).** Every BedWars round your server plays is recorded and kept: who played, what they did, and exactly what happened, minute by minute. Browse it in-game, export it to CSV, or put the live match code on your scoreboard.

[![Paper 1.21+](https://img.shields.io/badge/Paper-1.21%2B-blue)](https://papermc.io/)
[![MBedwars 5.x](https://img.shields.io/badge/MBedwars-5.x-orange)](https://www.spigotmc.org/resources/mbedwars.82729/)
[![Java 21](https://img.shields.io/badge/Java-21-red)](https://adoptium.net/)
[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-green)](LICENSE)
[![Releases](https://img.shields.io/badge/Download-Releases-brightgreen)](https://github.com/BeekrBonkr/Matchbook-Releases/releases)

<!-- Screenshots of the match list / details / event log GUIs go here -->

## Quick start

1. **Download** the latest `Matchbook-<version>.jar` from [Releases](https://github.com/BeekrBonkr/Matchbook-Releases/releases).
2. **Drop it in `plugins/MBedwars/add-ons/`**, not `plugins/`. Matchbook is an MBedwars add-on and lives in MBedwars' add-on folder.
3. **Restart the server.** That's it. Matches start recording immediately, and players can run `/mb matches` to see their history.

Nothing needs configuring to get going: matches are saved to flat YAML files out of the box. When you're ready for more, see [Configuration](#configuration), or [MySQL Setup](#mysql-setup) if you want one shared history across a whole network.

## Contents

**Getting started:** [Requirements](#requirements) · [Installation](#installation) · [Commands](#commands) · [Permissions](#permissions)

**Using it:** [In-Game GUI](#in-game-gui) · [CSV Export](#csv-export) · [PlaceholderAPI](#placeholderapi)

**Setting it up:** [Configuration](#configuration) · [MySQL Setup](#mysql-setup) · [Multi-Server / Proxy Networks](#multi-server--proxy-networks) · [Storage Layout](#storage-layout-yaml-mode)

**Help & reference:** [Troubleshooting](#troubleshooting) · [Known Limitations](#known-limitations) · [Building from Source](#building-from-source) · [Architecture & Internals](docs/ARCHITECTURE.md) · [Changelog](CHANGELOG.md) · [Contributing](#contributing) · [License](#license) · [Support](#support)

---

## Features

**Match history.** Every completed match is saved with per-player stats (kills, final kills, deaths, final deaths, beds destroyed, beds lost, wins and losses), browsable in-game, newest first.

**A full event log.** Every join, leave, death, kill, bed break and team elimination, timestamped, with the cause (fall, void, entity attack…). Viewable as a timeline in-game and exportable to CSV.

**Stats you can trust.** Exported stats are computed from the match's own event log, not from MBedwars' running counters (those are kept only as a cross-check). The stats CSV and the events CSV can never disagree, because one is derived from the other.

**Placements and ties.** 1st, 2nd, 3rd tracked per team. Standings are always a contiguous 1..N ranking, so a match can never record a 1st, 3rd and 4th with no 2nd. Matches ending without a winner are flagged as ties, with the tied teams tracked and shown by color.

**CSV export.** Player stats and event log as separate files, one match at a time or several combined into one report.

**Your storage, your choice.** Flat YAML files by default, or MySQL/MariaDB for a shared network-wide history. One command migrates between them, and you can switch backends with `/mb reload` (no restart).

**Built to survive a bad day.** Saves retry, then fall back to a local recovery file if storage is down. Duplicate round-end events, back-to-back arena restarts, quick leave/rejoins and mid-match team changes are all handled without corrupting records. A match's roster comes from MBedwars' own round-end lists and is checked against who was actually seen playing, so a player already queued for the next round can never leak into a match with stats they didn't earn.

**Network-friendly.** A hub or lobby server won't record matches for arenas that are really running elsewhere on the network. Set `mode.hub: true` and it becomes a read-only window onto the shared history.

**Quality-of-life.** Per-player display timezones (`/mb timezone`), `%matchbook_matchcode%` for scoreboards and holograms, automatic config upgrades that keep your settings and comments, and update alerts when a new build ships.

---

## Requirements

| Dependency | Version | Required |
|---|---|---|
| [Paper](https://papermc.io/) | 1.21+ | ✅ |
| [MBedwars](https://www.spigotmc.org/resources/mbedwars.82729/) | 5.x (built against 5.5.6) | ✅ |
| [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) | 2.11+ | Optional |
| MySQL / MariaDB | any recent | Optional, only for `storage.type: mysql` |

Java 21 is required (Paper 1.21 already needs it). The MySQL driver and connection pool are bundled inside the jar, nothing extra to install.

---

## Installation

1. Drop `Matchbook-<version>.jar` into `plugins/MBedwars/add-ons/`. **Not** `plugins/`. Matchbook is an MBedwars add-on and lives in MBedwars' add-on folder.
2. Restart the server. Matchbook creates its own folder and config at `plugins/MBedwars/add-ons/Matchbook/config.yml`.
3. That's the whole install. Matches record from the next round onward.

To change anything afterwards, edit `config.yml` (see [Configuration](#configuration)) and run `/mb reload`. Everything takes effect on reload (including switching storage backends) except `mode.hub`, which needs a restart.

**Upgrading:** replace the jar and restart. Your `config.yml` is merged with the new version's template automatically, so your settings and comments are kept and any new keys are filled in with defaults.

---

## Commands

All commands use `/matchbook` or the alias `/mb`. `matches`, `all`, `view`, `timezone` and `statskeys` open a GUI or read the calling player's data, so they only work in-game; the rest work from console too.

| Command | Permission | Description |
|---|---|---|
| `/mb help` | `mb.command.help` | Show the commands you have permission for. |
| `/mb matches` | `mb.command.matches` | Open your personal match history GUI. |
| `/mb all` | `mb.command.all` | Open the global match list (all matches, newest first). |
| `/mb view <matchcode>` | `mb.command.view` | Open the details GUI for a specific match. |
| `/mb timezone [zone\|reset]` | `mb.command.timezone` | Show or set the timezone match times are displayed in for you (alias `/mb tz`). Accepts `eastern`, `central`, `mountain`, `pacific`, `alaska`, `hawaii`, `arizona`, abbreviations like `est`/`cdt`, or any IANA id such as `America/New_York`. `reset` returns to the server default. |
| `/mb export <code>[,code...]` | `mb.command.export` | Export one or more matches to CSV files. |
| `/mb migrate yaml2mysql` | `mb.command.migrate` | Migrate all YAML match files into MySQL. |
| `/mb migrate mysql2yaml` | `mb.command.migrate` | Migrate all MySQL records to YAML files. |
| `/mb migrate ... --dry-run` | `mb.command.migrate` | Preview a migration without writing anything. |
| `/mb reload` | `mb.command.reload` | Reload `config.yml` without restarting, hot-swaps the storage backend too if anything under `storage:` changed. |
| `/mb statskeys [player]` | `mb.command.statskeys` | List all stat keys MBedwars exposes for a player (for `match.tracked_keys`). |
| `/mb test` | `mb.command.test` | Run a storage health check. |

Match codes tab-complete for `/mb view` and `/mb export` (including after a comma in a multi-match export), and `/mb timezone` tab-completes the friendly zone names.

---

## Permissions

### Permission Groups

| Node | Default | Grants |
|---|---|---|
| `mb.command.use` | Everyone | Required to use any `/mb` command at all. |
| `mb.command.default` | Everyone | `matches`, `all`, `view`, `timezone`, `export`, `help` |
| `mb.command.admin` | OP | `migrate`, `reload`, `statskeys`, `test` |

### Individual Nodes

| Node | Default |
|---|---|
| `mb.command.matches` | false |
| `mb.command.all` | false |
| `mb.command.view` | false |
| `mb.command.timezone` | false |
| `mb.command.export` | false |
| `mb.command.help` | false |
| `mb.command.migrate` | false |
| `mb.command.reload` | false |
| `mb.command.statskeys` | false |
| `mb.command.test` | false |

> **Tip:** Grant `mb.command.default` to your player rank and `mb.command.admin` to staff. The `mb.command.use` node is true by default so everyone can run `/mb` without a permission error; a subcommand still needs its own node (or its group) on top of that.

### Legacy nodes

These are kept for backwards compatibility with older permission setups:

- `matchbook.admin`: equivalent to `mb.command.admin`
- `matchbook.matches`: equivalent to `mb.command.matches`
- `matchbook.migrate`: equivalent to `mb.command.migrate`

---

## In-Game GUI

### Match History (`/mb matches`)

- Shows all of your past matches, most recent first.
- Each item shows the actual result: the winning team's color and name (e.g. **RED WIN**), or every tied team's color for a tie (e.g. **RED, BLUE TIE**). Aborted or unknown-result matches show as paper marked **PLAYED**. Your own team for that match is shown in the item tooltip.
- Displays your stats for that match in the item tooltip (the **All Matches** view from `/mb all` deliberately doesn't, those lines would be meaningless for matches you never played in).
- Match dates and times are shown in your display timezone, the server default from `display.timezone`, or your own choice via `/mb timezone`.
- Click any match to open Match Details.

### Match Details

- Players are grouped by team (fixed color order), then sorted by final kills → kills within each team.
- Each player is shown as a wool block colored by their team's actual bed color.
- Row 1 holds the match summary; the player list starts on row 2.
- **Spectators** are listed in the spyglass item (slot 47 in the nav bar).
- **Event Log button** (slot 51), click to open the full event timeline.
- Prev/Next navigate between pages when there are many players.
- **Back** returns you to the list you came from, your history or `/mb all`, on the same page. Opened directly with `/mb view`, it falls back to your own history.

### Event Log

- Paginated timeline of every event that occurred during the match.
- Each event is its own inventory item with an icon, description, and time offset.
- Time is shown as `+M:SS` from match start. Events before the round began show as `lobby`.
- Navigate with Prev/Next; click Back to return to Match Details (same page you left it from).

**Event types displayed:**

| Icon | Event |
|---|---|
| Lime glass pane | Match started (shows real wall-clock time) |
| Red glass pane | Match ended (shows duration) |
| Lime dye | Player joined |
| Red dye | Player left (still an active player) |
| Gray dye | Player left while spectating (already eliminated) |
| Skeleton skull | Regular death (shows cause when available, e.g. Fall, Void, Entity Attack) |
| Wither skeleton skull | Final death / eliminated (shows cause when available) |
| Iron sword | Regular kill (shows cause when available) |
| Golden sword | Final kill (shows cause when available) |
| TNT | Bed destroyed |
| Barrier | Team eliminated |
| Spyglass | Spectator joined |
| Ender eye | Spectator left |

---

## CSV Export

Running `/mb export <matchcode>` creates two files in `plugins/MBedwars/add-ons/Matchbook/exports/`:

### `<matchcode>.csv`: Player Stats

One row per participant with their stats for that match.

```csv
# match_codes: 8F3KQ-2JDXW
# matchbook_version: 0.7.5
uuid,username,team,kills,final_kills,deaths,final_deaths,beds_destroyed,wins,loses,beds_lost,1st_place,2nd_place,ties
...
```

The column set is `export.columns` from `config.yml`, or, when that list is empty, `uuid`, `username`, `team` plus every key in `match.tracked_keys`. `matchbook:*_place` columns are added automatically for whatever placements the exported matches contain, and `matchbook:ties` is always present so files from different exports line up in a spreadsheet.

### `<matchcode>_events.csv`: Event Log

One row per event in chronological order.

```csv
# match: 8F3KQ-2JDXW
# matchbook_version: 0.7.5
offset_seconds,wall_clock_unix,type,player_name,player_uuid,player_team,killer_name,killer_uuid,killer_team,bed_team,final,cause,was_spectating,kill_cause,stats_uncounted
0,1751234567,MATCH_START,,,,,,,,false,,false,,false
13,1751234580,PLAYER_JOIN,Steve,<uuid>,RED,,,,,false,,false,,false
58,1751234625,PLAYER_DEATH,Alex,<uuid>,BLUE,Steve,<uuid>,RED,,true,VOID,false,ENTITY_ATTACK,false
...
```

`matchbook_version` is the Matchbook version that **recorded** the match (captured when the match session was created and stored with the match), not the version doing the export. Matches recorded before 0.6.10 show `unknown`. Multi-match exports print `# matchbook_versions:` instead, a single value when all matches were recorded by the same build, otherwise `code=version` pairs.

A `PLAYER_DEATH` row is the complete record of one death: the victim (`player_*`), how they died (`cause`, the Bukkit damage cause, e.g. `FALL`, `VOID`, `ENTITY_ATTACK`, `PROJECTILE`), and the responsible player MBedwars credited (`killer_*`). `kill_cause` shows how that player contributed, the example row above reads "Alex fell into the void after being hit by Steve, and it was a final kill". Empty killer columns on a void/fall death mean nobody was credited: a genuine environmental death. `was_spectating` is `true` on a `PLAYER_LEAVE` row when the player had already been eliminated and was spectating at the moment they left. `stats_uncounted` (0.7.5+) is `true` on a `PLAYER_DEATH` row MBedwars flagged as not counting toward the victim's death stats, the row stays in the log, and the stats CSV skips it too, so the two files always reconcile.

Matches recorded before 0.7.0 log attribution as a separate `PLAYER_KILL` row (killer in `killer_*`, victim name in `player_name`, kill cause in `cause`) near the victim's `PLAYER_DEATH` row. Those matches export and display exactly as before, and `PLAYER_KILL` can still appear (rarely) in new recordings when a kill couldn't be matched to its death row.

For multi-match exports (`/mb export CODE1,CODE2`), the two files are named after the codes joined with `_` (`CODE1_CODE2.csv` and `CODE1_CODE2_events.csv`); the stats CSV aggregates by player (stats summed; `team` becomes `MIXED` if it varied), and the events CSV includes an extra `match` column at the start and is sorted chronologically across all matches. Cells are escaped and guarded against spreadsheet formula injection (a leading `=`, `+`, `-` or `@` gets a quote prefix).

---

## Configuration

Config lives at `plugins/MBedwars/add-ons/Matchbook/config.yml`. Every key is commented in the file itself; this section covers what's worth knowing before opening it.

On every load (startup and `/mb reload`), Matchbook syncs your config against the packaged template: missing keys are filled in with defaults, comments are restored, and your own values are kept. The file is only rewritten, with a backup at `config.yml.bak-<old-version>-<date>`, when something actually changed. You never need to re-configure from scratch after an update.

### Hub / Lobby Mode

```yaml
mode:
  hub: false   # set true on a server that should never record matches
```

When `mode.hub` is `true`, Matchbook registers no match-tracking listeners at all, it never creates a match session or writes a match to storage on that server. It still connects to the configured storage backend, so `/mb matches`, `/mb all`, `/mb view`, and `/mb export` keep working for browsing/exporting matches recorded elsewhere. This is meant for a hub/lobby server pointed at the same shared MySQL database as your backend arena servers (`storage.type: mysql`). Takes effect on server start/restart, not `/mb reload`.

### Storage

```yaml
storage:
  type: yaml          # yaml or mysql
  mysql:
    host: "127.0.0.1"
    port: 3306
    database: "matchbook"
    username: "matchbook"
    password: "change_me"
    params: "useUnicode=true&characterEncoding=utf8&useSSL=false"   # extra JDBC URL parameters
    table_prefix: "matchbook_"
    pool:                        # HikariCP: the defaults are fine for most servers
      maximum_pool_size: 10
      minimum_idle: 2
      connection_timeout_ms: 10000
      idle_timeout_ms: 300000
      max_lifetime_ms: 1800000
```

Changing anything under `storage:` (including switching `type` between `yaml` and `mysql`) takes effect on `/mb reload`, no server restart needed. Matchbook builds and validates the new backend (connects, runs a health check) *before* switching over, so a typo'd password or unreachable host just fails the reload and logs why, leaving the previous backend running untouched. (`mode.hub` is the one exception, it still needs a restart, see [Hub / Lobby Mode](#hub--lobby-mode).)

If a match ever can't be saved after a few retries (e.g. the database is briefly unreachable), Matchbook writes a local recovery copy to `matches/failed/<matchcode>.yml` instead of losing it, nothing to do at the time, just move/re-import that file once storage is healthy again.

`matches/failed/` is quarantine, not storage: recovery copies are deliberately **not** listed by `/mb all` or openable with `/mb view`, so a record the backend rejected can never be mistaken for one that saved cleanly. To bring one back, move it into a day folder under `matches/` (YAML mode) or import it (MySQL mode).

### Match Recording

```yaml
match:
  enforce_win_loss_from_result: true
  multiple_survivors_are_a_tie: true
  max_duration_minutes: 180
  tracked_keys:
    - "bedwars:kills"
    - "bedwars:final_kills"
    # ... add more using keys from /mb statskeys
```

- `enforce_win_loss_from_result`: write `bedwars:wins`/`bedwars:loses` from the match result Matchbook determined, rather than trusting when MBedwars happened to increment its own counters. Keep this on.
- `multiple_survivors_are_a_tie`: a match that ends with more than one team still standing (time limit, force-end) is recorded as a **tie for 1st** between the survivors, even if MBedwars' own tiebreak announced a winner. Set to `false` to record MBedwars' winner as 1st and the other survivors as runners-up. Teams eliminated earlier keep their earned placement either way, and a winner MBedwars names for a team Matchbook doesn't have alive is always trusted.
- `max_duration_minutes`: a match running longer than this without a proper round end is discarded, never saved. This is what protects against the "duplicate match with an absurd running time" bug (a stuck arena lingering until a restart). `0` disables the safeguard, not recommended.
- `tracked_keys`: the MBedwars stat keys captured per match. Kills, final kills, deaths, final deaths, beds destroyed and beds lost are computed from the event log regardless; any extra key you add here (from `/mb statskeys`) is read from MBedwars' per-round counters.

The remaining `match.*` keys are timing knobs in ticks (20 = 1 second) and rarely need touching: `start_snapshot_delay_ticks` and `end_snapshot_delay_ticks` (how long to let MBedwars settle before reading stats at round start/end), `snapshot_timeout_ticks` (how long to wait for a player's stat callback before saving without it), and `join_classify_delay_ticks` (how long after a player joins an arena before deciding whether they're a participant or a spectator, MBedwars can report stale team state in the first seconds of a round).

### Export Columns

```yaml
export:
  columns: []          # empty = uuid, username, team + every tracked key
  # columns:
  #   - uuid
  #   - username
  #   - team
  #   - bedwars:kills
  #   - bedwars:final_kills
```

### Display Timezone

```yaml
display:
  timezone: "server"   # or an IANA id, e.g. America/New_York
```

The server-wide default for match dates/times in the GUIs. `server` means the machine's own zone (typically UTC on a host). Display only, matches are always stored as absolute unix timestamps, so changing this never touches saved data. Players override it for themselves with `/mb timezone <zone>`; the choice is remembered per player.

### Placeholder Grace Period

```yaml
placeholder:
  grace_seconds: 60    # minimum 5
```

How long `%matchbook_matchcode%` keeps returning a player's last match code after the match ends, so scoreboards that keep rendering through the end screen don't go blank.

### Update Checks

```yaml
update_check:
  enabled: true
  interval_hours: 12
```

Matchbook polls [GitHub Releases](https://github.com/BeekrBonkr/Matchbook-Releases/releases) shortly after startup and then every `interval_hours`, and tells online operators (plus the console) once per newer version it finds. Operators who log in later are caught up on join. This works in hub mode too.

### Rejoin Memory

```yaml
rejoin:
  disable_on_leave: true
  disable_on_teleport: true
  disable_on_switch_arena: true
```

MBedwars remembers a player's arena after a disconnect and can block them from joining a different one until they rejoin. When a player leaves a running match by `/leave`, by teleport, or by switching arenas, Matchbook clears that memory so they aren't stuck, each transition is individually switchable.

### Export Upload

`export_upload.enabled` / `export_upload.server` configure an automatic Hastebin upload of exported CSVs. The upload code is currently **switched off at its call sites** (exports are always written locally; the keys are inert), they're kept in the config for when it returns.

---

## MySQL Setup

1. Create a database and user:
   ```sql
   CREATE DATABASE matchbook CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   CREATE USER 'matchbook'@'localhost' IDENTIFIED BY 'your_password';
   GRANT ALL PRIVILEGES ON matchbook.* TO 'matchbook'@'localhost';
   ```
2. Set `storage.type: mysql` in `config.yml` with your credentials.
3. Run `/mb reload` (or restart), the two tables (`<prefix>matches`, `<prefix>player_matches`) are created automatically on first connect. Confirm with `/mb test`.

> **TLS:** the shipped `storage.mysql.params` sets `useSSL=false`. Change it to suit your server; if you delete the `params` key entirely, Matchbook's built-in default turns TLS on **with certificate verification**, so a self-signed certificate then needs `verifyServerCertificate=false` in `params`.

### Migration

Migrate existing YAML data to MySQL (or back) without losing anything:

```
# Preview what would be migrated
/mb migrate yaml2mysql --dry-run

# Perform the migration
/mb migrate yaml2mysql

# Reverse: move MySQL data back to YAML
/mb migrate mysql2yaml
```

Migration runs in the background and only one can run at a time. A record that fails to import is logged and skipped rather than aborting the whole batch, and a dry run never touches the database schema.

---

## PlaceholderAPI

If PlaceholderAPI is installed, Matchbook registers the `%matchbook_matchcode%` placeholder. It returns the current active match code for the player's arena (or their last match code for a short grace period after the match ends, `placeholder.grace_seconds`, so scoreboards don't go blank during transitions). The older spellings `%matchbook_match_code%`, `%matchbook_match_id%` and `%matchbook_matchid%` still work. On a hub-mode server the placeholder returns an empty string, since nothing is being recorded there.

---

## Multi-Server / Proxy Networks

MBedwars has its own network-wide arena awareness (so hub servers behind a proxy can show live info for arenas hosted on other backend servers). Matchbook only ever creates a match record for an arena that has an actual game world loaded on that specific server, so installing Matchbook on a hub server with no arenas of its own is safe: it will never start tracking (and getting stuck on) matches that are really being played elsewhere on the network. If it ever rejects an arena for this reason, it logs one warning per arena name so you can confirm what happened.

For a hub server, prefer setting `mode.hub: true` explicitly (see [Hub / Lobby Mode](#hub--lobby-mode)) rather than relying solely on the automatic arena-locality check above, it skips match tracking entirely instead of rejecting arenas one at a time, and makes the server's role unambiguous.

If you run YAML storage per-server, each server's match history stays local to it. Point every server at the same MySQL database (`storage.type: mysql`) if you want one shared, network-wide match history instead, this is required for a hub server in `mode.hub: true` to have anything to read/export. Give each server its own `table_prefix` only if you *want* them kept apart.

---

## Storage Layout (YAML mode)

```
plugins/MBedwars/add-ons/Matchbook/
├── config.yml
├── matches/
│   ├── 06-30-2026/                      ← one folder per day (MM-dd-yyyy)
│   │   ├── 1751297443-a1b2c3…-8F3KQ-2JDXW.yml  ← <startUnix>-<arenaHash>-<matchcode>.yml
│   │   └── 1751299018-a1b2c3…-M7G9V-QDQ9T.yml
│   ├── failed/                          ← quarantined recovery copies (not listed by /mb)
│   └── ...
├── users/
│   └── <player-uuid>.yml    ← per-player match index + display-timezone override
└── exports/
    ├── 8F3KQ-2JDXW.csv
    └── 8F3KQ-2JDXW_events.csv
```

Each match file is a self-contained YAML document with the match summary, per-player stats, and the full event log. In MySQL mode the exact same document is stored as text in the `<prefix>matches` table, which is what makes migration in either direction a straight copy. The `users/` folder is used in both modes, it's where `/mb timezone` choices live.

---

## Building from Source

```bash
./gradlew shadowJar      # or ./gradlew build (both produce the shaded jar)
```

Output: `build/libs/Matchbook-<version>.jar`

Requires Java 21+. The jar bundles HikariCP and the MySQL driver; the plain `jar` task is disabled on purpose (see [How it's built](docs/ARCHITECTURE.md#1-how-its-built)).

---

## Known Limitations

- **`mode.hub` needs a restart.** It decides which event listeners get registered at startup, so `/mb reload` can't change it, everything else in `config.yml` reloads live.
- **Recovery copies are recovered by hand.** A match in `matches/failed/` is never picked up automatically; move it into a day folder (YAML) or import it (MySQL) once storage is healthy.
- **Fixes are not applied retroactively.** A match recorded by an older build keeps whatever that build wrote; later corrections to placement or roster logic only affect new recordings.
- **`/mb view` scans in YAML mode.** Match files are located by reading `match.match_id` from each file, so lookups scale with the number of matches. They run off the main thread, so the server doesn't stall, but a very large YAML archive is a good reason to move to MySQL.
- **Tab completion can lag one keystroke behind.** Match-code suggestions come from a cache refreshed in the background (30s), so the first keystroke after a long idle may show nothing; the next one will.
- **Hastebin upload is disabled.** The `export_upload.*` keys exist but do nothing right now.
- **`%matchbook_matchcode%` is per-server.** On a hub-mode server it's always empty.

---

## Troubleshooting

**Matches aren't being recorded.** Check that the jar is in `plugins/MBedwars/add-ons/` and not `plugins/`, and that `mode.hub` is `false` in `config.yml`. If the server is a hub behind a proxy, Matchbook deliberately ignores arenas hosted on other servers and logs one warning per arena name saying so.

**A match is missing after a database outage.** Look in `plugins/MBedwars/add-ons/Matchbook/matches/failed/`. Saves that failed after retries are quarantined there rather than lost. Move the file into a day folder under `matches/` (YAML mode) or import it (MySQL mode) once storage is healthy.

**`/mb reload` fails after a storage change.** That's by design: the new backend is built and health-checked before the switch, so a bad password or unreachable host leaves the old backend running untouched. The console log says exactly what failed. Run `/mb test` to check storage health at any time.

**A player is stuck and can't join a different arena.** MBedwars remembers their last arena after a disconnect. Matchbook clears that memory on leave, teleport and arena switch. See [Rejoin Memory](#rejoin-memory) if you've turned any of those off.

**Stats look wrong.** Run `/mb export <matchcode>` and compare the two CSVs. They're derived from the same event log, so the events file shows exactly what Matchbook saw happen. Note that fixes ship forward only: a match recorded by an older build keeps whatever that build wrote.

Still stuck? Open an issue and include your Paper version, MBedwars version, Matchbook version and the relevant console output.

---

## Contributing

Issues and pull requests are welcome. A few things that help:

- **Bug reports:** include Paper, MBedwars and Matchbook versions, your `config.yml` (minus credentials), and console output. A match code or an exported CSV is gold for anything stats-related.
- **Pull requests:** keep them focused, match the surrounding code style, and note in the description what you tested against. See [Architecture & Internals](docs/ARCHITECTURE.md) for how the pieces fit together before changing anything in the match lifecycle (that's the delicate part).
- **Building:** `./gradlew shadowJar`, Java 21. See [Building from Source](#building-from-source).

---

## License

Matchbook is licensed under the [GNU General Public License v3.0](LICENSE). You're free to use, modify and redistribute it, including on commercial servers; if you distribute a modified version, it has to stay open source under the same license.

---

## Support

This plugin is free and open source, built in my spare time. If it saved you some time or you'd like to see it
keep getting updates, you can buy me a coffee:

- [Ko-fi](https://ko-fi.com/bkrbnkr)
<!-- more ways to support go here -->
<!-- - [PayPal](...) -->
<!-- - [GitHub Sponsors](...) -->
