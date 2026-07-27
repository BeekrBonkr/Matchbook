package com.slg.matchbook.commands;

import com.slg.matchbook.MatchbookPlugin;
import com.slg.matchbook.gui.MatchesDetailsGui;
import com.slg.matchbook.gui.MatchesGui;
import com.slg.matchbook.io.MatchExporter;
import com.slg.matchbook.io.HasteUploader;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Root command for Matchbook (/matchbook, /mb).
 *
 * Permission model:
 *  - Every subcommand has a specific node: mb.command.<sub>
 *  - Subcommands are also grouped into:
 *      mb.command.default  (typical players/coaches)
 *      mb.command.admin    (migration & admin actions)
 *  - Legacy nodes (matchbook.*) are still accepted for backwards compatibility.
 */
public final class MatchbookCommand implements CommandExecutor, TabCompleter {

    // Group nodes
    private static final String PERM_USE = "mb.command.use";
    private static final String PERM_DEFAULT = "mb.command.default";
    private static final String PERM_ADMIN = "mb.command.admin";

    // Per-command nodes
    private static final String PERM_MATCHES = "mb.command.matches";
    private static final String PERM_ALL = "mb.command.all";
    private static final String PERM_VIEW = "mb.command.view";
    private static final String PERM_EXPORT = "mb.command.export";
    private static final String PERM_MIGRATE = "mb.command.migrate";
    private static final String PERM_HELP = "mb.command.help";
    private static final String PERM_RELOAD = "mb.command.reload";
    private static final String PERM_STATSKEYS = "mb.command.statskeys";
    private static final String PERM_TEST = "mb.command.test";

    private final MatchbookPlugin plugin;
    private final MatchExporter exporter;

    /**
     * Tab-completion cache for match ids, refreshed asynchronously.
     *
     * Tab completion runs on the main thread on every keystroke, but building the candidate list
     * hits storage: in YAML mode listAllMatchIds() loads EVERY match file on disk, and in MySQL
     * mode both lookups are blocking queries. On a server with a few thousand matches that froze
     * the main thread whenever someone tab-completed /mb view or /mb export. Instead, completion
     * serves the last cached list immediately (possibly empty on the very first keystroke) and
     * kicks off a background refresh when the cache is older than the TTL.
     */
    private static final long TAB_CACHE_TTL_MS = 30_000L;
    private static final int TAB_CACHE_MAX_SENDERS = 200;

    private static final class CachedIds {
        final List<String> ids;
        final long fetchedAt;
        CachedIds(List<String> ids, long fetchedAt) { this.ids = ids; this.fetchedAt = fetchedAt; }
    }

    private final java.util.concurrent.ConcurrentMap<String, CachedIds> tabIdCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> tabRefreshInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Guards against two admins kicking off overlapping migrations. */
    private final java.util.concurrent.atomic.AtomicBoolean migrationRunning = new java.util.concurrent.atomic.AtomicBoolean(false);

    public MatchbookCommand(MatchbookPlugin plugin) {
        this.plugin = plugin;
        this.exporter = new MatchExporter(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!sender.hasPermission(PERM_USE)) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use Matchbook commands.");
            return true;
        }

        if (args.length == 0) {
            // default: help, filtered by perms
            sendHelp(sender, label);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {
            case "help" -> {
                if (!canDefault(sender, PERM_HELP)) {
                    deny(sender);
                    return true;
                }
                sendHelp(sender, label);
                return true;
            }

            case "matches" -> {
                if (!canDefault(sender, PERM_MATCHES) && !sender.hasPermission("matchbook.matches")) {
                    deny(sender);
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(ChatColor.RED + "This command can only be used in-game.");
                    return true;
                }
                MatchesGui gui = plugin.getMatchesGui();
                if (gui == null) {
                    p.sendMessage(ChatColor.RED + "Matchbook menu is not ready yet. Try again in a moment.");
                    return true;
                }
                gui.openHistory(p, p.getUniqueId(), 0);
                return true;
            }

            case "all" -> {
                if (!canDefault(sender, PERM_ALL)) {
                    deny(sender);
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(ChatColor.RED + "This command can only be used in-game.");
                    return true;
                }
                MatchesGui gui = plugin.getMatchesGui();
                if (gui == null) {
                    p.sendMessage(ChatColor.RED + "Matchbook menu is not ready yet. Try again in a moment.");
                    return true;
                }
                gui.openAll(p, 0);
                return true;
            }

            case "view" -> {
                if (!canDefault(sender, PERM_VIEW)) {
                    deny(sender);
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(ChatColor.RED + "This command can only be used in-game.");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /" + label + " view <matchcode>");
                    return true;
                }

                String matchId = args[1].trim();
                MatchesDetailsGui details = plugin.getDetailsGui();
                if (details == null) {
                    p.sendMessage(ChatColor.RED + "Matchbook menu is not ready yet. Try again in a moment.");
                    return true;
                }

                // Validate the match exists before opening a GUI that would be empty — off the main
                // thread. In YAML mode this walks and parses every match file on disk, which froze the
                // server here exactly like the tab-completion path used to. findMatchFileById is not
                // called separately any more: loadMatchYaml already goes through it in YAML mode (and
                // always returns null in MySQL mode), so the old second call was a redundant full scan
                // that only ever ran for ids that don't exist.
                org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    boolean exists = plugin.getRepo().loadMatchYaml(matchId) != null;
                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!p.isOnline()) return;
                        if (!exists) {
                            p.sendMessage(ChatColor.RED + "Match not found: " + ChatColor.WHITE + matchId);
                            return;
                        }
                        details.openDetails(p, matchId, 0);
                    });
                });
                return true;
            }

            case "export" -> {
                if (!canDefault(sender, PERM_EXPORT)) {
                    deny(sender);
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /" + label + " export <matchcode>[, matchcode...]");
                    return true;
                }

                StringBuilder joined = new StringBuilder();
                for (int i = 1; i < args.length; i++) {
                    if (i > 1) joined.append(' ');
                    joined.append(args[i]);
                }

                List<String> matchCodes = parseMatchCodes(joined.toString());
                if (matchCodes.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "No match codes detected.");
                    return true;
                }

                sender.sendMessage(ChatColor.GRAY + "Exporting... (this runs async)");

                org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        File outFile;
                        if (matchCodes.size() == 1) {
                            outFile = exporter.exportMatchToCsv(matchCodes.get(0));
                            if (outFile == null) {
                                org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                                        sender.sendMessage(ChatColor.RED + "Match not found: " + matchCodes.get(0))
                                );
                                return;
                            }
                            File finalOutFile = outFile;
                            File eventsFile = exporter.exportMatchEventsToCsv(matchCodes.get(0));
                            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                                sender.sendMessage(ChatColor.GREEN + "Exported match " + ChatColor.WHITE + matchCodes.get(0));
                                sender.sendMessage(ChatColor.GRAY + "Stats:  " + finalOutFile.getAbsolutePath());
                                if (eventsFile != null) {
                                    sender.sendMessage(ChatColor.GRAY + "Events: " + eventsFile.getAbsolutePath());
                                } else {
                                    sender.sendMessage(ChatColor.DARK_GRAY + "(No event log recorded for this match)");
                                }
                            });

                            // Hastebin upload is temporarily disabled — see maybeUploadToHastebin() below.
                        } else {
                            outFile = exporter.exportMatchesCombinedToCsv(matchCodes);
                            File combinedEvents = exporter.exportCombinedEventsToCsv(matchCodes);
                            File finalOutFile1 = outFile;
                            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                                sender.sendMessage(ChatColor.GREEN + "Exported combined CSV for "
                                        + ChatColor.WHITE + matchCodes.size()
                                        + ChatColor.GREEN + " matches.");
                                sender.sendMessage(ChatColor.GRAY + "Stats:  " + finalOutFile1.getAbsolutePath());
                                if (combinedEvents != null) {
                                    sender.sendMessage(ChatColor.GRAY + "Events: " + combinedEvents.getAbsolutePath());
                                }
                            });

                            // Hastebin upload is temporarily disabled — see maybeUploadToHastebin() below.
                        }
                    } catch (Exception e) {
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                            sender.sendMessage(ChatColor.RED + "Export failed: " + e.getMessage());
                        });
                        plugin.getLogger().severe("Export failed: " + e.getMessage());
                        e.printStackTrace();
                    }
                });

                return true;
            }

            case "migrate" -> {
                // Admin-only
                if (!canAdmin(sender, PERM_MIGRATE) && !sender.hasPermission("matchbook.migrate") && !sender.hasPermission("matchbook.admin")) {
                    deny(sender);
                    return true;
                }

                if (args.length < 2) {
                    sender.sendMessage(ChatColor.RED + "Usage: /" + label + " migrate yaml2mysql|mysql2yaml [--dry-run]");
                    return true;
                }

                boolean dryRun = false;
                for (String a : args) {
                    if (a.equalsIgnoreCase("--dry-run") || a.equalsIgnoreCase("--dryrun")) {
                        dryRun = true;
                        break;
                    }
                }

                String mode = args[1].toLowerCase(Locale.ROOT);
                boolean toMySql = mode.equals("yaml2mysql") || mode.equals("yaml-to-mysql") || mode.equals("yaml2db");
                boolean toYaml = mode.equals("mysql2yaml") || mode.equals("mysql-to-yaml") || mode.equals("db2yaml");

                if (!toMySql && !toYaml) {
                    sender.sendMessage(ChatColor.RED + "Unknown migrate mode: " + args[1]);
                    sender.sendMessage(ChatColor.RED + "Usage: /" + label + " migrate yaml2mysql|mysql2yaml [--dry-run]");
                    return true;
                }

                // One migration at a time: two overlapping runs would interleave writes to the same
                // match files / rows and double-count the reported totals.
                if (!migrationRunning.compareAndSet(false, true)) {
                    sender.sendMessage(ChatColor.RED + "A migration is already running. Wait for it to finish.");
                    return true;
                }

                sender.sendMessage(ChatColor.GRAY + "Migrating... (this runs async; watch the console for progress)");

                // Off the main thread: a migration parses every match document and does a storage
                // round-trip for each one. Run inline on a few thousand matches and the server stalls
                // long enough for the watchdog to kill it.
                boolean finalDryRun = dryRun;
                org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try {
                        int count = toMySql
                                ? com.slg.matchbook.storage.MigrationService.migrateYamlToMySql(plugin, finalDryRun)
                                : com.slg.matchbook.storage.MigrationService.migrateMySqlToYaml(plugin, finalDryRun);
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                                sender.sendMessage(ChatColor.GREEN + "Migration complete: " + ChatColor.WHITE + count
                                        + ChatColor.GREEN + " matches." + (finalDryRun ? ChatColor.GRAY + " (dry-run — nothing was written)" : "")));
                    } catch (Exception e) {
                        plugin.getLogger().log(java.util.logging.Level.SEVERE, "Matchbook: migration failed", e);
                        org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                                sender.sendMessage(ChatColor.RED + "Migration failed: " + e.getMessage()));
                    } finally {
                        migrationRunning.set(false);
                    }
                });
                return true;
            }

            case "reload" -> {
                if (!canAdmin(sender, PERM_RELOAD) && !sender.hasPermission("matchbook.admin")) {
                    deny(sender);
                    return true;
                }
                boolean storageChanged = plugin.reloadMatchbook();
                sender.sendMessage(ChatColor.GREEN + "Matchbook config reloaded.");
                if (storageChanged) {
                    sender.sendMessage(ChatColor.YELLOW + "Storage settings changed — reconnecting in the background "
                            + "(no restart needed). Check console or run /" + label + " test in a few seconds to confirm.");
                }
                if (plugin.getSettings() != null) {
                    sender.sendMessage(ChatColor.GRAY + "Tracked keys: " + ChatColor.WHITE + String.join(", ", plugin.getSettings().trackedKeys()));
                }
                return true;
            }

            case "test", "testconnections", "health" -> {
                if (!canAdmin(sender, PERM_TEST) && !sender.hasPermission("matchbook.admin")) {
                    deny(sender);
                    return true;
                }

                // Hastebin upload testing is temporarily disabled — see maybeUploadToHastebin()/
                // testHastebinConnection() below, which are left in place for when it comes back.
                sender.sendMessage(ChatColor.GRAY + "Running connection tests... (this runs async)");

                org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    var repoResult = plugin.getRepo().healthCheck();

                    org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                        sender.sendMessage(ChatColor.YELLOW + "Matchbook health checks:");
                        sender.sendMessage(colorizeResult("Storage", repoResult.ok(), repoResult.message()));
                    });
                });

                return true;
            }

            case "statskeys", "discover", "discoverstats" -> {
                if (!canAdmin(sender, PERM_STATSKEYS) && !sender.hasPermission("matchbook.admin")) {
                    deny(sender);
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    sender.sendMessage(ChatColor.RED + "This command can only be used in-game.");
                    return true;
                }

                String targetName = args.length >= 2 ? args[1] : p.getName();
                Player target = plugin.getServer().getPlayerExact(targetName);
                if (target == null) {
                    sender.sendMessage(ChatColor.RED + "Player not online: " + ChatColor.WHITE + targetName);
                    return true;
                }

                // Async callback from MBedwars; bounce results to main thread.
                de.marcely.bedwars.api.BedwarsAPI.getPlayerDataAPI().getStats(target.getUniqueId(), statsObj -> {
                    var chosen = com.slg.matchbook.service.BedwarsStatsAdapter.pickBest(statsObj);
                    java.util.Set<String> keys = com.slg.matchbook.util.StatsKeyDiscovery.discoverKeys(chosen);

                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        sender.sendMessage(ChatColor.YELLOW + "Discovered " + keys.size() + " stat keys for " + ChatColor.WHITE + target.getName());
                        if (keys.isEmpty()) {
                            sender.sendMessage(ChatColor.GRAY + "Could not enumerate keys from this MBedwars version. You can still try known keys like bedwars:kills, bedwars:deaths, bedwars:wins, bedwars:loses, bedwars:final_kills, bedwars:final_deaths.");
                        } else {
                            // Split lines to avoid chat limit.
                            StringBuilder line = new StringBuilder(ChatColor.GRAY.toString());
                            for (String k : keys) {
                                if (line.length() + k.length() + 2 > 200) {
                                    sender.sendMessage(line.toString());
                                    line = new StringBuilder(ChatColor.GRAY.toString());
                                }
                                if (line.length() > 2) line.append(ChatColor.DARK_GRAY).append(", ").append(ChatColor.GRAY);
                                line.append(k);
                            }
                            if (!line.isEmpty()) sender.sendMessage(line.toString());
                        }
                    });
                });

                return true;
            }

            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown subcommand. Try: /" + label + " help");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {

        if (!sender.hasPermission(PERM_USE)) return List.of();

        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);

            List<String> subs = new ArrayList<>();
            if (canDefault(sender, PERM_HELP)) subs.add("help");
            if (canDefault(sender, PERM_MATCHES) || sender.hasPermission("matchbook.matches")) subs.add("matches");
            if (canDefault(sender, PERM_ALL)) subs.add("all");
            if (canDefault(sender, PERM_VIEW)) subs.add("view");
            if (canDefault(sender, PERM_EXPORT)) subs.add("export");
            if (canAdmin(sender, PERM_MIGRATE) || sender.hasPermission("matchbook.migrate") || sender.hasPermission("matchbook.admin")) subs.add("migrate");
            if (canAdmin(sender, PERM_RELOAD) || sender.hasPermission("matchbook.admin")) subs.add("reload");
            if (canAdmin(sender, PERM_STATSKEYS) || sender.hasPermission("matchbook.admin")) subs.add("statskeys");
            if (canAdmin(sender, PERM_TEST) || sender.hasPermission("matchbook.admin")) subs.add("test");

            return filterPrefix(subs, partial);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String partial = args[1].toLowerCase(Locale.ROOT);

            if (sub.equals("migrate")) {
                return filterPrefix(List.of("yaml2mysql", "mysql2yaml", "--dry-run"), partial);
            }

            if (sub.equals("statskeys")) {
                // Suggest online player names
                List<String> names = new ArrayList<>();
                for (Player p : plugin.getServer().getOnlinePlayers()) names.add(p.getName());
                return filterPrefix(names, partial);
            }

            if (sub.equals("view")) {
                return filterPrefix(candidateMatchIds(sender), partial);
            }
        }

        // export takes a comma-separated list of match codes and keeps accepting more after the
        // first one — whether typed as "ID1,ID2" (still args[1], no space) or "ID1, ID2" (a new
        // arg once a space follows the comma).
        if (args.length >= 2 && args[0].equalsIgnoreCase("export")) {
            return completeMatchCodeList(sender, args[args.length - 1]);
        }

        return List.of();
    }

    /**
     * Recent match ids; prioritizes the sender's own history when they're a player.
     *
     * Served from an async-refreshed cache — storage is never touched on the main thread here
     * (see tabIdCache). The first keystroke after a cold/expired cache may return an empty or
     * stale list; the refreshed ids show up on the next completion attempt.
     */
    private List<String> candidateMatchIds(CommandSender sender) {
        UUID senderUuid = sender instanceof Player p ? p.getUniqueId() : null;
        String key = senderUuid != null ? senderUuid.toString() : "console";

        long now = System.currentTimeMillis();
        CachedIds cached = tabIdCache.get(key);

        if ((cached == null || now - cached.fetchedAt > TAB_CACHE_TTL_MS) && tabRefreshInFlight.add(key)) {
            org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    List<String> ids = new ArrayList<>();
                    if (senderUuid != null) {
                        ids.addAll(plugin.getRepo().listMatchIdsForPlayer(senderUuid));
                    }
                    if (ids.isEmpty()) {
                        ids.addAll(plugin.getRepo().listAllMatchIds());
                    }
                    if (ids.size() > 50) ids = new ArrayList<>(ids.subList(0, 50));

                    if (tabIdCache.size() > TAB_CACHE_MAX_SENDERS) tabIdCache.clear();
                    tabIdCache.put(key, new CachedIds(List.copyOf(ids), System.currentTimeMillis()));
                } catch (Exception ignored) {
                } finally {
                    tabRefreshInFlight.remove(key);
                }
            });
        }

        return cached != null ? cached.ids : List.of();
    }

    /**
     * Completes the LAST match code in a comma-separated list, e.g. typing "ABCD-1234,EF" only
     * completes the "EF" segment, and the suggestion is re-prefixed with "ABCD-1234," so the whole
     * token stays valid. Codes already present earlier in the list aren't suggested again.
     */
    private List<String> completeMatchCodeList(CommandSender sender, String currentArg) {
        if (currentArg == null) currentArg = "";

        int lastComma = currentArg.lastIndexOf(',');
        String prefix = lastComma >= 0 ? currentArg.substring(0, lastComma + 1) : "";
        String segment = lastComma >= 0 ? currentArg.substring(lastComma + 1) : currentArg;

        Set<String> alreadyUsed = new HashSet<>();
        if (lastComma >= 0) {
            for (String code : prefix.split(",")) {
                String c = code.trim();
                if (!c.isEmpty()) alreadyUsed.add(c.toUpperCase(Locale.ROOT));
            }
        }

        List<String> matches = filterPrefix(candidateMatchIds(sender), segment.toLowerCase(Locale.ROOT));

        List<String> out = new ArrayList<>();
        for (String id : matches) {
            if (alreadyUsed.contains(id.toUpperCase(Locale.ROOT))) continue;
            out.add(prefix + id);
        }
        return out;
    }

    private void sendHelp(CommandSender sender, String label) {
        sender.sendMessage(ChatColor.YELLOW + "Matchbook commands you can use:");

        boolean any = false;

        if (canDefault(sender, PERM_MATCHES) || sender.hasPermission("matchbook.matches")) {
            sender.sendMessage(ChatColor.GRAY + " - /" + label + " matches" + ChatColor.DARK_GRAY + "  (your match history)");
            any = true;
        }
        if (canDefault(sender, PERM_ALL)) {
            sender.sendMessage(ChatColor.GRAY + " - /" + label + " all" + ChatColor.DARK_GRAY + "  (all matches, most recent first)");
            any = true;
        }
        if (canDefault(sender, PERM_VIEW)) {
            sender.sendMessage(ChatColor.GRAY + " - /" + label + " view <matchcode>" + ChatColor.DARK_GRAY + "  (open a specific match)");
            any = true;
        }
        if (canDefault(sender, PERM_EXPORT)) {
            sender.sendMessage(ChatColor.GRAY + " - /" + label + " export <matchcode>[, matchcode...]" + ChatColor.DARK_GRAY + "  (CSV export)");
            any = true;
        }
        if (canAdmin(sender, PERM_MIGRATE) || sender.hasPermission("matchbook.migrate") || sender.hasPermission("matchbook.admin")) {
            sender.sendMessage(ChatColor.GRAY + " - /" + label + " migrate yaml2mysql|mysql2yaml [--dry-run]" + ChatColor.DARK_GRAY + "  (admin)");
            any = true;
        }

        if (canAdmin(sender, PERM_RELOAD) || sender.hasPermission("matchbook.admin")) {
            sender.sendMessage(ChatColor.GRAY + " - /" + label + " reload" + ChatColor.DARK_GRAY + "  (reload config.yml)");
            any = true;
        }

        if (canAdmin(sender, PERM_STATSKEYS) || sender.hasPermission("matchbook.admin")) {
            sender.sendMessage(ChatColor.GRAY + " - /" + label + " statskeys [player]" + ChatColor.DARK_GRAY + "  (discover stat keys)");
            any = true;
        }

        if (canAdmin(sender, PERM_TEST) || sender.hasPermission("matchbook.admin")) {
            sender.sendMessage(ChatColor.GRAY + " - /" + label + " test" + ChatColor.DARK_GRAY + "  (test storage connection)");
            any = true;
        }

        if (!any) {
            sender.sendMessage(ChatColor.RED + "No commands available (missing permissions).");
        }
    }

    private static void deny(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
    }

    private static String colorizeResult(String label, boolean ok, String message) {
        ChatColor c = ok ? ChatColor.GREEN : ChatColor.RED;
        String m = message == null ? "" : message;
        return ChatColor.GRAY + " - " + label + ": " + c + (ok ? "OK" : "FAIL") + ChatColor.DARK_GRAY + "  " + ChatColor.GRAY + m;
    }

    private record SimpleResult(boolean ok, String message) {}

    private SimpleResult testHastebinConnection() {
        try {
            var yml = plugin.getMatchbookConfig().raw();
            boolean enabled = yml.getBoolean("export_upload.enabled", false);
            if (!enabled) {
                return new SimpleResult(false, "export_upload.enabled is false — enable it in config.yml first");
            }

            String server = yml.getString("export_upload.server", "https://hastebin.com");
            HasteUploader uploader = new HasteUploader(server);
            String url = uploader.upload("matchbook_connection_test");
            return new SimpleResult(true, "Upload OK: " + url);
        } catch (Exception e) {
            return new SimpleResult(false, e.getMessage());
        }
    }

    private static boolean canDefault(CommandSender sender, String specificNode) {
        return sender.hasPermission(PERM_DEFAULT) || sender.hasPermission(specificNode);
    }

    private static boolean canAdmin(CommandSender sender, String specificNode) {
        return sender.hasPermission(PERM_ADMIN) || sender.hasPermission(specificNode);
    }

    private static List<String> filterPrefix(List<String> options, String partial) {
        // Copy: the server may sort the returned completions in place, and options can be an
        // immutable cached list.
        if (partial == null || partial.isBlank()) return new ArrayList<>(options);
        String p = partial.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        }
        return out;
    }

    private void maybeUploadToHastebin(CommandSender sender, File csvFile) {
        try {
            var cfg = plugin.getMatchbookConfig().raw();
            if (!cfg.getBoolean("export_upload.enabled", false)) return;

            String server = cfg.getString("export_upload.server", "https://hastebin.com");
            String content = Files.readString(csvFile.toPath(), StandardCharsets.UTF_8);

            HasteUploader uploader = new HasteUploader(server);
            String url = uploader.upload(content);

            org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                    sender.sendMessage(ChatColor.GREEN + "Uploaded: " + ChatColor.WHITE + url));
        } catch (Exception e) {
            plugin.getLogger().warning("Export upload failed: " + e.getMessage());
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () ->
                    sender.sendMessage(ChatColor.RED + "Hastebin upload failed: " + ChatColor.GRAY + e.getMessage()));
        }
    }

    private static List<String> parseMatchCodes(String input) {
        if (input == null) return List.of();
        // NB: "\\s" (the character class), not "\s" — since Java 15 the latter is the escape for a
        // literal space, so the old pattern silently meant "[, ]+" and wouldn't split on a tab.
        String[] parts = input.trim().split("[,\\s]+");

        Set<String> unique = new LinkedHashSet<>();
        for (String p : parts) {
            String code = p.trim();
            if (!code.isEmpty()) unique.add(code);
        }
        return new ArrayList<>(unique);
    }
}
