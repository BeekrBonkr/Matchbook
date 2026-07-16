package com.slg.matchbook.service;

import com.google.gson.JsonParser;
import com.slg.matchbook.MatchbookPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Checks the project's GitHub Releases for a newer Matchbook version and alerts operators in
 * chat when one is found. Fully disable-able via config (see update_check.enabled).
 */
public final class UpdateChecker {

    private static final String RELEASES_API = "https://api.github.com/repos/BeekrBonkr/Matchbook-Releases/releases/latest";
    private static final String RELEASES_PAGE = "https://github.com/BeekrBonkr/Matchbook-Releases/releases/latest";

    private final MatchbookPlugin plugin;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Set once a check finds a newer release; cleared if a later check finds we're up to date. */
    private final AtomicReference<UpdateInfo> available = new AtomicReference<>();

    public record UpdateInfo(String version, String url) {}

    public UpdateChecker(MatchbookPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return plugin.getMatchbookConfig().raw().getBoolean("update_check.enabled", true);
    }

    /**
     * Runs an immediate check, then re-checks periodically. Safe to call from onEnable(); does
     * all network I/O off the main thread.
     */
    public void start() {
        if (!enabled()) return;

        long intervalHours = Math.max(1L, plugin.getMatchbookConfig().raw().getLong("update_check.interval_hours", 12L));
        long intervalTicks = intervalHours * 60L * 60L * 20L;

        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::runCheck, 100L, intervalTicks);
    }

    private void runCheck() {
        if (!enabled()) return;
        try {
            UpdateInfo info = fetchLatest();
            if (info == null) return;

            String running = plugin.getDescription().getVersion();
            if (isNewer(info.version(), running)) {
                boolean wasAlreadyKnown = available.getAndSet(info) != null;
                if (!wasAlreadyKnown) {
                    Bukkit.getScheduler().runTask(plugin, () -> announce(info, running));
                }
            } else {
                available.set(null);
            }
        } catch (Exception e) {
            plugin.getLogger().fine("Matchbook: update check failed: " + e.getMessage());
        }
    }

    private UpdateInfo fetchLatest() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(RELEASES_API))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Matchbook-UpdateChecker")
                .GET()
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) return null;

        var obj = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!obj.has("tag_name") || obj.get("tag_name").isJsonNull()) return null;

        String tag = obj.get("tag_name").getAsString().trim();
        if (tag.isEmpty()) return null;

        String url = (obj.has("html_url") && !obj.get("html_url").isJsonNull())
                ? obj.get("html_url").getAsString()
                : RELEASES_PAGE;

        return new UpdateInfo(stripLeadingV(tag), url);
    }

    private static String stripLeadingV(String v) {
        return (v.length() > 1 && (v.charAt(0) == 'v' || v.charAt(0) == 'V')) ? v.substring(1) : v;
    }

    /** True if candidate is a strictly newer semantic version than running. */
    static boolean isNewer(String candidate, String running) {
        int[] a = parseVersion(candidate);
        int[] b = parseVersion(running);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int av = i < a.length ? a[i] : 0;
            int bv = i < b.length ? b[i] : 0;
            if (av != bv) return av > bv;
        }
        return false;
    }

    private static int[] parseVersion(String v) {
        if (v == null) return new int[0];
        String cleaned = v.trim();
        int dash = cleaned.indexOf('-');
        if (dash >= 0) cleaned = cleaned.substring(0, dash); // drop pre-release/build suffix
        String[] parts = cleaned.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String digits = parts[i].replaceAll("[^0-9]", "");
            out[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
        }
        return out;
    }

    private void announce(UpdateInfo info, String running) {
        plugin.getLogger().warning("A new version is available: " + info.version()
                + " (running " + running + "). " + info.url());

        String msg = ChatColor.GOLD + "[Matchbook] " + ChatColor.YELLOW + "A new version is available: "
                + ChatColor.WHITE + info.version() + ChatColor.YELLOW + " (running " + running + "). "
                + ChatColor.GRAY + info.url();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp()) p.sendMessage(msg);
        }
    }

    /** Catches up an operator who logs in after the check already found an update. */
    public void notifyIfOp(Player player) {
        if (player == null || !player.isOp() || !enabled()) return;

        UpdateInfo info = available.get();
        if (info == null) return;

        String running = plugin.getDescription().getVersion();
        player.sendMessage(ChatColor.GOLD + "[Matchbook] " + ChatColor.YELLOW + "A new version is available: "
                + ChatColor.WHITE + info.version() + ChatColor.YELLOW + " (running " + running + "). "
                + ChatColor.GRAY + info.url());
    }
}
