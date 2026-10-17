package dev.asswa.skylite;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * SkyLite config screen in the flat "Athen-like" style: a category sidebar and a content
 * panel of "label + control" rows. All of it is the mod's own toolkit ({@link SkyLiteUi}),
 * so neither Cloth Config nor Mod Menu is required. Every control applies its value to the
 * live {@link SkyLiteConfig} at once and the file is written on close. Opened from Mod Menu
 * (when installed), by {@code /skylite} or {@code /skylite menu}.
 */
public final class SkyLiteConfigScreen extends Screen {
    private static final int HEADER_H = 36; // content header: title + separator; rows start below
    private static final int FOOTER_H = 36; // content footer: separator + buttons
    private static final int NAV_H = 24;    // sidebar entry height

    /** Sidebar categories; the rows of each category live in {@link #buildRows()}. */
    private enum Cat {
        BARS("skylite.config.nav.bars", "skylite.config.category.bars", SkyLiteUi.ICON_BARS),
        INFO("skylite.config.nav.info", "skylite.config.category.info", SkyLiteUi.ICON_INFO),
        ALERTS("skylite.config.nav.alerts", "skylite.config.category.alerts", SkyLiteUi.ICON_BELL),
        HUD("skylite.config.nav.hud", "skylite.config.category.hud", SkyLiteUi.ICON_EYE);

        final String navKey;
        final String titleKey;
        final String[] icon;

        Cat(String navKey, String titleKey, String[] icon) {
            this.navKey = navKey;
            this.titleKey = titleKey;
            this.icon = icon;
        }
    }

    private final Screen parent;
    private final SkyLiteConfig cfg;
    private final List<Row> rows = new ArrayList<>();

    private Cat current = Cat.BARS;
    private boolean rebuildPending;
    private EditBox keywordsBox;
    private double scroll;

    // Layout, recomputed on init()/resize only.
    private int sideX, sideY, sideW, sideH;
    private int conX, conW;
    private int rowsTop, rowsBottom;
    private int ctrlW, colorW;
    private String version;
    private String versionLine;

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
        // Adaptive layout: works from the 1080p GUI scale 4 (480x270 logical) upwards.
        int margin = Math.max(12, Math.min(48, this.width / 13));
        int gap = 8;
        sideX = margin;
        sideY = Math.max(16, Math.min(54, this.height / 7));
        sideH = this.height - 2 * sideY;
        sideW = Math.min(136, Math.max(104, this.width / 5));
        conX = sideX + sideW + gap;
        conW = this.width - margin - conX;
        if (conW < 240) { // narrow window: keep the content panel usable, shrink the sidebar
            conW = Math.max(150, this.width - 2 * margin - gap - 104);
            sideW = Math.max(88, this.width - 2 * margin - gap - conW);
            conX = sideX + sideW + gap;
        }
        ctrlW = Math.max(92, Math.min(152, conW - 2 * SkyLiteUi.PAD - 150));
        colorW = Math.max(86, Math.min(104, ctrlW));
        rowsTop = sideY + HEADER_H;
        rowsBottom = sideY + sideH - FOOTER_H - 2;

        version = modVersion();
        versionLine = "v" + version + " · MC " + SharedConstants.getCurrentVersion().name();

        rebuildContents();
    }

    /**
     * Drops all widgets of the previous state and builds the ones of {@link #current}. Used by
     * {@link #init()} and on category switch: the {@link #rows} list must be reset as well, or the
     * previous category's labels and widget positions would stay in the content panel.
     */
    private void rebuildContents() {
        clearFocus();
        clearWidgets();
        rows.clear();
        keywordsBox = null;
        buildWidgets();
    }

    /** Creates the footer buttons, the sidebar entries and the rows of the current category. */
    private void buildWidgets() {
        int right = conX + conW - SkyLiteUi.PAD;
        int footY = sideY + sideH - FOOTER_H + 8;
        int doneW = 88;
        int moveW = Math.min(140, Math.max(96, conW - 2 * SkyLiteUi.PAD - doneW - 8));
        addRenderableWidget(new SkyLiteUi.Btn(right - doneW, footY, doneW, 20,
                tr("skylite.config.done"), SkyLiteUi.Btn.PRIMARY, this::onClose));
        addRenderableWidget(new SkyLiteUi.Btn(right - doneW - 8 - moveW, footY, moveW, 20,
                tr("skylite.config.move_hud"), SkyLiteUi.Btn.NEUTRAL, () -> {
                    saveAll();
                    Minecraft.getInstance().setScreenAndShow(new SkyLiteHudEditor());
                }));

        int navY = sideY + 34;
        for (Cat cat : Cat.values()) {
            addRenderableWidget(new SkyLiteUi.NavItem(sideX + 1, navY, sideW - 2, NAV_H, cat.icon,
                    tr(cat.navKey), cat == current, () -> openCategory(cat)));
            navY += NAV_H;
        }

        buildRows();
    }

    private void openCategory(Cat cat) {
        if (cat != current) {
            stashKeywords(); // the field is a row widget: keep its text before rebuilding
            current = cat;
            scroll = 0;
            rebuildPending = true;
        }
    }

    /** Rows of the selected category, mirroring the old single-column screen. */
    private void buildRows() {
        switch (current) {
            case BARS -> {
                colorRow("skylite.config.bars.color_hp", 0);
                colorRow("skylite.config.bars.color_mana", 1);
                colorRow("skylite.config.bars.color_xp", 2);
                colorRow("skylite.config.bars.color_text", 3);
                sliderRow("skylite.config.bars.width_hp", 40, 320, () -> cfg.hpBarWidth, v -> cfg.hpBarWidth = v);
                sliderRow("skylite.config.bars.width_mana", 40, 320, () -> cfg.manaBarWidth, v -> cfg.manaBarWidth = v);
                sliderRow("skylite.config.bars.width_xp", 40, 320, () -> cfg.xpBarWidth, v -> cfg.xpBarWidth = v);
                sliderRow("skylite.config.bars.thickness_hp", 3, 14, () -> cfg.hpBarHeight, v -> cfg.hpBarHeight = v);
                sliderRow("skylite.config.bars.thickness_mana", 3, 14, () -> cfg.manaBarHeight, v -> cfg.manaBarHeight = v);
                sliderRow("skylite.config.bars.thickness_xp", 3, 14, () -> cfg.xpBarHeight, v -> cfg.xpBarHeight = v);
                toggleRow("skylite.config.bars.show_numbers", () -> cfg.showBarNumbers, v -> cfg.showBarNumbers = v);
                toggleRow("skylite.config.bars.number_background", () -> cfg.textBackground, v -> cfg.textBackground = v);
                sliderRow("skylite.config.bars.track_opacity", 0, 100, () -> cfg.trackOpacity, v -> cfg.trackOpacity = v);
                formatRow();
                toggleRow("skylite.config.bars.glyphs", () -> cfg.showGlyphs, v -> cfg.showGlyphs = v);
                toggleRow("skylite.config.bars.outline", () -> cfg.barOutline, v -> cfg.barOutline = v);
                toggleRow("skylite.config.bars.pulse", () -> cfg.lowValuePulse, v -> cfg.lowValuePulse = v);
            }
            case INFO -> {
                toggleRow("skylite.config.info.defense", () -> cfg.showDefense, v -> cfg.showDefense = v);
                toggleRow("skylite.config.info.area", () -> cfg.showArea, v -> cfg.showArea = v);
                toggleRow("skylite.config.info.cookie", () -> cfg.showCookie, v -> cfg.showCookie = v);
                toggleRow("skylite.config.info.godpot", () -> cfg.showGodPot, v -> cfg.showGodPot = v);
                toggleRow("skylite.config.info.purse", () -> cfg.showPurse, v -> cfg.showPurse = v);
                toggleRow("skylite.config.info.bits", () -> cfg.showBits, v -> cfg.showBits = v);
                toggleRow("skylite.config.info.speed", () -> cfg.showSpeed, v -> cfg.showSpeed = v);
                toggleRow("skylite.config.info.ping_fps", () -> cfg.showPingFps, v -> cfg.showPingFps = v);
                cornerRow();
            }
            case ALERTS -> {
                toggleRow("skylite.config.alerts.enabled", () -> cfg.alertsEnabled, v -> cfg.alertsEnabled = v);
                toggleRow("skylite.config.alerts.sound", () -> cfg.alertSound, v -> cfg.alertSound = v);
                toggleRow("skylite.config.alerts.flash", () -> cfg.alertFlash, v -> cfg.alertFlash = v);
                keywordsRow();
            }
            case HUD -> {
                toggleRow("skylite.config.hud.hearts", () -> cfg.hideVanillaHearts, v -> cfg.hideVanillaHearts = v);
                toggleRow("skylite.config.hud.hunger", () -> cfg.hideVanillaFoodBar, v -> cfg.hideVanillaFoodBar = v);
                toggleRow("skylite.config.hud.xp_bar", () -> cfg.hideVanillaXpBar, v -> cfg.hideVanillaXpBar = v);
                toggleRow("skylite.config.hud.stats_line", () -> cfg.hideStatsActionBar, v -> cfg.hideStatsActionBar = v);
            }
        }
    }

    private void addRow(String label, AbstractWidget widget, int width) {
        addRenderableWidget(widget);
        rows.add(new Row(label, widget, width));
    }

    private void colorRow(String key, int which) {
        SkyLiteUi.ColorButton button = new SkyLiteUi.ColorButton(0, 0, colorW, 20, rgbSupplier(which),
                () -> Minecraft.getInstance().setScreenAndShow(new SkyLiteColorScreen(this, cfg, which)));
        addRow(tr(key), button, colorW);
    }

    private IntSupplier rgbSupplier(int which) {
        return switch (which) {
            case 1 -> cfg::manaRgb;
            case 2 -> cfg::xpRgb;
            case 3 -> cfg::textRgb;
            default -> cfg::healthRgb;
        };
    }

    private void sliderRow(String key, int min, int max, IntSupplier getter, IntConsumer setter) {
        int value = Math.max(min, Math.min(max, getter.getAsInt()));
        addRow(tr(key), new SkyLiteUi.Slider(0, 0, ctrlW, 20, min, max, value, setter), ctrlW);
    }

    private void toggleRow(String key, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        int width = Math.min(64, ctrlW);
        addRow(tr(key), new SkyLiteUi.Toggle(0, 0, width, 20,
                tr("skylite.config.on"), tr("skylite.config.off"), getter, setter), width);
    }

    private void formatRow() {
        String[] formats = {"full", "compact", "percent"};
        String[] labels = new String[formats.length];
        for (int i = 0; i < formats.length; i++) {
            labels[i] = tr("skylite.config.bars.format_" + formats[i]);
        }
        SkyLiteUi.Dropdown dropdown = new SkyLiteUi.Dropdown(0, 0, ctrlW, 20, labels,
                () -> indexOf(formats, cfg.numberFormat),
                i -> cfg.numberFormat = formats[i]);
        addRow(tr("skylite.config.bars.number_format"), dropdown, ctrlW);
    }

    private void cornerRow() {
        SkyLiteConfig.Corner[] corners = SkyLiteConfig.Corner.values();
        String[] labels = new String[corners.length];
        for (int i = 0; i < corners.length; i++) {
            labels[i] = tr(corners[i].key);
        }
        SkyLiteUi.Dropdown dropdown = new SkyLiteUi.Dropdown(0, 0, ctrlW, 20, labels,
                () -> (cfg.pingFpsCorner == null ? SkyLiteConfig.Corner.LEFT_TOP : cfg.pingFpsCorner).ordinal(),
                i -> cfg.pingFpsCorner = corners[i]);
        addRow(tr("skylite.config.info.corner"), dropdown, ctrlW);
    }

    private void keywordsRow() {
        List<String> keywords = new ArrayList<>();
        for (String keyword : cfg.alertKeywords) {
            if (keyword != null && !keyword.isBlank()) {
                keywords.add(keyword.trim());
            }
        }
        keywordsBox = new SkyLiteUi.Field(this.font, 0, 0, ctrlW, 20);
        keywordsBox.setMaxLength(400);
        keywordsBox.setValue(String.join(", ", keywords));
        addRow(tr("skylite.config.alerts.keywords"), keywordsBox, ctrlW);
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xCC000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        if (rebuildPending) {
            rebuildPending = false;
            rebuildContents();
        }

        // Place the row controls at their scrolled spot; rows outside the view are hidden.
        int areaH = Math.max(1, rowsBottom - rowsTop);
        int contentH = rows.size() * SkyLiteUi.ROW_H;
        double maxScroll = Math.max(0, contentH - areaH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        int y0 = rowsTop - (int) Math.round(scroll);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = y0 + i * SkyLiteUi.ROW_H;
            row.widget.setX(conX + conW - SkyLiteUi.PAD - row.width);
            row.widget.setY(y + 1);
            row.widget.visible = y >= rowsTop && y + SkyLiteUi.ROW_H <= rowsBottom;
        }

        drawChrome(g);
        drawRows(g, y0, areaH, contentH, maxScroll);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** Panels, headers, separators and the sidebar labels (the widgets draw on top). */
    private void drawChrome(GuiGraphicsExtractor g) {
        SkyLiteUi.panel(g, sideX, sideY, sideW, sideH);
        SkyLiteUi.pattern(g, sideX + 9, sideY + 10, SkyLiteUi.HEART, SkyLiteUi.HEART_RED);
        g.text(font, "SkyLite", sideX + 23, sideY + 10, SkyLiteUi.TEXT, true);
        g.text(font, version, sideX + sideW - 9 - font.width(version), sideY + 10, SkyLiteUi.TEXT_MUTED, true);
        g.fill(sideX + 1, sideY + 28, sideX + sideW - 1, sideY + 29, SkyLiteUi.SEP);
        g.text(font, versionLine, sideX + 9, sideY + sideH - 16, SkyLiteUi.TEXT_MUTED, true);

        SkyLiteUi.panel(g, conX, sideY, conW, sideH);
        g.text(font, tr(current.titleKey), conX + SkyLiteUi.PAD, sideY + 12, SkyLiteUi.TEXT, true);
        g.fill(conX + 1, sideY + 31, conX + conW - 1, sideY + 32, SkyLiteUi.SEP);
        int footSep = sideY + sideH - FOOTER_H;
        g.fill(conX + 1, footSep, conX + conW - 1, footSep + 1, SkyLiteUi.SEP);
    }

    /** Row labels and the scrollbar. */
    private void drawRows(GuiGraphicsExtractor g, int y0, int areaH, int contentH, double maxScroll) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            int y = y0 + i * SkyLiteUi.ROW_H;
            if (y < rowsTop || y + SkyLiteUi.ROW_H > rowsBottom) {
                continue;
            }
            g.text(font, row.label, conX + SkyLiteUi.PAD, SkyLiteUi.textY(y + 1, 20), SkyLiteUi.TEXT_DIM, true);
        }

        if (maxScroll > 0) {
            int barX = conX + conW - 6;
            g.fill(barX, rowsTop, barX + 2, rowsBottom, SkyLiteUi.SCROLL_TRACK);
            int thumbH = Math.max(16, areaH * areaH / Math.max(1, contentH));
            int thumbY = rowsTop + (int) ((areaH - thumbH) * (scroll / maxScroll));
            g.fill(barX, thumbY, barX + 2, thumbY + thumbH, SkyLiteUi.SCROLL_THUMB);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= conX && mouseX <= conX + conW && mouseY >= rowsTop && mouseY <= rowsBottom) {
            scroll -= scrollY * SkyLiteUi.ROW_H;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
        stashKeywords();
        cfg.applyColors();
        cfg.save();
        SkyLiteClient.stats.rebuild();
    }

    /** Copies the keyword field into the config (called before row rebuilding and on save). */
    private void stashKeywords() {
        if (keywordsBox == null) {
            return;
        }
        List<String> list = new ArrayList<>();
        for (String part : keywordsBox.getValue().split(",")) {
            String keyword = part.trim();
            if (!keyword.isEmpty()) {
                list.add(keyword);
            }
        }
        cfg.alertKeywords = list;
    }

    /** The mod version from the metadata (without the "+26.3" suffix). */
    private static String modVersion() {
        String raw = FabricLoader.getInstance().getModContainer("skylite")
                .map(container -> container.getMetadata().getVersion().getFriendlyString())
                .orElse("0.1.0");
        int plus = raw.indexOf('+');
        return plus > 0 ? raw.substring(0, plus) : raw;
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    /** One content row: a label plus its control. */
    private static final class Row {
        final String label;
        final AbstractWidget widget;
        final int width;

        Row(String label, AbstractWidget widget, int width) {
            this.label = label;
            this.widget = widget;
            this.width = width;
        }
    }
}
