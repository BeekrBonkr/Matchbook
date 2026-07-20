# Matchbook

A Paper plugin that records persistent match history for [MBedwars](https://www.spigotmc.org/resources/mbedwars.82729/). Browse past matches in-game, export stats to CSV, and view a full event-by-event match timeline.

---

## Features

- **Match history** — every completed BedWars match is saved with per-player stats (kills, deaths, final kills, beds destroyed, wins, losses).
- **Event log** — every join, leave, death, kill, bed break, and team elimination is recorded with timestamps, including the death/kill cause (fall, void, entity attack, etc.) and whether a leaving player was already eliminated and spectating. Viewable in the GUI and exportable to CSV.
- **Placement tracking** — 1st, 2nd, 3rd place tracked per team and included in exports. Teams tied for 1st get a dedicated tie stat instead of a false 1st-place credit.
- **Tie detection** — matches that end without a winner are correctly flagged as ties, with the specific tied teams tracked and shown by color.
- **In-game GUI** — paginated match list, detailed per-player stats, event timeline viewer.
- **CSV export** — player stats and event log exported as separate CSV files.
- **PlaceholderAPI** — exposes `%matchbook_matchcode%` for scoreboards/holograms.
- **Dual storage** — flat YAML files (default) or MySQL/MariaDB with one-command migration.
- **Auto config updates** — on plugin upgrade, new config keys are added automatically while preserving your existing settings.
- **Built for reliability** — saves retry and fall back to a local recovery file if storage is down; duplicate round-end events, back-to-back arena restarts, quick leave/rejoins, and mid-match team changes are all handled without corrupting records. The match lifecycle is covered by a simulation harness that replays these edge cases against the real code.
- **Proxy/network safe** — on a hub server with no arenas of its own, Matchbook won't create match records for arenas MBedwars merely knows about over the network (via its remote-arena awareness); it only tracks arenas actually running on that server.
- **Hub/lobby mode** — an explicit `mode.hub` config flag to fully disable match recording on a server, while still reading and exporting matches from the shared storage backend.

---

## Requirements

| Dependency | Version | Required |
|---|---|---|
| [Paper](https://papermc.io/) | 1.21+ | ✅ |
| [MBedwars](https://www.spigotmc.org/resources/mbedwars.82729/) | 5.x | ✅ |
| [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) | 2.11+ | Optional |

---

## Installation

1. Drop `Matchbook-<version>.jar` into your `plugins/MBedwars/add-ons/` folder.
2. Restart the server. Matchbook creates its config at:
   `plugins/MBedwars/add-ons/Matchbook/config.yml`
3. Edit `config.yml` as needed (see [Configuration](#configuration)).
4. Reload with `/mb reload` or restart.

---

## Commands

All commands use `/matchbook` or the alias `/mb`.

| Command | Permission | Description |
|---|---|---|
| `/mb help` | `mb.command.help` | Show available commands. |
| `/mb matches` | `mb.command.matches` | Open your personal match history GUI. |
| `/mb all` | `mb.command.all` | Open the global match list (all matches, newest first). |
| `/mb view <matchcode>` | `mb.command.view` | Open the details GUI for a specific match. |
| `/mb export <code>[,code...]` | `mb.command.export` | Export one or more matches to CSV files. |
| `/mb migrate yaml2mysql` | `mb.command.migrate` | Migrate all YAML match files into MySQL. |
| `/mb migrate mysql2yaml` | `mb.command.migrate` | Migrate all MySQL records to YAML files. |
| `/mb migrate ... --dry-run` | `mb.command.migrate` | Preview migration without writing anything. |
| `/mb reload` | `mb.command.reload` | Reload `config.yml` without restarting — hot-swaps the storage backend too if `storage.*` changed. |
| `/mb statskeys [player]` | `mb.command.statskeys` | List all stat keys available from MBedwars for a player. |
| `/mb test` | `mb.command.test` | Run a storage health check. |

---

## Permissions

### Permission Groups

| Node | Default | Grants |
|---|---|---|
| `mb.command.use` | Everyone | Required to use any `/mb` command at all. |
| `mb.command.default` | Everyone | `matches`, `all`, `view`, `export`, `help` |
| `mb.command.admin` | OP | `migrate`, `reload`, `statskeys`, `test` |

### Individual Nodes

| Node | Default |
|---|---|
| `mb.command.matches` | false |
| `mb.command.all` | false |
| `mb.command.view` | false |
| `mb.command.export` | false |
| `mb.command.help` | false |
| `mb.command.migrate` | false |
| `mb.command.reload` | false |
| `mb.command.statskeys` | false |
| `mb.command.test` | false |

> **Tip:** Grant `mb.command.default` to your player rank and `mb.command.admin` to staff. The `mb.command.use` node is true by default so everyone can run `/mb` without a permission error.

### Legacy nodes

These are kept for backwards compatibility with older permission setups:

- `matchbook.admin` — equivalent to `mb.command.admin`
- `matchbook.matches` — equivalent to `mb.command.matches`
- `matchbook.migrate` — equivalent to `mb.command.migrate`

---

## In-Game GUI

### Match History (`/mb matches`)

- Shows all of your past matches, most recent first.
- Each item shows the actual result: the winning team's color and name (e.g. **RED WIN**), or every tied team's color for a tie (e.g. **RED, BLUE TIE**). Your own team for that match is shown in the item tooltip.
- Displays your stats for that match in the item tooltip.
- Click any match to open Match Details.

### Match Details

- Players are grouped by team (fixed color order), then sorted by final kills → kills within each team.
- Each player is shown as a wool block colored by their team's actual bed color.
- Row 1 holds the match summary; the player list starts on row 2.
- **Spectators** are listed in the spyglass item (slot 47 in the nav bar).
- **Event Log button** (slot 51) — click to open the full event timeline.
- Prev/Next navigate between pages when there are many players.

### Event Log

- Paginated timeline of every event that occurred during the match.
- Each event is its own inventory item with an icon, description, and time offset.
- Time is shown as `+M:SS` from match start. Events before the round began show as `lobby`.
- Navigate with Prev/Next; click Back to return to Match Details.

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

### `<matchcode>.csv` — Player Stats

One row per participant with their stat diffs for that match.

```csv
# match_codes: AB12
# matchbook_version: 0.7.0
uuid,username,team,kills,final_kills,deaths,final_deaths,beds_destroyed,wins,loses
...
```

### `<matchcode>_events.csv` — Event Log

One row per event in chronological order.

```csv
# match: AB12
# matchbook_version: 0.7.0
offset_seconds,wall_clock_unix,type,player_name,player_uuid,player_team,killer_name,killer_uuid,killer_team,bed_team,final,cause,was_spectating,kill_cause
0,1751234567,MATCH_START,,,,,,,,false,,false,
13,1751234580,PLAYER_JOIN,Steve,<uuid>,RED,,,,,false,,false,
58,1751234625,PLAYER_DEATH,Alex,<uuid>,BLUE,Steve,<uuid>,RED,,true,VOID,false,ENTITY_ATTACK
...
```

`matchbook_version` is the Matchbook version that **recorded** the match (captured when the match session was created and stored with the match), not the version doing the export. Matches recorded before 0.6.10 show `unknown`. Multi-match exports print `# matchbook_versions:` instead — a single value when all matches were recorded by the same build, otherwise `code=version` pairs.

A `PLAYER_DEATH` row is the complete record of one death: the victim (`player_*`), how they died (`cause` — the Bukkit damage cause, e.g. `FALL`, `VOID`, `ENTITY_ATTACK`, `PROJECTILE`), and the responsible player MBedwars credited (`killer_*`). `kill_cause` shows how that player contributed — the example row above reads "Alex fell into the void after being hit by Steve, and it was a final kill". Empty killer columns on a void/fall death mean nobody was credited: a genuine environmental death. `was_spectating` is `true` on a `PLAYER_LEAVE` row when the player had already been eliminated and was spectating at the moment they left.

Matches recorded before 0.7.0 log attribution as a separate `PLAYER_KILL` row (killer in `killer_*`, victim name in `player_name`, kill cause in `cause`) near the victim's `PLAYER_DEATH` row. Those matches export and display exactly as before, and `PLAYER_KILL` can still appear (rarely) in new recordings when a kill couldn't be matched to its death row.

For multi-match exports, the events CSV includes an extra `match` column at the start and is sorted chronologically across all matches.

---

## Configuration

Config lives at `plugins/MBedwars/add-ons/Matchbook/config.yml`.

On plugin update, Matchbook automatically backs up your config (`config.yml.bak-<old-version>-<date>`) and merges new settings in. You never need to re-configure from scratch.

### Hub / Lobby Mode

```yaml
mode:
  hub: false   # set true on a server that should never record matches
```

When `mode.hub` is `true`, Matchbook registers no match-tracking listeners at all — it never creates a match session or writes a match to storage on that server. It still connects to the configured storage backend, so `/mb matches`, `/mb all`, `/mb view`, and `/mb export` keep working for browsing/exporting matches recorded elsewhere. This is meant for a hub/lobby server pointed at the same shared MySQL database as your backend arena servers (`storage.type: mysql`). Takes effect on server start/restart, not `/mb reload`.

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
    table_prefix: "matchbook_"
```

Changing anything under `storage:` (including switching `type` between `yaml` and `mysql`) takes effect on `/mb reload` — no server restart needed. Matchbook builds and validates the new backend (connects, runs a health check) *before* switching over, so a typo'd password or unreachable host just fails the reload and logs why, leaving the previous backend running untouched. (`mode.hub` is the one exception — it still needs a restart, see [Hub / Lobby Mode](#hub--lobby-mode).)

If a match ever can't be saved after a few retries (e.g. the database is briefly unreachable), Matchbook writes a local recovery copy to `matches/failed/<matchcode>.yml` instead of losing it — nothing to do at the time, just move/re-import that file once storage is healthy again.

### Tracked Stats

```yaml
match:
  tracked_keys:
    - "bedwars:kills"
    - "bedwars:final_kills"
    # ... add more using keys from /mb statskeys
```

### Export Columns

```yaml
export:
  columns:
    - uuid
    - username
    - team
    - bedwars:kills
    - bedwars:final_kills
    # Leave empty to use all tracked_keys as defaults
```

---

## MySQL Setup

1. Create a database and user:
   ```sql
   CREATE DATABASE matchbook CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
   CREATE USER 'matchbook'@'localhost' IDENTIFIED BY 'your_password';
   GRANT ALL PRIVILEGES ON matchbook.* TO 'matchbook'@'localhost';
   ```
2. Set `storage.type: mysql` in `config.yml` with your credentials.
3. Restart the server — tables are created automatically.

> **TLS:** connections verify the server certificate by default. If your MySQL/MariaDB server uses a self-signed certificate, add `verifyServerCertificate=false` to `mysql.params` in `config.yml`.

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

---

## PlaceholderAPI

If PlaceholderAPI is installed, Matchbook registers the `%matchbook_matchcode%` placeholder. It returns the current active match code for the player's arena (or their last match code for a short grace period after the match ends, so scoreboards don't go blank during transitions).

---

## Multi-Server / Proxy Networks

MBedwars has its own network-wide arena awareness (so hub servers behind a proxy can show live info for arenas hosted on other backend servers). Matchbook only ever creates a match record for an arena that has an actual game world loaded on that specific server — so installing Matchbook on a hub server with no arenas of its own is safe: it will never start tracking (and getting stuck on) matches that are really being played elsewhere on the network. If it ever rejects an arena for this reason, it logs one warning per arena name so you can confirm what happened.

For a hub server, prefer setting `mode.hub: true` explicitly (see [Hub / Lobby Mode](#hub--lobby-mode)) rather than relying solely on the automatic arena-locality check above — it skips match tracking entirely instead of rejecting arenas one at a time, and makes the server's role unambiguous.

If you run YAML storage per-server, each server's match history stays local to it. Point every server at the same MySQL database (`storage.type: mysql`) if you want one shared, network-wide match history instead — this is required for a hub server in `mode.hub: true` to have anything to read/export.

---

## Storage Layout (YAML mode)

```
plugins/MBedwars/add-ons/Matchbook/
├── config.yml
├── matches/
│   ├── 06-30-2026/                      ← one folder per day (MM-dd-yyyy)
│   │   ├── 1751297443-a1b2c3…-8F3K-Q2JD.yml   ← <startUnix>-<arenaHash>-<matchcode>.yml
│   │   └── 1751299018-a1b2c3…-M7G9-QDQ9.yml
│   ├── failed/                          ← recovery copies written when storage was down
│   └── ...
├── users/
│   └── <player-uuid>.yml    ← per-player match index
└── exports/
    ├── AB12.csv
    └── AB12_events.csv
```

Each match file is a self-contained YAML document with the match summary, per-player stats, and the full event log.

---

## Building from Source

```bash
./gradlew shadowJar
```

Output: `build/libs/Matchbook-<version>.jar`

Requires Java 21+.
