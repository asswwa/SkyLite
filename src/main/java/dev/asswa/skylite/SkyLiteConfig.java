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
    private static final int CURRENT_VERSION = 18;

    /**
     * 0/1 = pre-bars; 2 = minimalist bars; 3 = vanilla HUD replacement; 4 = editable colours;
     * 5 = draggable bars; 6 = appearance options, info lines and the /skylite command;
     * 7 = per-bar width/thickness + number-background toggle; 8 = chat highlight and filter;
     * 9 = bar styles; 10 = optimization profiles, advisor, location rules and the FPS benchmark;
     * 11 = ping graph, session coin chat messages, low HP/mana + kick alerts, alert toast
     * text, chat rules; 12 = the HUD stays behind open menus (dimmed by the menu backdrop);
     * 13 = potion-effect list, coordinates, AFK timer, buff warnings, alert presets,
     * toast queue, config search, alert chime/volume; 14 = polish: info-line cap, toast
     * background, alert cooldown/dedup, flash/toast durations, benchmark persistence;
     * 15 = clock and session lines, LAG badge, effect-expiry warning, chat timestamps,
     * multi-action chat rules with an ignore list; 16 = the "Purse:"-style labels can be
     * hidden; 17 = every info line is its own movable block (per-line offsets); 18 = session
     * drop counter line, inventory-full warning, nickname-mention alerts, rare-drop
     * screenshots and the chat filter presets.
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
     * Keep the HUD drawn while a menu is open (v12). The menu's own dark backdrop covers the
     * HUD, so it shows through dimmed instead of vanishing; our own screens still hide it.
     */
    public boolean hudInScreens = true;

    /**
     * Where the bars live: "vanilla" (default) = each bar in the spot of the HUD element it
     * replaces (hearts left, hunger right, XP bar right above the hotbar); "panel" = stacked
     * card at hudX/hudY (the only layout before v4).
     */
    public String barLayout = "vanilla";

    /**
     * Bar look (v9): "classic" (flat, the original), "rounded" (soft corners), "capsule"
     * (fully round ends), "gloss" (shaded fill), "minimal" (thin bar, values beside),
     * "framed" (dark frame, recessed fill). All styles are plain rectangles, no textures.
     */
    public String barStyle = "classic";

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

    // Info-line positions (v17). Every line is its own movable block: the editor stores an
    // absolute offset in GUI pixels from the block's default spot (the lower-left corner of
    // the stack above the HP bar). (0, 0) = untouched, the line keeps its place in the stack.
    public static final int LINE_AFK = 0;
    public static final int LINE_CLOCK = 1;
    public static final int LINE_AREA = 2;
    public static final int LINE_COOKIE = 3;
    public static final int LINE_GODPOT = 4;
    public static final int LINE_PURSE = 5;
    public static final int LINE_BITS = 6;
    public static final int LINE_SESSION = 7;
    public static final int LINE_COORDS = 8;
    public static final int LINE_EFFECTS = 9;
    public static final int LINE_DEFENSE = 10;
    public static final int LINE_DROPS = 11;
    public static final int LINE_KIND_COUNT = 12;

    public int afkOffsetX = 0;
    public int afkOffsetY = 0;
    public int clockOffsetX = 0;
    public int clockOffsetY = 0;
    public int areaOffsetX = 0;
    public int areaOffsetY = 0;
    public int cookieOffsetX = 0;
    public int cookieOffsetY = 0;
    public int godPotOffsetX = 0;
    public int godPotOffsetY = 0;
    public int purseOffsetX = 0;
    public int purseOffsetY = 0;
    public int bitsOffsetX = 0;
    public int bitsOffsetY = 0;
    public int sessionOffsetX = 0;
    public int sessionOffsetY = 0;
    public int coordsOffsetX = 0;
    public int coordsOffsetY = 0;
    public int effectsOffsetX = 0;
    public int effectsOffsetY = 0;
    public int defenseOffsetX = 0;
    public int defenseOffsetY = 0;
    public int dropsOffsetX = 0;
    public int dropsOffsetY = 0;

    /** X offset of an info-line kind, see {@code LINE_*}; (0, 0) = still in the stack (v17). */
    public int lineOffsetX(int kind) {
        return switch (kind) {
            case LINE_CLOCK -> clockOffsetX;
            case LINE_AREA -> areaOffsetX;
            case LINE_COOKIE -> cookieOffsetX;
            case LINE_GODPOT -> godPotOffsetX;
            case LINE_PURSE -> purseOffsetX;
            case LINE_BITS -> bitsOffsetX;
            case LINE_SESSION -> sessionOffsetX;
            case LINE_COORDS -> coordsOffsetX;
            case LINE_EFFECTS -> effectsOffsetX;
            case LINE_DEFENSE -> defenseOffsetX;
            case LINE_DROPS -> dropsOffsetX;
            default -> afkOffsetX;
        };
    }

    /** Y offset of an info-line kind, see {@code LINE_*}; (0, 0) = still in the stack (v17). */
    public int lineOffsetY(int kind) {
        return switch (kind) {
            case LINE_CLOCK -> clockOffsetY;
            case LINE_AREA -> areaOffsetY;
            case LINE_COOKIE -> cookieOffsetY;
            case LINE_GODPOT -> godPotOffsetY;
            case LINE_PURSE -> purseOffsetY;
            case LINE_BITS -> bitsOffsetY;
            case LINE_SESSION -> sessionOffsetY;
            case LINE_COORDS -> coordsOffsetY;
            case LINE_EFFECTS -> effectsOffsetY;
            case LINE_DEFENSE -> defenseOffsetY;
            case LINE_DROPS -> dropsOffsetY;
            default -> afkOffsetY;
        };
    }

    public void setLineOffsets(int kind, int x, int y) {
        switch (kind) {
            case LINE_CLOCK -> { clockOffsetX = x; clockOffsetY = y; }
            case LINE_AREA -> { areaOffsetX = x; areaOffsetY = y; }
            case LINE_COOKIE -> { cookieOffsetX = x; cookieOffsetY = y; }
            case LINE_GODPOT -> { godPotOffsetX = x; godPotOffsetY = y; }
            case LINE_PURSE -> { purseOffsetX = x; purseOffsetY = y; }
            case LINE_BITS -> { bitsOffsetX = x; bitsOffsetY = y; }
            case LINE_SESSION -> { sessionOffsetX = x; sessionOffsetY = y; }
            case LINE_COORDS -> { coordsOffsetX = x; coordsOffsetY = y; }
            case LINE_EFFECTS -> { effectsOffsetX = x; effectsOffsetY = y; }
            case LINE_DEFENSE -> { defenseOffsetX = x; defenseOffsetY = y; }
            case LINE_DROPS -> { dropsOffsetX = x; dropsOffsetY = y; }
            default -> { afkOffsetX = x; afkOffsetY = y; }
        }
    }

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
    /** Word labels before the info lines ("Purse:", "XYZ: …"); off = bare values (v16). */
    public boolean infoLabels = false;
    public boolean showDefense = true;
    public boolean showPurse = false;
    public boolean showBits = false;
    /** Announce coins gained this session in chat whenever the purse grows (v11). */
    public boolean sessionCoinsChat = true;
    public boolean showArea = true;    // current island/area from the sidebar
    public boolean showCookie = true;  // Cookie Buff time left from the sidebar
    public boolean showGodPot = true;  // God Potion time left from the sidebar, when present
    public boolean showPingFps = true; // vanilla FPS/ping, no Hypixel data needed
    /** Sparkline of the last minute of ping samples under the FPS/ping line (v11). */
    public boolean showPingGraph = true;
    public Corner pingFpsCorner = Corner.LEFT_TOP;

    /** Active potion effects as info lines with mm:ss timers (v13). */
    public boolean showEffects = false;
    /** How many effect lines to show at most; the soonest to expire come first (v13). */
    public int effectsMax = 4;
    /** "XYZ: 12 / 64 / -30" line from the player position (v13). */
    public boolean showCoords = false;
    /** "13:20" real-time clock line (v15). */
    public boolean showClock = false;
    /** "Session 2h 15m" line — how long the game has been running (v15). */
    public boolean showSession = true;
    /** "Drops: 3 · 12m" line — session rare-drop count and time since the last one (v18). */
    public boolean showDrops = false;
    /** "AFK 12m" line after a minute without movement, rotation or keys (v13). */
    public boolean showAfk = true;
    /** Red "LAG" prefix in the FPS/ping line once the ping passes the threshold (v15). */
    public boolean lagBadge = true;
    /** Ping in ms at which the LAG badge appears, 50..1000 (v15). */
    public int lagThresholdMs = 250;
    /** Warn once when a potion effect is about to run out (v15). */
    public boolean effectWarn = true;
    /** Seconds before the end at which the effect warning fires, 10..60 (v15). */
    public int effectWarnSeconds = 30;
    /**
     * Polishing (v14): how many info lines the vanilla layout draws at most, 0 = only the
     * screen height limits them. The lines nearest the bars survive the cap.
     */
    public int infoLinesMax = 0;

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
    /** Show the alert's message text as a short toast near the hotbar (v11). */
    public boolean alertToast = true;
    /** Low HP alert: sound/flash/toast when health drops under {@link #lowHpPercent}. */
    public boolean lowHpAlert = false;
    public int lowHpPercent = 30;
    /** Low mana alert, same idea; mana comes from the sidebar stats line. */
    public boolean lowManaAlert = false;
    public int lowManaPercent = 20;
    /** Warn once when the player's inventory has this many free slots or fewer (v18). */
    public boolean invWarn = true;
    public int invWarnSlots = 3;
    /** Loud bell when the game moves the player off SkyBlock (Limbo / main lobby). */
    public boolean kickAlert = true;
    public List<String> alertKeywords = new ArrayList<>(List.of(
            "RARE DROP!", "CRAZY RARE DROP!", "PET DROP!"));

    /** Warn once when Cookie Buff / God Pot drop under five minutes (v13). */
    public boolean buffWarn = true;
    /** Alert chime: "bell", "pling", "orb" or "click" (v13). */
    public String alertChime = "pling";
    /** Chime volume in percent, 0..100 (v13). */
    public int alertVolume = 100;
    /** How many toasts may stack on screen at once, 1..3 (v13). */
    public int toastMax = 2;

    // Polishing (v14).
    /** Dark pill behind the toast and flash texts so they read over bright scenes. */
    public boolean toastBackground = true;
    /** Keyword-alert cooldown in ms, 0..3000 (0 = every match fires). */
    public int alertCooldownMs = 900;
    /** Drop an alert when the exact same message fired within the last 10 s. */
    public boolean alertDedup = true;
    /** Flash duration in ms, 400..8000. */
    public int alertFlashMs = 2200;
    /** Toast duration in ms, 400..8000. */
    public int toastMs = 1600;

    // Last FPS benchmark result, kept between screens and restarts (v14); -1 = none yet.
    public int benchLastAvg = -1;
    public int benchLastLow = -1;

    // Alert presets (v13): well-known Hypixel messages, each with its own toggle.
    public boolean presetParty = false;     // someone invited you to a party
    public boolean presetTrade = false;     // someone wants to trade
    public boolean presetVisit = false;     // someone visits your island
    public boolean presetInventory = false; // "your inventory is full"
    public boolean presetRestart = false;   // server restart warnings
    public boolean presetRareCatch = false; // "RARE CATCH!" while fishing
    /** Save a screenshot into screenshots/ when a rare drop appears in chat (v18, off: it does file I/O). */
    public boolean dropScreenshot = false;

    // --- Chat (v8) ---
    /** Colour the player's own nickname in incoming chat messages (gold and bold). */
    public boolean chatHighlightName = true;
    /** Alert when someone mentions the player's nickname; own messages are skipped (v18). */
    public boolean mentionAlert = true;
    /** Master switch for the spam cleaner below. */
    public boolean chatFilterEnabled = true;
    /** Also collapse identical messages that repeat within {@link ChatFilter#REPEAT_WINDOW_MS}. */
    public boolean chatHideRepeats = false;
    /** Prefix incoming system messages with a dark "[HH:MM]" stamp (v15). */
    public boolean chatTimestamps = false;
    /** Incoming messages containing any of these phrases (case-insensitive) are hidden. */
    public List<String> chatFilterPhrases = new ArrayList<>();
    /**
     * Phrases that switch the chat rules off for a message: it is left alone entirely (no
     * hiding, no sound, no toast, no marker) when it contains any of them (v15).
     */
    public List<String> chatRulesIgnore = new ArrayList<>();
    /**
     * Custom chat rules, one per entry: {@code "phrase => action"} where the action is
     * {@code sound}, {@code toast}, {@code hl} (prepend a highlight marker) or {@code hide} (v11).
     */
    public List<String> chatRules = new ArrayList<>();

    // --- Optimization (v10) ---
    /**
     * Location-based profiles: while enabled, entering a SkyBlock area applies the profile from
     * {@link #optAreaRules} (or {@link #optAreaDefault} elsewhere). Everything stays revertible
     * through {@link Optimizer}.
     */
    public boolean optAreaProfiles = false;
    /** Rules "place=profile" matched (case-insensitive substring) against the sidebar area. */
    public List<String> optAreaRules = new ArrayList<>(List.of("hub=perf", "dungeon=bal", "catacombs=bal"));
    /** Profile used outside the rules: "none", "bal", "perf" or "max". */
    public String optAreaDefault = "none";
    /** Vanilla options captured before the first optimization change; null = nothing to restore. */
    public OptSnapshot optRestore;

    /** Snapshot of the vanilla options the optimizer may change (see {@link Optimizer}). */
    public static final class OptSnapshot {
        public int renderDistance;
        public int simulationDistance;
        public double entityDistance;
        public String particles;
        public String clouds;
        public boolean entityShadows;
        public int biomeBlend;
        public boolean ambientOcclusion;
        public boolean cutoutLeaves;
    }

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
        if (cfg.chatFilterPhrases == null) {
            cfg.chatFilterPhrases = new ArrayList<>();
        }
        if (cfg.chatRules == null) {
            cfg.chatRules = new ArrayList<>();
        }
        if (cfg.chatRulesIgnore == null) {
            cfg.chatRulesIgnore = new ArrayList<>();
        }
        cfg.lowHpPercent = Math.max(5, Math.min(90, cfg.lowHpPercent));
        cfg.lowManaPercent = Math.max(5, Math.min(90, cfg.lowManaPercent));
        cfg.invWarnSlots = Math.max(0, Math.min(10, cfg.invWarnSlots));
        cfg.effectsMax = Math.max(1, Math.min(8, cfg.effectsMax));
        cfg.toastMax = Math.max(1, Math.min(3, cfg.toastMax));
        cfg.alertVolume = Math.max(0, Math.min(100, cfg.alertVolume));
        cfg.infoLinesMax = Math.max(0, Math.min(16, cfg.infoLinesMax));
        cfg.alertCooldownMs = Math.max(0, Math.min(3000, cfg.alertCooldownMs));
        cfg.alertFlashMs = Math.max(400, Math.min(8000, cfg.alertFlashMs));
        cfg.toastMs = Math.max(400, Math.min(8000, cfg.toastMs));
        cfg.lagThresholdMs = Math.max(50, Math.min(1000, cfg.lagThresholdMs));
        cfg.effectWarnSeconds = Math.max(10, Math.min(60, cfg.effectWarnSeconds));
        // Hand-edited offsets must not throw a line past the world: the editor clamps while
        // dragging, this only guards the file.
        for (int kind = 0; kind < LINE_KIND_COUNT; kind++) {
            cfg.setLineOffsets(kind,
                    Math.max(-2000, Math.min(2000, cfg.lineOffsetX(kind))),
                    Math.max(-2000, Math.min(2000, cfg.lineOffsetY(kind))));
        }
        if (!List.of("bell", "pling", "orb", "click").contains(cfg.alertChime)) {
            cfg.alertChime = "pling"; // hand-edited configs must not break the chime selector
        }
        if (!"full".equals(cfg.numberFormat) && !"compact".equals(cfg.numberFormat)
                && !"percent".equals(cfg.numberFormat)) {
            cfg.numberFormat = "full"; // hand-edited configs must not break the selector
        }
        if (!List.of("classic", "rounded", "capsule", "gloss", "minimal", "framed").contains(cfg.barStyle)) {
            cfg.barStyle = "classic"; // hand-edited configs must not break the selector
        }
        if (cfg.optAreaRules == null) {
            cfg.optAreaRules = new ArrayList<>(List.of("hub=perf", "dungeon=bal", "catacombs=bal"));
        }
        if (!List.of("none", "bal", "perf", "max").contains(cfg.optAreaDefault)) {
            cfg.optAreaDefault = "none"; // hand-edited configs must not break the rules
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
