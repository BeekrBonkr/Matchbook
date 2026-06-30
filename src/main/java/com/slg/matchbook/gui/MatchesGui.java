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

    private static final int ROWS      = 6;
    private static final int SIZE      = ROWS * 9;
    private static final int PAGE_SLOTS = 45; // rows 0-4
    private static final int SLOT_PREV = 45;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_NEXT = 53;

    private final MatchbookPlugin plugin;
    private final MatchesDetailsGui detailsGui;
    private final NamespacedKey KEY_MATCH_ID;

    public MatchesGui(MatchbookPlugin plugin, MatchesDetailsGui detailsGui) {
        this.plugin     = plugin;
        this.detailsGui = detailsGui;
        this.KEY_MATCH_ID = new NamespacedKey(plugin, "match_id");
    }

    // -----------------------------------------------------------------------
    // Public entry points
    // -----------------------------------------------------------------------

    public void openHistory(Player viewer, UUID targetUuid, int page) {
        List<String> matchIds = plugin.getRepo().listMatchIdsForPlayer(targetUuid);

        int maxPage = matchIds.isEmpty() ? 0 : Math.max(0, (matchIds.size() - 1) / PAGE_SLOTS);
        int p = Math.max(0, Math.min(page, maxPage));

        String title = ChatColor.DARK_GRAY + "Match History "
                + ChatColor.DARK_GRAY + "• " + ChatColor.GRAY
                + (p + 1) + "/" + (maxPage + 1);

        Inventory inv = Bukkit.createInventory(new HistoryHolder(viewer.getUniqueId(), targetUuid, p), SIZE, title);

        buildNavBar(inv, p, maxPage, true);

        int start = p * PAGE_SLOTS;
        int end   = Math.min(matchIds.size(), start + PAGE_SLOTS);
        int slot  = 0;
        for (int i = start; i < end; i++) {
            inv.setItem(slot++, buildMatchItem(viewer.getUniqueId(), matchIds.get(i)));
        }

        viewer.openInventory(inv);
    }

    public void openAll(Player viewer, int page) {
        List<String> matchIds = plugin.getRepo().listAllMatchIds();

        int maxPage = matchIds.isEmpty() ? 0 : Math.max(0, (matchIds.size() - 1) / PAGE_SLOTS);
        int p = Math.max(0, Math.min(page, maxPage));

        String title = ChatColor.DARK_GRAY + "All Matches "
                + ChatColor.DARK_GRAY + "• " + ChatColor.GRAY
                + (p + 1) + "/" + (maxPage + 1);

        Inventory inv = Bukkit.createInventory(new AllHolder(viewer.getUniqueId(), p), SIZE, title);

        buildNavBar(inv, p, maxPage, false);

        int start = p * PAGE_SLOTS;
        int end   = Math.min(matchIds.size(), start + PAGE_SLOTS);
        int slot  = 0;
        for (int i = start; i < end; i++) {
            inv.setItem(slot++, buildMatchItem(viewer.getUniqueId(), matchIds.get(i)));
        }

        viewer.openInventory(inv);
    }

    // -----------------------------------------------------------------------
    // Nav bar
    // -----------------------------------------------------------------------

    private static void buildNavBar(Inventory inv, int page, int maxPage, boolean hasClose) {
        for (int i = 45; i < 54; i++) inv.setItem(i, navPane());

        inv.setItem(SLOT_PREV, navButton(Material.SPECTRAL_ARROW, ChatColor.YELLOW + "Previous Page",
                page > 0 ? ChatColor.GRAY + "Go to page " + page : ChatColor.DARK_GRAY + "Already on first page"));

        inv.setItem(SLOT_BACK, navButton(hasClose ? Material.BARRIER : Material.DARK_OAK_DOOR,
                hasClose ? ChatColor.RED + "Close" : ChatColor.YELLOW + "Back",
                hasClose ? ChatColor.GRAY + "Close this menu" : ChatColor.GRAY + "Go back"));

        inv.setItem(SLOT_NEXT, navButton(Material.SPECTRAL_ARROW, ChatColor.YELLOW + "Next Page",
                page < maxPage ? ChatColor.GRAY + "Go to page " + (page + 2) : ChatColor.DARK_GRAY + "Already on last page"));
    }

    // -----------------------------------------------------------------------
    // Match item builder
    // -----------------------------------------------------------------------

    private ItemStack buildMatchItem(UUID viewerUuid, String matchEntry) {
        String matchId = matchEntry;
        if (matchEntry != null && (matchEntry.contains("/") || matchEntry.endsWith(".yml"))) {
            File legacy = new File(new File(plugin.getAddonDataFolder(), "matches"), matchEntry);
            if (legacy.exists()) {
                YamlConfiguration tmp = YamlConfiguration.loadConfiguration(legacy);
                String fromFile = tmp.getString("match.match_id", "");
                if (fromFile != null && !fromFile.isBlank()) matchId = fromFile;
            }
        }

        YamlConfiguration yml = plugin.getRepo().loadMatchYaml(matchId);

        if (yml == null) {
            ItemStack it = new ItemStack(Material.BARRIER);
            ItemMeta meta = it.getItemMeta();
            meta.setDisplayName(ChatColor.RED + matchId + ChatColor.DARK_GRAY + " • missing");
            meta.setLore(List.of(ChatColor.GRAY + "Match file not found."));
            meta.getPersistentDataContainer().set(KEY_MATCH_ID, PersistentDataType.STRING, matchId);
            it.setItemMeta(meta);
            return it;
        }

        String arena     = yml.getString("match.arena", "");
        String result    = yml.getString("match.result", "");
        long startUnix   = yml.getLong("match.start_unix", 0L);
        long endUnix     = yml.getLong("match.end_unix", 0L);
        long duration    = endUnix > startUnix ? endUnix - startUnix : 0L;

        int participantCount = yml.getStringList("match.participants").size();

        String viewerBase = "players." + viewerUuid;
        String viewerTeam = yml.getString(viewerBase + ".team", "");

        String pastTense = outcomePastTense(result, viewerTeam);
        ChatColor outcomeColor = switch (pastTense) {
            case "Won"  -> ChatColor.GREEN;
            case "Lost" -> ChatColor.RED;
            case "Tied" -> ChatColor.YELLOW;
            default     -> ChatColor.AQUA;
        };
        Material mat = switch (pastTense) {
            case "Won"  -> Material.LIME_WOOL;
            case "Lost" -> Material.RED_WOOL;
            case "Tied" -> Material.YELLOW_WOOL;
            default     -> Material.PAPER;
        };

        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(KEY_MATCH_ID, PersistentDataType.STRING, matchId);

        meta.setDisplayName(
                outcomeColor + "" + ChatColor.BOLD + pastTense
                + ChatColor.RESET + ChatColor.DARK_GRAY + " • "
                + teamColor(viewerTeam) + (viewerTeam.isBlank() ? "?" : viewerTeam)
                + ChatColor.DARK_GRAY + " • "
                + ChatColor.WHITE + matchId
        );

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Arena: " + ChatColor.WHITE + arena);
        lore.add(ChatColor.GRAY + "Date:  " + ChatColor.WHITE + formatUnix(startUnix));
        if (duration > 0) {
            lore.add(ChatColor.GRAY + "Length: " + ChatColor.WHITE + formatDuration((int) duration));
        }
        lore.add(ChatColor.GRAY + "Players: " + ChatColor.WHITE + participantCount);
        lore.add("");

        // Viewer stats
        lore.add(statLine("Kills",        yml.getLong(viewerBase + ".diff.bedwars:kills",         0)));
        lore.add(statLine("Final Kills",  yml.getLong(viewerBase + ".diff.bedwars:final_kills",   0)));
        lore.add(statLine("Final Deaths", yml.getLong(viewerBase + ".diff.bedwars:final_deaths",  0)));
        lore.add(statLine("Beds",         yml.getLong(viewerBase + ".diff.bedwars:beds_destroyed",0)));

        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Click to view match details");

        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    // -----------------------------------------------------------------------
    // Events
    // -----------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Inventory top = e.getView().getTopInventory();
        InventoryHolder h = top.getHolder();
        if (!(h instanceof HistoryHolder) && !(h instanceof AllHolder)) return;

        e.setCancelled(true);

        int raw = e.getRawSlot();
        if (raw < 0 || raw >= SIZE) return;

        if (raw == SLOT_BACK) { p.closeInventory(); return; }
        if (raw == SLOT_PREV) {
            if (h instanceof HistoryHolder hh) openHistory(p, hh.targetUuid, hh.page - 1);
            else openAll(p, ((AllHolder) h).page - 1);
            return;
        }
        if (raw == SLOT_NEXT) {
            if (h instanceof HistoryHolder hh) openHistory(p, hh.targetUuid, hh.page + 1);
            else openAll(p, ((AllHolder) h).page + 1);
            return;
        }

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
        if (!(top.getHolder() instanceof HistoryHolder) && !(top.getHolder() instanceof AllHolder)) return;
        for (int raw : e.getRawSlots()) {
            if (raw < SIZE) { e.setCancelled(true); return; }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) { /* no persistent state */ }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ItemStack navPane() {
        ItemStack it = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(" ");
        it.setItemMeta(m);
        return it;
    }

    private static ItemStack navButton(Material mat, String name, String lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(name);
        if (lore != null) meta.setLore(List.of(lore));
        it.setItemMeta(meta);
        return it;
    }

    private static String statLine(String name, long value) {
        return ChatColor.GRAY + "• " + ChatColor.WHITE + name + ": " + ChatColor.AQUA + value;
    }

    private static String formatUnix(long unix) {
        if (unix <= 0) return "—";
        return new SimpleDateFormat("MM/dd/yy h:mm a").format(new Date(unix * 1000L));
    }

    private static String formatDuration(int totalSec) {
        int m = totalSec / 60, s = totalSec % 60;
        return m + "m " + s + "s";
    }

    public static ChatColor teamColor(String team) {
        if (team == null) return ChatColor.GRAY;
        return switch (team.toUpperCase(Locale.ROOT)) {
            case "RED"              -> ChatColor.RED;
            case "BLUE"             -> ChatColor.BLUE;
            case "GREEN", "LIME"    -> ChatColor.GREEN;
            case "YELLOW"           -> ChatColor.YELLOW;
            case "PINK"             -> ChatColor.LIGHT_PURPLE;
            case "AQUA", "CYAN"     -> ChatColor.AQUA;
            case "WHITE"            -> ChatColor.WHITE;
            case "GRAY", "GREY"     -> ChatColor.DARK_GRAY;
            case "ORANGE"           -> ChatColor.GOLD;
            case "PURPLE"           -> ChatColor.DARK_PURPLE;
            default                 -> ChatColor.GRAY;
        };
    }

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

    // -----------------------------------------------------------------------
    // Holders
    // -----------------------------------------------------------------------

    private static final class HistoryHolder implements InventoryHolder {
        final UUID viewerUuid;
        final UUID targetUuid;
        final int page;

        HistoryHolder(UUID viewerUuid, UUID targetUuid, int page) {
            this.viewerUuid = viewerUuid;
            this.targetUuid = targetUuid;
            this.page       = page;
        }

        @Override public Inventory getInventory() { return null; }
    }

    private static final class AllHolder implements InventoryHolder {
        final UUID viewerUuid;
        final int page;

        AllHolder(UUID viewerUuid, int page) {
            this.viewerUuid = viewerUuid;
            this.page       = page;
        }

        @Override public Inventory getInventory() { return null; }
    }
}
