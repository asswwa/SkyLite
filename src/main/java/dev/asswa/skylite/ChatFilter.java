package dev.asswa.skylite;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

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

    private final ArrayDeque<Repeat> recent = new ArrayDeque<>();

    /** One remembered chat line: its plain text and when it was last seen. */
    private record Repeat(String text, long at) {
    }

    /** {@code false} = hide the message. Called for every incoming chat/system message. */
    public boolean allow(String plain) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null || !cfg.chatFilterEnabled || plain == null || plain.isEmpty()) {
            return true;
        }
        List<String> phrases = cfg.chatFilterPhrases;
        if (phrases != null && !phrases.isEmpty()) {
            String lower = null;
            for (String phrase : phrases) {
                if (phrase == null || phrase.isBlank()) {
                    continue;
                }
                if (lower == null) {
                    lower = plain.toLowerCase(Locale.ROOT);
                }
                if (lower.contains(phrase.trim().toLowerCase(Locale.ROOT))) {
                    return false;
                }
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

    /** Rewrites the message with the player's own nickname highlighted; the rest is kept as is. */
    public Component highlight(Component message) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null || !cfg.chatHighlightName) {
            return message;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return message;
        }
        String name = mc.player.getName().getString();
        if (name == null || name.isEmpty()) {
            return message;
        }
        if (indexOfIgnoreCase(message.getString(), name, 0) < 0) {
            return message; // fast path: most messages do not mention the player
        }
        return highlightIn(message, name);
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

    private static int indexOfIgnoreCase(String text, String needle, int from) {
        int limit = text.length() - needle.length();
        for (int i = Math.max(0, from); i <= limit; i++) {
            if (text.regionMatches(true, i, needle, 0, needle.length())) {
                return i;
            }
        }
        return -1;
    }
}
