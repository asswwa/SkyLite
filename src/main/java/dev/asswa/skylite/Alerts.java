package dev.asswa.skylite;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Rare-drop / keyword alerts: a vanilla sound plus a short flashing line,
 * drawn by {@link HudPanel}. No assets of our own.
 */
public final class Alerts {
    /** Fallback durations when the config is unavailable; the v14 sliders override them. */
    public static final long FLASH_DURATION_MS = 2200L;
    private static final long TOAST_DURATION_MS = 1600L;
    private static final long COOLDOWN_MS = 900L;
    private static final int MAX_FLASH_CHARS = 80;
    private static final int MAX_TOASTS = 3; // the config slider picks 1..3 of these to show
    /** Identical message text within this window fires one alert only (v14). */
    private static final long DEDUP_MS = 10_000L;
    /** Minimum gap between chimes so two alerts never overlap (v14). */
    private static final long CHIME_STAGGER_MS = 400L;
    private static final int MAX_QUEUED_CHIMES = 4;

    // Read by HudPanel on the render thread.
    String flashText;
    long flashUntil;

    /** One queued toast; the newest sits closest to the hotbar (v13). */
    static final class Toast {
        final String text;
        final long until;

        Toast(String text, long until) {
            this.text = text;
            this.until = until;
        }
    }

    final Deque<Toast> toasts = new ArrayDeque<>();

    /** Chimes waiting for their slot, so overlapping alerts do not double-sound (v14). */
    private static final class Chime {
        final String id;
        final long at;

        Chime(String id, long at) {
            this.id = id;
            this.at = at;
        }
    }

    private final Deque<Chime> chimes = new ArrayDeque<>();
    private long nextChimeAt;

    private long lastAlertAt;
    private String lastFiredText;
    private long lastFiredTextAt;

    /** Lowercase keyword needles, rebuilt only when the config list instance is replaced. */
    private static final String[] NO_KEYWORDS = new String[0];
    private List<String> keywordSource;
    private String[] keywordNeedles = NO_KEYWORDS;

    /** Lowercase needles of the alert presets (v13), one row per config toggle. */
    private static final String[][] PRESETS = {
            {"invited you to join their party", "invited you to a party", "you have been invited to"},
            {"wants to trade with you", "has sent you a trade request", "trade request"},
            {"is visiting your island"},
            {"your inventory is full", "inventory full"},
            {"server is restarting", "server restart", "restarting in", "server closing",
                    "server will restart"},
            {"rare catch"},
    };

    public void onGameMessage(String plain) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null || !cfg.alertsEnabled || plain == null || plain.isEmpty()) {
            return;
        }
        if (!SkyLiteClient.stats.isOnSkyBlock()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAlertAt < cooldownMs(cfg)) {
            return;
        }
        String[] needles = keywords();
        boolean presets = anyPreset(cfg);
        if (needles.length == 0 && !presets) {
            return; // nothing to look for
        }
        for (String needle : needles) {
            if (ChatFilter.indexOfIgnoreCase(plain, needle, 0) >= 0) {
                fire(plain, now, needle);
                return;
            }
        }
        if (presets) {
            for (int i = 0; i < PRESETS.length; i++) {
                if (!presetEnabled(cfg, i)) {
                    continue;
                }
                for (String needle : PRESETS[i]) {
                    if (ChatFilter.indexOfIgnoreCase(plain, needle, 0) >= 0) {
                        fire(plain, now, "preset:" + needle);
                        return;
                    }
                }
            }
        }
    }

    private static boolean anyPreset(SkyLiteConfig cfg) {
        for (int i = 0; i < PRESETS.length; i++) {
            if (presetEnabled(cfg, i)) {
                return true;
            }
        }
        return false;
    }

    private static boolean presetEnabled(SkyLiteConfig cfg, int which) {
        return switch (which) {
            case 0 -> cfg.presetParty;
            case 1 -> cfg.presetTrade;
            case 2 -> cfg.presetVisit;
            case 3 -> cfg.presetInventory;
            case 4 -> cfg.presetRestart;
            default -> cfg.presetRareCatch;
        };
    }

    /**
     * The configured keywords as lowercase, trimmed needles. Cached by list identity so a chat
     * message never allocates a lowercase copy of itself or of every keyword.
     */
    private String[] keywords() {
        SkyLiteConfig cfg = SkyLiteClient.config;
        List<String> source = cfg == null ? null : cfg.alertKeywords;
        if (source == keywordSource) {
            return keywordNeedles;
        }
        keywordSource = source;
        if (source == null || source.isEmpty()) {
            keywordNeedles = NO_KEYWORDS;
            return keywordNeedles;
        }
        List<String> needles = new ArrayList<>(source.size());
        for (String keyword : source) {
            if (keyword == null) {
                continue;
            }
            String needle = keyword.trim().toLowerCase(Locale.ROOT);
            if (!needle.isEmpty()) {
                needles.add(needle);
            }
        }
        keywordNeedles = needles.toArray(NO_KEYWORDS);
        return keywordNeedles;
    }

    private void fire(String line, long now, String keyword) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg.alertDedup && line.equals(lastFiredText) && now - lastFiredTextAt < DEDUP_MS) {
            return; // the same message again within seconds: one alert is enough (v14)
        }
        lastAlertAt = now;
        lastFiredText = line;
        lastFiredTextAt = now;
        diag(keyword, line);
        String shown = clip(line);
        if (cfg.alertFlash) {
            flashText = shown;
            flashUntil = now + flashMs(cfg);
        }
        if (cfg.alertSound) {
            playChime(false);
        }
        if (cfg.alertToast) {
            toast(Component.literal(shown)); // item 32: the message text, not to be missed while AFK
        }
    }

    /**
     * An alert raised by the mod itself (low HP/mana, kicked to Limbo): flash + sound + toast,
     * each following its own alert toggle. {@code bell} picks the louder, more alarming chime.
     */
    public void onEvent(String line, boolean bell) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null || !cfg.alertsEnabled || line == null || line.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        String shown = clip(line);
        if (cfg.alertFlash) {
            flashText = shown;
            flashUntil = now + flashMs(cfg);
        }
        if (cfg.alertSound) {
            playChime(bell);
        }
        if (cfg.alertToast) {
            toast(Component.literal(shown));
        }
    }

    private static long cooldownMs(SkyLiteConfig cfg) {
        return cfg == null ? COOLDOWN_MS : Math.max(0, Math.min(3000, cfg.alertCooldownMs));
    }

    private static long flashMs(SkyLiteConfig cfg) {
        return cfg == null ? FLASH_DURATION_MS : Math.max(400, Math.min(8000, cfg.alertFlashMs));
    }

    private static long toastMs(SkyLiteConfig cfg) {
        return cfg == null ? TOAST_DURATION_MS : Math.max(400, Math.min(8000, cfg.toastMs));
    }

    /**
     * Queues the configured alert chime. Two alerts in the same tick (Cookie + God Pot) would
     * otherwise overlap, so a chime never sounds closer than {@link #CHIME_STAGGER_MS} to the
     * previous one; {@code SkyLiteClient} drains the queue on the client tick.
     */
    public static void playChime(boolean bell) {
        SkyLiteClient.alerts.enqueueChime(bell);
    }

    private void enqueueChime(boolean bell) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        String id = resolveChimeId(cfg, bell);
        long now = System.currentTimeMillis();
        if (nextChimeAt <= now) {
            nextChimeAt = now + CHIME_STAGGER_MS;
            playNow(id, chimeVolume(cfg));
            return;
        }
        if (chimes.size() >= MAX_QUEUED_CHIMES) {
            return; // an alert storm must not schedule a minute of chimes
        }
        chimes.addLast(new Chime(id, nextChimeAt));
        nextChimeAt += CHIME_STAGGER_MS;
    }

    /** Called every client tick: plays the chimes whose slot has come. */
    public void tick() {
        if (chimes.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        while (!chimes.isEmpty() && chimes.peekFirst().at <= now) {
            playNow(chimes.pollFirst().id, chimeVolume(SkyLiteClient.config));
        }
        if (chimes.isEmpty()) {
            nextChimeAt = now;
        }
    }

    private static String resolveChimeId(SkyLiteConfig cfg, boolean bell) {
        if (bell) {
            return "bell"; // the alarm bell ignores the selection
        }
        return cfg == null || cfg.alertChime == null ? "pling" : cfg.alertChime;
    }

    private static float chimeVolume(SkyLiteConfig cfg) {
        return cfg == null ? 1.0F : Math.max(0, Math.min(100, cfg.alertVolume)) / 100.0F;
    }

    private static void playNow(String id, float volume) {
        SoundEvent sound;
        float pitch;
        switch (id) {
            case "bell" -> {
                sound = SoundEvents.NOTE_BLOCK_BELL.value();
                pitch = 0.9F;
            }
            case "orb" -> {
                sound = SoundEvents.EXPERIENCE_ORB_PICKUP;
                pitch = 1.0F;
            }
            case "click" -> {
                sound = SoundEvents.UI_BUTTON_CLICK.value();
                pitch = 1.0F;
            }
            default -> {
                sound = SoundEvents.NOTE_BLOCK_PLING.value();
                pitch = 1.2F;
            }
        }
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    /** The standard alert chime alone (chat rules "sound" action); no toggles, no cooldown here. */
    public static void pling() {
        playChime(false);
    }

    /** Plays the selected chime at once, for the "Test" button in the settings (v13); no queue. */
    public void previewChime() {
        SkyLiteConfig cfg = SkyLiteClient.config;
        playNow(resolveChimeId(cfg, false), chimeVolume(cfg));
    }

    /** A toast with a plain line (chat rules); the text is shown exactly as passed. */
    public void toastText(String text) {
        if (text != null && !text.isEmpty()) {
            toast(Component.literal(text));
        }
    }

    /** Trims a long message so neither the flash nor the toast can run off the screen. */
    static String clip(String line) {
        return line.length() > MAX_FLASH_CHARS ? line.substring(0, MAX_FLASH_CHARS) + "…" : line;
    }

    // Bounded diagnostics (20 lines per session) so the log shows whether alerts trigger.
    private static int diagBudget = 20;

    private static void diag(String keyword, String text) {
        if (diagBudget <= 0) {
            return;
        }
        diagBudget--;
        SkyLiteClient.LOGGER.info("[SkyLite diag] alert keyword={} text={}", keyword, text);
    }

    /** Short transient message near the hotbar (used for keybind toggles). */
    public void toast(Component text) {
        String value = text.getString(); // translated once, not on every frame of the toast
        long now = System.currentTimeMillis();
        pruneToasts(now);
        while (toasts.size() >= MAX_TOASTS) {
            toasts.pollFirst(); // the queue never grows past the config slider's range
        }
        toasts.addLast(new Toast(value, now + toastMs(SkyLiteClient.config)));
    }

    /** Drops expired toasts; called by the HUD once per drawn frame and when queuing. */
    void pruneToasts(long now) {
        while (!toasts.isEmpty() && now >= toasts.peekFirst().until) {
            toasts.pollFirst();
        }
    }
}
