package dev.asswa.skylite;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Session-scoped numbers: coins gained — announced in chat when the purse is topped up — and the
 * rare-drop journal (what, when, where). Everything lives in memory: a "session" lasts until the
 * game restarts or {@code /skylite session reset}.
 *
 * <p>All methods are called from the client thread: the sidebar poll, the chat events and
 * the drop screen.
 */
public final class SessionTracker {
    /** Case-insensitive substrings that mark a rare drop in Hypixel's chat. */
    private static final String[] DROP_MARKERS = {"RARE DROP!", "PET DROP!"};
    /** Journal cap: enough for a long session, bounded so nothing can grow forever. */
    public static final int MAX_DROPS = 200;
    private static final int MAX_DROP_CHARS = 120;
    /** At most one "coins gained" chat line per this many milliseconds. */
    private static final long MESSAGE_COOLDOWN_MS = 2000L;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** Start of the game session; the HUD "Session" line counts from here (v15). */
    private final long startedAt = System.currentTimeMillis();

    /** One journal entry: the pre-formatted time, the message text and the island/area. */
    public record Drop(String when, String text, String area, long at) {
    }

    private long gained;
    private long lastPurse = Long.MIN_VALUE;
    private boolean gainPending;
    private long lastMessageAt;
    private final List<Drop> drops = new ArrayList<>(); // newest first

    /**
     * One purse sample, as the raw sidebar string. Keeps the session total of gains and
     * announces it in chat, throttled so quick consecutive top-ups do not flood the chat.
     * {@code null} / unparseable values are ignored.
     */
    public void onPurse(String purseText) {
        double parsed = StatsState.parseAmount(purseText);
        if (Double.isNaN(parsed)) {
            return;
        }
        long purse = Math.round(parsed);
        long now = System.currentTimeMillis();
        if (lastPurse != Long.MIN_VALUE && purse > lastPurse) {
            gained += purse - lastPurse;
            gainPending = true;
        }
        lastPurse = purse;
        if (gainPending && now - lastMessageAt >= MESSAGE_COOLDOWN_MS) {
            gainPending = false; // anything swallowed by the throttle shows up in the next line
            lastMessageAt = now;
            announceGained();
        }
    }

    /** The chat line: just the session's total gain — no per-hour part (on request). */
    private void announceGained() {
        Minecraft mc = Minecraft.getInstance();
        Component msg = Component.literal("SkyLite » ").withStyle(ChatFormatting.DARK_AQUA)
                .append(Component.translatable("skylite.chat.session_gain", "+" + fmt(gained))
                        .withStyle(ChatFormatting.GOLD));
        mc.gui.hud.getChat().addClientSystemMessage(msg);
    }

    /**
     * Feed of incoming chat lines: logs a rare drop with its time and the current island.
     * Returns {@code true} when this line was a rare drop, so the caller can react
     * (the drop screenshot, v18).
     */
    public boolean onChat(String plain, String area) {
        if (plain == null || plain.isEmpty()) {
            return false;
        }
        for (String marker : DROP_MARKERS) {
            if (ChatFilter.indexOfIgnoreCase(plain, marker, 0) >= 0) {
                addDrop(plain, area);
                return true;
            }
        }
        return false;
    }

    private void addDrop(String plain, String area) {
        String text = StatsState.stripFormatting(plain).trim();
        if (text.length() > MAX_DROP_CHARS) {
            text = text.substring(0, MAX_DROP_CHARS) + "…";
        }
        long now = System.currentTimeMillis();
        drops.add(0, new Drop(TIME.format(LocalDateTime.now()), text, area, now));
        while (drops.size() > MAX_DROPS) {
            drops.remove(drops.size() - 1);
        }
    }

    /** The journal, newest first (read-only — the screen must not mutate it). */
    public List<Drop> drops() {
        return drops;
    }

    public void clearDrops() {
        drops.clear();
    }

    /** Resets the coin counter (the journal is cleared from its screen). */
    public void reset() {
        gained = 0;
        gainPending = false;
    }

    /** "Session 2h 15m" body: how long the game has been running, minute precision (v15). */
    public String durationText() {
        long minutes = (System.currentTimeMillis() - startedAt) / 60_000L;
        if (minutes < 60) {
            return minutes + "m";
        }
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    /**
     * "3 · 12m" — the session's rare-drop count and how long ago the newest one landed (v18).
     * Shows a bare "0" until the first drop; refreshes through the 2 Hz sidebar poll.
     */
    public String dropsText() {
        int count = drops.size();
        if (count == 0) {
            return "0";
        }
        return count + " · " + ago(System.currentTimeMillis() - drops.get(0).at());
    }

    /** Compact "how long ago": "45s", "12m", "2h 15m". */
    private static String ago(long millis) {
        long seconds = Math.max(0L, millis / 1000L);
        if (seconds < 60) {
            return seconds + "s";
        }
        long minutes = seconds / 60L;
        if (minutes < 60) {
            return minutes + "m";
        }
        return (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    /** Compact coin numbers: "812", "12.3k", "470k", "1.2M" (sign is kept). */
    static String fmt(long value) {
        long a = Math.abs(value);
        if (a < 1_000L) {
            return Long.toString(value);
        }
        if (a < 1_000_000L) {
            return fmt1(value / 1e3, "k");
        }
        if (a < 1_000_000_000L) {
            return fmt1(value / 1e6, "M");
        }
        return fmt1(value / 1e9, "B");
    }

    private static String fmt1(double v, String suffix) {
        String s = String.format(Locale.ROOT, "%.1f", v);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s + suffix;
    }
}
