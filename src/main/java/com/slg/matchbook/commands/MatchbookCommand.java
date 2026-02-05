package com.slg.matchbook.commands;

import com.slg.matchbook.MatchbookPlugin;
import com.slg.matchbook.gui.MatchesGui;
import com.slg.matchbook.io.MatchExporter;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.*;

public final class MatchbookCommand implements CommandExecutor {

    private final MatchbookPlugin plugin;
    private final MatchExporter exporter;

    public MatchbookCommand(MatchbookPlugin plugin) {
        this.plugin = plugin;
        this.exporter = new MatchExporter(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Matchbook commands:");
            sender.sendMessage(ChatColor.GRAY + " - /mb matches");
            sender.sendMessage(ChatColor.GRAY + " - /mb export <matchcode>[, matchcode...]");
            return true;
        }

        // /mb matches
        if (args[0].equalsIgnoreCase("matches")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(ChatColor.RED + "This command can only be used in-game.");
                return true;
            }

            if (!p.hasPermission("matchbook.matches")) {
                p.sendMessage(ChatColor.RED + "You don't have permission to do that.");
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

        // /mb export <matchcode>[, matchcode...]
        if (args[0].equalsIgnoreCase("export")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /mb export <matchcode>[, matchcode...]");
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

        sender.sendMessage(ChatColor.RED + "Unknown subcommand. Try: /mb matches or /mb export ...");
        return true;
    }

    private static List<String> parseMatchCodes(String input) {
        if (input == null) return List.of();
        String[] parts = input.trim().split("[,\\s]+");

        Set<String> unique = new LinkedHashSet<>();
        for (String p : parts) {
            String code = p.trim();
            if (!code.isEmpty()) unique.add(code);
        }
        return new ArrayList<>(unique);
    }
}
