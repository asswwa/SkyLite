package dev.asswa.skylite;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts the numbers Hypixel already sends to the client:
 * <ul>
 *   <li>health / defense / mana — from the action bar overlay text;</li>
 *   <li>purse / bits / area / Cookie Buff / God Potion — from the sidebar scoreboard lines;</li>
 *   <li>experience — the vanilla XP bar fields, which SkyBlock repurposes for level progress.</li>
 * </ul>
 * Bar ratios and render strings are cached and rebuilt only when a value changes.
 */
public final class StatsState {
    /** One number token: digits with separators and an optional k/m/b suffix. */
    private static final String NUM = "[0-9][0-9,.']*[kKmMbB]?";
    // The server text uses private-use glyphs (E010 heart, E003 mana, E008 defense) alongside
    // the classic ❤/♥/✦/✎/✧/❈ unicode ones, so both variants are accepted.
    private static final Pattern HEALTH = Pattern.compile("(" + NUM + ")\\s*/\\s*(" + NUM + ")\\s*[❤♥\\x{E010}]");
    private static final Pattern HEALTH_REV = Pattern.compile("[❤♥\\x{E010}]\\s*(" + NUM + ")\\s*/\\s*(" + NUM + ")");
    private static final Pattern DEFENSE = Pattern.compile("(" + NUM + ")\\s*[❈\\x{E008}]");
    // ✦ is the classic mana glyph, ✎/✧ are historic variants.
    private static final Pattern MANA = Pattern.compile("(" + NUM + ")\\s*/\\s*(" + NUM + ")\\s*[✦✎✧\\x{E003}]");
    private static final Pattern MANA_REV = Pattern.compile("[✦✎✧\\x{E003}]\\s*(" + NUM + ")\\s*/\\s*(" + NUM + ")");
    // Label-based fallbacks for when the server uses an unknown glyph font.
    private static final Pattern ANY_PAIR = Pattern.compile("(" + NUM + ")\\s*/\\s*(" + NUM + ")");
    private static final Pattern MANA_LABEL =
            Pattern.compile("(" + NUM + ")\\s*/\\s*(" + NUM + ")[^0-9A-Za-z]{0,4}Mana");
    /** A lone number immediately followed by the "Defense" label. */
    private static final Pattern DEFENSE_LABEL = Pattern.compile("(" + NUM + ")[^0-9A-Za-z]{0,8}Defense");

    /** Private-use glyph Hypixel puts before the island/area name (renders via their pack). */
    private static final char AREA_GLYPH = '\uE067';

    private static final int C_DEFENSE = 0xFF55FF55;
    private static final int C_PURSE = 0xFFFFAA00;
    private static final int C_BITS = 0xFF55FFFF;
    private static final int C_AREA = 0xFFBBBBBB;
    private static final int C_COOKIE = 0xFFFFCC55;
    private static final int C_GOD_POT = 0xFFC08CFF;
    private static final int C_COORDS = 0xFFA8B0BC;
    private static final int C_AFK = 0xFFFFD24A;
    private static final int C_EFFECT = 0xFF9CD8FF;
    private static final int C_EFFECT_WARN = 0xFFFFB86B;
    private static final int C_EFFECT_CRIT = 0xFFFF7B7B;
    private static final int C_CLOCK = 0xFFC9D4E0;
    private static final int C_SESSION = 0xFF9DB8C9;
    private static final int C_DROPS = 0xFF9CE0A0;

    /** "13:20" — the clock line and the chat timestamps share this format. */
    public static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** Idle time after which the AFK line appears. */
    private static final long AFK_AFTER_MS = 60_000L;
    private static final int EFFECT_WARN_SECONDS = 60;
    private static final int EFFECT_CRIT_SECONDS = 15;

    private boolean skyblockActive;
    private boolean sidebarErrorLogged;
    private boolean statsLineParsed;

    // Raw display strings.
    private String health, healthMax, defense, mana, manaMax, purse, bits;
    private String area, cookie, godPot;
    private String healthText, manaText, xpText, speedText;

    // SkyBlock Speed % (the server drives the vanilla movement speed attribute); -1 = unknown.
    private int speed = -1;

    // Bar ratios (0..1, NaN = unknown -> the bar row is hidden).
    private float healthRatio = Float.NaN;
    private float manaRatio = Float.NaN;
    private float xpRatio = Float.NaN;

    private int xpLevel = -1;
    private int xpPercent = -1;

    // Cached optional text lines (area/cookie/god-pot/purse/bits/defense when enabled).
    private String[] lines = new String[0];
    private int[] colors = new int[0];
    // v17: the kind (SkyLiteConfig.LINE_*) of every entry in `lines`, so the editor can move
    // every line as its own block.
    private int[] lineKinds = new int[0];

    // v13: potion-effect snapshot, merged into the info lines by rebuild().
    private String[] effectTexts = new String[0];
    private int[] effectColors = new int[0];
    // v15: the same snapshot in full (name + seconds left) for the expiry warnings, even when
    // the effect list itself is off; -1 = infinite duration.
    private String[] effectNames = new String[0];
    private int[] effectSeconds = new int[0];

    // v15: real-time clock and session length lines (refreshed by the 2 Hz sidebar poll).
    private String clockText;
    private String sessionText;
    /** v18: "3 · 12m" — the session drop counter and the age of the newest drop. */
    private String dropsText;

    // v13: block coordinates (Integer.MIN_VALUE = unknown so far).
    private int coordsX = Integer.MIN_VALUE;
    private int coordsY = Integer.MIN_VALUE;
    private int coordsZ = Integer.MIN_VALUE;

    // v13: AFK timer — the last tick at which the player did anything.
    private boolean activityKnown;
    private long lastActivityAt;
    private double lastX, lastY, lastZ;
    private float lastYaw, lastPitch;
    private String afkText;

    // v13: seconds left on Cookie Buff / God Pot parsed from the sidebar; -1 = unknown.
    private int cookieSeconds = -1;
    private int godPotSeconds = -1;

    public boolean isOnSkyBlock() {
        return skyblockActive;
    }

    /** Current island/area from the sidebar, or null when unknown (used by the optimizer rules). */
    public String area() {
        return area;
    }

    /** Raw purse string from the sidebar; the session tracker parses it into numbers. */
    public String purse() {
        return purse;
    }

    /** Seconds left on the Cookie Buff parsed from the sidebar; -1 when unknown (v13). */
    public int cookieSeconds() {
        return cookieSeconds;
    }

    /** Seconds left on the God Pot parsed from the sidebar; -1 when unknown (v13). */
    public int godPotSeconds() {
        return godPotSeconds;
    }

    /** Active-effect names (with level), soonest to expire first — for the expiry warning (v15). */
    public String[] effectNames() {
        return effectNames;
    }

    /** Seconds left per {@link #effectNames()} entry; -1 = infinite (v15). */
    public int[] effectSeconds() {
        return effectSeconds;
    }

    public String[] lines() {
        return lines;
    }

    public int[] colors() {
        return colors;
    }

    /** Kind of every {@link #lines()} entry — {@code SkyLiteConfig.LINE_*} (v17). */
    public int[] lineKinds() {
        return lineKinds;
    }

    public float healthRatio() {
        return healthRatio;
    }

    public float manaRatio() {
        return manaRatio;
    }

    public float xpRatio() {
        return xpRatio;
    }

    public String healthText() {
        return healthText;
    }

    public String manaText() {
        return manaText;
    }

    public String xpText() {
        return xpText;
    }

    public String speedText() {
        return speedText;
    }

    /** Called for every action-bar (overlay) message. */
    public void parseActionBar(String text) {
        statsLineParsed = false;
        if (text == null || text.isEmpty()) {
            return;
        }
        text = stripFormatting(text);
        if (text.indexOf('/') < 0 && text.indexOf('❈') < 0 && text.indexOf(AREA_GLYPH) < 0) {
            return; // most overlay texts (combat timers, mining progress, ...) are irrelevant
        }
        boolean changed = false;

        Matcher m = HEALTH.matcher(text);
        boolean healthFound = m.find();
        if (!healthFound) {
            m = HEALTH_REV.matcher(text);
            healthFound = m.find();
        }
        if (healthFound) {
            changed |= setHealth(m.group(1), m.group(2));
        }

        m = DEFENSE.matcher(text);
        boolean defenseFound = m.find();
        if (!defenseFound) {
            m = DEFENSE_LABEL.matcher(text);
            defenseFound = m.find();
        }
        if (defenseFound) {
            changed |= setDefense(m.group(1));
        }

        m = MANA.matcher(text);
        boolean manaFound = m.find();
        if (!manaFound) {
            m = MANA_REV.matcher(text);
            manaFound = m.find();
        }
        if (manaFound) {
            changed |= setMana(m.group(1), m.group(2));
        }

        // Fallbacks when the glyphs are unknown: label "Mana" locates the mana pair,
        // "Defense" means this is the combined stats line whose first pair is health.
        if (!manaFound) {
            Matcher label = MANA_LABEL.matcher(text);
            if (label.find()) {
                manaFound = true;
                changed |= setMana(label.group(1), label.group(2));
            }
        }
        if (!healthFound && text.contains("Defense")) {
            Matcher pair = ANY_PAIR.matcher(text);
            if (pair.find() && !(Objects.equals(pair.group(1), mana) && Objects.equals(pair.group(2), manaMax))) {
                healthFound = true;
                changed |= setHealth(pair.group(1), pair.group(2));
            }
        }

        // In hubs the middle stat segment shows the current area instead of Defense.
        int ai = text.indexOf(AREA_GLYPH);
        if (ai < 0) {
            ai = text.indexOf('⏣');
        }
        if (ai >= 0) {
            String rest = text.substring(ai);
            int cut = rest.indexOf("   ");
            String value = (cut > 0 ? rest.substring(0, cut) : rest).trim();
            if (!value.isEmpty() && !value.equals(area)) {
                area = value;
                changed = true;
            }
        }

        // Remember whether this exact message is the combined stats line ("… Defense … Mana"):
        // only then is it safe for the caller to hide it.
        statsLineParsed = healthFound && manaFound
                && (text.contains("Defense") || text.contains("Mana") || ai >= 0);

        if (changed) {
            rebuild();
        }
    }

    /**
     * True when the most recent action bar text was Hypixel's combined stats line and our
     * parsers captured it — a format change simply leaves the line visible instead of losing it.
     */
    public boolean isStatsLineParsed() {
        return statsLineParsed && health != null && mana != null;
    }

    /** Called every 10 ticks; cheap scoreboard read. */
    public void updateSidebar(Minecraft client) {
        try {
            readSidebar(client);
            updatePlayerStats(client);
            updateEffects(client);
            updateTimedLines();
        } catch (Throwable t) {
            // A hostile/unexpected sidebar format must never crash the game: log once, stay passive.
            if (!sidebarErrorLogged) {
                sidebarErrorLogged = true;
                SkyLiteClient.LOGGER.warn("SkyLite: sidebar parse failed, keeping last known values", t);
            }
        }
    }

    private void readSidebar(Minecraft client) {
        if (client == null || client.level == null) {
            setSkyblockActive(false);
            cookieSeconds = -1;
            godPotSeconds = -1;
            return;
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) {
            setSkyblockActive(false);
            cookieSeconds = -1;
            godPotSeconds = -1;
            return;
        }
        String title = objective.getDisplayName().getString();
        boolean active = ChatFilter.indexOfIgnoreCase(title, "skyblock", 0) >= 0;
        setSkyblockActive(active);
        if (!active) {
            return;
        }

        String newPurse = null;
        String newBits = null;
        String newArea = null;
        String newCookie = null;
        String newGodPot = null;
        for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
            String owner = entry.owner();
            if (owner == null) {
                continue;
            }
            // entry.display() is nullable; ownerName() falls back to the raw owner string and
            // formatNameForTeam() re-attaches the team prefix/suffix that Hypixel splits lines into.
            PlayerTeam team = scoreboard.getPlayersTeam(owner);
            String raw = PlayerTeam.formatNameForTeam(team, entry.ownerName()).getString();
            SkyLiteClient.diagSidebar(raw);
            String line = stripFormatting(raw); // the sidebar embeds §-codes right inside the text
            int idx = line.indexOf("Purse:");
            if (idx >= 0) {
                newPurse = tail(line, idx + "Purse:".length());
            }
            idx = line.indexOf("Bits:");
            if (idx >= 0) {
                newBits = tail(line, idx + "Bits:".length());
            }
            idx = line.indexOf("Cookie Buff:");
            if (idx >= 0) {
                newCookie = tail(line, idx + "Cookie Buff:".length());
            }
            idx = line.indexOf("God Potion:");
            if (idx >= 0) {
                newGodPot = tail(line, idx + "God Potion:".length());
            }
            idx = line.indexOf("God Pot:");
            if (idx >= 0) {
                newGodPot = tail(line, idx + "God Pot:".length());
            }
            // The area/island line starts with Hypixel's private-use glyph E067; ⏣ and the
            // "Area:" label are fallbacks.
            idx = line.indexOf(AREA_GLYPH);
            if (idx < 0) {
                idx = line.indexOf('⏣');
            }
            if (idx >= 0) {
                String value = line.substring(idx).trim();
                newArea = value.isEmpty() ? null : value;
            } else {
                idx = line.indexOf("Area:");
                if (idx >= 0) {
                    newArea = tail(line, idx + "Area:".length());
                }
            }
        }
        if (!Objects.equals(newPurse, purse) || !Objects.equals(newBits, bits)
                || !Objects.equals(newArea, area) || !Objects.equals(newCookie, cookie)
                || !Objects.equals(newGodPot, godPot)) {
            purse = newPurse;
            bits = newBits;
            area = newArea;
            cookie = newCookie;
            godPot = newGodPot;
            rebuild();
        }
        // Parsed every poll: the seconds drive the "about to expire" warnings; they are not
        // part of the rendered lines, so no rebuild is needed here.
        cookieSeconds = newCookie == null ? -1 : parseDuration(newCookie);
        godPotSeconds = newGodPot == null ? -1 : parseDuration(newGodPot);
    }

    /**
     * Player-side values that need no server text: the XP bar (SkyBlock repurposes it for level
     * progress) and the walk speed — on SkyBlock the Speed stat arrives as the vanilla movement
     * speed attribute (0.1 = Speed 100).
     */
    private void updatePlayerStats(Minecraft client) {
        if (client == null || client.player == null) {
            xpRatio = Float.NaN;
            return;
        }
        float progress = client.player.experienceProgress;
        if (Float.isNaN(progress)) {
            progress = 0.0F;
        }
        xpRatio = Math.max(0.0F, Math.min(1.0F, progress));
        int percent = Math.round(xpRatio * 100.0F);
        int level = client.player.experienceLevel;
        if (level != xpLevel || percent != xpPercent) {
            xpLevel = level;
            xpPercent = percent;
            rebuild();
        }

        int newSpeed = (int) Math.round(client.player.getAttributeValue(Attributes.MOVEMENT_SPEED) * 1000.0);
        if (newSpeed != speed) {
            speed = newSpeed;
            rebuild();
        }

        if (SkyLiteClient.config != null && SkyLiteClient.config.showCoords) {
            int x = (int) Math.floor(client.player.getX());
            int y = (int) Math.floor(client.player.getY());
            int z = (int) Math.floor(client.player.getZ());
            if (x != coordsX || y != coordsY || z != coordsZ) {
                coordsX = x;
                coordsY = y;
                coordsZ = z;
                rebuild();
            }
        }
    }

    /**
     * Refreshes the potion-effect snapshot (called from the 2 Hz sidebar poll). The full list
     * (name + seconds left) is always kept for the expiry warning (v15); the display strings
     * follow the config. The soonest to expire come first; the row is orange under a minute
     * and red under fifteen seconds.
     */
    private void updateEffects(Minecraft client) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null || client == null || client.player == null) {
            effectNames = new String[0];
            effectSeconds = new int[0];
            if (effectTexts.length > 0) {
                effectTexts = new String[0];
                effectColors = new int[0];
                rebuild();
            }
            return;
        }
        if (!cfg.showEffects && !cfg.effectWarn) {
            // Neither the list nor the expiry warning is on: skip the whole snapshot.
            effectNames = new String[0];
            effectSeconds = new int[0];
            if (effectTexts.length > 0) {
                effectTexts = new String[0];
                effectColors = new int[0];
                rebuild();
            }
            return;
        }
        List<MobEffectInstance> active = new ArrayList<>(client.player.getActiveEffects());
        active.sort(Comparator.comparingInt(StatsState::remainingTicks));
        int total = active.size();
        String[] names = new String[total];
        int[] secondsLeft = new int[total];
        for (int i = 0; i < total; i++) {
            MobEffectInstance effect = active.get(i);
            String name = effect.getEffect().value().getDisplayName().getString();
            int level = effect.getAmplifier() + 1;
            if (level > 1) {
                name = name + " " + roman(level);
            }
            names[i] = name;
            secondsLeft[i] = effect.isInfiniteDuration() ? -1 : effect.getDuration() / 20;
        }
        effectNames = names;
        effectSeconds = secondsLeft;

        if (!cfg.showEffects) {
            if (effectTexts.length > 0) {
                effectTexts = new String[0];
                effectColors = new int[0];
                rebuild();
            }
            return;
        }
        int max = Math.max(1, Math.min(8, cfg.effectsMax));
        int n = Math.min(effectNames.length, max);
        String[] texts = new String[n];
        int[] cols = new int[n];
        for (int i = 0; i < n; i++) {
            int seconds = effectSeconds[i];
            texts[i] = effectNames[i] + "  " + (seconds < 0 ? "∞" : formatClock(seconds));
            cols[i] = seconds < 0 ? C_EFFECT
                    : seconds < EFFECT_CRIT_SECONDS ? C_EFFECT_CRIT
                    : seconds < EFFECT_WARN_SECONDS ? C_EFFECT_WARN : C_EFFECT;
        }
        if (!Arrays.equals(texts, effectTexts)) {
            effectTexts = texts;
            effectColors = cols;
            rebuild();
        }
    }

    /** The per-minute lines: the real-time clock and the session length (v15). */
    private void updateTimedLines() {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null) {
            return;
        }
        String newClock = cfg.showClock ? CLOCK.format(LocalTime.now()) : null;
        if (!Objects.equals(newClock, clockText)) {
            clockText = newClock;
            rebuild();
        }
        String newSession = cfg.showSession ? SkyLiteClient.session.durationText() : null;
        if (!Objects.equals(newSession, sessionText)) {
            sessionText = newSession;
            rebuild();
        }
        String newDrops = cfg.showDrops ? SkyLiteClient.session.dropsText() : null;
        if (!Objects.equals(newDrops, dropsText)) {
            dropsText = newDrops;
            rebuild();
        }
    }

    /**
     * Per-tick activity watcher for the AFK line (v13): movement, rotation or any gameplay key
     * counts; the line appears after a minute of complete silence. Cheap float comparisons only.
     */
    public void tickActivity(Minecraft client) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null || client == null || client.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        double x = client.player.getX();
        double y = client.player.getY();
        double z = client.player.getZ();
        float yaw = client.player.getYRot();
        float pitch = client.player.getXRot();
        if (!activityKnown) {
            activityKnown = true;
            lastActivityAt = now;
            lastX = x;
            lastY = y;
            lastZ = z;
            lastYaw = yaw;
            lastPitch = pitch;
            return;
        }
        boolean moved = Math.abs(x - lastX) > 0.001 || Math.abs(y - lastY) > 0.001
                || Math.abs(z - lastZ) > 0.001
                || Math.abs(yaw - lastYaw) > 0.05F || Math.abs(pitch - lastPitch) > 0.05F;
        lastX = x;
        lastY = y;
        lastZ = z;
        lastYaw = yaw;
        lastPitch = pitch;
        if (!moved && client.gui.screen() == null && anyKeyDown(client.options)) {
            moved = true; // the keys count even while a movement is blocked by a wall
        }
        if (moved) {
            lastActivityAt = now;
            if (afkText != null) {
                afkText = null;
                rebuild();
            }
            return;
        }
        long idle = now - lastActivityAt;
        String text = cfg.showAfk && idle >= AFK_AFTER_MS ? "AFK " + (idle / 60_000L) + "m" : null;
        if (!Objects.equals(text, afkText)) {
            afkText = text;
            rebuild();
        }
    }

    private static boolean anyKeyDown(Options options) {
        return options.keyUp.isDown() || options.keyDown.isDown()
                || options.keyLeft.isDown() || options.keyRight.isDown()
                || options.keyJump.isDown() || options.keyShift.isDown()
                || options.keySprint.isDown() || options.keyAttack.isDown()
                || options.keyUse.isDown();
    }

    private boolean setHealth(String cur, String max) {
        if (Objects.equals(cur, health) && Objects.equals(max, healthMax)) {
            return false;
        }
        health = cur;
        healthMax = max;
        healthRatio = ratio(cur, max);
        return true;
    }

    private boolean setMana(String cur, String max) {
        if (Objects.equals(cur, mana) && Objects.equals(max, manaMax)) {
            return false;
        }
        mana = cur;
        manaMax = max;
        manaRatio = ratio(cur, max);
        return true;
    }

    private boolean setDefense(String value) {
        if (Objects.equals(value, defense)) {
            return false;
        }
        defense = value;
        return true;
    }

    /** Re-composes the cached render strings from current values + config. */
    public void rebuild() {
        SkyLiteConfig cfg = SkyLiteClient.config;
        String format = cfg == null ? "full" : cfg.numberFormat;
        boolean glyphs = cfg != null && cfg.showGlyphs;

        healthText = compose(health, healthMax, healthRatio, format, glyphs ? "❤ " : "");
        manaText = compose(mana, manaMax, manaRatio, format, glyphs ? "✎ " : "");
        xpText = composeXp(format);
        speedText = speed < 0 ? null : speed + "%";

        String[] texts = new String[20];
        int[] cols = new int[20];
        int[] kinds = new int[20];
        int n = 0;
        if (cfg != null) {
            // v16: the word labels are optional — off by default, the values read as bare numbers.
            boolean labels = cfg.infoLabels;
            if (cfg.showAfk && afkText != null) {
                texts[n] = afkText; // idle notice on top: it is the first thing to re-read
                kinds[n] = SkyLiteConfig.LINE_AFK;
                cols[n++] = C_AFK;
            }
            if (cfg.showClock && clockText != null) {
                texts[n] = clockText;
                kinds[n] = SkyLiteConfig.LINE_CLOCK;
                cols[n++] = C_CLOCK;
            }
            if (cfg.showArea && area != null) {
                texts[n] = area;
                kinds[n] = SkyLiteConfig.LINE_AREA;
                cols[n++] = C_AREA;
            }
            if (cfg.showCookie && cookie != null) {
                texts[n] = (labels ? "Cookie: " : "") + cookie;
                kinds[n] = SkyLiteConfig.LINE_COOKIE;
                cols[n++] = C_COOKIE;
            }
            if (cfg.showGodPot && godPot != null) {
                texts[n] = (labels ? "God Pot: " : "") + godPot;
                kinds[n] = SkyLiteConfig.LINE_GODPOT;
                cols[n++] = C_GOD_POT;
            }
            if (cfg.showPurse && purse != null) {
                texts[n] = (labels ? "Purse: " : "") + purse;
                kinds[n] = SkyLiteConfig.LINE_PURSE;
                cols[n++] = C_PURSE;
            }
            if (cfg.showBits && bits != null) {
                texts[n] = (labels ? "Bits: " : "") + bits;
                kinds[n] = SkyLiteConfig.LINE_BITS;
                cols[n++] = C_BITS;
            }
            if (cfg.showSession && sessionText != null) {
                texts[n] = (labels ? "Session " : "") + sessionText;
                kinds[n] = SkyLiteConfig.LINE_SESSION;
                cols[n++] = C_SESSION;
            }
            if (cfg.showDrops && dropsText != null) {
                texts[n] = (labels ? "Drops: " : "") + dropsText;
                kinds[n] = SkyLiteConfig.LINE_DROPS;
                cols[n++] = C_DROPS;
            }
            if (cfg.showCoords && coordsX != Integer.MIN_VALUE) {
                texts[n] = (labels ? "XYZ: " : "") + coordsX + " / " + coordsY + " / " + coordsZ;
                kinds[n] = SkyLiteConfig.LINE_COORDS;
                cols[n++] = C_COORDS;
            }
            if (cfg.showEffects) {
                // The whole effect list is one movable block (v17).
                for (int i = 0; i < effectTexts.length && n < texts.length - 1; i++) {
                    texts[n] = effectTexts[i];
                    kinds[n] = SkyLiteConfig.LINE_EFFECTS;
                    cols[n++] = effectColors[i];
                }
            }
            // Defense stays last, so it sits closest to the bars.
            if (cfg.showDefense && defense != null) {
                texts[n] = "❈ " + defense;
                kinds[n] = SkyLiteConfig.LINE_DEFENSE;
                cols[n++] = C_DEFENSE;
            }
        }
        lines = Arrays.copyOf(texts, n);
        colors = Arrays.copyOf(cols, n);
        lineKinds = Arrays.copyOf(kinds, n);
    }

    /**
     * Bar value text in the configured style: "full" = 566/566, "compact" = 566,
     * "percent" = 84 %; the optional glyph prefix mimics Hypixel's own look.
     */
    private static String compose(String cur, String max, float ratio, String format, String glyph) {
        if (cur == null) {
            return null;
        }
        String body;
        switch (format) {
            case "compact":
                body = cur;
                break;
            case "percent":
                if (Float.isNaN(ratio)) {
                    return null;
                }
                body = Math.round(ratio * 100.0F) + "%";
                break;
            default:
                body = max != null ? cur + "/" + max : cur;
        }
        return glyph + body;
    }

    private String composeXp(String format) {
        if (xpLevel < 0) {
            return null;
        }
        switch (format) {
            case "compact":
                return "Lvl " + xpLevel;
            case "percent":
                return xpPercent + "%";
            default:
                return "Lvl " + xpLevel + " · " + xpPercent + "%";
        }
    }

    private void setSkyblockActive(boolean active) {
        if (skyblockActive != active) {
            skyblockActive = active;
            rebuild();
        }
    }

    /** Removes the legacy section-sign colour codes that the server embeds right inside the text. */
    static String stripFormatting(String s) {
        if (s.indexOf('\u00A7') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean skipNext = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (skipNext) {
                skipNext = false;
            } else if (c == '\u00A7') {
                skipNext = true;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static float ratio(String cur, String max) {
        double c = parseAmount(cur);
        double m = parseAmount(max);
        if (Double.isNaN(c) || Double.isNaN(m) || m <= 0.0) {
            return Float.NaN;
        }
        return (float) Math.max(0.0, Math.min(1.0, c / m));
    }

    /** "1,234" / "12.5k" / "3M" -> double; NaN when unparseable. Shared with the session tracker. */
    static double parseAmount(String s) {
        if (s == null || s.isEmpty()) {
            return Double.NaN;
        }
        int end = s.length();
        double mult = 1.0;
        char last = Character.toLowerCase(s.charAt(end - 1));
        if (last == 'k' || last == 'm' || last == 'b') {
            mult = last == 'k' ? 1e3 : last == 'm' ? 1e6 : 1e9;
            end--;
        }
        // Hand-rolled scan: no temporary strings for thousands separators or the suffix.
        double value = 0.0;
        double fraction = 0.1;
        boolean afterDot = false;
        boolean digits = false;
        for (int i = 0; i < end; i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                digits = true;
                if (afterDot) {
                    value += (c - '0') * fraction;
                    fraction *= 0.1;
                } else {
                    value = value * 10.0 + (c - '0');
                }
            } else if (c == '.') {
                if (afterDot) {
                    return Double.NaN;
                }
                afterDot = true;
            } else if (c != ',' && c != '\'') {
                return Double.NaN;
            }
        }
        return digits ? value * mult : Double.NaN;
    }

    /**
     * "3d 4h" / "12h 30m" / "45s" -> seconds; -1 when no unit could be read. Used for the
     * Cookie Buff / God Pot expiry warnings (v13).
     */
    static int parseDuration(String s) {
        if (s == null || s.isEmpty()) {
            return -1;
        }
        int total = 0;
        int value = -1;
        int units = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (c >= '0' && c <= '9') {
                value = (value < 0 ? 0 : value) * 10 + (c - '0');
            } else if (c == 'd' || c == 'h' || c == 'm' || c == 's') {
                if (value >= 0) {
                    total += value * switch (c) {
                        case 'd' -> 86400;
                        case 'h' -> 3600;
                        case 'm' -> 60;
                        default -> 1;
                    };
                    units++;
                    value = -1;
                }
            }
        }
        return units > 0 ? total : -1;
    }

    /** Seconds -> "m:ss" (or "h:mm:ss" for hours-long effects). */
    private static String formatClock(int seconds) {
        int hours = seconds / 3600;
        int minutes = seconds % 3600 / 60;
        int secs = seconds % 60;
        StringBuilder sb = new StringBuilder(8);
        if (hours > 0) {
            sb.append(hours).append(':');
            if (minutes < 10) {
                sb.append('0');
            }
        }
        sb.append(minutes).append(':');
        if (secs < 10) {
            sb.append('0');
        }
        sb.append(secs);
        return sb.toString();
    }

    /** Potion level as a Roman numeral, matching the tooltips ("Speed II", "Speed IV", …). */
    private static String roman(int level) {
        return switch (level) {
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> Integer.toString(level);
        };
    }

    /** Sort key for the effect list: infinite effects go last. */
    private static int remainingTicks(MobEffectInstance effect) {
        return effect.isInfiniteDuration() ? Integer.MAX_VALUE : effect.getDuration();
    }

    private static String tail(String line, int from) {
        String value = line.substring(from).trim();
        return value.isEmpty() ? null : value;
    }
}
