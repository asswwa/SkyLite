package dev.asswa.skylite;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * In-game bar placement editor (Controls → SkyLite → "Move the bars", F8 by default, or
 * {@code /skylite edit}): drag the three bars and the speed plate anywhere on the screen,
 * resize the hovered bar with the wheel and right-click one to recolour it. The offsets are
 * stored relative to each bar's default spot, so the layout survives GUI-scale and
 * window-size changes. The editor draws bars through the same {@link HudPanel} code path
 * as the HUD, so what you see here is what the HUD renders.
 */
public final class SkyLiteHudEditor extends Screen {
    private static final int HP = 0;
    private static final int MANA = 1;
    private static final int XP = 2;
    private static final int SPEED = 3;
    private static final int GRAB_MARGIN = 2;
    private static final long WHEEL_SAVE_INTERVAL_MS = 500L;
    private static final String SPEED_SAMPLE = "280%"; // shown in the editor until live data lands

    private int dragging = -1;
    private int grabDx;
    private int grabDy;

    // Hint texts and their widths: resolved on init, not on every frame.
    private String hint1;
    private String hint2;
    private String hint3;
    private int hint1W;
    private int hint2W;
    private int hint3W;

    // Wheel resizes change the config live; the file is written at most twice a second.
    private long lastWheelSaveAt;
    private boolean wheelSavePending;

    public SkyLiteHudEditor() {
        super(Component.translatable("skylite.editor.title"));
    }

    @Override
    protected void init() {
        int y = this.height - 28;
        addRenderableWidget(Button.builder(Component.translatable("skylite.editor.reset"), b -> resetOffsets())
                .bounds(this.width / 2 - 105, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("skylite.editor.done"), b -> onClose())
                .bounds(this.width / 2 + 5, y, 100, 20).build());

        hint1 = Component.translatable("skylite.editor.hint1").getString();
        hint2 = Component.translatable("skylite.editor.hint2").getString();
        hint3 = Component.translatable("skylite.editor.hint3").getString();
        hint1W = font.width(hint1);
        hint2W = font.width(hint2);
        hint3W = font.width(hint3);
    }

    /** A flat translucent curtain instead of the usual blurred menu background. */
    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0x99000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null) {
            return;
        }
        StatsState stats = SkyLiteClient.stats;
        HudPanel.Anchors a = HudPanel.anchors(g.guiWidth(), g.guiHeight(), cfg);

        int hpX = a.hpX + cfg.hpBarOffsetX;
        int hpY = a.rowY + cfg.hpBarOffsetY;
        int mpX = a.manaX + cfg.manaBarOffsetX;
        int mpY = a.rowY + cfg.manaBarOffsetY;
        int xpX = a.xpX + cfg.xpBarOffsetX;
        int xpY = a.xpY + cfg.xpBarOffsetY;

        // Live values when the parsers have them; sample numbers otherwise (off SkyBlock).
        String hpText = stats.healthText() != null ? stats.healthText() : "1500/1500";
        String mpText = stats.manaText() != null ? stats.manaText() : "250/250";
        String xpText = stats.xpText() != null ? stats.xpText() : "Lvl 30 · 47%";
        boolean numbers = cfg.showBarNumbers;

        HudPanel.drawStyledBar(g, font, hpX, hpY, a.hpW, a.hpH, ratio(stats.healthRatio(), 0.75F),
                cfg.healthRgb(), numbers ? hpText : null, true, HudPanel.textSide(cfg, HP));
        outline(g, hpX, hpY, a.hpW, a.hpH, dragging == HP || over(mouseX, mouseY, hpX, hpY, a.hpW, a.hpH));

        HudPanel.drawStyledBar(g, font, mpX, mpY, a.manaW, a.manaH, ratio(stats.manaRatio(), 0.6F),
                cfg.manaRgb(), numbers ? mpText : null, true, HudPanel.textSide(cfg, MANA));
        outline(g, mpX, mpY, a.manaW, a.manaH, dragging == MANA || over(mouseX, mouseY, mpX, mpY, a.manaW, a.manaH));

        HudPanel.drawStyledBar(g, font, xpX, xpY, a.xpW, a.xpH, ratio(stats.xpRatio(), 0.35F),
                cfg.xpRgb(), numbers ? xpText : null, false, HudPanel.textSide(cfg, XP));
        outline(g, xpX, xpY, a.xpW, a.xpH, dragging == XP || over(mouseX, mouseY, xpX, xpY, a.xpW, a.xpH));

        // Defense-style lines follow the HP bar in the HUD; preview them here too.
        String[] lines = stats.lines();
        if (lines.length > 0) {
            int[] colors = stats.colors();
            int lineH = font.lineHeight + 1;
            int ly = hpY - 1 - lines.length * lineH;
            for (int i = 0; i < lines.length; i++) {
                g.text(font, lines[i], hpX, ly + i * lineH, colors[i], true);
            }
        }

        // The speed plate is dragged like a bar (it has no wheel size and shares the text colour).
        String speedText = currentSpeedText();
        int spX = a.speedX + cfg.speedOffsetX;
        int spY = a.speedY + cfg.speedOffsetY;
        int spW = HudPanel.speedPlateWidth(font, speedText);
        int spH = HudPanel.speedPlateHeight(font);
        HudPanel.drawSpeedPlate(g, font, spX, spY, speedText, cfg);
        outline(g, spX, spY, spW, spH, dragging == SPEED || over(mouseX, mouseY, spX, spY, spW, spH));

        g.text(font, hint1, (this.width - hint1W) / 2, 10, 0xFFFFFFFF, true);
        g.text(font, hint2, (this.width - hint2W) / 2, 10 + font.lineHeight + 2, 0xFFAAAAAA, true);
        g.text(font, hint3, (this.width - hint3W) / 2, 10 + 2 * (font.lineHeight + 2), 0xFFAAAAAA, true);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true; // a widget (button) took it
        }
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null) {
            return false;
        }
        int mx = (int) event.x();
        int my = (int) event.y();
        // Right button (3) — 26.3 uses SDL numbering: 1 = left, 2 = middle, 3 = right.
        if (event.button() == 3) {
            int which = barAt(mx, my);
            if (which >= 0) {
                Minecraft.getInstance().setScreenAndShow(new SkyLiteColorScreen(this, cfg, which));
                return true;
            }
            return false;
        }
        if (event.button() != 1) { // 26.3 input API: the left mouse button is 1, not 0
            return false;
        }
        int which = barAt(mx, my);
        if (which >= 0) {
            HudPanel.Anchors a = HudPanel.anchors(this.width, this.height, cfg);
            int barX = switch (which) {
                case HP -> a.hpX + cfg.hpBarOffsetX;
                case MANA -> a.manaX + cfg.manaBarOffsetX;
                case SPEED -> a.speedX + cfg.speedOffsetX;
                default -> a.xpX + cfg.xpBarOffsetX; // XP
            };
            int barY = switch (which) {
                case XP -> a.xpY + cfg.xpBarOffsetY;
                case SPEED -> a.speedY + cfg.speedOffsetY;
                default -> a.rowY + (which == HP ? cfg.hpBarOffsetY : cfg.manaBarOffsetY);
            };
            dragging = which;
            grabDx = mx - barX;
            grabDy = my - barY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging < 0) {
            return super.mouseDragged(event, dragX, dragY);
        }
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null) {
            return true;
        }
        dragBar(cfg, HudPanel.anchors(this.width, this.height, cfg), dragging,
                (int) event.x() - grabDx, (int) event.y() - grabDy);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (dragging >= 0) {
            dragging = -1;
            saveNow(); // autosave after every drag; ESC / "Done" save as well
            return true;
        }
        flushWheelSave(); // a plain click commits any throttled wheel change
        return super.mouseReleased(event);
    }

    /** Wheel over a bar: width of that bar; Ctrl/Shift + wheel: its thickness. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        int which = cfg == null ? -1 : barAt((int) mouseX, (int) mouseY);
        if (cfg != null && scrollY != 0.0 && which >= 0) {
            if (which == SPEED) {
                return true; // the plate has no size to resize
            }
            boolean tall = InputConstants.isKeyDown(InputConstants.KEY_LCONTROL)
                    || InputConstants.isKeyDown(InputConstants.KEY_LSHIFT);
            int step = scrollY > 0 ? 1 : -1;
            switch (which) {
                case HP -> {
                    if (tall) {
                        cfg.hpBarHeight = clamp(cfg.hpBarHeight + step, 3, 14);
                    } else {
                        cfg.hpBarWidth = clamp(cfg.hpBarWidth + step * 2, 40, 320);
                    }
                }
                case MANA -> {
                    if (tall) {
                        cfg.manaBarHeight = clamp(cfg.manaBarHeight + step, 3, 14);
                    } else {
                        cfg.manaBarWidth = clamp(cfg.manaBarWidth + step * 2, 40, 320);
                    }
                }
                default -> {
                    if (tall) {
                        cfg.xpBarHeight = clamp(cfg.xpBarHeight + step, 3, 14);
                    } else {
                        cfg.xpBarWidth = clamp(cfg.xpBarWidth + step * 2, 40, 320);
                    }
                }
            }
            saveThrottled(cfg); // the wheel can spin fast — do not hit the disk per notch
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        saveNow();
        super.onClose(); // back to the game
    }

    @Override
    public void removed() {
        flushWheelSave(); // e.g. jumped to the colour picker with a wheel save still pending
        super.removed();
    }

    /** Writes the config right away; any throttled wheel change goes out with it. */
    private void saveNow() {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg != null) {
            wheelSavePending = false;
            cfg.save();
        }
    }

    /** Wheel saves are capped to twice a second; leaving the screen flushes the last one. */
    private void saveThrottled(SkyLiteConfig cfg) {
        long now = System.currentTimeMillis();
        if (now - lastWheelSaveAt >= WHEEL_SAVE_INTERVAL_MS) {
            lastWheelSaveAt = now;
            wheelSavePending = false;
            cfg.save();
        } else {
            wheelSavePending = true;
        }
    }

    private void flushWheelSave() {
        if (wheelSavePending) {
            wheelSavePending = false;
            SkyLiteConfig cfg = SkyLiteClient.config;
            if (cfg != null) {
                cfg.save();
            }
        }
    }

    /** Back to the default vanilla spots. */
    private void resetOffsets() {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null) {
            return;
        }
        cfg.hpBarOffsetX = 0;
        cfg.hpBarOffsetY = 0;
        cfg.manaBarOffsetX = 0;
        cfg.manaBarOffsetY = 0;
        cfg.xpBarOffsetX = 0;
        cfg.xpBarOffsetY = 0;
        cfg.speedOffsetX = 0;
        cfg.speedOffsetY = 0;
        saveNow();
    }

    /** Index of the widget under the cursor: 0 HP, 1 mana, 2 XP, 3 speed plate; -1 when none. */
    private int barAt(int mx, int my) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg == null) {
            return -1;
        }
        HudPanel.Anchors a = HudPanel.anchors(this.width, this.height, cfg);
        String speedText = currentSpeedText();
        if (over(mx, my, a.speedX + cfg.speedOffsetX, a.speedY + cfg.speedOffsetY,
                HudPanel.speedPlateWidth(font, speedText), HudPanel.speedPlateHeight(font))) {
            return SPEED; // the plate is drawn on top of the bars
        }
        int[] xs = {a.hpX + cfg.hpBarOffsetX, a.manaX + cfg.manaBarOffsetX, a.xpX + cfg.xpBarOffsetX};
        int[] ys = {a.rowY + cfg.hpBarOffsetY, a.rowY + cfg.manaBarOffsetY, a.xpY + cfg.xpBarOffsetY};
        int[] ws = {a.hpW, a.manaW, a.xpW};
        int[] hs = {a.hpH, a.manaH, a.xpH};
        for (int i = 0; i < 3; i++) {
            if (over(mx, my, xs[i], ys[i], ws[i], hs[i])) {
                return i;
            }
        }
        return -1;
    }

    /** Live Speed % when the parser has it; a sample otherwise (off SkyBlock). */
    private String currentSpeedText() {
        String live = SkyLiteClient.stats.speedText();
        return live != null ? live : SPEED_SAMPLE;
    }

    /**
     * Moves the dragged bar with soft snapping: back to its default vanilla spot, and to the
     * screen centre line, when the cursor gets close.
     */
    private void dragBar(SkyLiteConfig cfg, HudPanel.Anchors a, int idx, int mouseX, int mouseY) {
        int baseX = switch (idx) {
            case MANA -> a.manaX;
            case XP -> a.xpX;
            case SPEED -> a.speedX;
            default -> a.hpX;
        };
        int baseY = switch (idx) {
            case XP -> a.xpY;
            case SPEED -> a.speedY;
            default -> a.rowY;
        };
        int w = switch (idx) {
            case MANA -> a.manaW;
            case XP -> a.xpW;
            case SPEED -> HudPanel.speedPlateWidth(font, currentSpeedText());
            default -> a.hpW;
        };
        int h = switch (idx) {
            case MANA -> a.manaH;
            case XP -> a.xpH;
            case SPEED -> HudPanel.speedPlateHeight(font);
            default -> a.hpH;
        };

        int nx = clamp(mouseX, 0, Math.max(0, this.width - w));
        int ny = clamp(mouseY, 0, Math.max(0, this.height - h));

        int offX = nx - baseX;
        int offY = ny - baseY;
        if (Math.abs(offX) <= 3) {
            offX = 0; // snap back to the vanilla spot
        }
        if (Math.abs(offY) <= 3) {
            offY = 0;
        }
        if (Math.abs((baseX + offX + w / 2) - this.width / 2) <= 4) {
            offX = (this.width / 2 - w / 2) - baseX; // snap to the centre line
        }

        switch (idx) {
            case MANA -> {
                cfg.manaBarOffsetX = offX;
                cfg.manaBarOffsetY = offY;
            }
            case XP -> {
                cfg.xpBarOffsetX = offX;
                cfg.xpBarOffsetY = offY;
            }
            case SPEED -> {
                cfg.speedOffsetX = offX;
                cfg.speedOffsetY = offY;
            }
            default -> {
                cfg.hpBarOffsetX = offX;
                cfg.hpBarOffsetY = offY;
            }
        }
    }

    /** Bar height is h + 2·margin, so thin bars are still easy to grab. */
    private static boolean over(int mx, int my, int x, int y, int w, int h) {
        return mx >= x - GRAB_MARGIN && mx < x + w + GRAB_MARGIN
                && my >= y - GRAB_MARGIN && my < y + h + GRAB_MARGIN;
    }

    private static void outline(GuiGraphicsExtractor g, int x, int y, int w, int h, boolean on) {
        if (!on) {
            return;
        }
        int c = 0xFFFFFFFF;
        g.fill(x - 1, y - 1, x + w + 1, y, c);
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, c);
        g.fill(x - 1, y, x, y + h, c);
        g.fill(x + w, y, x + w + 1, y + h, c);
    }

    private static float ratio(float value, float fallback) {
        return Float.isNaN(value) ? fallback : value;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }
}
