package dev.asswa.skylite;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import java.util.List;
import java.util.Locale;

/**
 * Rare-drop / keyword alerts: a vanilla sound plus a short flashing line,
 * drawn by {@link HudPanel}. No assets of our own.
 */
public final class Alerts {
    public static final long FLASH_DURATION_MS = 2200L;
    private static final long TOAST_DURATION_MS = 1600L;
    private static final long COOLDOWN_MS = 900L;
    private static final int MAX_FLASH_CHARS = 80;

    // Read by HudPanel on the render thread.
    String flashText;
    long flashUntil;
    String toastLine;
    long toastUntil;

    private long lastAlertAt;

    public void onGameMessage(String plain) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null || !cfg.alertsEnabled || plain == null || plain.isEmpty()) {
            return;
        }
        if (!SkyLiteClient.stats.isOnSkyBlock()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastAlertAt < COOLDOWN_MS) {
            return;
        }
        List<String> keywords = cfg.alertKeywords;
        if (keywords.isEmpty()) {
            return; // nothing to look for — skip the lowercase copy of every chat line
        }
        String lower = plain.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword == null) {
                continue;
            }
            String needle = keyword.trim().toLowerCase(Locale.ROOT);
            if (!needle.isEmpty() && lower.contains(needle)) {
                fire(plain, now, keyword);
                return;
            }
        }
    }

    private void fire(String line, long now, String keyword) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        lastAlertAt = now;
        diag(keyword, line);
        if (cfg.alertFlash) {
            flashText = line.length() > MAX_FLASH_CHARS
                    ? line.substring(0, MAX_FLASH_CHARS) + "…"
                    : line;
            flashUntil = now + FLASH_DURATION_MS;
        }
        if (cfg.alertSound) {
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_PLING.value(), 1.2F, 1.0F));
        }
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
        toastLine = text.getString(); // translated once, not on every frame of the toast
        toastUntil = System.currentTimeMillis() + TOAST_DURATION_MS;
    }
}
