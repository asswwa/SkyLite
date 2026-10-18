package dev.asswa.skylite;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

    public boolean isOnSkyBlock() {
        return skyblockActive;
    }

    /** Current island/area from the sidebar, or null when unknown (used by the optimizer rules). */
    public String area() {
        return area;
    }

    public String[] lines() {
        return lines;
    }

    public int[] colors() {
        return colors;
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
            return;
        }
        Scoreboard scoreboard = client.level.getScoreboard();
        Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
        if (objective == null) {
            setSkyblockActive(false);
            return;
        }
        String title = objective.getDisplayName().getString();
        boolean active = title.toLowerCase(Locale.ROOT).contains("skyblock");
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

        List<String> texts = new ArrayList<>(6);
        List<Integer> cols = new ArrayList<>(6);
        if (cfg != null) {
            if (cfg.showArea && area != null) {
                texts.add(area);
                cols.add(C_AREA);
            }
            if (cfg.showCookie && cookie != null) {
                texts.add("Cookie: " + cookie);
                cols.add(C_COOKIE);
            }
            if (cfg.showGodPot && godPot != null) {
                texts.add("God Pot: " + godPot);
                cols.add(C_GOD_POT);
            }
            if (cfg.showPurse && purse != null) {
                texts.add("Purse: " + purse);
                cols.add(C_PURSE);
            }
            if (cfg.showBits && bits != null) {
                texts.add("Bits: " + bits);
                cols.add(C_BITS);
            }
            // Defense stays last, so it sits closest to the bars.
            if (cfg.showDefense && defense != null) {
                texts.add("❈ " + defense);
                cols.add(C_DEFENSE);
            }
        }
        lines = texts.toArray(new String[0]);
        colors = new int[cols.size()];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = cols.get(i);
        }
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
    private static String stripFormatting(String s) {
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

    /** "1,234" / "12.5k" / "3M" -> double; NaN when unparseable. */
    private static double parseAmount(String s) {
        if (s == null || s.isEmpty()) {
            return Double.NaN;
        }
        String v = s.replace(",", "").replace("'", "");
        double mult = 1.0;
        char last = Character.toLowerCase(v.charAt(v.length() - 1));
        if (last == 'k' || last == 'm' || last == 'b') {
            mult = last == 'k' ? 1e3 : last == 'm' ? 1e6 : 1e9;
            v = v.substring(0, v.length() - 1);
        }
        try {
            return Double.parseDouble(v) * mult;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static String tail(String line, int from) {
        String value = line.substring(from).trim();
        return value.isEmpty() ? null : value;
    }
}
