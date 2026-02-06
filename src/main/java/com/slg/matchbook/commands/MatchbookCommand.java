package com.slg.matchbook.commands;

import com.slg.matchbook.MatchbookPlugin;
import com.slg.matchbook.gui.MatchesDetailsGui;
import com.slg.matchbook.gui.MatchesGui;
import com.slg.matchbook.io.MatchExporter;
import org.bukkit.ChatColor;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.io.File;
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

    private final MatchbookPlugin plugin;
    private final MatchExporter exporter;

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

                // Validate the match exists before opening a GUI that would be empty.
                if (plugin.getRepo().loadMatchYaml(matchId) == null && plugin.getRepo().findMatchFileById(matchId) == null) {
                    p.sendMessage(ChatColor.RED + "Match not found: " + ChatColor.WHITE + matchId);
                    return true;
                }

                details.openDetails(p, matchId, 0);
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

                try {
                    File outFile;

                    if (matchCodes.size() == 1) {
                        outFile = exporter.exportMatchToCsv(matchCodes.get(0));
                        if (outFile == null) {
                            sender.sendMessage(ChatColor.RED + "Match not found: " + matchCodes.get(0));
                            return true;
                        }
                        sender.sendMessage(ChatColor.GREEN + "Exported match " + ChatColor.WHITE + matchCodes.get(0));
                    } else {
                        outFile = exporter.exportMatchesCombinedToCsv(matchCodes);
                        sender.sendMessage(ChatColor.GREEN + "Exported combined CSV for "
                                + ChatColor.WHITE + matchCodes.size()
                                + ChatColor.GREEN + " matches.");
                    }

                    sender.sendMessage(ChatColor.GRAY + "Saved to: " + outFile.getAbsolutePath());
                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "Export failed: " + e.getMessage());
                    plugin.getLogger().severe("Export failed: " + e.getMessage());
                    e.printStackTrace();
                }

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
                try {
                    if (mode.equals("yaml2mysql") || mode.equals("yaml-to-mysql") || mode.equals("yaml2db")) {
                        int count = com.slg.matchbook.storage.MigrationService.migrateYamlToMySql(plugin, dryRun);
                        sender.sendMessage(ChatColor.GREEN + "Migration complete: " + ChatColor.WHITE + count + ChatColor.GREEN + " matches.");
                    } else if (mode.equals("mysql2yaml") || mode.equals("mysql-to-yaml") || mode.equals("db2yaml")) {
                        int count = com.slg.matchbook.storage.MigrationService.migrateMySqlToYaml(plugin, dryRun);
                        sender.sendMessage(ChatColor.GREEN + "Migration complete: " + ChatColor.WHITE + count + ChatColor.GREEN + " matches.");
                    } else {
                        sender.sendMessage(ChatColor.RED + "Unknown migrate mode: " + args[1]);
                        sender.sendMessage(ChatColor.RED + "Usage: /" + label + " migrate yaml2mysql|mysql2yaml [--dry-run]");
                    }
                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "Migration failed: " + e.getMessage());
                    plugin.getLogger().severe("Matchbook: migration failed: " + e.getMessage());
                    e.printStackTrace();
                }
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

            return filterPrefix(subs, partial);
        }

        if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            String partial = args[1].toLowerCase(Locale.ROOT);

            if (sub.equals("migrate")) {
                return filterPrefix(List.of("yaml2mysql", "mysql2yaml", "--dry-run"), partial);
            }

            if (sub.equals("view") || sub.equals("export")) {
                // Suggest recent matches; if player, prioritize their own history.
                List<String> ids = new ArrayList<>();
                try {
                    if (sender instanceof Player p) {
                        ids.addAll(plugin.getRepo().listMatchIdsForPlayer(p.getUniqueId()));
                    }
                    if (ids.isEmpty()) {
                        ids.addAll(plugin.getRepo().listAllMatchIds());
                    }
                } catch (Exception ignored) {}

                // cap suggestions (tab complete should be light)
                if (ids.size() > 50) ids = ids.subList(0, 50);

                // export supports comma-separated lists; don't try to be clever—just suggest raw ids
                return filterPrefix(ids, partial);
            }
        }

        return List.of();
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

        if (!any) {
            sender.sendMessage(ChatColor.RED + "No commands available (missing permissions).");
        }
    }

    private static void deny(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
    }

    private static boolean canDefault(CommandSender sender, String specificNode) {
        return sender.hasPermission(PERM_DEFAULT) || sender.hasPermission(specificNode);
    }

    private static boolean canAdmin(CommandSender sender, String specificNode) {
        return sender.hasPermission(PERM_ADMIN) || sender.hasPermission(specificNode);
    }

    private static List<String> filterPrefix(List<String> options, String partial) {
        if (partial == null || partial.isBlank()) return options;
        String p = partial.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        }
        return out;
    }

    private static List<String> parseMatchCodes(String input) {
        if (input == null) return List.of();
        String[] parts = input.trim().split("[,\s]+");

        Set<String> unique = new LinkedHashSet<>();
        for (String p : parts) {
            String code = p.trim();
            if (!code.isEmpty()) unique.add(code);
        }
        return new ArrayList<>(unique);
    }
}
