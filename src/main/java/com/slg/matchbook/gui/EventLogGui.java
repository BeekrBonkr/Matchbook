package com.slg.matchbook.gui;

import com.slg.matchbook.MatchbookPlugin;
import com.slg.matchbook.io.MatchYamlCodec;
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

import java.text.SimpleDateFormat;
import java.util.*;

public final class EventLogGui implements Listener {

    private static final int ROWS       = 6;
    private static final int SIZE       = ROWS * 9;
    private static final int PAGE_SLOTS = 45; // rows 0-4
    private static final int SLOT_PREV  = 45;
    private static final int SLOT_BACK  = 49;
    private static final int SLOT_NEXT  = 53;

    private final MatchbookPlugin plugin;
    private final NamespacedKey KEY_MATCH_ID;

    public EventLogGui(MatchbookPlugin plugin) {
        this.plugin = plugin;
        this.KEY_MATCH_ID = new NamespacedKey(plugin, "match_id");
    }

    // -----------------------------------------------------------------------
    // Public entry point
    // -----------------------------------------------------------------------

    public void openEvents(Player viewer, String matchId, int page) {
        UUID viewerUuid = viewer.getUniqueId();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            YamlConfiguration yml = plugin.getRepo().loadMatchYaml(matchId);

            List<Map<String, Object>> events = MatchYamlCodec.readRawEvents(yml);
            long startUnix = yml != null ? yml.getLong("match.start_unix", 0L) : 0L;

            int maxPage = events.isEmpty() ? 0 : Math.max(0, (events.size() - 1) / PAGE_SLOTS);
            int p = Math.max(0, Math.min(page, maxPage));

            int start = p * PAGE_SLOTS;
            int end = Math.min(events.size(), start + PAGE_SLOTS);
            List<ItemStack> eventItems = new ArrayList<>();
            for (int i = start; i < end; i++) {
                eventItems.add(buildEventItem(events.get(i), startUnix));
            }

            boolean empty = events.isEmpty();
            int maxPageFinal = maxPage;
            int pFinal = p;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;

                String title = ChatColor.DARK_GRAY + "Event Log " + ChatColor.GRAY
                        + "(" + matchId + ") " + ChatColor.DARK_GRAY + "• "
                        + ChatColor.GRAY + (pFinal + 1) + "/" + (maxPageFinal + 1);

                Inventory inv = Bukkit.createInventory(new EventLogHolder(viewerUuid, matchId, pFinal), SIZE, title);

                // Bottom nav bar
                for (int i = 45; i < 54; i++) inv.setItem(i, navPane());
                inv.setItem(SLOT_PREV, navButton(Material.ARROW, ChatColor.YELLOW + "Previous Page",
                        ChatColor.GRAY + "Page " + pFinal + " of " + (maxPageFinal + 1)));
                inv.setItem(SLOT_BACK, navButton(Material.DARK_OAK_DOOR, ChatColor.RED + "Back to Match Details", null));
                inv.setItem(SLOT_NEXT, navButton(Material.ARROW, ChatColor.YELLOW + "Next Page",
                        ChatColor.GRAY + "Page " + (pFinal + 2) + " of " + (maxPageFinal + 1)));

                if (empty) {
                    ItemStack placeholder = new ItemStack(Material.STRUCTURE_VOID);
                    ItemMeta meta = placeholder.getItemMeta();
                    meta.setDisplayName(ChatColor.GRAY + "No events recorded");
                    meta.setLore(List.of(ChatColor.DARK_GRAY + "This match was saved before event logging was added."));
                    placeholder.setItemMeta(meta);
                    inv.setItem(22, placeholder);
                } else {
                    int slot = 0;
                    for (ItemStack it : eventItems) inv.setItem(slot++, it);
                }

                viewer.openInventory(inv);
            });
        });
    }

    // -----------------------------------------------------------------------
    // Event item builder
    // -----------------------------------------------------------------------

    private ItemStack buildEventItem(Map<String, Object> ev, long startUnix) {
        String type      = str(ev, "type", "UNKNOWN");
        long   timestamp = num(ev, "timestamp");
        int    offset    = (int) num(ev, "offset");

        String timeLabel = formatOffset(offset, timestamp, startUnix);

        return switch (type) {
            case "MATCH_START"  -> matchStartItem(timestamp);
            case "MATCH_END"    -> matchEndItem(timeLabel, offset);
            case "PLAYER_JOIN"  -> playerJoinItem(ev, timeLabel);
            case "PLAYER_LEAVE" -> playerLeaveItem(ev, timeLabel);
            case "PLAYER_DEATH" -> playerDeathItem(ev, timeLabel);
            case "PLAYER_KILL"  -> playerKillItem(ev, timeLabel);
            case "BED_BREAK"    -> bedBreakItem(ev, timeLabel);
            case "TEAM_ELIMINATE" -> teamEliminateItem(ev, timeLabel);
            case "SPECTATOR_JOIN"  -> spectatorJoinItem(ev, timeLabel);
            case "SPECTATOR_LEAVE" -> spectatorLeaveItem(ev, timeLabel);
            default -> unknownItem(type, timeLabel);
        };
    }

    private ItemStack matchStartItem(long timestamp) {
        ItemStack it = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "Match Start");
        meta.setLore(List.of(
                ChatColor.GRAY + "Time: " + ChatColor.WHITE + formatWall(timestamp),
                ChatColor.DARK_GRAY + "Start of the match"
        ));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack matchEndItem(String timeLabel, int offsetSeconds) {
        ItemStack it = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Match End");
        meta.setLore(List.of(
                ChatColor.GRAY + "At: " + ChatColor.WHITE + timeLabel,
                ChatColor.GRAY + "Duration: " + ChatColor.WHITE + formatDuration(offsetSeconds)
        ));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack playerJoinItem(Map<String, Object> ev, String timeLabel) {
        String name = str(ev, "player_name", "Unknown");
        String team = str(ev, "player_team", "");

        ItemStack it = new ItemStack(Material.LIME_DYE);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.GREEN + "+" + ChatColor.WHITE + " " + name + teamSuffix(team));
        meta.setLore(List.of(
                ChatColor.DARK_GRAY + "Player joined",
                ChatColor.GRAY + "At: " + ChatColor.WHITE + timeLabel,
                ChatColor.GRAY + "UUID: " + ChatColor.DARK_GRAY + str(ev, "player_uuid", "")
        ));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack playerLeaveItem(Map<String, Object> ev, String timeLabel) {
        String name = str(ev, "player_name", "Unknown");
        String team = str(ev, "player_team", "");
        boolean wasSpectating = bool(ev, "was_spectating");

        ItemStack it = new ItemStack(wasSpectating ? Material.GRAY_DYE : Material.RED_DYE);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName((wasSpectating ? ChatColor.DARK_GRAY : ChatColor.RED) + "−" + ChatColor.WHITE
                + " " + name + teamSuffix(team));
        meta.setLore(List.of(
                ChatColor.DARK_GRAY + (wasSpectating ? "Left while spectating (already eliminated)" : "Left the match"),
                ChatColor.GRAY + "At: " + ChatColor.WHITE + timeLabel
        ));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack playerDeathItem(Map<String, Object> ev, String timeLabel) {
        String name = str(ev, "player_name", "Unknown");
        String team = str(ev, "player_team", "");
        boolean fatal = bool(ev, "final");
        String cause = str(ev, "cause", "");
        // Since 0.7.0 attribution lives on the death row itself (older matches carry it in a
        // separate PLAYER_KILL row, rendered by playerKillItem).
        String killer = str(ev, "killer_name", "");
        String killerTeam = str(ev, "killer_team", "");
        String killCause = str(ev, "kill_cause", "");

        Material mat = fatal ? Material.WITHER_SKELETON_SKULL : Material.SKELETON_SKULL;
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();

        String display = (fatal ? ChatColor.DARK_RED : ChatColor.RED) + "☠ " + ChatColor.WHITE + name
                + teamSuffix(team) + ChatColor.DARK_GRAY + (fatal ? " was eliminated" : " died");
        if (!killer.isBlank()) {
            display += ChatColor.DARK_GRAY + " by " + ChatColor.GRAY + killer + teamSuffix(killerTeam);
        }
        meta.setDisplayName(display);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "At: " + ChatColor.WHITE + timeLabel);
        lore.add(ChatColor.GRAY + "Type: " + (fatal ? ChatColor.DARK_RED + "Final death" : ChatColor.GRAY + "Regular death"));
        if (!cause.isBlank()) lore.add(ChatColor.GRAY + "Cause: " + ChatColor.WHITE + formatCause(cause));
        if (!killer.isBlank()) {
            lore.add(ChatColor.GRAY + "Credited to: " + ChatColor.WHITE + killer
                    + (killCause.isBlank() ? "" : ChatColor.GRAY + " (" + formatCause(killCause) + ")"));
        }
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack playerKillItem(Map<String, Object> ev, String timeLabel) {
        String killer     = str(ev, "killer_name", "Unknown");
        String killerTeam = str(ev, "killer_team", "");
        String victim     = str(ev, "player_name", "");
        boolean finalKill = bool(ev, "final");
        String cause      = str(ev, "cause", "");

        ItemStack it = new ItemStack(finalKill ? Material.GOLDEN_SWORD : Material.IRON_SWORD);
        ItemMeta meta = it.getItemMeta();

        String display = ChatColor.YELLOW + "⚔ " + ChatColor.WHITE + killer + teamSuffix(killerTeam);
        if (!victim.isBlank()) display += ChatColor.DARK_GRAY + " → " + ChatColor.GRAY + victim;
        meta.setDisplayName(display);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "At: " + ChatColor.WHITE + timeLabel);
        lore.add(ChatColor.GRAY + "Type: " + (finalKill ? ChatColor.GOLD + "Final kill" : ChatColor.GRAY + "Regular kill"));
        if (!cause.isBlank()) lore.add(ChatColor.GRAY + "Cause: " + ChatColor.WHITE + formatCause(cause));
        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack bedBreakItem(Map<String, Object> ev, String timeLabel) {
        String breaker  = str(ev, "player_name", "Unknown");
        String bTeam    = str(ev, "player_team", "");
        String bedTeam  = str(ev, "bed_team", "");

        ItemStack it = new ItemStack(Material.TNT);
        ItemMeta meta = it.getItemMeta();

        String bedLabel = bedTeam.isBlank() ? "Unknown" : bedTeam;
        meta.setDisplayName(ChatColor.GOLD + "Bed Destroyed" + ChatColor.DARK_GRAY
                + " — " + teamColor(bedLabel) + bedLabel + ChatColor.DARK_GRAY + " team");

        meta.setLore(List.of(
                ChatColor.GRAY + "By: " + ChatColor.WHITE + breaker + teamSuffix(bTeam),
                ChatColor.GRAY + "At: " + ChatColor.WHITE + timeLabel
        ));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack teamEliminateItem(Map<String, Object> ev, String timeLabel) {
        String team = str(ev, "player_team", "");

        ItemStack it = new ItemStack(Material.BARRIER);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(teamColor(team) + "" + ChatColor.BOLD + team + ChatColor.RESET
                + ChatColor.RED + " team eliminated");
        meta.setLore(List.of(ChatColor.GRAY + "At: " + ChatColor.WHITE + timeLabel));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack spectatorJoinItem(Map<String, Object> ev, String timeLabel) {
        String name = str(ev, "player_name", "Unknown");
        ItemStack it = new ItemStack(Material.SPYGLASS);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.GRAY + "👁 " + ChatColor.WHITE + name
                + ChatColor.DARK_GRAY + " started spectating");
        meta.setLore(List.of(ChatColor.GRAY + "At: " + ChatColor.WHITE + timeLabel));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack spectatorLeaveItem(Map<String, Object> ev, String timeLabel) {
        String name = str(ev, "player_name", "Unknown");
        ItemStack it = new ItemStack(Material.ENDER_EYE);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.DARK_GRAY + "👁 " + ChatColor.GRAY + name
                + ChatColor.DARK_GRAY + " stopped spectating");
        meta.setLore(List.of(ChatColor.GRAY + "At: " + ChatColor.WHITE + timeLabel));
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack unknownItem(String type, String timeLabel) {
        ItemStack it = new ItemStack(Material.PAPER);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.GRAY + type);
        meta.setLore(List.of(ChatColor.DARK_GRAY + "At: " + timeLabel));
        it.setItemMeta(meta);
        return it;
    }

    // -----------------------------------------------------------------------
    // Click handling
    // -----------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (!(e.getView().getTopInventory().getHolder() instanceof EventLogHolder holder)) return;

        e.setCancelled(true);

        int raw = e.getRawSlot();
        if (raw < 0 || raw >= SIZE) return;

        if (raw == SLOT_BACK) {
            plugin.getDetailsGui().openDetails(p, holder.matchId, 0);
            return;
        }
        if (raw == SLOT_PREV) {
            openEvents(p, holder.matchId, holder.page - 1);
            return;
        }
        if (raw == SLOT_NEXT) {
            openEvents(p, holder.matchId, holder.page + 1);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof EventLogHolder)) return;
        for (int raw : e.getRawSlots()) {
            if (raw < SIZE) { e.setCancelled(true); return; }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) { /* no state to clear */ }

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

    private static String formatOffset(int offsetSeconds, long timestamp, long startUnix) {
        if (offsetSeconds < 0) return "lobby";
        return String.format("+%d:%02d", offsetSeconds / 60, offsetSeconds % 60);
    }

    private static String formatDuration(int totalSeconds) {
        if (totalSeconds <= 0) return "?";
        int m = totalSeconds / 60, s = totalSeconds % 60;
        return m + "m " + s + "s";
    }

    private static String formatWall(long unix) {
        if (unix <= 0) return "?";
        return new SimpleDateFormat("MMM d, h:mm a").format(new Date(unix * 1000L));
    }

    /** "ENTITY_ATTACK" -> "Entity Attack" */
    private static String formatCause(String raw) {
        if (raw == null || raw.isBlank()) return "Unknown";
        String[] parts = raw.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1).toLowerCase());
        }
        return sb.length() > 0 ? sb.toString() : "Unknown";
    }

    private static String teamSuffix(String team) {
        if (team == null || team.isBlank()) return "";
        return " " + ChatColor.DARK_GRAY + "[" + teamColor(team) + team + ChatColor.DARK_GRAY + "]";
    }

    private static ChatColor teamColor(String team) {
        return MatchesGui.teamColor(team);
    }

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v instanceof String s ? s : def;
    }

    private static long num(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.longValue();
        return 0L;
    }

    private static boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Boolean b) return b;
        return false;
    }

    // -----------------------------------------------------------------------
    // Holder
    // -----------------------------------------------------------------------

    static final class EventLogHolder implements InventoryHolder {
        final UUID viewerUuid;
        final String matchId;
        final int page;

        EventLogHolder(UUID viewerUuid, String matchId, int page) {
            this.viewerUuid = viewerUuid;
            this.matchId    = matchId;
            this.page       = page;
        }

        @Override public Inventory getInventory() { return null; }
    }
}
