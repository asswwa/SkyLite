package dev.asswa.skylite;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import org.joml.Matrix3x2fStack;

/**
 * Draws the minimalist bars (health / mana / XP), the optional info lines, the FPS/ping corner
 * widget, the alert flash and the toggle toast. Everything is plain rectangles and cached
 * strings — the cheapest possible HUD work.
 *
 * <p>Bars come in six styles ({@link SkyLiteConfig#barStyle}): classic, rounded, capsule, gloss,
 * minimal, framed — still plain rectangles only, no textures.
 *
 * <p>The panel steps aside while a GUI screen is open (inventory, chests, menus) — only the chat
 * keeps it visible, like the vanilla HUD does.
 *
 * <p>Two layouts: {@code "vanilla"} (default) puts each bar into the spot of the HUD element
 * it replaces — hearts row on the left, hunger row on the right, the XP bar right above the
 * hotbar (per-bar offsets and sizes from the in-game editor are applied on top); {@code "panel"}
 * is the stacked card at hudX/hudY.
 */
public final class HudPanel implements HudElement {
    private static final int C_BAR_TRACK = 0x80000000;
    private static final int C_PANEL_BG = 0x66000000;
    private static final int C_TEXT_BG = 0xB0000000;
    private static final int C_FRAME = 0xB9000000;

    // Where the value of a bar is drawn: inside (default) or beside it (the "minimal" style).
    static final int SIDE_CENTER = 0;
    static final int SIDE_LEFT = -1;
    static final int SIDE_RIGHT = 1;

    // Corner-widget text and width, recomposed only when FPS or ping changes (MIN_VALUE
    // forces the first composition).
    private int lastFps = Integer.MIN_VALUE;
    private int lastPing = Integer.MIN_VALUE;
    private String cornerText;
    private int cornerWidth;

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null || mc.player == null || mc.level == null) {
            return;
        }
        if (mc.gui.screen() != null && !(mc.gui.screen() instanceof ChatScreen)) {
            return; // stay out of the open GUIs; only the chat keeps the HUD, like vanilla
        }
        if (cfg.hudEnabled) {
            drawStats(g, mc.font, cfg);
            if (cfg.showSpeed) {
                drawSpeed(g, mc.font, cfg);
            }
            if (cfg.showPingFps) {
                drawCornerInfo(g, mc.font, cfg, mc);
            }
        }
        drawFlash(g, mc.font);
        drawToast(g, mc.font);
    }

    private void drawStats(GuiGraphicsExtractor g, Font font, SkyLiteConfig cfg) {
        StatsState stats = SkyLiteClient.stats;
        if (!stats.isOnSkyBlock()) {
            return;
        }
        if ("panel".equals(cfg.barLayout)) {
            drawPanel(g, font, cfg, stats);
        } else {
            drawVanillaLayout(g, font, cfg, stats);
        }
    }

    /** Bar 0 = HP, 1 = mana, 2 = XP. Sizes come from the config with sane limits. */
    private static int barWidth(SkyLiteConfig cfg, int which) {
        int w = which == 0 ? cfg.hpBarWidth : which == 1 ? cfg.manaBarWidth : cfg.xpBarWidth;
        return Math.max(40, Math.min(w, 320));
    }

    private static int barHeight(SkyLiteConfig cfg, int which) {
        int h = which == 0 ? cfg.hpBarHeight : which == 1 ? cfg.manaBarHeight : cfg.xpBarHeight;
        h = Math.max(2, Math.min(h, 20));
        return switch (styleOf(cfg)) {
            case "capsule" -> 13; // round caps need room to read as a capsule
            case "minimal" -> 5;  // the thin bar is the whole point of the style
            default -> h;
        };
    }

    /**
     * Each bar in its default spot (or wherever the in-game editor moved it): hearts row (left),
     * hunger row (right), XP bar hugging the hotbar. Per-bar offsets from the config are applied
     * on top of the anchors, so a moved bar keeps its place across GUI-scale and window changes.
     */
    private void drawVanillaLayout(GuiGraphicsExtractor g, Font font, SkyLiteConfig cfg, StatsState stats) {
        boolean hp = cfg.showHealthBar && !Float.isNaN(stats.healthRatio());
        boolean mp = cfg.showManaBar && !Float.isNaN(stats.manaRatio());
        boolean xp = cfg.showXpBar && !Float.isNaN(stats.xpRatio());
        String[] lines = stats.lines();
        if (!hp && !mp && !xp && lines.length == 0) {
            return;
        }

        Anchors a = anchors(g.guiWidth(), g.guiHeight(), cfg);
        boolean numbers = cfg.showBarNumbers;

        int hpX = a.hpX + cfg.hpBarOffsetX;
        int hpY = a.rowY + cfg.hpBarOffsetY;
        int mpX = a.manaX + cfg.manaBarOffsetX;
        int mpY = a.rowY + cfg.manaBarOffsetY;
        int xpX = a.xpX + cfg.xpBarOffsetX;
        int xpY = a.xpY + cfg.xpBarOffsetY;

        if (hp) {
            drawStyledBar(g, font, hpX, hpY, a.hpW, a.hpH, stats.healthRatio(), cfg.healthRgb(),
                    numbers ? stats.healthText() : null, true, textSide(cfg, 0));
        }
        if (mp) {
            drawStyledBar(g, font, mpX, mpY, a.manaW, a.manaH, stats.manaRatio(), cfg.manaRgb(),
                    numbers ? stats.manaText() : null, true, textSide(cfg, 1));
        }
        if (xp) {
            drawStyledBar(g, font, xpX, xpY, a.xpW, a.xpH, stats.xpRatio(), cfg.xpRgb(),
                    numbers ? stats.xpText() : null, false, textSide(cfg, 2)); // the XP bar never pulses
        }

        // Optional text lines (defense, …) follow the HP bar, like the vanished armor row.
        if (lines.length > 0) {
            int[] colors = stats.colors();
            int lineH = font.lineHeight + 1;
            int ly = hpY - 1 - lines.length * lineH;
            for (int i = 0; i < lines.length; i++) {
                g.text(font, lines[i], hpX, ly + i * lineH, colors[i], true);
            }
        }
    }

    /**
     * Where each bar sits by default, in GUI coordinates (before per-bar offsets): HP in the
     * hearts row on the left, mana in the hunger row on the right, the XP bar centred two pixels
     * above the hotbar. Rows stack bottom-up using the tallest bar of each row. The Speed %
     * defaults just right of the hotbar.
     */
    static final class Anchors {
        final int hpX;
        final int manaX;
        final int rowY;
        final int xpX;
        final int xpY;
        final int hpW;
        final int manaW;
        final int xpW;
        final int hpH;
        final int manaH;
        final int xpH;
        final int speedX;
        final int speedY;

        Anchors(int hpX, int manaX, int rowY, int xpX, int xpY,
                int hpW, int manaW, int xpW, int hpH, int manaH, int xpH,
                int speedX, int speedY) {
            this.hpX = hpX;
            this.manaX = manaX;
            this.rowY = rowY;
            this.xpX = xpX;
            this.xpY = xpY;
            this.hpW = hpW;
            this.manaW = manaW;
            this.xpW = xpW;
            this.hpH = hpH;
            this.manaH = manaH;
            this.xpH = xpH;
            this.speedX = speedX;
            this.speedY = speedY;
        }
    }

    static Anchors anchors(int guiWidth, int guiHeight, SkyLiteConfig cfg) {
        int center = guiWidth / 2;
        int hpW = barWidth(cfg, 0);
        int manaW = barWidth(cfg, 1);
        int xpW = Math.min(barWidth(cfg, 2), Math.max(60, guiWidth - 20));
        int hpH = barHeight(cfg, 0);
        int manaH = barHeight(cfg, 1);
        int xpH = barHeight(cfg, 2);
        int xpY = guiHeight - 24 - xpH;
        int rowY = xpY - 1 - Math.max(hpH, manaH);
        int hpX = center - 91;
        return new Anchors(hpX, center + 91 - manaW, rowY, center - xpW / 2, xpY,
                hpW, manaW, xpW, hpH, manaH, xpH,
                center + 91 + 4, guiHeight - 16); // right of the hotbar, vertically centred on it
    }

    /** The bar style from the config; unknown values fall back to the original flat look. */
    static String styleOf(SkyLiteConfig cfg) {
        return cfg == null || cfg.barStyle == null ? "classic" : cfg.barStyle;
    }

    /**
     * Where the value of bar {@code which} (0 HP, 1 mana, 2 XP) goes for the current style:
     * {@code "minimal"} puts the numbers beside the bars — HP on the left, mana/XP on the
     * right — every other style keeps them inside.
     */
    static int textSide(SkyLiteConfig cfg, int which) {
        if ("minimal".equals(styleOf(cfg))) {
            return which == 0 ? SIDE_LEFT : SIDE_RIGHT;
        }
        return SIDE_CENTER;
    }

    /** One bar in the configured style: shape first, then the value text (inside or beside). */
    static void drawStyledBar(GuiGraphicsExtractor g, Font font, int x, int y, int barW, int barH,
                              float ratio, int color, String text, boolean pulse, int side) {
        drawBarShape(g, x, y, barW, barH, ratio, color, pulse);
        if (text == null) {
            return;
        }
        if (side == SIDE_CENTER) {
            drawBarText(g, font, x, y, barW, barH, text);
        } else {
            drawSideText(g, font, x, y, barW, barH, text, side);
        }
    }

    /** Outline, track and fill — the styles differ here and nowhere else. */
    private static void drawBarShape(GuiGraphicsExtractor g, int x, int y, int barW, int barH,
                                     float ratio, int color, boolean pulse) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        boolean outline = cfg != null && cfg.barOutline;
        float clamped = Math.max(0.0F, Math.min(1.0F, ratio));
        int fill = Math.round(barW * clamped);
        int fillC = fillColor(cfg, ratio, color, pulse);
        int track = trackColor(cfg);
        switch (styleOf(cfg)) {
            case "rounded" -> {
                if (outline) {
                    shapeRows(g, x - 1, y - 1, barW + 2, barH + 2, roundInsets(barH + 2, 3), outlineOf(color));
                }
                shapeRows(g, x, y, barW, barH, roundInsets(barH, 2), track);
                if (fill >= barW) {
                    shapeRows(g, x, y, barW, barH, roundInsets(barH, 2), fillC);
                } else if (fill > 0) {
                    shapeRowsLeft(g, x, y, fill, barH, roundInsets(barH, 2), fillC);
                }
            }
            case "capsule" -> {
                if (outline) {
                    shapeRows(g, x - 1, y - 1, barW + 2, barH + 2, capsuleInsets(barH + 2), outlineOf(color));
                }
                shapeRows(g, x, y, barW, barH, capsuleInsets(barH), track);
                if (fill >= barW) {
                    shapeRows(g, x, y, barW, barH, capsuleInsets(barH), fillC);
                } else if (fill > 0) {
                    shapeRowsLeft(g, x, y, fill, barH, capsuleInsets(barH), fillC);
                }
            }
            case "gloss" -> {
                if (outline) {
                    g.fill(x - 1, y - 1, x + barW + 1, y + barH + 1, outlineOf(color));
                }
                g.fill(x, y, x + barW, y + barH, track);
                if (fill > 0) {
                    g.fill(x, y, x + fill, y + barH, fillC);
                    g.fill(x, y, x + fill, y + Math.max(3, barH / 2), lighten(fillC, 0.5F));
                    g.fill(x, y, x + fill, y + 1, 0x64FFFFFF);
                    g.fill(x, y + barH - 2, x + fill, y + barH, darken(fillC, 0.72F));
                }
            }
            case "framed" -> {
                g.fill(x - 2, y - 2, x + barW + 2, y + barH + 2, C_FRAME);
                // The recess stays dark enough to read as a slot even with the track off.
                int innerPct = cfg == null ? 50 : Math.max(35, Math.min(100, cfg.trackOpacity));
                g.fill(x, y, x + barW, y + barH, innerPct * 255 / 100 << 24);
                int inner = Math.round((barW - 2) * clamped);
                if (inner > 0) {
                    g.fill(x + 1, y + 1, x + 1 + inner, y + barH - 1, fillC);
                }
            }
            default -> { // "classic" (and any unknown value): the original flat bar
                if (outline) {
                    g.fill(x - 1, y - 1, x + barW + 1, y + barH + 1, outlineOf(color));
                }
                g.fill(x, y, x + barW, y + barH, track);
                if (fill > 0) {
                    g.fill(x, y, x + fill, y + barH, fillC);
                }
            }
        }
    }

    /** Value beside the bar ("minimal"): HP sits to the left, mana/XP to the right. */
    private static void drawSideText(GuiGraphicsExtractor g, Font font, int x, int y, int barW, int barH,
                                     String text, int side) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        int textColor = cfg == null ? 0xFFFFFFFF : cfg.textRgb();
        int tx = side == SIDE_LEFT ? x - 5 - font.width(text) : x + barW + 5;
        g.text(font, text, tx, y + (barH - font.lineHeight) / 2, textColor, true);
    }

    // --- shape helpers: pixel insets per row, cached per bar height (bars are ≤ 20 px thin) ---
    private static final int[][] INS_ROUNDED = new int[24][];       // radius 2
    private static final int[][] INS_ROUNDED_OUTER = new int[24][]; // radius 3
    private static final int[][] INS_CAPSULE = new int[24][];       // radius = half the height

    /** Staircase insets for the "rounded" style: [r … 1, 0 … 0, 1 … r]. */
    private static int[] roundInsets(int h, int r) {
        int[][] cache = r >= 3 ? INS_ROUNDED_OUTER : INS_ROUNDED;
        if (h < 5 || h >= cache.length) {
            return null; // too thin to round — the plain rectangle reads better
        }
        if (cache[h] == null) {
            r = Math.min(r, h / 2);
            int[] table = new int[h];
            for (int i = 0; i < r; i++) {
                table[i] = r - i;
                table[h - 1 - i] = r - i;
            }
            cache[h] = table;
        }
        return cache[h];
    }

    /** Circle insets for the "capsule" style: the ends follow a half-circle profile. */
    private static int[] capsuleInsets(int h) {
        if (h < 5 || h >= INS_CAPSULE.length) {
            return null;
        }
        if (INS_CAPSULE[h] == null) {
            int r = h / 2;
            int[] table = new int[h];
            for (int dy = 0; dy < h; dy++) {
                int d = Math.min(dy, h - 1 - dy);
                if (d >= r) {
                    continue;
                }
                double frac = (d + 0.5) / r;
                double v = r * (1.0 - frac);
                table[dy] = (int) Math.round(r - Math.sqrt(Math.max(0.0, (double) r * r - v * v)));
            }
            INS_CAPSULE[h] = table;
        }
        return INS_CAPSULE[h];
    }

    /** Fills rows of width {@code w} following the inset table; both ends are rounded. */
    private static void shapeRows(GuiGraphicsExtractor g, int x, int y, int w, int h, int[] ins, int c) {
        if (ins == null) {
            g.fill(x, y, x + w, y + h, c);
            return;
        }
        int dy = 0;
        while (dy < h) {
            int ix = ins[dy];
            int end = dy + 1;
            while (end < h && ins[end] == ix) {
                end++; // merge equal rows into one rectangle
            }
            if (x + w - ix > x + ix) {
                g.fill(x + ix, y + dy, x + w - ix, y + end, c);
            }
            dy = end;
        }
    }

    /** Same, but the right end stays square — for a partially filled bar. */
    private static void shapeRowsLeft(GuiGraphicsExtractor g, int x, int y, int w, int h, int[] ins, int c) {
        if (ins == null) {
            g.fill(x, y, x + w, y + h, c);
            return;
        }
        int dy = 0;
        while (dy < h) {
            int ix = ins[dy];
            int end = dy + 1;
            while (end < h && ins[end] == ix) {
                end++;
            }
            if (x + w > x + ix) {
                g.fill(x + ix, y + dy, x + w, y + end, c);
            }
            dy = end;
        }
    }

    /** The fill colour blended towards white (the pulse alpha is kept). */
    private static int lighten(int argb, float f) {
        int a = argb & 0xFF000000;
        int r = Math.min(255, (int) (((argb >> 16) & 0xFF) + (255 - ((argb >> 16) & 0xFF)) * f));
        int g2 = Math.min(255, (int) (((argb >> 8) & 0xFF) + (255 - ((argb >> 8) & 0xFF)) * f));
        int b = Math.min(255, (int) ((argb & 0xFF) + (255 - (argb & 0xFF)) * f));
        return a | (r << 16) | (g2 << 8) | b;
    }

    /** The fill colour darkened by the factor (the pulse alpha is kept). */
    private static int darken(int argb, float f) {
        int a = argb & 0xFF000000;
        int r = (int) (((argb >> 16) & 0xFF) * f);
        int g2 = (int) (((argb >> 8) & 0xFF) * f);
        int b = (int) ((argb & 0xFF) * f);
        return a | (r << 16) | (g2 << 8) | b;
    }

    /** Track (background) colour: black at the configured opacity. */
    private static int trackColor(SkyLiteConfig cfg) {
        if (cfg == null) {
            return C_BAR_TRACK;
        }
        int alpha = Math.max(0, Math.min(100, cfg.trackOpacity)) * 255 / 100;
        return alpha << 24;
    }

    /** Fill colour with the optional low-value pulse (soft blink below ~30 %). */
    private static int fillColor(SkyLiteConfig cfg, float ratio, int color, boolean pulse) {
        if (cfg == null || !pulse || !cfg.lowValuePulse || ratio >= 0.3F) {
            return color;
        }
        float t = Math.abs((float) Math.sin(System.currentTimeMillis() / 140.0));
        int alpha = (int) ((0.5F + 0.5F * t) * ((color >>> 24) & 0xFF));
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    /** A darker copy of the bar colour, used for the 1-px outline. */
    private static int outlineOf(int color) {
        int r = (int) (((color >> 16) & 0xFF) * 0.45F);
        int g2 = (int) (((color >> 8) & 0xFF) * 0.45F);
        int b = (int) ((color & 0xFF) * 0.45F);
        return 0xFF000000 | (r << 16) | (g2 << 8) | b;
    }

    /**
     * The value is exactly as tall as the bar: drawn natively when the bar is at least font
     * height, scaled down when the bar is thinner. By default the number sits on a dark pill
     * spanning the full bar height (configurable via the "Number background" option).
     */
    private static void drawBarText(GuiGraphicsExtractor g, Font font, int x, int y, int barW, int barH,
                                    String text) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        int textColor = cfg == null ? 0xFFFFFFFF : cfg.textRgb();
        boolean pill = cfg == null || cfg.textBackground;
        int tw = font.width(text);
        int fh = font.lineHeight;
        if (barH >= fh) {
            int tx = x + (barW - tw) / 2;
            int ty = y + (barH - fh) / 2;
            if (pill) {
                g.fill(tx - 2, y, tx + tw + 2, y + barH, C_TEXT_BG);
            }
            g.text(font, text, tx, ty, textColor, true);
        } else {
            float s = (float) barH / fh;
            Matrix3x2fStack pose = g.pose();
            pose.pushMatrix();
            pose.translate(x + (barW - tw * s) / 2.0F, y);
            pose.scale(s, s);
            if (pill) {
                g.fill(-2, 0, tw + 2, fh, C_TEXT_BG);
            }
            g.text(font, text, 0, 0, textColor, true);
            pose.popMatrix();
        }
    }

    /** The stacked card layout (barLayout "panel"): bars and text lines at hudX/hudY. */
    private void drawPanel(GuiGraphicsExtractor g, Font font, SkyLiteConfig cfg, StatsState stats) {
        boolean hp = cfg.showHealthBar && !Float.isNaN(stats.healthRatio());
        boolean mp = cfg.showManaBar && !Float.isNaN(stats.manaRatio());
        boolean xp = cfg.showXpBar && !Float.isNaN(stats.xpRatio());
        String[] lines = stats.lines();
        if (!hp && !mp && !xp && lines.length == 0) {
            return;
        }

        int x = cfg.hudX;
        int y = cfg.hudY;
        boolean scaled = Math.abs(cfg.hudScale - 1.0F) > 0.001F;

        Matrix3x2fStack pose = g.pose();
        if (scaled) {
            pose.pushMatrix();
            pose.translate(x, y);
            pose.scale(cfg.hudScale, cfg.hudScale);
            x = 0;
            y = 0;
        }

        int rowH = font.lineHeight + 2;
        boolean numbers = cfg.showBarNumbers;

        int barRows = (hp ? 1 : 0) + (mp ? 1 : 0) + (xp ? 1 : 0);
        int totalH = barRows * rowH + lines.length * (font.lineHeight + 1);

        if (cfg.hudBackground) {
            int maxW = Math.max(barWidth(cfg, 0), Math.max(barWidth(cfg, 1), barWidth(cfg, 2)));
            if (numbers) {
                if (hp && stats.healthText() != null) {
                    maxW = Math.max(maxW, barWidth(cfg, 0) + 4 + font.width(stats.healthText()));
                }
                if (mp && stats.manaText() != null) {
                    maxW = Math.max(maxW, barWidth(cfg, 1) + 4 + font.width(stats.manaText()));
                }
                if (xp && stats.xpText() != null) {
                    maxW = Math.max(maxW, barWidth(cfg, 2) + 4 + font.width(stats.xpText()));
                }
            }
            for (String line : lines) {
                maxW = Math.max(maxW, font.width(line));
            }
            g.fill(x - 3, y - 3, x + maxW + 3, y + totalH + 1, C_PANEL_BG);
        }

        int rowY = y;
        if (hp) {
            drawBar(g, font, x, rowY, barWidth(cfg, 0), Math.min(barHeight(cfg, 0), font.lineHeight),
                    stats.healthRatio(), cfg.healthRgb(), numbers ? stats.healthText() : null, true);
            rowY += rowH;
        }
        if (mp) {
            drawBar(g, font, x, rowY, barWidth(cfg, 1), Math.min(barHeight(cfg, 1), font.lineHeight),
                    stats.manaRatio(), cfg.manaRgb(), numbers ? stats.manaText() : null, true);
            rowY += rowH;
        }
        if (xp) {
            drawBar(g, font, x, rowY, barWidth(cfg, 2), Math.min(barHeight(cfg, 2), font.lineHeight),
                    stats.xpRatio(), cfg.xpRgb(), numbers ? stats.xpText() : null, false);
            rowY += rowH;
        }

        int[] colors = stats.colors();
        int lineH = font.lineHeight + 1;
        for (int i = 0; i < lines.length; i++) {
            g.text(font, lines[i], x, rowY + i * lineH, colors[i], true);
        }

        if (scaled) {
            pose.popMatrix();
        }
    }

    /** One bar row: the styled shape, optional exact value to the right. */
    private static void drawBar(GuiGraphicsExtractor g, Font font, int x, int y, int barW, int barH,
                                float ratio, int color, String text, boolean pulse) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        int barY = y + (font.lineHeight - barH) / 2;
        drawBarShape(g, x, barY, barW, barH, ratio, color, pulse);
        if (text != null) {
            int textColor = cfg == null ? 0xFFFFFFFF : cfg.textRgb();
            g.text(font, text, x + barW + 4, y, textColor, true);
        }
    }

    /** The standalone Speed % plate (position comes from the in-game editor, toggle from the config). */
    private void drawSpeed(GuiGraphicsExtractor g, Font font, SkyLiteConfig cfg) {
        StatsState stats = SkyLiteClient.stats;
        String text = stats.speedText();
        if (text == null || !stats.isOnSkyBlock()) {
            return;
        }
        Anchors a = anchors(g.guiWidth(), g.guiHeight(), cfg);
        drawSpeedPlate(g, font, a.speedX + cfg.speedOffsetX, a.speedY + cfg.speedOffsetY, text, cfg);
    }

    /** Bare Speed % text, same colour as the bar numbers — no background, no label. */
    static void drawSpeedPlate(GuiGraphicsExtractor g, Font font, int x, int y, String text, SkyLiteConfig cfg) {
        g.text(font, text, x, y, cfg.textRgb(), true);
    }

    static int speedPlateWidth(Font font, String text) {
        return font.width(text);
    }

    static int speedPlateHeight(Font font) {
        return font.lineHeight;
    }

    /** FPS + ping in the chosen corner (vanilla data, works on any server). */
    private void drawCornerInfo(GuiGraphicsExtractor g, Font font, SkyLiteConfig cfg, Minecraft mc) {
        int fps = mc.getFps();
        int ping = SkyLiteClient.pingMeter.ping();
        if (ping <= 0 && mc.player != null && mc.getConnection() != null) {
            // Fallback for servers where the status ping is blocked: the tab-list value.
            PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null && info.getLatency() > 0) {
                ping = info.getLatency();
            }
        }
        if (fps != lastFps || ping != lastPing) {
            lastFps = fps;
            lastPing = ping;
            cornerText = ping < 0 ? "FPS " + fps : "FPS " + fps + "  ·  " + ping + " ms";
            cornerWidth = font.width(cornerText);
        }
        int x = switch (cfg.pingFpsCorner) {
            case LEFT_TOP, LEFT_BOTTOM -> 4;
            default -> g.guiWidth() - cornerWidth - 4;
        };
        int y = switch (cfg.pingFpsCorner) {
            case LEFT_TOP, RIGHT_TOP -> 4;
            default -> g.guiHeight() - font.lineHeight - 4;
        };
        g.text(font, cornerText, x, y, cfg.textRgb(), true);
    }

    private void drawFlash(GuiGraphicsExtractor g, Font font) {
        Alerts alerts = SkyLiteClient.alerts;
        String text = alerts.flashText;
        if (text == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now >= alerts.flashUntil) {
            alerts.flashText = null;
            return;
        }
        int alpha = (int) (120 + 110 * Math.abs(Math.sin(now / 90.0)));
        int color = (alpha << 24) | 0xFFAA00;
        int x = (g.guiWidth() - font.width(text)) / 2;
        g.text(font, text, x, g.guiHeight() / 5, color, true);
    }

    private void drawToast(GuiGraphicsExtractor g, Font font) {
        Alerts alerts = SkyLiteClient.alerts;
        String text = alerts.toastLine;
        if (text == null) {
            return;
        }
        if (System.currentTimeMillis() >= alerts.toastUntil) {
            alerts.toastLine = null;
            return;
        }
        int x = (g.guiWidth() - font.width(text)) / 2;
        g.text(font, text, x, g.guiHeight() - 68, 0xFFFFFFFF, true);
    }
}
