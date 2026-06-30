# Matchbook

A Paper plugin that records persistent match history for [MBedwars](https://www.spigotmc.org/resources/mbedwars.82729/). Browse past matches in-game, export stats to CSV, and view a full event-by-event match timeline.

---

## Features

- **Match history** — every completed BedWars match is saved with per-player stats (kills, deaths, final kills, beds destroyed, wins, losses).
- **Event log** — every join, leave, death, kill, bed break, and team elimination is recorded with timestamps. Viewable in the GUI and exportable to CSV.
- **Placement tracking** — 1st, 2nd, 3rd place tracked per team and included in exports.
- **Tie detection** — matches that end without a winner are correctly flagged as ties.
- **In-game GUI** — paginated match list, detailed per-player stats, event timeline viewer.
- **CSV export** — player stats and event log exported as separate CSV files.
- **Hastebin upload** — optionally upload exported CSVs to any Hastebin-compatible server automatically. Files are always saved locally too.
- **PlaceholderAPI** — exposes `%matchbook_matchcode%` for scoreboards/holograms.
- **Dual storage** — flat YAML files (default) or MySQL/MariaDB with one-command migration.
- **Auto config updates** — on plugin upgrade, new config keys are added automatically while preserving your existing settings.

---

## Requirements

| Dependency | Version | Required |
|---|---|---|
| [Paper](https://papermc.io/) | 1.21+ | ✅ |
| [MBedwars](https://www.spigotmc.org/resources/mbedwars.82729/) | 5.x | ✅ |
| [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) | 2.11+ | Optional |

---

## Installation

1. Drop `Matchbook-<version>.jar` into your `plugins/` folder.
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
| `/mb reload` | `mb.command.reload` | Reload `config.yml` without restarting. |
| `/mb statskeys [player]` | `mb.command.statskeys` | List all stat keys available from MBedwars for a player. |
| `/mb test [--upload]` | `mb.command.test` | Run a storage health check; optionally test Hastebin upload. |

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
- Each item is colored by outcome: **green** (won), **red** (lost), **yellow** (tied).
- Displays your stats for that match in the item tooltip.
- Click any match to open Match Details.

### Match Details

- Shows all participants sorted by winning team → final kills → kills.
- Each player is shown as a wool block colored by their team.
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
| Red dye | Player left |
| Skeleton skull | Regular death |
| Wither skeleton skull | Final death (eliminated) |
| Iron sword | Regular kill |
| Golden sword | Final kill |
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
uuid,username,team,kills,final_kills,deaths,final_deaths,beds_destroyed,wins,loses
...
```

### `<matchcode>_events.csv` — Event Log

One row per event in chronological order.

```csv
offset_seconds,wall_clock_unix,type,player_name,player_uuid,player_team,killer_name,killer_uuid,killer_team,bed_team,final
0,1751234567,MATCH_START,,,,,,,, false
13,1751234580,PLAYER_JOIN,Steve,<uuid>,RED,,,,, false
58,1751234625,PLAYER_KILL,Alex,<uuid>,BLUE,Steve,<uuid>,RED,,true
...
```

For multi-match exports, the events CSV includes an extra `match` column at the start and is sorted chronologically across all matches.

---

## Configuration

Config lives at `plugins/MBedwars/add-ons/Matchbook/config.yml`.

On plugin update, Matchbook automatically backs up your config (`config.yml.bak-<old-version>-<date>`) and merges new settings in. You never need to re-configure from scratch.

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

### Hastebin Upload

```yaml
export_upload:
  enabled: true
  server: "https://hastebin.com"   # or any compatible instance, e.g. https://hst.sh
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

## Storage Layout (YAML mode)

```
plugins/MBedwars/add-ons/Matchbook/
├── config.yml
├── matches/
│   ├── 2026-06-30/
│   │   ├── AB12.yml
│   │   └── CD34.yml
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
