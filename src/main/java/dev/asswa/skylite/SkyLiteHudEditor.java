package dev.asswa.skylite;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * In-game placement editor (Controls → SkyLite → "Move the bars", F8 by default, or
 * {@code /skylite edit}): drag the three bars, the speed plate and every info line
 * anywhere on the screen, resize the hovered bar with the wheel and right-click one to
 * recolour it. The offsets are stored relative to each element's default spot, so the
 * layout survives GUI-scale and window-size changes. The editor draws bars through the
 * same {@link HudPanel} code path as the HUD, so what you see here is what the HUD renders.
 */
public final class SkyLiteHudEditor extends Screen {
    private static final int HP = 0;
    private static final int MANA = 1;
    private static final int XP = 2;
    private static final int SPEED = 3;
    /** Element ids of the info lines: {@code LINE_BASE + SkyLiteConfig.LINE_*} (v17). */
    private static final int LINE_BASE = 10;
    private static final int GRAB_MARGIN = 2;
    private static final long WHEEL_SAVE_INTERVAL_MS = 500L;
    private static final String SPEED_SAMPLE = "280%"; // shown in the editor until live data lands

    /** Badge names of the info-line kinds, in {@code SkyLiteConfig.LINE_*} order. */
    private static final String[] LINE_NAMES = {
            "AFK", "Clock", "Area", "Cookie", "God Pot", "Purse",
            "Bits", "Session", "Coords", "Effects", "Defense", "Drops"};

    private int dragging = -1;
    /** Last clicked element; the arrow keys nudge it (v14). */
    private int selected = -1;
    private int grabDx;
    private int grabDy;

    // Scratch lists of drawn info-line boxes {kind, x, y, w, h}, refilled on demand (v17).
    private final List<int[]> lineBoxes = new ArrayList<>();
    private final List<int[]> lineScratch = new ArrayList<>();

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
        outline(g, hpX, hpY, a.hpW, a.hpH, active(HP, mouseX, mouseY, hpX, hpY, a.hpW, a.hpH));

        HudPanel.drawStyledBar(g, font, mpX, mpY, a.manaW, a.manaH, ratio(stats.manaRatio(), 0.6F),
                cfg.manaRgb(), numbers ? mpText : null, true, HudPanel.textSide(cfg, MANA));
        outline(g, mpX, mpY, a.manaW, a.manaH, active(MANA, mouseX, mouseY, mpX, mpY, a.manaW, a.manaH));

        HudPanel.drawStyledBar(g, font, xpX, xpY, a.xpW, a.xpH, ratio(stats.xpRatio(), 0.35F),
                cfg.xpRgb(), numbers ? xpText : null, false, HudPanel.textSide(cfg, XP));
        outline(g, xpX, xpY, a.xpW, a.xpH, active(XP, mouseX, mouseY, xpX, xpY, a.xpW, a.xpH));

        // Every info line is its own movable block (v17): drawn through the same HUD path,
        // outlined and hit-tested per kind.
        HudPanel.drawInfoLines(g, font, cfg, stats, hpX, hpY);
        collectLineBoxes(cfg, a, lineBoxes, false);
        for (int[] box : lineBoxes) {
            outline(g, box[1], box[2], box[3], box[4],
                    active(LINE_BASE + box[0], mouseX, mouseY, box[1], box[2], box[3], box[4]));
        }

        // The speed plate is dragged like a bar (it has no wheel size and shares the text colour).
        String speedText = currentSpeedText();
        int spX = a.speedX + cfg.speedOffsetX;
        int spY = a.speedY + cfg.speedOffsetY;
        int spW = HudPanel.speedPlateWidth(font, speedText);
        int spH = HudPanel.speedPlateHeight(font);
        HudPanel.drawSpeedPlate(g, font, spX, spY, speedText, cfg);
        outline(g, spX, spY, spW, spH, active(SPEED, mouseX, mouseY, spX, spY, spW, spH));

        // v14: offset readout of the hovered/selected element, the arrows nudge it by 1 px.
        int hovered = barAt(mouseX, mouseY);
        int info = hovered >= 0 ? hovered : selected;
        if (info >= 0) {
            drawOffsetBadge(g, cfg, info);
        }

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
            if (which >= 0 && which < LINE_BASE) { // the info lines have no colour of their own
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
            if (doubleClick) {
                setOffsets(cfg, which, 0, 0); // v14: a double click puts the element back
                resetSize(cfg, which);
                selected = which;
                saveNow();
                return true;
            }
            HudPanel.Anchors a = HudPanel.anchors(this.width, this.height, cfg);
            int[] rect = elemRect(cfg, a, which);
            dragging = which;
            selected = which;
            grabDx = mx - rect[0];
            grabDy = my - rect[1];
            return true;
        }
        selected = -1; // clicking empty space drops the keyboard selection
        return false;
    }

    /** Arrow keys nudge the selected element; Shift makes the step ten pixels (v14). */
    @Override
    public boolean keyPressed(KeyEvent event) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        if (cfg != null && selected >= 0 && !event.isEscape()) {
            int step = event.hasShiftDown() ? 10 : 1;
            int dx = 0;
            int dy = 0;
            if (event.isLeft()) {
                dx = -step;
            } else if (event.isRight()) {
                dx = step;
            } else if (event.isUp()) {
                dy = -step;
            } else if (event.isDown()) {
                dy = step;
            }
            if (dx != 0 || dy != 0) {
                nudge(cfg, selected, dx, dy);
                return true;
            }
        }
        return super.keyPressed(event);
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
            if (which == SPEED || which >= LINE_BASE) {
                return true; // these have no size of their own to resize
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
        for (int kind = 0; kind < SkyLiteConfig.LINE_KIND_COUNT; kind++) {
            cfg.setLineOffsets(kind, 0, 0); // v17: every info line back into the stack
        }
        saveNow();
    }

    /** Double-click: the element returns to the stock size too (v14, roadmap 33). */
    private static void resetSize(SkyLiteConfig cfg, int idx) {
        if (idx >= LINE_BASE) {
            return; // info lines are sized by their text
        }
        switch (idx) {
            case MANA -> {
                cfg.manaBarWidth = 90;
                cfg.manaBarHeight = 9;
            }
            case XP -> {
                cfg.xpBarWidth = 182;
                cfg.xpBarHeight = 9;
            }
            case SPEED -> {
                // the plate has no size of its own
            }
            default -> {
                cfg.hpBarWidth = 90;
                cfg.hpBarHeight = 9;
            }
        }
    }

    /**
     * Index of the widget under the cursor: 0 HP, 1 mana, 2 XP, 3 speed plate,
     * {@code LINE_BASE + kind} for an info line; -1 when none.
     */
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
        collectLineBoxes(cfg, a, lineBoxes, false);
        for (int i = lineBoxes.size() - 1; i >= 0; i--) { // the text wins over the bars (v17)
            int[] box = lineBoxes.get(i);
            if (over(mx, my, box[1], box[2], box[3], box[4])) {
                return LINE_BASE + box[0];
            }
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

    /**
     * Collects the boxes of the drawn info-line groups into {@code out} as
     * {@code {kind, x, y, w, h}}, in draw order. Mirrors {@link HudPanel#drawInfoLines} so
     * the hit tests and outlines match the screen exactly; with {@code ignoreOffset} it
     * yields the default stacked boxes for the snap-back (v17).
     */
    private void collectLineBoxes(SkyLiteConfig cfg, HudPanel.Anchors a, List<int[]> out,
                                  boolean ignoreOffset) {
        out.clear();
        StatsState stats = SkyLiteClient.stats;
        String[] lines = stats.lines();
        int[] kinds = stats.lineKinds();
        if (lines.length == 0 || kinds.length != lines.length) {
            return;
        }
        int hpX = a.hpX + cfg.hpBarOffsetX;
        int hpY = a.rowY + cfg.hpBarOffsetY;
        int lineH = font.lineHeight + 1;
        int max = cfg.infoLinesMax;
        int unmovedTotal = 0;
        for (int i = 0; i < lines.length; i++) {
            // In the baseline (auto) layout everything is stacked, so nothing is skipped by the cap.
            if (ignoreOffset
                    || (cfg.lineOffsetX(kinds[i]) == 0 && cfg.lineOffsetY(kinds[i]) == 0)) {
                unmovedTotal++;
            }
        }
        int skipUnmoved = max > 0 ? Math.max(0, unmovedTotal - max) : 0;
        int member = 0;
        int prevKind = -1;
        for (int i = 0; i < lines.length; i++) {
            int kind = kinds[i];
            if (kind != prevKind) {
                member = 0;
                prevKind = kind;
            }
            int offX = ignoreOffset ? 0 : cfg.lineOffsetX(kind);
            int offY = ignoreOffset ? 0 : cfg.lineOffsetY(kind);
            boolean moved = offX != 0 || offY != 0;
            if (!moved && skipUnmoved > 0) {
                skipUnmoved--;
                member++;
                continue; // hidden by the cap, exactly like the HUD
            }
            int x = moved ? hpX + offX : hpX;
            int y = moved ? hpY - 1 + offY + member * lineH : hpY - 1 - (lines.length - i) * lineH;
            int[] box = null;
            for (int b = 0; b < out.size(); b++) {
                if (out.get(b)[0] == kind) {
                    box = out.get(b);
                    break;
                }
            }
            if (box == null) {
                out.add(new int[]{kind, x, y, font.width(lines[i]), font.lineHeight});
            } else {
                box[3] = Math.max(box[3], font.width(lines[i]));
                box[4] += lineH; // the group's lines are drawn lineH apart (v17)
            }
            member++;
        }
    }

    /** The box of one info-line kind as drawn, or null when it is not on screen (v17). */
    private int[] lineRect(SkyLiteConfig cfg, HudPanel.Anchors a, int kind, boolean ignoreOffset) {
        collectLineBoxes(cfg, a, lineScratch, ignoreOffset);
        for (int[] box : lineScratch) {
            if (box[0] == kind) {
                return box;
            }
        }
        return null;
    }

    /** The drawn box {@code {x, y, w, h}} of an element, wherever it currently is (v17). */
    private int[] elemRect(SkyLiteConfig cfg, HudPanel.Anchors a, int idx) {
        if (idx >= LINE_BASE) {
            int[] box = lineRect(cfg, a, idx - LINE_BASE, false);
            if (box != null) {
                return new int[]{box[1], box[2], box[3], box[4]};
            }
            return new int[]{a.hpX + cfg.hpBarOffsetX, a.rowY + cfg.hpBarOffsetY - 1, 1,
                    font.lineHeight};
        }
        return new int[]{baseX(a, cfg, idx), baseY(a, cfg, idx), elemW(cfg, a, idx),
                elemH(cfg, a, idx)};
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
        if (idx >= LINE_BASE) {
            dragLine(cfg, a, idx - LINE_BASE, mouseX, mouseY);
            return;
        }
        int baseX = baseX(a, cfg, idx);
        int baseY = baseY(a, cfg, idx);
        int w = elemW(cfg, a, idx);
        int h = elemH(cfg, a, idx);

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
        setOffsets(cfg, idx, offX, offY);
    }

    /**
     * Moves one info-line block (v17) and snaps it back into the stack when dropped close to
     * its default spot; everywhere else the spot is kept exactly, the other lines do not move.
     */
    private void dragLine(SkyLiteConfig cfg, HudPanel.Anchors a, int kind, int mouseX, int mouseY) {
        int[] rect = lineRect(cfg, a, kind, false);
        int w = rect == null ? font.width("0") : rect[3];
        int h = rect == null ? font.lineHeight : rect[4];
        int nx = clamp(mouseX, 0, Math.max(0, this.width - w));
        int ny = clamp(mouseY, 0, Math.max(0, this.height - h));
        int[] auto = lineRect(cfg, a, kind, true);
        if (auto != null && Math.abs(nx - auto[1]) <= 3 && Math.abs(ny - auto[2]) <= 3) {
            cfg.setLineOffsets(kind, 0, 0); // dropped onto its slot: back into the stack
            return;
        }
        cfg.setLineOffsets(kind, nx - (a.hpX + cfg.hpBarOffsetX),
                ny - (a.rowY + cfg.hpBarOffsetY - 1));
    }

    /** Arrow-key nudge by one pixel (Shift: ten); the element stays fully on screen (v14). */
    private void nudge(SkyLiteConfig cfg, int idx, int dx, int dy) {
        HudPanel.Anchors a = HudPanel.anchors(this.width, this.height, cfg);
        if (idx >= LINE_BASE) {
            nudgeLine(cfg, a, idx - LINE_BASE, dx, dy);
            saveThrottled(cfg);
            return;
        }
        int baseX = baseX(a, cfg, idx);
        int baseY = baseY(a, cfg, idx);
        int w = elemW(cfg, a, idx);
        int h = elemH(cfg, a, idx);
        int nx = clamp(baseX + offsetX(cfg, idx) + dx, 0, Math.max(0, this.width - w));
        int ny = clamp(baseY + offsetY(cfg, idx) + dy, 0, Math.max(0, this.height - h));
        setOffsets(cfg, idx, nx - baseX, ny - baseY);
        saveThrottled(cfg); // holding an arrow key repeats fast, do not hit the disk per event
    }

    /** One-pixel nudge of an info line; stepping exactly onto its slot re-attaches it (v17). */
    private void nudgeLine(SkyLiteConfig cfg, HudPanel.Anchors a, int kind, int dx, int dy) {
        int[] rect = lineRect(cfg, a, kind, false);
        if (rect == null) {
            return;
        }
        int nx = clamp(rect[1] + dx, 0, Math.max(0, this.width - rect[3]));
        int ny = clamp(rect[2] + dy, 0, Math.max(0, this.height - rect[4]));
        int[] auto = lineRect(cfg, a, kind, true);
        if (auto != null && nx == auto[1] && ny == auto[2]) {
            cfg.setLineOffsets(kind, 0, 0); // exactly back on its slot: re-attach to the stack
            return;
        }
        cfg.setLineOffsets(kind, nx - (a.hpX + cfg.hpBarOffsetX),
                ny - (a.rowY + cfg.hpBarOffsetY - 1));
    }

    /** "HP  +12, -4 px" pill near the element the arrows would nudge (v14). */
    private void drawOffsetBadge(GuiGraphicsExtractor g, SkyLiteConfig cfg, int idx) {
        HudPanel.Anchors a = HudPanel.anchors(this.width, this.height, cfg);
        if (idx >= LINE_BASE) {
            drawLineBadge(g, cfg, a, idx - LINE_BASE);
            return;
        }
        int x = baseX(a, cfg, idx) + offsetX(cfg, idx);
        int y = baseY(a, cfg, idx) + offsetY(cfg, idx);
        int w = elemW(cfg, a, idx);
        int h = elemH(cfg, a, idx);
        int ox = offsetX(cfg, idx);
        int oy = offsetY(cfg, idx);
        String name = switch (idx) {
            case MANA -> "Mana";
            case XP -> "XP";
            case SPEED -> "Speed";
            default -> "HP";
        };
        String text = name + "  " + (ox > 0 ? "+" : "") + ox + ", " + (oy > 0 ? "+" : "") + oy + " px";
        int tx = clamp(x + (w - font.width(text)) / 2, 2, Math.max(2, this.width - font.width(text) - 2));
        int ty = y - font.lineHeight - 6;
        if (ty < 2) {
            ty = y + h + 5; // no room above the element: go below it
        }
        if (ty + font.lineHeight + 2 > this.height) {
            ty = Math.max(2, this.height - font.lineHeight - 3);
        }
        g.fill(tx - 3, ty - 2, tx + font.width(text) + 3, ty + font.lineHeight + 1, 0xB0000000);
        g.text(font, text, tx, ty, 0xFFFFFFFF, true);
    }

    /** "Purse  +12, -4 px" pill with the line's shift from its slot in the stack (v17). */
    private void drawLineBadge(GuiGraphicsExtractor g, SkyLiteConfig cfg, HudPanel.Anchors a, int kind) {
        int[] rect = lineRect(cfg, a, kind, false);
        if (rect == null) {
            return;
        }
        int[] auto = lineRect(cfg, a, kind, true);
        int dx = auto == null ? 0 : rect[1] - auto[1];
        int dy = auto == null ? 0 : rect[2] - auto[2];
        String name = kind >= 0 && kind < LINE_NAMES.length ? LINE_NAMES[kind] : "Info";
        String text = name + "  " + (dx > 0 ? "+" : "") + dx + ", " + (dy > 0 ? "+" : "") + dy + " px";
        int tx = clamp(rect[1] + (rect[3] - font.width(text)) / 2, 2,
                Math.max(2, this.width - font.width(text) - 2));
        int ty = rect[2] - font.lineHeight - 6;
        if (ty < 2) {
            ty = rect[2] + rect[4] + 5; // no room above the line: go below it
        }
        if (ty + font.lineHeight + 2 > this.height) {
            ty = Math.max(2, this.height - font.lineHeight - 3);
        }
        g.fill(tx - 3, ty - 2, tx + font.width(text) + 3, ty + font.lineHeight + 1, 0xB0000000);
        g.text(font, text, tx, ty, 0xFFFFFFFF, true);
    }

    /** Default (offset-free) spot of a bar or the speed plate. */
    private static int baseX(HudPanel.Anchors a, SkyLiteConfig cfg, int idx) {
        return switch (idx) {
            case MANA -> a.manaX;
            case XP -> a.xpX;
            case SPEED -> a.speedX;
            default -> a.hpX;
        };
    }

    private static int baseY(HudPanel.Anchors a, SkyLiteConfig cfg, int idx) {
        return switch (idx) {
            case XP -> a.xpY;
            case SPEED -> a.speedY;
            default -> a.rowY;
        };
    }

    private int elemW(SkyLiteConfig cfg, HudPanel.Anchors a, int idx) {
        return switch (idx) {
            case MANA -> a.manaW;
            case XP -> a.xpW;
            case SPEED -> HudPanel.speedPlateWidth(font, currentSpeedText());
            default -> a.hpW;
        };
    }

    private int elemH(SkyLiteConfig cfg, HudPanel.Anchors a, int idx) {
        return switch (idx) {
            case MANA -> a.manaH;
            case XP -> a.xpH;
            case SPEED -> HudPanel.speedPlateHeight(font);
            default -> a.hpH;
        };
    }

    private static int offsetX(SkyLiteConfig cfg, int idx) {
        return switch (idx) {
            case MANA -> cfg.manaBarOffsetX;
            case XP -> cfg.xpBarOffsetX;
            case SPEED -> cfg.speedOffsetX;
            default -> cfg.hpBarOffsetX;
        };
    }

    private static int offsetY(SkyLiteConfig cfg, int idx) {
        return switch (idx) {
            case MANA -> cfg.manaBarOffsetY;
            case XP -> cfg.xpBarOffsetY;
            case SPEED -> cfg.speedOffsetY;
            default -> cfg.hpBarOffsetY;
        };
    }

    private static void setOffsets(SkyLiteConfig cfg, int idx, int x, int y) {
        if (idx >= LINE_BASE) {
            cfg.setLineOffsets(idx - LINE_BASE, x, y); // (0, 0) = back into the stack (v17)
            return;
        }
        switch (idx) {
            case MANA -> {
                cfg.manaBarOffsetX = x;
                cfg.manaBarOffsetY = y;
            }
            case XP -> {
                cfg.xpBarOffsetX = x;
                cfg.xpBarOffsetY = y;
            }
            case SPEED -> {
                cfg.speedOffsetX = x;
                cfg.speedOffsetY = y;
            }
            default -> {
                cfg.hpBarOffsetX = x;
                cfg.hpBarOffsetY = y;
            }
        }
    }

    /** Bar height is h + 2·margin, so thin bars are still easy to grab. */
    private static boolean over(int mx, int my, int x, int y, int w, int h) {
        return mx >= x - GRAB_MARGIN && mx < x + w + GRAB_MARGIN
                && my >= y - GRAB_MARGIN && my < y + h + GRAB_MARGIN;
    }

    /** Outline highlight of the hovered, dragged or keyboard-selected element (v14). */
    private boolean active(int idx, int mx, int my, int x, int y, int w, int h) {
        return dragging == idx || selected == idx || over(mx, my, x, y, w, h);
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
