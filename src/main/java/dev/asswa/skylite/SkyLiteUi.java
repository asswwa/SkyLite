package dev.asswa.skylite;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Tiny flat UI toolkit for the SkyLite screens ("Athen-like" dark panels): panels, borders,
 * pixel icons and controls are plain rectangles plus the vanilla font — no textures, no new
 * dependencies, and nothing is drawn while the screens are closed.
 */
public final class SkyLiteUi {
    private SkyLiteUi() {
    }

    // ---------- palette (matches the approved mock-up) ----------
    public static final int PANEL_BG = 0xEE13171D;
    public static final int PANEL_BG_SOLID = 0xFC13171D;
    public static final int PANEL_BORDER = 0xFF3A424E;
    public static final int SEP = 0xFF262C34;
    public static final int TEXT = 0xFFE8ECF1;
    public static final int TEXT_DIM = 0xFF9CA5AF;
    public static final int TEXT_MUTED = 0xFF747C86;
    public static final int ACCENT = 0xFF7AA2F7;
    public static final int ACCENT_FILL = 0xEB7AA2F7;
    public static final int ACCENT_FILL_HOVER = 0xF08CB0F9;
    public static final int ACCENT_EDGE = 0xFF96B6FA;
    public static final int ACCENT_EDGE_HOVER = 0xFFB0C8FB;
    public static final int ACCENT_SOFT = 0x307AA2F7;
    public static final int ACCENT_TRACK = 0x447AA2F7;
    public static final int ON_ACCENT = 0xFF10141A;
    public static final int CONTROL_BG = 0xFF191E25;
    public static final int CONTROL_BORDER = 0xFF3E4652;
    public static final int CONTROL_BORDER_HOVER = 0xFF5C6774;
    public static final int NEUTRAL_TEXT = 0xFFD0D6DE;
    public static final int RED = 0xFFC97B7B;
    public static final int RED_BG = 0xFF1A1E25;
    public static final int RED_BORDER = 0xFF704848;
    public static final int RED_BORDER_HOVER = 0xFF955F5F;
    public static final int HOVER_WASH = 0x14FFFFFF;
    public static final int SCROLL_TRACK = 0x0CFFFFFF;
    public static final int SCROLL_THUMB = 0xDC606A76;
    public static final int HEART_RED = 0xFFFF6060;

    public static final int ROW_H = 22;   // content row height
    public static final int TEXT_H = 8;   // vanilla glyph height
    public static final int PAD = 12;     // panel inner padding

    // ---------- pixel icons ('#' is painted) ----------
    public static final String[] ICON_BARS = {".######.", ".######.", "........", ".####...", ".####...", "........", ".##.....", ".##....."};
    public static final String[] ICON_INFO = {".#..####", "........", ".#..####", "........", ".#..####"};
    public static final String[] ICON_BELL = {"...#....", "..###...", "..###...", "..###...", ".#####..", "..###...", "........", "........"};
    public static final String[] ICON_CHAT = {".######.", "#..###.#", "#......#", ".######.", "..#....."};
    public static final String[] ICON_EYE = {"..####..", ".#....#.", "#..##..#", "#..##..#", ".#....#.", "..####.."};
    public static final String[] ICON_BOLT = {"...###..", "..###...", ".###....", "#####...", "..###...", ".###....", "###.....", "##......"};
    public static final String[] ARROW_DOWN = {"#######.", ".#####..", "..###...", "...#...."};
    public static final String[] HEART = {".##.##..", "#######.", "#######.", ".#####..", "..###...", "...#...."};

    // ---------- primitives ----------

    /** Filled rectangle with a 1px border. */
    public static void box(GuiGraphicsExtractor g, int x, int y, int w, int h, int fill, int border) {
        g.fill(x, y, x + w, y + h, fill);
        border(g, x, y, w, h, border);
    }

    /** 1px frame around a rectangle. */
    public static void border(GuiGraphicsExtractor g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** Standard screen panel: dark fill plus a 1px border. */
    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        box(g, x, y, w, h, PANEL_BG, PANEL_BORDER);
    }

    /** Draws a pixel icon: every '#' becomes a 1x1 rectangle. */
    public static void pattern(GuiGraphicsExtractor g, int x, int y, String[] rows, int color) {
        for (int dy = 0; dy < rows.length; dy++) {
            String row = rows[dy];
            for (int dx = 0; dx < row.length(); dx++) {
                if (row.charAt(dx) == '#') {
                    g.fill(x + dx, y + dy, x + dx + 1, y + dy + 1, color);
                }
            }
        }
    }

    /** Top of the vertically centred text inside a control of height {@code h}. */
    public static int textY(int y, int h) {
        return y + (h - TEXT_H) / 2;
    }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    // ---------- widgets ----------

    /** Shared widget base: narration plus small drawing helpers. */
    public abstract static class Flat extends AbstractWidget {
        protected Flat(int x, int y, int w, int h, Component message) {
            super(x, y, w, h, message);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {
            defaultButtonNarrationText(out);
        }

        protected final boolean hovered(int mouseX, int mouseY) {
            return this.active && this.isMouseOver(mouseX, mouseY);
        }

        protected final void drawBox(GuiGraphicsExtractor g, int fill, int border) {
            box(g, getX(), getY(), getWidth(), getHeight(), fill, border);
        }
    }

    /** Flat button: neutral (dark), primary (blue fill) or danger (red outline). */
    public static final class Btn extends Flat {
        public static final int NEUTRAL = 0;
        public static final int PRIMARY = 1;
        public static final int DANGER = 2;

        private final String label;
        private final int kind;
        private final Runnable onPress;

        public Btn(int x, int y, int w, int h, String label, int kind, Runnable onPress) {
            super(x, y, w, h, Component.literal(label));
            this.label = label;
            this.kind = kind;
            this.onPress = onPress;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            onPress.run();
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            if (!this.active) { // disabled: dim, no hover
                drawBox(g, CONTROL_BG, CONTROL_BORDER);
                Font font = font();
                g.text(font, label, getX() + (getWidth() - font.width(label)) / 2,
                        textY(getY(), getHeight()), TEXT_MUTED, true);
                return;
            }
            boolean hover = hovered(mouseX, mouseY);
            int fill;
            int border;
            int text;
            boolean shadow;
            switch (kind) {
                case PRIMARY -> {
                    fill = hover ? ACCENT_FILL_HOVER : ACCENT_FILL;
                    border = hover ? ACCENT_EDGE_HOVER : ACCENT_EDGE;
                    text = ON_ACCENT;
                    shadow = false;
                }
                case DANGER -> {
                    fill = RED_BG;
                    border = hover ? RED_BORDER_HOVER : RED_BORDER;
                    text = RED;
                    shadow = true;
                }
                default -> {
                    fill = CONTROL_BG;
                    border = hover ? CONTROL_BORDER_HOVER : CONTROL_BORDER;
                    text = hover ? TEXT : NEUTRAL_TEXT;
                    shadow = true;
                }
            }
            drawBox(g, fill, border);
            Font font = font();
            g.text(font, label, getX() + (getWidth() - font.width(label)) / 2,
                    textY(getY(), getHeight()), text, shadow);
        }
    }

    /** On/off control: blue fill when on, outlined dark when off. */
    public static final class Toggle extends Flat {
        private final Supplier<Boolean> getter;
        private final Consumer<Boolean> setter;
        private final String onText;
        private final String offText;

        public Toggle(int x, int y, int w, int h, String onText, String offText,
                      Supplier<Boolean> getter, Consumer<Boolean> setter) {
            super(x, y, w, h, Component.literal(onText + " / " + offText));
            this.onText = onText;
            this.offText = offText;
            this.getter = getter;
            this.setter = setter;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            setter.accept(!getter.get());
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            boolean on = getter.get();
            boolean hover = hovered(mouseX, mouseY);
            Font font = font();
            if (on) {
                drawBox(g, hover ? ACCENT_FILL_HOVER : ACCENT_FILL, hover ? ACCENT_EDGE_HOVER : ACCENT_EDGE);
                g.text(font, onText, getX() + (getWidth() - font.width(onText)) / 2,
                        textY(getY(), getHeight()), ON_ACCENT, false);
            } else {
                drawBox(g, CONTROL_BG, hover ? CONTROL_BORDER_HOVER : CONTROL_BORDER);
                g.text(font, offText, getX() + (getWidth() - font.width(offText)) / 2,
                        textY(getY(), getHeight()), hover ? TEXT_DIM : TEXT_MUTED, true);
            }
        }
    }

    /**
     * Flat dropdown: a click expands the option list under (or above) the control, clicking an
     * entry applies it. The list itself is drawn by the screen after all widgets — see
     * {@link #renderList} — so no row or button can cover it.
     */
    public static final class Dropdown extends Flat {
        private static final int ITEM_H = 18;
        private final String[] options;
        private final Supplier<Integer> index;
        private final IntConsumer setIndex;
        private String shown;
        private int shownIndex = Integer.MIN_VALUE;
        private int shownWidth = -1;
        private boolean open;

        public Dropdown(int x, int y, int w, int h, String[] options,
                        Supplier<Integer> index, IntConsumer setIndex) {
            super(x, y, w, h, Component.literal(options.length == 0 ? "" : options[0]));
            this.options = options;
            this.index = index;
            this.setIndex = setIndex;
        }

        @Override
        protected boolean isValidClickButton(MouseButtonInfo info) {
            return info.button() == 1 || info.button() == 3; // SDL numbering: 1 = left, 3 = right
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            if (options.length != 0) {
                open = !open; // a second click on the control collapses the list again
            }
        }

        /** True while the option list is expanded. */
        public boolean isOpen() {
            return open;
        }

        public void close() {
            open = false;
        }

        /**
         * A click inside the expanded list: picks the option under the cursor and closes it.
         * The screen calls this before dispatching clicks to the widgets.
         */
        public boolean clickedInList(double mx, double my, int screenH) {
            if (!open || options.length == 0) {
                return false;
            }
            int top = listTop(screenH);
            if (mx < getX() || mx >= getX() + getWidth() || my < top || my >= top + listHeight()) {
                return false;
            }
            int i = (int) Math.floor((my - (top + 1)) / (double) ITEM_H);
            if (i >= 0 && i < options.length) {
                setIndex.accept(i);
            }
            open = false;
            return true;
        }

        /** Draws the expanded list; the screen calls it after every widget is rendered. */
        public void renderList(GuiGraphicsExtractor g, int mouseX, int mouseY) {
            if (!open || options.length == 0) {
                return;
            }
            int top = listTop(g.guiHeight());
            int x = getX();
            SkyLiteUi.box(g, x, top, getWidth(), listHeight(), PANEL_BG_SOLID, PANEL_BORDER);
            int selected = Math.floorMod(index.get(), options.length);
            for (int i = 0; i < options.length; i++) {
                int iy = top + 1 + i * ITEM_H;
                boolean over = mouseX >= x && mouseX < x + getWidth() && mouseY >= iy && mouseY < iy + ITEM_H;
                if (over) {
                    g.fill(x + 1, iy, x + getWidth() - 1, iy + ITEM_H, ACCENT_SOFT);
                }
                if (i == selected) {
                    g.fill(x + 1, iy + 3, x + 3, iy + ITEM_H - 3, ACCENT);
                }
                g.text(font(), fit(options[i], getWidth() - 12), x + 7, textY(iy, ITEM_H),
                        i == selected ? ACCENT : (over ? TEXT : TEXT_DIM), true);
            }
        }

        /** Top edge of the list: under the control, flipped above it when there is no room. */
        private int listTop(int screenH) {
            int top = getY() + getHeight() + 1;
            if (top + listHeight() > screenH - 2) {
                top = getY() - 1 - listHeight();
            }
            return Math.max(2, top);
        }

        private int listHeight() {
            return options.length * ITEM_H + 2;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            boolean hover = hovered(mouseX, mouseY);
            drawBox(g, CONTROL_BG, open ? ACCENT_EDGE : hover ? CONTROL_BORDER_HOVER : CONTROL_BORDER);
            int i = options.length == 0 ? 0 : Math.floorMod(index.get(), options.length);
            if (i != shownIndex || getWidth() != shownWidth) {
                shownIndex = i;
                shownWidth = getWidth();
                shown = fit(options.length == 0 ? "" : options[i], getWidth() - 24);
                setMessage(Component.literal(shown));
            }
            g.text(font(), shown, getX() + 6, textY(getY(), getHeight()), TEXT, true);
            pattern(g, getX() + getWidth() - 12, getY() + (getHeight() - ARROW_DOWN.length) / 2, ARROW_DOWN, TEXT_DIM);
        }

        /** Trims a too-long option with ".." so it never runs under the arrow. */
        private static String fit(String text, int maxWidth) {
            Font font = font();
            if (font.width(text) <= maxWidth) {
                return text;
            }
            String cut = text;
            while (cut.length() > 3 && font.width(cut + "..") > maxWidth) {
                cut = cut.substring(0, cut.length() - 1);
            }
            return cut + "..";
        }
    }

    /** Colour row button: a small swatch plus the current hex value. */
    public static final class ColorButton extends Flat {
        private final IntSupplier rgb;
        private final Runnable onPress;
        private int shownRgb = Integer.MIN_VALUE;
        private String shownHex = "#000000";

        public ColorButton(int x, int y, int w, int h, IntSupplier rgb, Runnable onPress) {
            super(x, y, w, h, Component.literal("color"));
            this.rgb = rgb;
            this.onPress = onPress;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            onPress.run();
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            boolean hover = hovered(mouseX, mouseY);
            drawBox(g, CONTROL_BG, hover ? CONTROL_BORDER_HOVER : CONTROL_BORDER);
            int value = rgb.getAsInt() & 0xFFFFFF;
            if (value != shownRgb) {
                shownRgb = value;
                shownHex = String.format("#%06X", value);
                setMessage(Component.literal(shownHex));
            }
            g.fill(getX() + 5, getY() + 5, getX() + 16, getY() + 16, 0xFF000000 | value);
            border(g, getX() + 5, getY() + 5, 11, 11, 0x5A000000);
            g.text(font(), shownHex, getX() + 21, textY(getY(), getHeight()), TEXT, true);
        }
    }

    /** Flat slider: a dark track with an accent fill, or an R/G/B gradient with a white knob. */
    public static final class Slider extends AbstractSliderButton {
        private final int min;
        private final int max;
        private final IntConsumer onValue;
        private final int gradFrom;
        private final int gradTo;
        private int shownValue = Integer.MIN_VALUE;
        private String shownText = "";

        /** Plain accent slider (config rows). */
        public Slider(int x, int y, int w, int h, int min, int max, int value, IntConsumer onValue) {
            this(x, y, w, h, min, max, value, 0, 0, onValue);
        }

        /** Gradient slider (colour channels); {@code gradFrom}/{@code gradTo} are opaque ARGB. */
        public Slider(int x, int y, int w, int h, int min, int max, int value,
                      int gradFrom, int gradTo, IntConsumer onValue) {
            super(x, y, w, h, Component.empty(), (double) (value - min) / Math.max(1, max - min));
            this.min = min;
            this.max = max;
            this.gradFrom = gradFrom;
            this.gradTo = gradTo;
            this.onValue = onValue;
            updateMessage();
        }

        public int current() {
            return min + (int) Math.round(this.value * (max - min));
        }

        @Override
        protected void updateMessage() {
            if (onValue == null) {
                return; // defensive: the super constructor must not hit unset fields
            }
            int value = current();
            if (value != shownValue) {
                shownValue = value;
                shownText = Integer.toString(value);
                setMessage(Component.literal(shownText));
            }
        }

        @Override
        protected void applyValue() {
            onValue.accept(current());
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            boolean hover = this.active && this.isMouseOver(mouseX, mouseY);
            box(g, x, y, w, h, CONTROL_BG, hover ? CONTROL_BORDER_HOVER : CONTROL_BORDER);
            int innerW = w - 2;
            int value = current();
            double fraction = max == min ? 0.0 : (double) (value - min) / (max - min);
            if (gradFrom != 0) {
                for (int px = 0; px < innerW; px++) {
                    g.fill(x + 1 + px, y + 1, x + 2 + px, y + h - 1,
                            mix(gradFrom, gradTo, (float) px / Math.max(1, innerW - 1)));
                }
                int knob = x + 1 + (int) Math.round(innerW * fraction);
                g.fill(knob - 1, y + 1, knob + 1, y + h - 1, 0xE6FFFFFF);
            } else {
                int filled = (int) Math.round(innerW * fraction);
                if (filled > 0) {
                    g.fill(x + 1, y + 1, x + 1 + filled, y + h - 1, ACCENT_TRACK);
                }
            }
            Font font = font();
            g.text(font, shownText, x + (w - font.width(shownText)) / 2, textY(y, h),
                    gradFrom != 0 ? 0xFFFFFFFF : TEXT, true);
        }

        private static int mix(int from, int to, float t) {
            int fr = (from >> 16) & 0xFF;
            int fg = (from >> 8) & 0xFF;
            int fb = from & 0xFF;
            int tr = (to >> 16) & 0xFF;
            int tg = (to >> 8) & 0xFF;
            int tb = to & 0xFF;
            int r = (int) (fr + (tr - fr) * t);
            int g = (int) (fg + (tg - fg) * t);
            int b = (int) (fb + (tb - fb) * t);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }

    /** Sidebar entry: a pixel icon plus a label; accent-highlighted when selected. */
    public static final class NavItem extends Flat {
        private final String[] icon;
        private final String label;
        private final boolean selected;
        private final Runnable onPress;

        public NavItem(int x, int y, int w, int h, String[] icon, String label, boolean selected, Runnable onPress) {
            super(x, y, w, h, Component.literal(label));
            this.icon = icon;
            this.label = label;
            this.selected = selected;
            this.onPress = onPress;
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            onPress.run();
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            if (selected) {
                g.fill(x, y, x + w, y + h, ACCENT_SOFT);
                g.fill(x, y, x + 2, y + h, ACCENT);
            } else if (hovered(mouseX, mouseY)) {
                g.fill(x, y, x + w, y + h, HOVER_WASH);
            }
            pattern(g, x + 8, y + (h - icon.length) / 2, icon, selected ? ACCENT : TEXT_MUTED);
            g.text(font(), label, x + 22, textY(y, h), selected ? TEXT : TEXT_DIM, true);
        }
    }

    /** Flat text field: our own box around a vanilla EditBox without its textured frame. */
    public static final class Field extends EditBox {
        public Field(Font font, int x, int y, int w, int h) {
            super(font, x, y, w, h, Component.empty());
            setBordered(false);
            setTextColor(TEXT);
            setTextColorUneditable(TEXT_DIM);
        }

        @Override
        public int getInnerWidth() {
            return getWidth() - 8; // keep the vanilla 4px side padding
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            int border = isFocused() ? ACCENT
                    : (isMouseOver(mouseX, mouseY) ? CONTROL_BORDER_HOVER : CONTROL_BORDER);
            box(g, getX(), getY(), getWidth(), getHeight(), CONTROL_BG, border);
            // The borderless field paints its text at the widget's top-left corner; the pose
            // shift recreates the padded, vertically centred look of the bordered vanilla field.
            Matrix3x2fStack pose = g.pose();
            pose.pushMatrix();
            pose.translate(4.0F, (getHeight() - TEXT_H) / 2.0F);
            super.extractWidgetRenderState(g, mouseX, mouseY, partialTick);
            pose.popMatrix();
        }
    }

    /** Read-only text in the control column (hints, benchmark results); right-aligned, inert. */
    public static final class Label extends Flat {
        private final Supplier<String> text;
        private final int color;

        public Label(int x, int y, int w, int h, Supplier<String> text, int color) {
            super(x, y, w, h, Component.empty());
            this.text = text;
            this.color = color;
            this.active = false; // never swallows clicks
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            Font font = font();
            String value = text.get();
            g.text(font, value, getX() + getWidth() - font.width(value),
                    textY(getY(), getHeight()), color, true);
        }
    }

    /** Live FPS sparkline of the benchmark: one column per slot, reads the shared sample list. */
    public static final class Graph extends Flat {
        private final List<Integer> samples;

        public Graph(int x, int y, int w, int h, List<Integer> samples) {
            super(x, y, w, h, Component.empty());
            this.samples = samples;
            this.active = false;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
            drawBox(g, CONTROL_BG, CONTROL_BORDER);
            int innerW = getWidth() - 2;
            int innerH = getHeight() - 2;
            if (innerW <= 0 || innerH <= 0 || samples.isEmpty()) {
                return;
            }
            int max = 60;
            for (int sample : samples) {
                max = Math.max(max, sample);
            }
            int count = samples.size();
            int x0 = getX() + 1;
            int y0 = getY() + 1;
            for (int px = 0; px < innerW; px++) {
                int sample = samples.get((int) ((long) px * count / innerW));
                int height = Math.max(1, (int) Math.round((double) sample / max * innerH));
                g.fill(x0 + px, y0 + innerH - height, x0 + px + 1, y0 + innerH, ACCENT_TRACK);
            }
        }
    }
}
