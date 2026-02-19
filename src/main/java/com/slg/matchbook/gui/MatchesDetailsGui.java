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

public final class MatchesDetailsGui implements Listener {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    private static final int PAGE_SLOTS = 45; // 0..44
    private static final int SLOT_PREV = 45;
    private static final int SLOT_BACK = 49;
    private static final int SLOT_NEXT = 53;

    private final MatchbookPlugin plugin;
    private final NamespacedKey KEY_MATCH_ID;

    private final Map<UUID, DetailsState> open = new HashMap<>();

    public MatchesDetailsGui(MatchbookPlugin plugin) {
        this.plugin = plugin;
        this.KEY_MATCH_ID = new NamespacedKey(plugin, "match_id");
    }

    public void openDetails(Player viewer, String matchId, int page) {
        YamlConfiguration yml = plugin.getRepo().loadMatchYaml(matchId);
        // Legacy fallback: if repo is YAML but match not indexed correctly
        if (yml == null) {
            File matchFile = findMatchFileById(matchId);
            if (matchFile != null) yml = YamlConfiguration.loadConfiguration(matchFile);
        }

        Inventory inv = Bukkit.createInventory(new DetailsHolder(viewer.getUniqueId(), matchId, page), SIZE,
                ChatColor.DARK_GRAY + "Match Details " + ChatColor.GRAY + "(" + matchId + ")");

        // reserve bottom row
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, pane(Material.BLACK_STAINED_GLASS_PANE, " "));
        }

        inv.setItem(SLOT_PREV, button(Material.ARROW, ChatColor.YELLOW + "Previous Page"));
        inv.setItem(SLOT_NEXT, button(Material.ARROW, ChatColor.YELLOW + "Next Page"));
        inv.setItem(SLOT_BACK, button(Material.ARROW, ChatColor.YELLOW + "Back"));

        if (yml == null) {
            inv.setItem(22, errorItem("Match not found", matchId));
            open.put(viewer.getUniqueId(), new DetailsState(matchId, page));
            viewer.openInventory(inv);
            return;
        }

        // Header item (center top-ish)
        inv.setItem(4, buildHeaderItem(yml));

        // Build player list (all participants)
        List<String> participants = yml.getStringList("match.participants");

        // Sort: winning team first, then final kills desc, then kills desc
        final YamlConfiguration ymlFinal = yml;
        participants.sort((a, b) -> comparePlayers(ymlFinal, a, b));

        int maxPage = Math.max(0, (participants.size() - 1) / PAGE_SLOTS);
        int p = Math.max(0, Math.min(page, maxPage));

        // Update title with page info (re-open inventory with new title)
        inv = Bukkit.createInventory(new DetailsHolder(viewer.getUniqueId(), matchId, p), SIZE,
                ChatColor.DARK_GRAY + "Match Details " + ChatColor.GRAY + "(" + matchId + ") "
                        + ChatColor.DARK_GRAY + "• "
                        + ChatColor.GRAY + (p + 1) + "/" + (maxPage + 1));

        // bottom row again
        for (int i = 45; i < 54; i++) inv.setItem(i, pane(Material.BLACK_STAINED_GLASS_PANE, " "));
        inv.setItem(SLOT_PREV, button(Material.ARROW, ChatColor.YELLOW + "Previous Page"));
        inv.setItem(SLOT_NEXT, button(Material.ARROW, ChatColor.YELLOW + "Next Page"));
        inv.setItem(SLOT_BACK, button(Material.ARROW, ChatColor.YELLOW + "Back"));
        inv.setItem(4, buildHeaderItem(yml));

        int start = p * PAGE_SLOTS;
        int end = Math.min(participants.size(), start + PAGE_SLOTS);

        int slot = 0;
        for (int i = start; i < end; i++) {
            String uuidStr = participants.get(i);
            inv.setItem(slot++, buildPlayerItem(yml, uuidStr, viewer.getUniqueId()));
        }

        open.put(viewer.getUniqueId(), new DetailsState(matchId, p));
        viewer.openInventory(inv);
    }

    /* =========================================================
       Item builders
       ========================================================= */

    private ItemStack buildHeaderItem(YamlConfiguration yml) {
        String matchId = yml.getString("match.match_id", "");
        String arena = yml.getString("match.arena", "");
        String result = yml.getString("match.result", "");
        long startUnix = yml.getLong("match.start_unix", 0L);
        long endUnix = yml.getLong("match.end_unix", 0L);

        ItemStack it = new ItemStack(Material.BOOK);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Match " + ChatColor.WHITE + matchId);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Arena: " + ChatColor.WHITE + arena);
        lore.add(ChatColor.GRAY + "Result: " + ChatColor.WHITE + result);
        lore.add(ChatColor.GRAY + "Start: " + ChatColor.WHITE + formatUnix(startUnix));
        lore.add(ChatColor.GRAY + "End: " + ChatColor.WHITE + formatUnix(endUnix));
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Players listed below (colored by team)");

        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack buildPlayerItem(YamlConfiguration yml, String uuidStr, UUID viewerUuid) {
        String base = "players." + uuidStr;

        String username = yml.getString(base + ".username", uuidStr);
        String team = yml.getString(base + ".team", "");

        Material wool = teamWool(team);

        ItemStack it = new ItemStack(wool);
        ItemMeta meta = it.getItemMeta();

        boolean isYou = uuidStr.equalsIgnoreCase(viewerUuid.toString());
        meta.setDisplayName((isYou ? ChatColor.GOLD + "★ " : "") + ChatColor.WHITE + username
                + ChatColor.DARK_GRAY + " • " + MatchesGui.teamColor(team) + (team.isBlank() ? "?" : team));

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "UUID: " + ChatColor.DARK_GRAY + uuidStr);
        lore.add("");

        // Dynamically show any recorded diff keys for this player.
        // This keeps GUI in sync with whatever Matchbook recorded (and whatever exporters can output).
        var diffSection = yml.getConfigurationSection(base + ".diff");
        Map<String, Long> diff = new LinkedHashMap<>();
        if (diffSection != null) {
            for (String k : diffSection.getKeys(false)) {
                diff.put(k, yml.getLong(base + ".diff." + k, 0L));
            }
        }

        // Special-case placement: collapse matchbook:*_place one-hot keys into a single friendly line.
        String placement = resolvePlacementFromDiff(diff);
        if (placement != null) {
            lore.add(ChatColor.GRAY + "• " + ChatColor.WHITE + "Placement: " + ChatColor.AQUA + placement);
        }

        // Preferred ordering for common keys
        List<String> preferred = List.of(
                "bedwars:kills",
                "bedwars:deaths",
                "bedwars:final_kills",
                "bedwars:final_deaths",
                "bedwars:beds_destroyed",
                "bedwars:wins",
                "bedwars:loses"
        );

        Set<String> shown = new HashSet<>();
        for (String key : preferred) {
            if (!diff.containsKey(key)) continue;
            lore.add(statLine(friendlyName(key), diff.getOrDefault(key, 0L)));
            shown.add(key);
        }

        // Show any remaining keys (sorted)
        List<String> remaining = new ArrayList<>(diff.keySet());
        remaining.removeAll(shown);
        // Don't show the one-hot placement keys twice.
        remaining.removeIf(k -> k != null && k.startsWith("matchbook:") && k.endsWith("_place"));
        remaining.sort(String.CASE_INSENSITIVE_ORDER);
        for (String key : remaining) {
            lore.add(statLine(friendlyName(key), diff.getOrDefault(key, 0L)));
        }

        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private static String resolvePlacementFromDiff(Map<String, Long> diff) {
        if (diff == null || diff.isEmpty()) return null;
        // Find a matchbook:*_place key with value > 0
        for (String k : diff.keySet()) {
            if (k == null) continue;
            if (!k.startsWith("matchbook:") || !k.endsWith("_place")) continue;
            long v = diff.getOrDefault(k, 0L);
            if (v <= 0L) continue;
            // key is matchbook:1st_place -> show "1st"
            String shortKey = k.substring("matchbook:".length());
            if (shortKey.endsWith("_place")) shortKey = shortKey.substring(0, shortKey.length() - "_place".length());
            return shortKey;
        }
        return null;
    }

    private static String friendlyName(String key) {
        if (key == null) return "";
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "bedwars:kills" -> "Kills";
            case "bedwars:deaths" -> "Deaths";
            case "bedwars:final_kills" -> "Final Kills";
            case "bedwars:final_deaths" -> "Final Deaths";
            case "bedwars:beds_destroyed" -> "Beds Destroyed";
            case "bedwars:wins" -> "Wins";
            case "bedwars:loses" -> "Losses";
            default -> {
                // Remove namespace when present
                int idx = key.indexOf(':');
                String s = idx >= 0 && idx + 1 < key.length() ? key.substring(idx + 1) : key;
                // Make it look nicer: underscores -> spaces
                yield titleCase(s.replace('_', ' '));
            }
        };
    }

    private static String titleCase(String s) {
        if (s == null || s.isBlank()) return "";
        String[] parts = s.trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(p.charAt(0)));
            if (p.length() > 1) out.append(p.substring(1));
        }
        return out.toString();
    }

    private static String statLine(String name, long value) {
        return ChatColor.GRAY + "• " + ChatColor.WHITE + name + ": " + ChatColor.AQUA + value;
    }

    private static Material teamWool(String team) {
        if (team == null) return Material.WHITE_WOOL;
        return switch (team.toUpperCase(Locale.ROOT)) {
            case "RED" -> Material.RED_WOOL;
            case "BLUE" -> Material.BLUE_WOOL;
            case "GREEN" -> Material.GREEN_WOOL;
            case "YELLOW" -> Material.YELLOW_WOOL;
            case "PINK" -> Material.PINK_WOOL;
            case "AQUA", "CYAN" -> Material.CYAN_WOOL;
            case "WHITE" -> Material.WHITE_WOOL;
            case "GRAY", "GREY" -> Material.GRAY_WOOL;
            case "ORANGE" -> Material.ORANGE_WOOL;
            case "PURPLE" -> Material.PURPLE_WOOL;
            default -> Material.WHITE_WOOL;
        };
    }

    /* =========================================================
       Sorting
       ========================================================= */

    private static int comparePlayers(YamlConfiguration yml, String a, String b) {
        String result = yml.getString("match.result", "");
        String winTeam = "";
        if (result != null && result.toUpperCase(Locale.ROOT).startsWith("WIN:")) {
            winTeam = result.substring("WIN:".length()).trim();
        }

        String teamA = yml.getString("players." + a + ".team", "");
        String teamB = yml.getString("players." + b + ".team", "");

        // winning team first
        boolean aWin = !winTeam.isBlank() && winTeam.equalsIgnoreCase(teamA);
        boolean bWin = !winTeam.isBlank() && winTeam.equalsIgnoreCase(teamB);
        if (aWin != bWin) return aWin ? -1 : 1;

        // then final kills desc
        long aFK = yml.getLong("players." + a + ".diff.bedwars:final_kills", 0L);
        long bFK = yml.getLong("players." + b + ".diff.bedwars:final_kills", 0L);
        if (aFK != bFK) return Long.compare(bFK, aFK);

        // then kills desc
        long aK = yml.getLong("players." + a + ".diff.bedwars:kills", 0L);
        long bK = yml.getLong("players." + b + ".diff.bedwars:kills", 0L);
        if (aK != bK) return Long.compare(bK, aK);

        return a.compareToIgnoreCase(b);
    }

    /* =========================================================
       File lookup
       ========================================================= */

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
       Events (lock + navigation)
       ========================================================= */

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof DetailsHolder holder)) return;

        e.setCancelled(true);

        int raw = e.getRawSlot();
        if (raw < 0 || raw >= SIZE) return;

        if (raw == SLOT_BACK) {
            // Back to history (self)
            plugin.getMatchesGui().openHistory(p, p.getUniqueId(), 0);
            return;
        }
        if (raw == SLOT_PREV) {
            openDetails(p, holder.matchId, holder.page - 1);
            return;
        }
        if (raw == SLOT_NEXT) {
            openDetails(p, holder.matchId, holder.page + 1);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof DetailsHolder)) return;

        for (int raw : e.getRawSlots()) {
            if (raw < SIZE) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        open.remove(e.getPlayer().getUniqueId());
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

    private static ItemStack errorItem(String title, String line) {
        ItemStack it = new ItemStack(Material.BARRIER);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.RED + title);
        meta.setLore(List.of(ChatColor.GRAY + line));
        it.setItemMeta(meta);
        return it;
    }

    private static String formatUnix(long unix) {
        if (unix <= 0) return "";
        return new SimpleDateFormat("MM/dd hh:mm a").format(new Date(unix * 1000L));
    }

    /* =========================================================
       Holder/state
       ========================================================= */

    private static final class DetailsState {
        final String matchId;
        final int page;
        DetailsState(String matchId, int page) { this.matchId = matchId; this.page = page; }
    }

    private static final class DetailsHolder implements InventoryHolder {
        final UUID viewerUuid;
        final String matchId;
        final int page;

        DetailsHolder(UUID viewerUuid, String matchId, int page) {
            this.viewerUuid = viewerUuid;
            this.matchId = matchId;
            this.page = page;
        }

        @Override public Inventory getInventory() { return null; }
    }
}
