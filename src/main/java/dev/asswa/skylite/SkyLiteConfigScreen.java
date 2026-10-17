package dev.asswa.skylite;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The built-in SkyLite config screen — plain vanilla widgets, so neither Cloth Config nor
 * Mod Menu is required. Opened from Mod Menu (when installed), by {@code /skylite} or
 * {@code /skylite menu}. A scrolling list of "label + control" rows; every control applies
 * its value to the live {@link SkyLiteConfig} at once and the file is written on close.
 */
public final class SkyLiteConfigScreen extends Screen {
    private static final int ROW_H = 22;
    private static final int CONTROL_W = 150;
    private static final int TOGGLE_W = 45;

    private final Screen parent;
    private final SkyLiteConfig cfg;
    private final List<Row> rows = new ArrayList<>();

    private EditBox keywordsBox;
    private double scroll;

    private SkyLiteConfigScreen(Screen parent, SkyLiteConfig cfg) {
        super(Component.literal("SkyLite"));
        this.parent = parent;
        this.cfg = cfg;
    }

    /** Entry point used by the command and the Mod Menu hook; {@code parent} may be null. */
    public static Screen create(Screen parent) {
        SkyLiteConfig cfg = SkyLiteClient.config;
        return cfg == null ? parent : new SkyLiteConfigScreen(parent, cfg);
    }

    @Override
    protected void init() {
        clearWidgets();
        rows.clear();
        keywordsBox = null;

        int cx = this.width / 2;
        addRenderableWidget(Button.builder(Component.translatable("key.skylite.move_bars"), b -> {
            saveAll();
            Minecraft.getInstance().setScreenAndShow(new SkyLiteHudEditor());
        }).bounds(cx - 105, this.height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("skylite.config.done"), b -> onClose())
                .bounds(cx + 5, this.height - 28, 100, 20).build());

        header("skylite.config.category.bars");
        colorRow("skylite.config.bars.color_hp", 0);
        colorRow("skylite.config.bars.color_mana", 1);
        colorRow("skylite.config.bars.color_xp", 2);
        colorRow("skylite.config.bars.color_text", 3);
        sliderRow("skylite.config.bars.width_hp", 40, 320, cfg.hpBarWidth, v -> cfg.hpBarWidth = v);
        sliderRow("skylite.config.bars.width_mana", 40, 320, cfg.manaBarWidth, v -> cfg.manaBarWidth = v);
        sliderRow("skylite.config.bars.width_xp", 40, 320, cfg.xpBarWidth, v -> cfg.xpBarWidth = v);
        sliderRow("skylite.config.bars.thickness_hp", 3, 14, cfg.hpBarHeight, v -> cfg.hpBarHeight = v);
        sliderRow("skylite.config.bars.thickness_mana", 3, 14, cfg.manaBarHeight, v -> cfg.manaBarHeight = v);
        sliderRow("skylite.config.bars.thickness_xp", 3, 14, cfg.xpBarHeight, v -> cfg.xpBarHeight = v);
        toggleRow("skylite.config.bars.show_numbers", cfg.showBarNumbers, v -> cfg.showBarNumbers = v);
        toggleRow("skylite.config.bars.number_background", cfg.textBackground, v -> cfg.textBackground = v);
        sliderRow("skylite.config.bars.track_opacity", 0, 100, cfg.trackOpacity, v -> cfg.trackOpacity = v);
        formatRow();
        toggleRow("skylite.config.bars.glyphs", cfg.showGlyphs, v -> cfg.showGlyphs = v);
        toggleRow("skylite.config.bars.outline", cfg.barOutline, v -> cfg.barOutline = v);
        toggleRow("skylite.config.bars.pulse", cfg.lowValuePulse, v -> cfg.lowValuePulse = v);

        header("skylite.config.category.info");
        toggleRow("skylite.config.info.defense", cfg.showDefense, v -> cfg.showDefense = v);
        toggleRow("skylite.config.info.area", cfg.showArea, v -> cfg.showArea = v);
        toggleRow("skylite.config.info.cookie", cfg.showCookie, v -> cfg.showCookie = v);
        toggleRow("skylite.config.info.godpot", cfg.showGodPot, v -> cfg.showGodPot = v);
        toggleRow("skylite.config.info.purse", cfg.showPurse, v -> cfg.showPurse = v);
        toggleRow("skylite.config.info.bits", cfg.showBits, v -> cfg.showBits = v);
        toggleRow("skylite.config.info.speed", cfg.showSpeed, v -> cfg.showSpeed = v);
        toggleRow("skylite.config.info.ping_fps", cfg.showPingFps, v -> cfg.showPingFps = v);
        cornerRow();

        header("skylite.config.category.alerts");
        toggleRow("skylite.config.alerts.enabled", cfg.alertsEnabled, v -> cfg.alertsEnabled = v);
        toggleRow("skylite.config.alerts.sound", cfg.alertSound, v -> cfg.alertSound = v);
        toggleRow("skylite.config.alerts.flash", cfg.alertFlash, v -> cfg.alertFlash = v);
        keywordsRow();

        header("skylite.config.category.hud");
        toggleRow("skylite.config.hud.hearts", cfg.hideVanillaHearts, v -> cfg.hideVanillaHearts = v);
        toggleRow("skylite.config.hud.hunger", cfg.hideVanillaFoodBar, v -> cfg.hideVanillaFoodBar = v);
        toggleRow("skylite.config.hud.xp_bar", cfg.hideVanillaXpBar, v -> cfg.hideVanillaXpBar = v);
        toggleRow("skylite.config.hud.stats_line", cfg.hideStatsActionBar, v -> cfg.hideStatsActionBar = v);
    }

    /** Section header (gold text, no control). */
    private void header(String key) {
        rows.add(new Row(Component.translatable(key).getString(), null, 0, true));
    }

    private void toggleRow(String key, boolean initial, Consumer<Boolean> setter) {
        boolean[] state = {initial};
        Button b = Button.builder(toggleText(state[0]), btn -> {
            state[0] = !state[0];
            btn.setMessage(toggleText(state[0]));
            setter.accept(state[0]);
        }).bounds(0, 0, TOGGLE_W, 20).build();
        addRenderableWidget(b);
        rows.add(new Row(Component.translatable(key).getString(), b, TOGGLE_W, false));
    }

    private static Component toggleText(boolean on) {
        return Component.translatable(on ? "skylite.config.on" : "skylite.config.off");
    }

    private void sliderRow(String key, int min, int max, int value, IntConsumer setter) {
        String label = Component.translatable(key).getString();
        IntSlider slider = new IntSlider(0, 0, CONTROL_W, 20, label, min, max, value, setter);
        addRenderableWidget(slider);
        rows.add(new Row(label, slider, CONTROL_W, false));
    }

    /** Colour row: a button with the current hex value; clicking opens the RGB picker. */
    private void colorRow(String key, int which) {
        Button b = Button.builder(Component.literal(colorHex(which)), btn ->
                        Minecraft.getInstance().setScreenAndShow(new SkyLiteColorScreen(this, cfg, which)))
                .bounds(0, 0, CONTROL_W, 20).build();
        addRenderableWidget(b);
        rows.add(new Row(Component.translatable(key).getString(), b, CONTROL_W, false));
    }

    private String colorHex(int which) {
        int rgb = switch (which) {
            case 0 -> cfg.healthRgb();
            case 1 -> cfg.manaRgb();
            case 2 -> cfg.xpRgb();
            default -> cfg.textRgb();
        };
        return SkyLiteConfig.toHex(rgb);
    }

    private void formatRow() {
        String[] formats = {"full", "compact", "percent"};
        String[] state = {cfg.numberFormat};
        Button b = Button.builder(formatText(state[0]), btn -> {
            int index = 0;
            for (int k = 0; k < formats.length; k++) {
                if (formats[k].equals(state[0])) {
                    index = k;
                }
            }
            state[0] = formats[(index + 1) % formats.length];
            btn.setMessage(formatText(state[0]));
            cfg.numberFormat = state[0];
        }).bounds(0, 0, CONTROL_W, 20).build();
        addRenderableWidget(b);
        rows.add(new Row(Component.translatable("skylite.config.bars.number_format").getString(), b, CONTROL_W, false));
    }

    private static Component formatText(String value) {
        return Component.translatable("skylite.config.bars.format_" + value);
    }

    private void cornerRow() {
        SkyLiteConfig.Corner[] corners = SkyLiteConfig.Corner.values();
        SkyLiteConfig.Corner[] state = {
                cfg.pingFpsCorner == null ? SkyLiteConfig.Corner.LEFT_TOP : cfg.pingFpsCorner};
        Button b = Button.builder(Component.translatable(state[0].key), btn -> {
            state[0] = corners[(state[0].ordinal() + 1) % corners.length];
            btn.setMessage(Component.translatable(state[0].key));
            cfg.pingFpsCorner = state[0];
        }).bounds(0, 0, CONTROL_W, 20).build();
        addRenderableWidget(b);
        rows.add(new Row(Component.translatable("skylite.config.info.corner").getString(), b, CONTROL_W, false));
    }

    private void keywordsRow() {
        List<String> kws = new ArrayList<>();
        for (String kw : cfg.alertKeywords) {
            if (kw != null && !kw.isBlank()) {
                kws.add(kw.trim());
            }
        }
        keywordsBox = new EditBox(this.font, 0, 0, CONTROL_W, 20, Component.empty());
        keywordsBox.setMaxLength(400);
        keywordsBox.setValue(String.join(", ", kws));
        addRenderableWidget(keywordsBox);
        rows.add(new Row(Component.translatable("skylite.config.alerts.keywords").getString(), keywordsBox, CONTROL_W, false));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xCC000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int colW = Math.min(360, this.width - 40);
        int colLeft = (this.width - colW) / 2;
        int areaTop = 28;
        int areaBottom = this.height - 38;
        int areaH = Math.max(1, areaBottom - areaTop);
        int contentH = rows.size() * ROW_H;
        double maxScroll = Math.max(0, contentH - areaH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int y0 = areaTop - (int) Math.round(scroll);

        // Move every control to its scrolled spot; controls outside the view are hidden.
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.widget == null) {
                continue;
            }
            int y = y0 + i * ROW_H;
            row.widget.setX(colLeft + colW - row.width);
            row.widget.setY(y + 1);
            row.widget.visible = y >= areaTop && y + ROW_H <= areaBottom;
        }

        super.extractRenderState(g, mouseX, mouseY, partialTick);

        String title = this.title.getString();
        g.text(font, title, (this.width - font.width(title)) / 2, 10, 0xFFFFFFFF, true);

        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = y0 + i * ROW_H;
            if (y < areaTop || y + ROW_H > areaBottom) {
                continue;
            }
            int color = row.header ? 0xFFEEEE55 : 0xFFFFFFFF;
            g.text(font, row.label, colLeft, y + (ROW_H - font.lineHeight) / 2, color, true);
        }

        if (maxScroll > 0) {
            int sbX = colLeft + colW + 6;
            g.fill(sbX, areaTop, sbX + 2, areaBottom, 0x40FFFFFF);
            int thumbH = Math.max(16, areaH * areaH / Math.max(1, contentH));
            int thumbY = areaTop + (int) ((areaH - thumbH) * (scroll / maxScroll));
            g.fill(sbX, thumbY, sbX + 2, thumbY + thumbH, 0xA0FFFFFF);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll -= scrollY * ROW_H;
        return true;
    }

    @Override
    public void onClose() {
        saveAll();
        Minecraft mc = Minecraft.getInstance();
        if (parent == null) {
            mc.gui.setScreen(null); // no parent (opened by command) — back to the game
        } else {
            mc.setScreenAndShow(parent);
        }
    }

    /** Applies the text field and writes everything to skylite.json. */
    private void saveAll() {
        if (keywordsBox != null) {
            List<String> list = new ArrayList<>();
            for (String part : keywordsBox.getValue().split(",")) {
                String kw = part.trim();
                if (!kw.isEmpty()) {
                    list.add(kw);
                }
            }
            cfg.alertKeywords = list;
        }
        cfg.applyColors();
        cfg.save();
        SkyLiteClient.stats.rebuild();
    }

    /** One list row: a section header ({@code widget == null}) or a label plus its control. */
    private static final class Row {
        final String label; // resolved once: a label never changes while the screen is open
        final AbstractWidget widget;
        final int width;
        final boolean header;

        Row(String label, AbstractWidget widget, int width, boolean header) {
            this.label = label;
            this.widget = widget;
            this.width = width;
            this.header = header;
        }
    }

    /** Integer slider with "label: value" text and a value callback. */
    private static final class IntSlider extends AbstractSliderButton {
        private final String label;
        private final int min;
        private final int max;
        private final IntConsumer onValue;

        IntSlider(int x, int y, int w, int h, String label, int min, int max, int value,
                  IntConsumer onValue) {
            super(x, y, w, h, Component.literal(label), (double) (value - min) / (max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.onValue = onValue;
            updateMessage();
        }

        private int current() {
            return min + (int) Math.round(value * (max - min));
        }

        @Override
        protected void updateMessage() {
            if (label == null) {
                return; // defensive: the super constructor must not hit unset fields
            }
            setMessage(Component.literal(label + ": " + current()));
        }

        @Override
        protected void applyValue() {
            onValue.accept(current());
        }
    }
}
