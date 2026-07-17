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
        UUID viewerUuid = viewer.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> matchIds = plugin.getRepo().listMatchIdsForPlayer(targetUuid);

            int maxPage = matchIds.isEmpty() ? 0 : Math.max(0, (matchIds.size() - 1) / PAGE_SLOTS);
            int p = Math.max(0, Math.min(page, maxPage));

            int start = p * PAGE_SLOTS;
            int end   = Math.min(matchIds.size(), start + PAGE_SLOTS);
            List<ItemStack> items = new ArrayList<>();
            for (int i = start; i < end; i++) {
                items.add(buildMatchItem(viewerUuid, matchIds.get(i), true));
            }

            int maxPageFinal = maxPage;
            int pFinal = p;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;

                String title = ChatColor.DARK_GRAY + "Match History "
                        + ChatColor.DARK_GRAY + "• " + ChatColor.GRAY
                        + (pFinal + 1) + "/" + (maxPageFinal + 1);

                Inventory inv = Bukkit.createInventory(new HistoryHolder(viewerUuid, targetUuid, pFinal), SIZE, title);
                buildNavBar(inv, pFinal, maxPageFinal, true);

                int slot = 0;
                for (ItemStack it : items) inv.setItem(slot++, it);

                viewer.openInventory(inv);
            });
        });
    }

    public void openAll(Player viewer, int page) {
        UUID viewerUuid = viewer.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> matchIds = plugin.getRepo().listAllMatchIds();

            int maxPage = matchIds.isEmpty() ? 0 : Math.max(0, (matchIds.size() - 1) / PAGE_SLOTS);
            int p = Math.max(0, Math.min(page, maxPage));

            int start = p * PAGE_SLOTS;
            int end   = Math.min(matchIds.size(), start + PAGE_SLOTS);
            List<ItemStack> items = new ArrayList<>();
            for (int i = start; i < end; i++) {
                items.add(buildMatchItem(viewerUuid, matchIds.get(i), false));
            }

            int maxPageFinal = maxPage;
            int pFinal = p;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;

                String title = ChatColor.DARK_GRAY + "All Matches "
                        + ChatColor.DARK_GRAY + "• " + ChatColor.GRAY
                        + (pFinal + 1) + "/" + (maxPageFinal + 1);

                Inventory inv = Bukkit.createInventory(new AllHolder(viewerUuid, pFinal), SIZE, title);
                buildNavBar(inv, pFinal, maxPageFinal, false);

                int slot = 0;
                for (ItemStack it : items) inv.setItem(slot++, it);

                viewer.openInventory(inv);
            });
        });
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

    private ItemStack buildMatchItem(UUID viewerUuid, String matchEntry, boolean includeViewerStats) {
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
        String viewerDye  = yml.getString(viewerBase + ".team_color", null);

        String resultUpper = result == null ? "" : result.toUpperCase(Locale.ROOT);
        boolean isTie = resultUpper.equals("TIE");
        boolean isWin = resultUpper.startsWith("WIN:");

        String titleLine;
        Material mat;

        if (isWin) {
            String winTeam = result.substring("WIN:".length()).trim();
            String winDye = winTeam.equalsIgnoreCase(viewerTeam) ? viewerDye : findTeamDyeColor(yml, winTeam);
            String label = winTeam.isBlank() ? "?" : winTeam;
            titleLine = teamColor(winTeam, winDye) + "" + ChatColor.BOLD + label + " WIN";
            mat = teamWool(winTeam, winDye);
        } else if (isTie) {
            List<String> tiedTeams = yml.getStringList("match.tied_teams");
            if (tiedTeams.isEmpty()) {
                titleLine = ChatColor.YELLOW + "" + ChatColor.BOLD + "TIE";
            } else {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < tiedTeams.size(); i++) {
                    String t = tiedTeams.get(i);
                    String dye = t.equalsIgnoreCase(viewerTeam) ? viewerDye : findTeamDyeColor(yml, t);
                    if (i > 0) sb.append(ChatColor.GRAY).append(", ");
                    sb.append(teamColor(t, dye)).append(ChatColor.BOLD).append(t.isBlank() ? "?" : t);
                }
                sb.append(ChatColor.RESET).append(' ').append(ChatColor.YELLOW).append(ChatColor.BOLD).append("TIE");
                titleLine = sb.toString();
            }
            mat = Material.YELLOW_WOOL;
        } else {
            titleLine = ChatColor.AQUA + "" + ChatColor.BOLD + "PLAYED";
            mat = Material.PAPER;
        }

        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(KEY_MATCH_ID, PersistentDataType.STRING, matchId);

        meta.setDisplayName(
                titleLine
                + ChatColor.RESET + ChatColor.DARK_GRAY + " • "
                + ChatColor.WHITE + matchId
        );

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Arena: " + ChatColor.WHITE + arena);
        lore.add(ChatColor.GRAY + "Date:  " + ChatColor.WHITE + formatUnix(startUnix));
        if (duration > 0) {
            lore.add(ChatColor.GRAY + "Length: " + ChatColor.WHITE + formatDuration((int) duration));
        }
        lore.add(ChatColor.GRAY + "Players: " + ChatColor.WHITE + participantCount);
        if (!viewerTeam.isBlank()) {
            lore.add(ChatColor.GRAY + "Your team: " + teamColor(viewerTeam, viewerDye) + viewerTeam);
        }
        lore.add("");

        // Viewer stats — omitted in the "All Matches" browse view, where the viewer is looking
        // at matches they may not have even participated in, so "their" stats would be meaningless.
        if (includeViewerStats) {
            lore.add(statLine("Kills",        yml.getLong(viewerBase + ".diff.bedwars:kills",         0)));
            lore.add(statLine("Final Kills",  yml.getLong(viewerBase + ".diff.bedwars:final_kills",   0)));
            lore.add(statLine("Final Deaths", yml.getLong(viewerBase + ".diff.bedwars:final_deaths",  0)));
            lore.add(statLine("Beds",         yml.getLong(viewerBase + ".diff.bedwars:beds_destroyed",0)));
            lore.add("");
        }
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

    // -----------------------------------------------------------------------
    // Team color / wool resolution
    //
    // MBedwars' Team enum has 16 members (YELLOW, ORANGE, RED, BLUE, LIGHT_BLUE, CYAN, LIGHT_GREEN,
    // GREEN, PURPLE, PINK, WHITE, LIGHT_GRAY, GRAY, BROWN, BLACK, MAGENTA), each with its own
    // org.bukkit.DyeColor that an admin can reassign per-arena. Saved matches persist that DyeColor
    // (players.<uuid>.team_color) at save time via MatchYamlCodec; that's the source of truth here.
    // TEAM_NAME_TO_DYE below is only a fallback for matches saved before that field existed, or for
    // callers (event log) that only have the bare team name string.
    // -----------------------------------------------------------------------

    /** Fixed, stable display order for teams — mirrors MBedwars' Team enum declaration order. */
    public static final List<String> FIXED_TEAM_ORDER = List.of(
            "YELLOW", "ORANGE", "RED", "BLUE", "LIGHT_BLUE", "CYAN", "LIGHT_GREEN", "GREEN",
            "PURPLE", "PINK", "WHITE", "LIGHT_GRAY", "GRAY", "BROWN", "BLACK", "MAGENTA"
    );

    private static final Map<String, String> TEAM_NAME_TO_DYE = Map.ofEntries(
            Map.entry("YELLOW", "YELLOW"),
            Map.entry("ORANGE", "ORANGE"),
            Map.entry("RED", "RED"),
            Map.entry("BLUE", "BLUE"),
            Map.entry("LIGHT_BLUE", "LIGHT_BLUE"),
            Map.entry("AQUA", "LIGHT_BLUE"),
            Map.entry("CYAN", "CYAN"),
            Map.entry("LIGHT_GREEN", "LIME"),
            Map.entry("LIME", "LIME"),
            Map.entry("GREEN", "GREEN"),
            Map.entry("PURPLE", "PURPLE"),
            Map.entry("PINK", "PINK"),
            Map.entry("WHITE", "WHITE"),
            Map.entry("LIGHT_GRAY", "LIGHT_GRAY"),
            Map.entry("GREY", "GRAY"),
            Map.entry("GRAY", "GRAY"),
            Map.entry("BROWN", "BROWN"),
            Map.entry("BLACK", "BLACK"),
            Map.entry("MAGENTA", "MAGENTA")
    );

    private static final Map<String, ChatColor> DYE_TO_CHAT = Map.ofEntries(
            Map.entry("WHITE", ChatColor.WHITE),
            Map.entry("ORANGE", ChatColor.GOLD),
            Map.entry("MAGENTA", ChatColor.LIGHT_PURPLE),
            Map.entry("LIGHT_BLUE", ChatColor.AQUA),
            Map.entry("YELLOW", ChatColor.YELLOW),
            Map.entry("LIME", ChatColor.GREEN),
            Map.entry("PINK", ChatColor.LIGHT_PURPLE),
            Map.entry("GRAY", ChatColor.DARK_GRAY),
            Map.entry("LIGHT_GRAY", ChatColor.GRAY),
            Map.entry("CYAN", ChatColor.DARK_AQUA),
            Map.entry("PURPLE", ChatColor.DARK_PURPLE),
            Map.entry("BLUE", ChatColor.DARK_BLUE),
            Map.entry("BROWN", ChatColor.DARK_RED),
            Map.entry("GREEN", ChatColor.DARK_GREEN),
            Map.entry("RED", ChatColor.RED),
            Map.entry("BLACK", ChatColor.BLACK)
    );

    /** Resolves the DyeColor name to use for a team: prefers the persisted color, falls back to name. */
    public static String resolveDyeColorName(String team, String persistedDyeColor) {
        if (persistedDyeColor != null && !persistedDyeColor.isBlank()) {
            return persistedDyeColor.toUpperCase(Locale.ROOT);
        }
        if (team == null) return "WHITE";
        return TEAM_NAME_TO_DYE.getOrDefault(team.toUpperCase(Locale.ROOT), "WHITE");
    }

    public static ChatColor teamColor(String team) {
        return teamColor(team, null);
    }

    public static ChatColor teamColor(String team, String persistedDyeColor) {
        return DYE_TO_CHAT.getOrDefault(resolveDyeColorName(team, persistedDyeColor), ChatColor.GRAY);
    }

    public static Material teamWool(String team) {
        return teamWool(team, null);
    }

    public static Material teamWool(String team, String persistedDyeColor) {
        String dye = resolveDyeColorName(team, persistedDyeColor);
        try {
            return Material.valueOf(dye + "_WOOL");
        } catch (IllegalArgumentException ex) {
            return Material.WHITE_WOOL;
        }
    }

    /** Finds the persisted dye color for a team by scanning participants (used when the viewer isn't on that team). */
    private static String findTeamDyeColor(YamlConfiguration yml, String teamName) {
        if (yml == null || teamName == null || teamName.isBlank()) return null;
        for (String uuidStr : yml.getStringList("match.participants")) {
            String t = yml.getString("players." + uuidStr + ".team", "");
            if (teamName.equalsIgnoreCase(t)) {
                String dye = yml.getString("players." + uuidStr + ".team_color", null);
                if (dye != null) return dye;
            }
        }
        return null;
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
