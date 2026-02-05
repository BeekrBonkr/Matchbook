package com.slg.matchbook.commands;

import com.slg.matchbook.MatchbookPlugin;
import com.slg.matchbook.io.MatchExporter;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

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
            sender.sendMessage(ChatColor.GRAY + " - /mb export <matchcode>");
            return true;
        }

        if (args[0].equalsIgnoreCase("export")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /mb export <matchcode>");
                return true;
            }

            String matchCode = args[1].trim();

            try {
                var out = exporter.exportMatchToCsv(matchCode);
                if (out == null) {
                    sender.sendMessage(ChatColor.RED + "Match not found: " + matchCode);
                    return true;
                }

                sender.sendMessage(ChatColor.GREEN + "Exported: " + ChatColor.WHITE + out.getName());
                sender.sendMessage(ChatColor.GRAY + "Saved to: " + out.getAbsolutePath());
            } catch (Exception e) {
                sender.sendMessage(ChatColor.RED + "Export failed: " + e.getMessage());
                plugin.getLogger().severe("Export failed: " + e.getMessage());
                e.printStackTrace();
            }

            return true;
        }

        sender.sendMessage(ChatColor.RED + "Unknown subcommand. Try: /mb export <matchcode>");
        return true;
    }
}
