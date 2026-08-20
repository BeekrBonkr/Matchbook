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

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class MatchesDetailsGui implements Listener {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    private static final int HEADER_SLOT    = 4;
    private static final int PLAYER_START_SLOT = 9; // row 1 is reserved for the header; players start on row 2
    private static final int PAGE_SLOTS = 36; // rows 1-4
    private static final int SLOT_PREV      = 45;
    private static final int SLOT_BACK      = 49;
    private static final int SLOT_SPECTATORS = 47;
    private static final int SLOT_EVENTS    = 51;
    private static final int SLOT_NEXT      = 53;

    private final MatchbookPlugin plugin;

    public MatchesDetailsGui(MatchbookPlugin plugin) {
        this.plugin = plugin;
    }

    public void openDetails(Player viewer, String matchId, int page) {
        openDetails(viewer, matchId, page, null);
    }

    public void openDetails(Player viewer, String matchId, int page, ReturnTarget origin) {
        UUID viewerUuid = viewer.getUniqueId();

        // Repo reads (file/JDBC) can block for a while under MySQL storage; keep them off the main thread
        // and only hop back to build/open the inventory once everything is ready.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            YamlConfiguration yml = plugin.getRepo().loadMatchYaml(matchId);
            if (yml == null) {
                File matchFile = findMatchFileById(matchId);
                if (matchFile != null) yml = YamlConfiguration.loadConfiguration(matchFile);
            }
            final YamlConfiguration ymlFinal = yml;

            if (ymlFinal == null) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!viewer.isOnline()) return;
                    Inventory inv = Bukkit.createInventory(new DetailsHolder(viewerUuid, matchId, 0, origin), SIZE,
                            ChatColor.DARK_GRAY + "Match Details");
                    buildNavBar(inv, null, 0, 0, origin);
                    inv.setItem(22, errorItem("Match not found", matchId));
                    viewer.openInventory(inv);
                });
                return;
            }

            List<String> participants = ymlFinal.getStringList("match.participants");
            participants.sort((a, b) -> comparePlayers(ymlFinal, a, b));

            int maxPage = participants.isEmpty() ? 0 : Math.max(0, (participants.size() - 1) / PAGE_SLOTS);
            int p = Math.max(0, Math.min(page, maxPage));

            int start = p * PAGE_SLOTS;
            int end   = Math.min(participants.size(), start + PAGE_SLOTS);
            List<ItemStack> playerItems = new ArrayList<>();
            for (int i = start; i < end; i++) {
                playerItems.add(buildPlayerItem(ymlFinal, participants.get(i), viewerUuid));
            }
            ItemStack header = buildHeaderItem(ymlFinal, plugin.getTimezones().zoneFor(viewerUuid));

            int maxPageFinal = maxPage;
            int pFinal = p;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewer.isOnline()) return;

                String title = ChatColor.DARK_GRAY + "Match Details "
                        + ChatColor.GRAY + "(" + matchId + ") "
                        + ChatColor.DARK_GRAY + "• "
                        + ChatColor.GRAY + (pFinal + 1) + "/" + (maxPageFinal + 1);

                Inventory inv = Bukkit.createInventory(new DetailsHolder(viewerUuid, matchId, pFinal, origin), SIZE, title);

                buildNavBar(inv, ymlFinal, pFinal, maxPageFinal, origin);
                inv.setItem(HEADER_SLOT, header);

                int slot = PLAYER_START_SLOT;
                for (ItemStack it : playerItems) inv.setItem(slot++, it);

                viewer.openInventory(inv);
            });
        });
    }

    // -----------------------------------------------------------------------
    // Nav bar builder (shared across all pages)
    // -----------------------------------------------------------------------

    private void buildNavBar(Inventory inv, YamlConfiguration yml, int page, int maxPage, ReturnTarget origin) {
        // Fill bottom row with gray panes
        for (int i = 45; i < 54; i++) inv.setItem(i, navPane());

        String backLore;
        if (origin instanceof ReturnTarget.AllMatches) backLore = "Return to all matches";
        else if (origin instanceof ReturnTarget.History) backLore = "Return to match history";
        else backLore = "Return to your matches";

        inv.setItem(SLOT_PREV, navButton(Material.SPECTRAL_ARROW, ChatColor.YELLOW + "Previous Page",
                page > 0 ? ChatColor.GRAY + "Go to page " + page : ChatColor.DARK_GRAY + "Already on first page"));
        inv.setItem(SLOT_BACK, navButton(Material.DARK_OAK_DOOR, ChatColor.RED + "Back",
                ChatColor.GRAY + backLore));
        inv.setItem(SLOT_NEXT, navButton(Material.SPECTRAL_ARROW, ChatColor.YELLOW + "Next Page",
                page < maxPage ? ChatColor.GRAY + "Go to page " + (page + 2) : ChatColor.DARK_GRAY + "Already on last page"));

        // Spectators info (slot 47)
        inv.setItem(SLOT_SPECTATORS, buildSpectatorsItem(yml));

        // Event log button (slot 51)
        boolean hasEvents = yml != null && yml.contains("events") && !yml.getList("events", List.of()).isEmpty();
        inv.setItem(SLOT_EVENTS, buildEventLogButton(hasEvents));
    }

    // -----------------------------------------------------------------------
    // Item builders
    // -----------------------------------------------------------------------

    private ItemStack buildHeaderItem(YamlConfiguration yml, ZoneId zone) {
        String matchId  = yml.getString("match.match_id", "");
        String arena    = yml.getString("match.arena", "");
        String result   = yml.getString("match.result", "");
        long startUnix  = yml.getLong("match.start_unix", 0L);
        long endUnix    = yml.getLong("match.end_unix", 0L);
        long duration   = endUnix > startUnix ? endUnix - startUnix : 0L;

        ChatColor resultColor = resultColor(result);

        ItemStack it = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Match " + ChatColor.RESET + ChatColor.WHITE + matchId);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Arena: " + ChatColor.WHITE + arena);
        lore.add(ChatColor.GRAY + "Result: " + resultColor + result);
        lore.add(ChatColor.GRAY + "Start:  " + ChatColor.WHITE + formatUnix(startUnix, zone));
        lore.add(ChatColor.GRAY + "End:    " + ChatColor.WHITE + formatUnix(endUnix, zone));
        if (duration > 0) {
            lore.add(ChatColor.GRAY + "Length: " + ChatColor.WHITE + formatDuration((int) duration));
        }
        lore.add("");
        lore.add(ChatColor.DARK_GRAY + "Players listed below, colored by team");

        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack buildSpectatorsItem(YamlConfiguration yml) {
        List<String> specs = yml != null ? yml.getStringList("match.spectators") : Collections.emptyList();
        int count = specs != null ? specs.size() : 0;

        ItemStack it = new ItemStack(Material.SPYGLASS);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "Spectators " + ChatColor.DARK_GRAY + "• " + ChatColor.WHITE + count);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + "Players watching (not counted in stats)");
        lore.add("");

        if (count <= 0) {
            lore.add(ChatColor.DARK_GRAY + "None");
        } else {
            int shown = 0;
            for (String uuidStr : specs) {
                if (uuidStr == null || uuidStr.isBlank()) continue;
                String name = yml.getString("spectators." + uuidStr + ".username", uuidStr);
                lore.add(ChatColor.GRAY + "• " + ChatColor.WHITE + name);
                if (++shown >= 12) break;
            }
            if (count > shown) lore.add(ChatColor.DARK_GRAY + "+" + (count - shown) + " more...");
        }

        meta.setLore(lore);
        it.setItemMeta(meta);
        return it;
    }

    private static ItemStack buildEventLogButton(boolean hasEvents) {
        ItemStack it = new ItemStack(hasEvents ? Material.WRITABLE_BOOK : Material.BOOK);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.AQUA + "" + ChatColor.BOLD + "Event Log");
        if (hasEvents) {
            meta.setLore(List.of(
                    ChatColor.GRAY + "View the full match timeline:",
                    ChatColor.GRAY + "joins, deaths, kills, bed breaks...",
                    "",
                    ChatColor.YELLOW + "Click to open"
            ));
        } else {
            meta.setLore(List.of(
                    ChatColor.DARK_GRAY + "No events recorded for this match.",
                    ChatColor.DARK_GRAY + "(Saved before event logging was added)"
            ));
        }
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack buildPlayerItem(YamlConfiguration yml, String uuidStr, UUID viewerUuid) {
        String base     = "players." + uuidStr;
        String username = yml.getString(base + ".username", uuidStr);
        String team     = yml.getString(base + ".team", "");
        String dyeColor = yml.getString(base + ".team_color", null);

        Material wool = MatchesGui.teamWool(team, dyeColor);
        ItemStack it = new ItemStack(wool);
        ItemMeta meta = it.getItemMeta();

        boolean isYou = uuidStr.equalsIgnoreCase(viewerUuid.toString());
        ChatColor tc  = MatchesGui.teamColor(team, dyeColor);

        meta.setDisplayName(
                (isYou ? ChatColor.GOLD + "★ " : "")
                + ChatColor.WHITE + username
                + ChatColor.DARK_GRAY + " • " + tc + (team.isBlank() ? "?" : team)
        );

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.DARK_GRAY + "UUID: " + ChatColor.DARK_GRAY + uuidStr);
        lore.add("");

        var diffSection = yml.getConfigurationSection(base + ".diff");
        Map<String, Long> diff = new LinkedHashMap<>();
        if (diffSection != null) {
            for (String k : diffSection.getKeys(false)) {
                diff.put(k, yml.getLong(base + ".diff." + k, 0L));
            }
        }

        String placement = resolvePlacement(diff);
        if (placement != null) {
            lore.add(ChatColor.GRAY + "• " + ChatColor.WHITE + "Placement: " + ChatColor.GOLD + placement);
        } else if (diff.getOrDefault("matchbook:ties", 0L) > 0L) {
            // Tied teams intentionally have no matchbook:*_place key (a tie isn't a definitive
            // placement) — show 0 rather than silently omitting the line.
            lore.add(ChatColor.GRAY + "• " + ChatColor.WHITE + "Placement: " + ChatColor.GOLD + "0");
        }

        List<String> preferred = List.of(
                "bedwars:kills", "bedwars:deaths",
                "bedwars:final_kills", "bedwars:final_deaths",
                "bedwars:beds_destroyed", "bedwars:wins", "bedwars:loses"
        );
        Set<String> shown = new HashSet<>();
        for (String key : preferred) {
            if (!diff.containsKey(key)) continue;
            lore.add(statLine(friendlyName(key), diff.getOrDefault(key, 0L)));
            shown.add(key);
        }

        List<String> remaining = new ArrayList<>(diff.keySet());
        remaining.removeAll(shown);
        remaining.removeIf(k -> k != null && k.startsWith("matchbook:") && k.endsWith("_place"));
        remaining.sort(String.CASE_INSENSITIVE_ORDER);
        for (String key : remaining) {
            lore.add(statLine(friendlyName(key), diff.getOrDefault(key, 0L)));
        }

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
        if (!(e.getView().getTopInventory().getHolder() instanceof DetailsHolder holder)) return;

        e.setCancelled(true);

        int raw = e.getRawSlot();
        if (raw < 0 || raw >= SIZE) return;

        if (raw == SLOT_BACK) {
            if (holder.origin instanceof ReturnTarget.AllMatches all) {
                plugin.getMatchesGui().openAll(p, all.page());
            } else if (holder.origin instanceof ReturnTarget.History hist) {
                plugin.getMatchesGui().openHistory(p, hist.targetUuid(), hist.page());
            } else {
                // Opened directly (e.g. /matchbook view) — fall back to the viewer's own history.
                plugin.getMatchesGui().openHistory(p, p.getUniqueId(), 0);
            }
            return;
        }
        if (raw == SLOT_PREV) {
            openDetails(p, holder.matchId, holder.page - 1, holder.origin);
            return;
        }
        if (raw == SLOT_NEXT) {
            openDetails(p, holder.matchId, holder.page + 1, holder.origin);
            return;
        }
        if (raw == SLOT_EVENTS) {
            EventLogGui eventGui = plugin.getEventLogGui();
            if (eventGui != null) eventGui.openEvents(p, holder.matchId, 0, holder.page, holder.origin);
            return;
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof DetailsHolder)) return;
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

    private static ItemStack errorItem(String title, String detail) {
        ItemStack it = new ItemStack(Material.BARRIER);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(ChatColor.RED + title);
        meta.setLore(List.of(ChatColor.GRAY + detail));
        it.setItemMeta(meta);
        return it;
    }

    private static String statLine(String name, long value) {
        return ChatColor.GRAY + "• " + ChatColor.WHITE + name + ": " + ChatColor.AQUA + value;
    }

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM/dd/yy h:mm a", Locale.US);

    private static String formatUnix(long unix, ZoneId zone) {
        if (unix <= 0) return "—";
        return DATE_FMT.withZone(zone).format(Instant.ofEpochSecond(unix));
    }

    private static String formatDuration(int totalSec) {
        int m = totalSec / 60, s = totalSec % 60;
        return m + "m " + s + "s";
    }

    private static ChatColor resultColor(String result) {
        if (result == null) return ChatColor.GRAY;
        String r = result.toUpperCase(Locale.ROOT);
        if (r.startsWith("WIN")) return ChatColor.GREEN;
        if (r.equals("TIE"))    return ChatColor.YELLOW;
        if (r.equals("ABORTED") || r.equals("UNKNOWN")) return ChatColor.DARK_GRAY;
        return ChatColor.GRAY;
    }

    private static String resolvePlacement(Map<String, Long> diff) {
        if (diff == null) return null;
        for (var e : diff.entrySet()) {
            String k = e.getKey();
            if (k == null || !k.startsWith("matchbook:") || !k.endsWith("_place")) continue;
            if (e.getValue() == null || e.getValue() <= 0L) continue;
            String short_ = k.substring("matchbook:".length(), k.length() - "_place".length());
            return short_;
        }
        return null;
    }

    private static String friendlyName(String key) {
        if (key == null) return "";
        return switch (key.toLowerCase(Locale.ROOT)) {
            case "bedwars:kills"         -> "Kills";
            case "bedwars:deaths"        -> "Deaths";
            case "bedwars:final_kills"   -> "Final Kills";
            case "bedwars:final_deaths"  -> "Final Deaths";
            case "bedwars:beds_destroyed"-> "Beds Destroyed";
            case "bedwars:wins"          -> "Wins";
            case "bedwars:loses"         -> "Losses";
            default -> {
                int idx = key.indexOf(':');
                String s = idx >= 0 ? key.substring(idx + 1) : key;
                yield titleCase(s.replace('_', ' '));
            }
        };
    }

    private static String titleCase(String s) {
        if (s == null || s.isBlank()) return "";
        String[] parts = s.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) sb.append(part.substring(1));
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Sorting — group players by team (fixed color order), then by performance within team.
    // -----------------------------------------------------------------------

    private static int teamOrderIndex(String team) {
        if (team == null || team.isBlank()) return MatchesGui.FIXED_TEAM_ORDER.size();
        int idx = MatchesGui.FIXED_TEAM_ORDER.indexOf(team.toUpperCase(Locale.ROOT));
        return idx < 0 ? MatchesGui.FIXED_TEAM_ORDER.size() : idx;
    }

    private static int comparePlayers(YamlConfiguration yml, String a, String b) {
        String teamA = yml.getString("players." + a + ".team", "");
        String teamB = yml.getString("players." + b + ".team", "");

        int orderA = teamOrderIndex(teamA);
        int orderB = teamOrderIndex(teamB);
        if (orderA != orderB) return Integer.compare(orderA, orderB);

        long aFK = yml.getLong("players." + a + ".diff.bedwars:final_kills", 0L);
        long bFK = yml.getLong("players." + b + ".diff.bedwars:final_kills", 0L);
        if (aFK != bFK) return Long.compare(bFK, aFK);

        long aK = yml.getLong("players." + a + ".diff.bedwars:kills", 0L);
        long bK = yml.getLong("players." + b + ".diff.bedwars:kills", 0L);
        if (aK != bK) return Long.compare(bK, aK);

        return a.compareToIgnoreCase(b);
    }

    // -----------------------------------------------------------------------
    // File lookup (legacy fallback)
    // -----------------------------------------------------------------------

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
                if (matchId.equalsIgnoreCase(y.getString("match.match_id", ""))) return f;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Holder
    // -----------------------------------------------------------------------

    static final class DetailsHolder implements InventoryHolder {
        final UUID viewerUuid;
        final String matchId;
        final int page;
        final ReturnTarget origin;

        DetailsHolder(UUID viewerUuid, String matchId, int page, ReturnTarget origin) {
            this.viewerUuid = viewerUuid;
            this.matchId    = matchId;
            this.page       = page;
            this.origin     = origin;
        }

        @Override public Inventory getInventory() { return null; }
    }
}
