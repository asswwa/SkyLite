package dev.asswa.skylite;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Small client-side chat helpers: highlight the player's own nickname in incoming messages and
 * hide spam (configured phrases and recent repeats). Fed by the Fabric message events in
 * {@link SkyLiteClient}. On Hypixel every chat line is a system message, so the nickname
 * highlight covers the whole chat there; other servers use player-chat packets, which only get
 * the filter (Fabric has no "modify chat" event to rewrite them).
 */
public final class ChatFilter {
    /** A message identical to one seen within this many milliseconds is collapsed to a single line. */
    public static final long REPEAT_WINDOW_MS = 10_000L;
    /** How many recent messages the repeat filter remembers, so a burst collapses as a whole. */
    private static final int REPEAT_MEMORY = 12;
    /** Chat-rule action bits ("phrase => action"); v15 allows several joined with "+". */
    private static final int A_SOUND = 1;
    private static final int A_TOAST = 2;
    private static final int A_HIGHLIGHT = 4;
    private static final int A_HIDE = 8;
    private static final long RULE_SOUND_COOLDOWN_MS = 900L;
    private static final long RULE_TOAST_COOLDOWN_MS = 400L;
    private static final int MAX_RULES = 50;

    private final ArrayDeque<Repeat> recent = new ArrayDeque<>();

    /** One parsed chat rule: a lowercase phrase plus the action bitmask. */
    private record Rule(String phrase, int actions) {
    }

    // Parsed rules, rebuilt only when the config list instance changes (screens replace it on save).
    private List<String> rulesSource;
    private List<Rule> parsedRules = List.of();
    private long lastRuleSoundAt;
    private long lastRuleToastAt;

    // Lowercase hidden-phrase needles, cached the same way.
    private static final String[] NO_PHRASES = new String[0];
    private List<String> phraseSource;
    private String[] phraseNeedles = NO_PHRASES;

    // v15: messages containing any of these skip the rules altogether (cached by list identity).
    private List<String> ignoreSource;
    private String[] ignoreNeedles = NO_PHRASES;

    // The player's own name, recomposed only when the player (UUID) changes.
    private UUID cachedNameId;
    private String cachedName;

    /** One remembered chat line: its plain text and when it was last seen. */
    private record Repeat(String text, long at) {
    }

    /** {@code false} = hide the message. Called for every incoming chat/system message. */
    public boolean allow(String plain) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null || plain == null || plain.isEmpty()) {
            return true;
        }
        List<Rule> rules = rules();
        if (!rules.isEmpty() && !ignored(plain)) {
            for (Rule rule : rules) { // "hide" wins before any other action can fire
                if ((rule.actions() & A_HIDE) != 0 && indexOfIgnoreCase(plain, rule.phrase(), 0) >= 0) {
                    return false;
                }
            }
            long now = System.currentTimeMillis();
            for (Rule rule : rules) {
                if (indexOfIgnoreCase(plain, rule.phrase(), 0) < 0) {
                    continue;
                }
                if ((rule.actions() & A_SOUND) != 0 && now - lastRuleSoundAt >= RULE_SOUND_COOLDOWN_MS) {
                    lastRuleSoundAt = now;
                    Alerts.pling();
                }
                if ((rule.actions() & A_TOAST) != 0 && now - lastRuleToastAt >= RULE_TOAST_COOLDOWN_MS) {
                    lastRuleToastAt = now;
                    SkyLiteClient.alerts.toastText(Alerts.clip(plain));
                }
            }
        }
        if (!cfg.chatFilterEnabled) {
            return true;
        }
        for (String needle : phrases()) {
            if (indexOfIgnoreCase(plain, needle, 0) >= 0) {
                return false;
            }
        }
        if (cfg.chatHideRepeats) {
            long now = System.currentTimeMillis();
            // Compare with every line of the last window, not just the previous one: Hypixel
            // interleaves separator lines and other notices between repeated messages.
            recent.removeIf(entry -> now - entry.at() > REPEAT_WINDOW_MS);
            for (Iterator<Repeat> it = recent.iterator(); it.hasNext(); ) {
                Repeat entry = it.next();
                if (entry.text().equals(plain)) {
                    it.remove();
                    recent.addLast(new Repeat(plain, now)); // refresh: a continuous stream stays collapsed
                    return false;
                }
            }
            recent.addLast(new Repeat(plain, now));
            while (recent.size() > REPEAT_MEMORY) {
                recent.removeFirst();
            }
        }
        return true;
    }

    /**
     * Rewrites the message with the player's own nickname highlighted, a marker added when a
     * chat rule with the "hl" action matches and, when enabled, a "[HH:MM]" timestamp (v15);
     * the rest is kept as is. Timestamps only cover system messages (on Hypixel, all chat):
     * Fabric has no "modify chat" event for player-chat packets.
     */
    public Component highlight(Component message) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null) {
            return message;
        }
        Component result = message;
        if (cfg.chatHighlightName) {
            String name = playerName();
            if (name != null && !name.isEmpty() && containsIgnoreCase(result, name)) {
                result = highlightIn(result, name);
            }
        }
        List<Rule> rules = rules();
        if (!rules.isEmpty() && !ignored(result)) {
            for (Rule rule : rules) {
                if ((rule.actions() & A_HIGHLIGHT) != 0 && containsIgnoreCase(result, rule.phrase())) {
                    result = Component.literal("\u25A0 ").withStyle(ChatFormatting.AQUA).append(result);
                    break; // one marker per line reads better than a stack of them
                }
            }
        }
        if (cfg.chatTimestamps) {
            result = Component.literal("[" + StatsState.CLOCK.format(LocalDateTime.now()) + "] ")
                    .withStyle(ChatFormatting.DARK_GRAY).append(result);
        }
        return result;
    }

    /** True when the message contains one of the rules-ignore phrases (v15). */
    private boolean ignored(String plain) {
        for (String needle : ignores()) {
            if (indexOfIgnoreCase(plain, needle, 0) >= 0) {
                return true;
            }
        }
        return false;
    }

    private boolean ignored(Component message) {
        for (String needle : ignores()) {
            if (containsIgnoreCase(message, needle)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Case-insensitive search over the plain-text segments, without flattening the whole
     * component into a temporary string on every chat line. Highlights only ever rewrite a
     * single segment anyway, so a per-segment search matches what {@link #highlightIn} can do.
     */
    private static boolean containsIgnoreCase(Component component, String needle) {
        if (component.getContents() instanceof PlainTextContents plain
                && indexOfIgnoreCase(plain.text(), needle, 0) >= 0) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (containsIgnoreCase(sibling, needle)) {
                return true;
            }
        }
        return false;
    }

    /** Parses the "phrase => action" config list; cached until the list instance is replaced. */
    private List<Rule> rules() {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null) {
            return List.of();
        }
        List<String> source = cfg.chatRules;
        if (source == rulesSource) {
            return parsedRules;
        }
        rulesSource = source;
        if (source == null || source.isEmpty()) {
            parsedRules = List.of();
            return parsedRules;
        }
        List<Rule> out = new ArrayList<>();
        for (String entry : source) {
            if (entry == null || out.size() >= MAX_RULES) {
                continue;
            }
            Rule rule = parseRule(entry);
            if (rule != null) {
                out.add(rule);
            }
        }
        parsedRules = out;
        return out;
    }

    /** Hidden phrases as lowercase, trimmed needles (cached by list identity). */
    private String[] phrases() {
        SkyLiteConfig cfg = SkyLiteClient.config;
        List<String> source = cfg == null ? null : cfg.chatFilterPhrases;
        if (source == phraseSource) {
            return phraseNeedles;
        }
        phraseSource = source;
        if (source == null || source.isEmpty()) {
            phraseNeedles = NO_PHRASES;
            return phraseNeedles;
        }
        List<String> needles = new ArrayList<>(source.size());
        for (String phrase : source) {
            if (phrase == null) {
                continue;
            }
            String needle = phrase.trim().toLowerCase(Locale.ROOT);
            if (!needle.isEmpty()) {
                needles.add(needle);
            }
        }
        phraseNeedles = needles.toArray(NO_PHRASES);
        return phraseNeedles;
    }

    /** Rules-ignore phrases as lowercase needles (cached by list identity). */
    private String[] ignores() {
        SkyLiteConfig cfg = SkyLiteClient.config;
        List<String> source = cfg == null ? null : cfg.chatRulesIgnore;
        if (source == ignoreSource) {
            return ignoreNeedles;
        }
        ignoreSource = source;
        if (source == null || source.isEmpty()) {
            ignoreNeedles = NO_PHRASES;
            return ignoreNeedles;
        }
        List<String> needles = new ArrayList<>(source.size());
        for (String phrase : source) {
            if (phrase == null) {
                continue;
            }
            String needle = phrase.trim().toLowerCase(Locale.ROOT);
            if (!needle.isEmpty()) {
                needles.add(needle);
            }
        }
        ignoreNeedles = needles.toArray(NO_PHRASES);
        return ignoreNeedles;
    }

    /** The local player's name; Player#getName allocates a component, so it is cached per UUID. */
    private String playerName() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }
        UUID id = mc.player.getUUID();
        if (id.equals(cachedNameId)) {
            return cachedName;
        }
        cachedNameId = id;
        cachedName = mc.player.getName().getString();
        return cachedName;
    }

    /**
     * "phrase => action" (also "->"; RU action words accepted). Several actions may be joined
     * with "+" — {@code "rare drop! => sound+toast"} (v15). Null when the entry is unusable.
     */
    private static Rule parseRule(String entry) {
        String s = entry.trim();
        int sep = s.indexOf("=>");
        if (sep < 0) {
            sep = s.indexOf("->");
        }
        if (sep <= 0) {
            return null;
        }
        String phrase = s.substring(0, sep).trim().toLowerCase(Locale.ROOT);
        String action = s.substring(sep + 2).trim().toLowerCase(Locale.ROOT);
        if (phrase.isEmpty()) {
            return null;
        }
        int actions = 0;
        for (String token : action.split("\\+")) {
            actions |= switch (token.trim()) {
                case "sound", "звук" -> A_SOUND;
                case "toast", "тост" -> A_TOAST;
                case "hl", "highlight", "подсветка" -> A_HIGHLIGHT;
                case "hide", "скрыть" -> A_HIDE;
                default -> 0;
            };
        }
        return actions == 0 ? null : new Rule(phrase, actions);
    }

    /** Rebuilds the component tree; only plain-text segments carry text we can rewrite. */
    private static Component highlightIn(Component source, String name) {
        Style base = source.getStyle();
        MutableComponent rebuilt;
        if (source.getContents() instanceof PlainTextContents plain) {
            rebuilt = split(plain.text(), base, name);
        } else {
            rebuilt = MutableComponent.create(source.getContents()).withStyle(base);
        }
        for (Component sibling : source.getSiblings()) {
            rebuilt.append(highlightIn(sibling, name));
        }
        return rebuilt;
    }

    /** Splits one text segment into pieces, colouring every occurrence of {@code name}. */
    private static MutableComponent split(String text, Style base, String name) {
        MutableComponent out = Component.empty();
        int from = 0;
        while (true) {
            int hit = indexOfIgnoreCase(text, name, from);
            if (hit < 0) {
                if (from < text.length()) {
                    out.append(Component.literal(text.substring(from)).withStyle(base));
                }
                return out;
            }
            if (hit > from) {
                out.append(Component.literal(text.substring(from, hit)).withStyle(base));
            }
            out.append(Component.literal(text.substring(hit, hit + name.length()))
                    .withStyle(base.withColor(ChatFormatting.GOLD).withBold(true)));
            from = hit + name.length();
        }
    }

    /** Case-insensitive indexOf, shared with the session tracker's drop matching. */
    static int indexOfIgnoreCase(String text, String needle, int from) {
        int limit = text.length() - needle.length();
        for (int i = Math.max(0, from); i <= limit; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }
}
