package dev.asswa.skylite;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plain JSON config at {@code config/skylite.json}. Deliberately minimal: no GUI
 * framework dependencies; everything is editable in-game through the Mod Menu screen
 * (see {@link SkyLiteConfigScreen}), the HUD editor and the /skylite command.
 */
public final class SkyLiteConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("skylite.json");
    private static final int CURRENT_VERSION = 7;

    /**
     * 0/1 = pre-bars; 2 = minimalist bars; 3 = vanilla HUD replacement; 4 = editable colours;
     * 5 = draggable bars; 6 = appearance options, info lines and the /skylite command;
     * 7 = per-bar width/thickness + number-background toggle.
     */
    public int configVersion = 0;

    /** Corner used by the FPS/ping line; the key is resolved by the config screen. */
    public enum Corner {
        LEFT_TOP("skylite.config.corner.left_top"),
        RIGHT_TOP("skylite.config.corner.right_top"),
        LEFT_BOTTOM("skylite.config.corner.left_bottom"),
        RIGHT_BOTTOM("skylite.config.corner.right_bottom");

        public final String key;

        Corner(String key) {
            this.key = key;
        }
    }

    // --- HUD ---
    public boolean hudEnabled = true;
    public int hudX = 4;
    public int hudY = 4;
    public float hudScale = 1.0F;
    public boolean hudBackground = true;

    /**
     * Where the bars live: "vanilla" (default) = each bar in the spot of the HUD element it
     * replaces (hearts left, hunger right, XP bar right above the hotbar); "panel" = stacked
     * card at hudX/hudY (the only layout before v4).
     */
    public String barLayout = "vanilla";

    // Minimalist bars — the main HUD content since v2.
    public boolean showHealthBar = true;
    public boolean showManaBar = true;
    /** SkyBlock repurposes the vanilla XP bar for level progress; this is that bar. */
    public boolean showXpBar = true;
    /** Draw the exact values inside the bars. */
    public boolean showBarNumbers = true;
    /** Legacy shared width (pre-v7); kept so old skylite.json files still load. */
    public int barWidth = 90;
    /** Legacy shared thickness (pre-v7). */
    public int barHeight = 9;

    // Per-bar size (v7); migrated from the shared values above on first load.
    public int hpBarWidth = 90;
    public int manaBarWidth = 90;
    public int xpBarWidth = 182;
    /** Default 9 matches the font height, so the value text is exactly as tall as the bar. */
    public int hpBarHeight = 9;
    public int manaBarHeight = 9;
    public int xpBarHeight = 9;

    // Bar placement in the "vanilla" layout: offsets in GUI pixels from each bar's default
    // anchor spot (hearts row / hunger row / above the hotbar). Changed by dragging the bars
    // in the in-game editor (Controls → SkyLite → "Move the bars", F8 by default).
    public int hpBarOffsetX = 0;
    public int hpBarOffsetY = 0;
    public int manaBarOffsetX = 0;
    public int manaBarOffsetY = 0;
    public int xpBarOffsetX = 0;
    public int xpBarOffsetY = 0;

    // Bar colours as "#RRGGBB" (8-digit "#AARRGGBB" is also accepted for transparency).
    public String healthColor = "#FF5555";
    public String manaColor = "#55AAFF";
    public String xpColor = "#80FF20";
    public String textColor = "#FFFFFF";

    // Appearance (v6).
    /** Value style: "full" = 566/566, "compact" = 566, "percent" = 84%. */
    public String numberFormat = "full";
    /** Prefix the values with Hypixel-style glyphs (❤ 566/566, ✎ 72/116). */
    public boolean showGlyphs = false;
    /** Track (background) opacity in percent, 0..100. */
    public int trackOpacity = 50;
    /** Dark pill behind the numbers inside the bars (the "Number background" option). */
    public boolean textBackground = true;
    /** 1-px outline around each bar. */
    public boolean barOutline = false;
    /** Soft pulse of the fill when HP/mana drop below ~30 %. */
    public boolean lowValuePulse = true;

    // Info lines & corner widget (v6). All values come from data the server already sends.
    public boolean showDefense = true;
    public boolean showPurse = false;
    public boolean showBits = false;
    public boolean showArea = true;    // current island/area from the sidebar
    public boolean showCookie = true;  // Cookie Buff time left from the sidebar
    public boolean showGodPot = true;  // God Potion time left from the sidebar, when present
    public boolean showPingFps = true; // vanilla FPS/ping, no Hypixel data needed
    public Corner pingFpsCorner = Corner.LEFT_TOP;

    // Standalone Speed % plate (movable in the bar editor, like the bars).
    public boolean showSpeed = true;
    public int speedOffsetX = 0;
    public int speedOffsetY = 0;

    // Replace the default HUD while the SkyLite panel is on (F6) and we are on SkyBlock.
    public boolean hideStatsActionBar = true; // Hypixel's "…❤ …❈ Defense …✦ Mana" line
    public boolean hideVanillaHearts = true;
    public boolean hideVanillaFoodBar = true; // the hunger row, replaced by the mana bar
    public boolean hideVanillaXpBar = true;   // contextual bar + level number

    // --- Alerts ---
    public boolean alertsEnabled = true;
    public boolean alertSound = true;
    public boolean alertFlash = true;
    public List<String> alertKeywords = new ArrayList<>(List.of(
            "RARE DROP!", "CRAZY RARE DROP!", "PET DROP!"));

    // Parsed colour cache (transient: never written to disk).
    private transient int rgbHealth = 0xFFFF5555;
    private transient int rgbMana = 0xFF55AAFF;
    private transient int rgbXp = 0xFF80FF20;
    private transient int rgbText = 0xFFFFFFFF;

    public int healthRgb() {
        return rgbHealth;
    }

    public int manaRgb() {
        return rgbMana;
    }

    public int xpRgb() {
        return rgbXp;
    }

    public int textRgb() {
        return rgbText;
    }

    /** Re-parses the colour strings; called on load and after the config screen saves. */
    public void applyColors() {
        rgbHealth = parseColor(healthColor, 0xFFFF5555);
        rgbMana = parseColor(manaColor, 0xFF55AAFF);
        rgbXp = parseColor(xpColor, 0xFF80FF20);
        rgbText = parseColor(textColor, 0xFFFFFFFF);
    }

    /** "#RRGGBB" / "#AARRGGBB" -> ARGB int; {@code fallback} when unparseable. */
    public static int parseColor(String value, int fallback) {
        if (value != null) {
            String hex = value.trim();
            if (hex.startsWith("#")) {
                hex = hex.substring(1);
            }
            try {
                if (hex.length() == 6) {
                    return 0xFF000000 | Integer.parseUnsignedInt(hex, 16);
                }
                if (hex.length() == 8) {
                    return (int) Long.parseLong(hex, 16);
                }
            } catch (NumberFormatException ignored) {
                // fall through to the fallback colour
            }
        }
        return fallback;
    }

    /** ARGB int -> "#RRGGBB" (the config stores opaque colours; the picker has no alpha). */
    public static String toHex(int rgb) {
        return String.format("#%06X", rgb & 0xFFFFFF);
    }

    public static SkyLiteConfig load() {
        SkyLiteConfig cfg = null;
        if (Files.exists(PATH)) {
            try {
                cfg = GSON.fromJson(Files.readString(PATH), SkyLiteConfig.class);
            } catch (Exception e) {
                SkyLiteClient.LOGGER.warn("Could not read {}, using defaults", PATH, e);
            }
        }
        if (cfg == null) {
            cfg = new SkyLiteConfig();
            cfg.configVersion = CURRENT_VERSION;
        } else {
            if (cfg.configVersion < 2) {
                // v0/v1 -> v2: the HUD became bars-first; purse/bits/defense text lines off.
                cfg.showPurse = false;
                cfg.showBits = false;
                cfg.showDefense = false;
            }
            if (cfg.configVersion < 3) {
                // v3: the vanilla action bar stats line is hidden by default now, so defense
                // gets a home in the panel again.
                cfg.showDefense = true;
            }
            if (cfg.configVersion < 4) {
                // v4: bars default to the font height so the value text fits exactly;
                // colours became editable in the Mod Menu screen.
                if (cfg.barHeight == 5) {
                    cfg.barHeight = 9;
                }
            }
            if (cfg.configVersion < 7) {
                // v7: per-bar width/thickness, migrated from the old shared values.
                cfg.hpBarWidth = cfg.manaBarWidth = cfg.barWidth;
                cfg.xpBarWidth = Math.min(cfg.barWidth * 2 + 2, 320);
                cfg.hpBarHeight = cfg.manaBarHeight = cfg.xpBarHeight = cfg.barHeight;
            }
            cfg.configVersion = CURRENT_VERSION;
        }
        if (cfg.alertKeywords == null) {
            cfg.alertKeywords = new ArrayList<>();
        }
        if (!"full".equals(cfg.numberFormat) && !"compact".equals(cfg.numberFormat)
                && !"percent".equals(cfg.numberFormat)) {
            cfg.numberFormat = "full"; // hand-edited configs must not break the selector
        }
        cfg.applyColors();
        cfg.save();
        return cfg;
    }

    public void save() {
        try {
            Files.writeString(PATH, GSON.toJson(this));
        } catch (IOException e) {
            SkyLiteClient.LOGGER.warn("Could not save {}\n", PATH, e);
        }
    }
}
