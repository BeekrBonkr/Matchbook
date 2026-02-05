package com.slg.matchbook.gui;

import com.slg.matchbook.MatchbookPlugin;
import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MatchesGui implements Listener {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    private static final int PAGE_SLOTS = 45; // 0..44
    private static final int SLOT_PREV = 45;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_NEXT = 53;

    private final MatchbookPlugin plugin;
    private final MatchesDetailsGui detailsGui;

    private final NamespacedKey KEY_MATCH_ID;
    private final Map<UUID, HistoryState> openHistory = new HashMap<>();

    public MatchesGui(MatchbookPlugin plugin, MatchesDetailsGui detailsGui) {
        this.plugin = plugin;
        this.detailsGui = detailsGui;
        this.KEY_MATCH_ID = new NamespacedKey(plugin, "match_id");
    }

    /* =========================================================
       Public entry point
       ========================================================= */

    /** Open match history for targetUuid (usually self). */
    public void openHistory(Player viewer, UUID targetUuid, int page) {
        List<String> matchIds = loadUserMatchIds(targetUuid); // newest first recommended

        int maxPage = Math.max(0, (matchIds.size() - 1) / PAGE_SLOTS);
        int p = Math.max(0, Math.min(page, maxPage));

        Inventory inv = Bukkit.createInventory(new HistoryHolder(viewer.getUniqueId(), targetUuid, p), SIZE,
                ChatColor.DARK_GRAY + "Matchbook Matches " + ChatColor.GRAY + "(" + (p + 1) + "/" + (maxPage + 1) + ")");

        // reserve bottom row
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, pane(Material.BLACK_STAINED_GLASS_PANE, " "));
        }

        inv.setItem(SLOT_PREV, button(Material.ARROW, ChatColor.YELLOW + "Previous Page"));
        inv.setItem(SLOT_NEXT, button(Material.ARROW, ChatColor.YELLOW + "Next Page"));
        inv.setItem(SLOT_BACK, button(Material.BARRIER, ChatColor.RED + "Close"));

        // Fill match items
        int start = p * PAGE_SLOTS;
        int end = Math.min(matchIds.size(), start + PAGE_SLOTS);

        int slot = 0;
        for (int i = start; i < end; i++) {
            String matchId = matchIds.get(i);
            ItemStack it = buildHistoryItem(viewer.getUniqueId(), matchId);
            inv.setItem(slot++, it);
        }

        openHistory.put(viewer.getUniqueId(), new HistoryState(targetUuid, p));
        viewer.openInventory(inv);
    }

    /* =========================================================
       Data access
       ========================================================= */

    /** Reads Matchbook/users/<uuid>.yml -> matches: [ ... match_id ... ] */
    private List<String> loadUserMatchIds(UUID targetUuid) {
        File usersDir = new File(plugin.getAddonDataFolder(), "users");
        File f = new File(usersDir, targetUuid.toString() + ".yml");
        if (!f.exists()) return List.of();

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(f);
        List<String> ids = yml.getStringList("matches");

        // If you’re currently storing filenames instead of match IDs,
        // replace this with a mapping step. But your new system should store match_id.
        return new ArrayList<>(ids);
    }

    /** Find match YAML by match.match_id (scans matches/<day>/*.yml). */
    private File findMatchFileById(String matchId) {
        File matchesDir = new File(plugin.getAddonDataFolder(), "matches");
        if (!matchesDir.exists()) return null;

        File[] dayDirs = matchesDir.listFiles(File::isDirectory);
        if (dayDirs == null) return null;

        for (File day : dayDirs) {
            File[] files = day.listFiles((dir, name) -> name.endsWith(".yml"));
            if (files == null) continue;

            for (File f : files) {
                YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
                String id = y.getString("match.match_id", "");
                if (matchId.equalsIgnoreCase(id)) return f;
            }
        }
        return null;
    }

    /* =========================================================
       Item builders
       ========================================================= */

    private ItemStack buildHistoryItem(UUID viewerUuid, String matchEntry) {
        // matchEntry can be either a matchId (preferred) or a legacy relative path (MM-dd-yyyy/file.yml)
        File matchFile;
        String matchId = matchEntry;

        if (matchEntry != null && (matchEntry.contains("/") || matchEntry.endsWith(".yml"))) {
            matchFile = new File(new File(plugin.getAddonDataFolder(), "matches"), matchEntry);
            if (!matchFile.exists()) matchFile = null;
            if (matchFile != null) {
                YamlConfiguration ymlTmp = YamlConfiguration.loadConfiguration(matchFile);
                String fromFile = ymlTmp.getString("match.match_id", "");
                if (fromFile != null && !fromFile.isBlank()) matchId = fromFile;
            }
        } else {
            matchFile = findMatchFileById(matchId);
        }

        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();

        // store match id in PDC so click can open details
        meta.getPersistentDataContainer().set(KEY_MATCH_ID, PersistentDataType.STRING, matchId);

        if (matchFile == null) {
            meta.setDisplayName(ChatColor.RED + matchId + ChatColor.GRAY + " • missing file");
            meta.setLore(List.of(ChatColor.GRAY + "Match YAML not found."));
            it.setItemMeta(meta);
            return it;
        }

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(matchFile);

        String arena = yml.getString("match.arena", "");
        String result = yml.getString("match.result", "");
        long startUnix = yml.getLong("match.start_unix", 0L);

        // Determine viewer’s team + outcome (past tense)
        String viewerBase = "players." + viewerUuid;
        String viewerTeam = yml.getString(viewerBase + ".team", "");

        String pastTense = outcomePastTense(result, viewerTeam); // Won/Lost/Tied
        ChatColor outcomeColor = switch (pastTense) {
            case "Won" -> ChatColor.GREEN;
            case "Lost" -> ChatColor.RED;
            case "Tied" -> ChatColor.YELLOW;
            default -> ChatColor.AQUA;
        };

        meta.setDisplayName(ChatColor.AQUA + matchId + ChatColor.DARK_GRAY + " • " + outcomeColor + pastTense
                + ChatColor.DARK_GRAY + " • " + teamColor(viewerTeam) + (viewerTeam.isBlank() ? "?" : viewerTeam));

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Arena: " + ChatColor.WHITE + arena);
        lore.add(ChatColor.GRAY + "When: " + ChatColor.WHITE + formatUnix(startUnix));
        lore.add("");

        // One stat per line
        lore.add(statLine("Kills", yml.getLong(viewerBase + ".diff.bedwars:kills", 0)));
        lore.add(statLine("Final Kills", yml.getLong(viewerBase + ".diff.bedwars:final_kills", 0)));
        lore.add(statLine("Final Deaths", yml.getLong(viewerBase + ".diff.bedwars:final_deaths", 0)));
        lore.add(statLine("Beds Destroyed", yml.getLong(viewerBase + ".diff.bedwars:beds_destroyed", 0)));
        lore.add(statLine("Wins", yml.getLong(viewerBase + ".diff.bedwars:wins", 0)));
        lore.add(statLine("Losses", yml.getLong(viewerBase + ".diff.bedwars:loses", 0)));

        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Click to view match details");

        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private static String statLine(String name, long value) {
        return ChatColor.GRAY + "• " + ChatColor.WHITE + name + ": " + ChatColor.AQUA + value;
    }

    /* =========================================================
       Events (locking + navigation + open details)
       ========================================================= */

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof HistoryHolder holder)) return;

        // lock everything in our GUI
        e.setCancelled(true);

        int raw = e.getRawSlot();
        if (raw < 0 || raw >= SIZE) return;

        if (raw == SLOT_BACK) {
            p.closeInventory();
            return;
        }
        if (raw == SLOT_PREV) {
            openHistory(p, holder.targetUuid, holder.page - 1);
            return;
        }
        if (raw == SLOT_NEXT) {
            openHistory(p, holder.targetUuid, holder.page + 1);
            return;
        }

        // Match slot click: open details if item has match_id
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        String matchId = meta.getPersistentDataContainer().get(KEY_MATCH_ID, PersistentDataType.STRING);
        if (matchId == null || matchId.isBlank()) return;

        detailsGui.openDetails(p, matchId, 0);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof HistoryHolder)) return;

        // if any slot affected is in top inv, cancel
        for (int raw : e.getRawSlots()) {
            if (raw < SIZE) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        openHistory.remove(e.getPlayer().getUniqueId());
    }

    /* =========================================================
       Helpers
       ========================================================= */

    private static ItemStack pane(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack button(Material m, String name) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        it.setItemMeta(meta);
        return it;
    }

    private static String formatUnix(long unix) {
        if (unix <= 0) return "";
        return new SimpleDateFormat("MM/dd hh:mm a").format(new Date(unix * 1000L));
    }

    public static ChatColor teamColor(String team) {
        if (team == null) return ChatColor.GRAY;
        return switch (team.toUpperCase(Locale.ROOT)) {
            case "RED" -> ChatColor.RED;
            case "BLUE" -> ChatColor.BLUE;
            case "GREEN" -> ChatColor.GREEN;
            case "YELLOW" -> ChatColor.YELLOW;
            case "PINK" -> ChatColor.LIGHT_PURPLE;
            case "AQUA", "CYAN" -> ChatColor.AQUA;
            case "WHITE" -> ChatColor.WHITE;
            case "GRAY", "GREY" -> ChatColor.DARK_GRAY;
            case "ORANGE" -> ChatColor.GOLD;
            case "PURPLE" -> ChatColor.DARK_PURPLE;
            default -> ChatColor.GRAY;
        };
    }

    /**
     * result format is "WIN:TEAM" or "TIE"
     * viewerTeam is their team name.
     */
    private static String outcomePastTense(String result, String viewerTeam) {
        if (result == null || result.isBlank()) return "Played";

        if (result.equalsIgnoreCase("TIE")) return "Tied";

        if (result.toUpperCase(Locale.ROOT).startsWith("WIN:")) {
            String winTeam = result.substring("WIN:".length()).trim();
            if (viewerTeam != null && !viewerTeam.isBlank() && winTeam.equalsIgnoreCase(viewerTeam)) return "Won";
            return "Lost";
        }

        return "Played";
    }

    /* =========================================================
       Holders / state
       ========================================================= */

    private static final class HistoryState {
        final UUID targetUuid;
        final int page;
        HistoryState(UUID targetUuid, int page) { this.targetUuid = targetUuid; this.page = page; }
    }

    private static final class HistoryHolder implements InventoryHolder {
        final UUID viewerUuid;
        final UUID targetUuid;
        final int page;

        HistoryHolder(UUID viewerUuid, UUID targetUuid, int page) {
            this.viewerUuid = viewerUuid;
            this.targetUuid = targetUuid;
            this.page = page;
        }

        @Override public Inventory getInventory() { return null; }
    }
}
