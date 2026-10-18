package dev.asswa.skylite;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
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
        CHAT("skylite.config.nav.chat", "skylite.config.category.chat", SkyLiteUi.ICON_CHAT),
        HUD("skylite.config.nav.hud", "skylite.config.category.hud", SkyLiteUi.ICON_EYE),
        OPTIMIZE("skylite.config.nav.optimize", "skylite.config.category.optimize", SkyLiteUi.ICON_BOLT);

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
    private EditBox chatFilterBox;
    private EditBox areaRulesBox;
    private double scroll;

    // FPS benchmark of the "Optimization" tab (per screen lifetime; the sample list is live).
    private static final long BENCH_DURATION_NS = 10_000_000_000L;
    private static final long BENCH_SAMPLE_NS = 50_000_000L;
    private final List<Integer> benchSamples = new ArrayList<>();
    private SkyLiteUi.Btn benchButton;
    private boolean benchRunning;
    private long benchStartNs;
    private long benchSampleNs;
    private int benchAvg = -1;
    private int benchLow = -1;
    private int benchPrevAvg = -1;
    private int benchPrevLow = -1;

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
        chatFilterBox = null;
        areaRulesBox = null;
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
            stashFields(); // the fields are row widgets: keep their text before rebuilding
            current = cat;
            scroll = 0;
            rebuildPending = true;
        }
    }

    /** Rows of the selected category, mirroring the old single-column screen. */
    private void buildRows() {
        switch (current) {
            case BARS -> {
                styleRow();
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
            case CHAT -> {
                toggleRow("skylite.config.chat.highlight", () -> cfg.chatHighlightName, v -> cfg.chatHighlightName = v);
                toggleRow("skylite.config.chat.filter", () -> cfg.chatFilterEnabled, v -> cfg.chatFilterEnabled = v);
                toggleRow("skylite.config.chat.repeats", () -> cfg.chatHideRepeats, v -> cfg.chatHideRepeats = v);
                phrasesRow();
            }
            case HUD -> {
                toggleRow("skylite.config.hud.hearts", () -> cfg.hideVanillaHearts, v -> cfg.hideVanillaHearts = v);
                toggleRow("skylite.config.hud.hunger", () -> cfg.hideVanillaFoodBar, v -> cfg.hideVanillaFoodBar = v);
                toggleRow("skylite.config.hud.xp_bar", () -> cfg.hideVanillaXpBar, v -> cfg.hideVanillaXpBar = v);
                toggleRow("skylite.config.hud.stats_line", () -> cfg.hideStatsActionBar, v -> cfg.hideStatsActionBar = v);
            }
            case OPTIMIZE -> optimizeRows();
        }
    }

    /** The "Optimization" tab: one-click profiles, advisor, mod tips, location rules, FPS benchmark. */
    private void optimizeRows() {
        Minecraft mc = Minecraft.getInstance();
        noteRow(tr("skylite.opt.section.profiles"));
        for (Optimizer.Profile profile : Optimizer.Profile.values()) {
            addRow(tr(profile.nameKey), new SkyLiteUi.Btn(0, 0, ctrlW, 20,
                    tr("skylite.opt.apply"), SkyLiteUi.Btn.NEUTRAL, () -> {
                        Optimizer.applyProfile(Minecraft.getInstance(), cfg, profile);
                        stashFields();
                        rebuildPending = true;
                    }), ctrlW, tipFor(profile.nameKey));
        }
        SkyLiteUi.Btn restore = new SkyLiteUi.Btn(0, 0, ctrlW, 20, tr("skylite.opt.restore"),
                SkyLiteUi.Btn.NEUTRAL, () -> {
                    if (Optimizer.restore(Minecraft.getInstance(), cfg)) {
                        stashFields();
                        rebuildPending = true;
                    }
                });
        restore.active = Optimizer.canRestore(cfg);
        addRow(tr("skylite.opt.restore.label"), restore, ctrlW, "skylite.tip.opt.restore");

        noteRow(tr("skylite.opt.section.advisor"));
        List<Optimizer.Advice> advices = Optimizer.advices(mc);
        if (advices.isEmpty()) {
            noteRow(tr("skylite.opt.advice.done"));
        } else {
            int adviceW = Math.min(96, ctrlW);
            for (Optimizer.Advice advice : advices) {
                addRow(advice.label(), new SkyLiteUi.Btn(0, 0, adviceW, 20,
                        tr("skylite.opt.apply"), SkyLiteUi.Btn.NEUTRAL, () -> {
                            Optimizer.captureOnce(cfg, Minecraft.getInstance().options);
                            advice.apply().run();
                            Optimizer.persist(cfg, Minecraft.getInstance().options);
                            stashFields();
                            rebuildPending = true;
                        }), adviceW);
            }
            addRow("", new SkyLiteUi.Btn(0, 0, ctrlW, 20, tr("skylite.opt.advice.all"),
                    SkyLiteUi.Btn.PRIMARY, () -> {
                        Optimizer.captureOnce(cfg, Minecraft.getInstance().options);
                        for (Optimizer.Advice advice : advices) {
                            advice.apply().run();
                        }
                        Optimizer.persist(cfg, Minecraft.getInstance().options);
                        stashFields();
                        rebuildPending = true;
                    }), ctrlW);
        }

        noteRow(tr("skylite.opt.section.mods"));
        int modW = Math.min(96, ctrlW);
        for (Optimizer.ModTip tip : Optimizer.MOD_TIPS) {
            boolean present = Optimizer.installed(tip.modId());
            SkyLiteUi.Btn button = new SkyLiteUi.Btn(0, 0, modW, 20,
                    tr(present ? "skylite.opt.mod.installed" : "skylite.opt.mod.download"),
                    SkyLiteUi.Btn.NEUTRAL, () -> Optimizer.openUrl(tip.url()));
            button.active = !present;
            addRow(tr(tip.nameKey()), button, modW, "skylite.tip.opt.mod");
        }

        noteRow(tr("skylite.opt.section.area"));
        toggleRow("skylite.opt.area.toggle", () -> cfg.optAreaProfiles, v -> cfg.optAreaProfiles = v);
        String[] areaIds = {"none", "bal", "perf", "max"};
        String[] areaLabels = {tr("skylite.opt.area.none"), tr("skylite.opt.profile.balanced"),
                tr("skylite.opt.profile.performance"), tr("skylite.opt.profile.maximum")};
        addRow(tr("skylite.opt.area.default"), new SkyLiteUi.Dropdown(0, 0, ctrlW, 20, areaLabels,
                () -> indexOf(areaIds, cfg.optAreaDefault),
                i -> cfg.optAreaDefault = areaIds[i]), ctrlW, tipFor("skylite.opt.area.default"));
        areaRulesBox = new SkyLiteUi.Field(this.font, 0, 0, ctrlW, 20);
        areaRulesBox.setMaxLength(400);
        areaRulesBox.setValue(joinList(cfg.optAreaRules));
        addRow(tr("skylite.opt.area.rules"), areaRulesBox, ctrlW, tipFor("skylite.opt.area.rules"));
        noteRow(tr("skylite.opt.area.hint"));

        noteRow(tr("skylite.opt.section.bench"));
        int startW = Math.min(110, ctrlW);
        benchButton = new SkyLiteUi.Btn(0, 0, startW, 20, tr("skylite.opt.bench.start"),
                SkyLiteUi.Btn.NEUTRAL, this::startBench);
        benchButton.active = !benchRunning;
        addRow(tr("skylite.opt.bench.title"), benchButton, startW, tipFor("skylite.opt.bench.title"));
        addRow(tr("skylite.opt.bench.current"),
                new SkyLiteUi.Label(0, 0, ctrlW, 20, this::benchCurrentText, SkyLiteUi.TEXT), ctrlW,
                tipFor("skylite.opt.bench.current"));
        addRow(tr("skylite.opt.bench.prev"),
                new SkyLiteUi.Label(0, 0, ctrlW, 20, this::benchPrevText, SkyLiteUi.TEXT_DIM), ctrlW,
                tipFor("skylite.opt.bench.prev"));
        addRow(tr("skylite.opt.bench.delta"),
                new SkyLiteUi.Label(0, 0, ctrlW, 20, this::benchDeltaText, SkyLiteUi.TEXT_DIM), ctrlW,
                tipFor("skylite.opt.bench.delta"));
        addRow("", new SkyLiteUi.Graph(0, 0, ctrlW, 20, benchSamples), ctrlW);
        noteRow(tr("skylite.opt.bench.hint"));
    }

    /** A text-only row (section titles and hints); notes have no control. */
    private void noteRow(String text) {
        rows.add(new Row(text, null, 0, null, true));
    }

    private void startBench() {
        if (benchRunning) {
            return;
        }
        benchRunning = true;
        benchSamples.clear();
        benchStartNs = System.nanoTime();
        benchSampleNs = 0L;
        if (benchButton != null) {
            benchButton.active = false;
        }
    }

    /** Samples the smoothed FPS while a measurement is running; runs once per rendered frame. */
    private void tickBench() {
        if (!benchRunning) {
            return;
        }
        long now = System.nanoTime();
        if (benchSampleNs == 0L || now - benchSampleNs >= BENCH_SAMPLE_NS) {
            benchSampleNs = now;
            benchSamples.add(Minecraft.getInstance().getFps());
        }
        if (now - benchStartNs >= BENCH_DURATION_NS) {
            finishBench();
        }
    }

    private void finishBench() {
        benchRunning = false;
        if (benchButton != null) {
            benchButton.active = true;
        }
        if (benchSamples.isEmpty()) {
            return;
        }
        long sum = 0;
        for (int sample : benchSamples) {
            sum += sample;
        }
        List<Integer> sorted = new ArrayList<>(benchSamples);
        sorted.sort(null);
        int slowest = sorted.size() - Math.max(1, sorted.size() / 100);
        benchPrevAvg = benchAvg;
        benchPrevLow = benchLow;
        benchAvg = (int) (sum / benchSamples.size());
        benchLow = sorted.get(Math.min(sorted.size() - 1, slowest));
    }

    private String benchCurrentText() {
        if (benchRunning) {
            long seconds = Math.min(10L, (System.nanoTime() - benchStartNs) / 1_000_000_000L);
            return Component.translatable("skylite.opt.bench.running", seconds).getString();
        }
        return benchAvg < 0 ? tr("skylite.opt.bench.dash")
                : Component.translatable("skylite.opt.bench.result", benchAvg, benchLow).getString();
    }

    private String benchPrevText() {
        return benchPrevAvg < 0 ? tr("skylite.opt.bench.dash")
                : Component.translatable("skylite.opt.bench.result", benchPrevAvg, benchPrevLow).getString();
    }

    private String benchDeltaText() {
        if (benchAvg < 0 || benchPrevAvg < 0) {
            return tr("skylite.opt.bench.dash");
        }
        int diff = benchAvg - benchPrevAvg;
        if (diff > 0) {
            return Component.translatable("skylite.opt.bench.faster", diff).getString();
        }
        if (diff < 0) {
            return Component.translatable("skylite.opt.bench.slower", -diff).getString();
        }
        return tr("skylite.opt.bench.same");
    }

    /** Adds a row without a hover explanation. */
    private void addRow(String label, AbstractWidget widget, int width) {
        addRow(label, widget, width, null);
    }

    /** Adds a row; {@code tipKey} (when non-null) becomes the explanation shown on label hover. */
    private void addRow(String label, AbstractWidget widget, int width, String tipKey) {
        addRenderableWidget(widget);
        rows.add(new Row(label, widget, width, tip(tipKey)));
    }

    /** Resolves a tooltip text; null when the key is null or missing from the language file. */
    private static String tip(String key) {
        if (key == null) {
            return null;
        }
        String text = Component.translatable(key).getString();
        return text.isEmpty() || text.equals(key) ? null : text; // a missing key resolves to itself
    }

    /** Tooltip key of a label key: skylite.config.x -> skylite.tip.x, skylite.opt.x -> skylite.tip.opt.x. */
    private static String tipFor(String labelKey) {
        if (labelKey.startsWith("skylite.config.")) {
            return "skylite.tip." + labelKey.substring("skylite.config.".length());
        }
        if (labelKey.startsWith("skylite.opt.")) {
            return "skylite.tip.opt." + labelKey.substring("skylite.opt.".length());
        }
        return null;
    }

    private void colorRow(String key, int which) {
        SkyLiteUi.ColorButton button = new SkyLiteUi.ColorButton(0, 0, colorW, 20, rgbSupplier(which),
                () -> Minecraft.getInstance().setScreenAndShow(new SkyLiteColorScreen(this, cfg, which)));
        addRow(tr(key), button, colorW, tipFor(key));
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
        addRow(tr(key), new SkyLiteUi.Slider(0, 0, ctrlW, 20, min, max, value, setter), ctrlW, tipFor(key));
    }

    private void toggleRow(String key, Supplier<Boolean> getter, Consumer<Boolean> setter) {
        int width = Math.min(64, ctrlW);
        addRow(tr(key), new SkyLiteUi.Toggle(0, 0, width, 20,
                tr("skylite.config.on"), tr("skylite.config.off"), getter, setter), width, tipFor(key));
    }

    private void styleRow() {
        String[] styles = {"classic", "rounded", "capsule", "gloss", "minimal", "framed"};
        String[] labels = new String[styles.length];
        for (int i = 0; i < styles.length; i++) {
            labels[i] = tr("skylite.config.bars.style_" + styles[i]);
        }
        SkyLiteUi.Dropdown dropdown = new SkyLiteUi.Dropdown(0, 0, ctrlW, 20, labels,
                () -> indexOf(styles, cfg.barStyle),
                i -> cfg.barStyle = styles[i]);
        addRow(tr("skylite.config.bars.style"), dropdown, ctrlW, tipFor("skylite.config.bars.style"));
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
        addRow(tr("skylite.config.bars.number_format"), dropdown, ctrlW,
                tipFor("skylite.config.bars.number_format"));
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
        addRow(tr("skylite.config.info.corner"), dropdown, ctrlW, tipFor("skylite.config.info.corner"));
    }

    private void keywordsRow() {
        keywordsBox = new SkyLiteUi.Field(this.font, 0, 0, ctrlW, 20);
        keywordsBox.setMaxLength(400);
        keywordsBox.setValue(joinList(cfg.alertKeywords));
        addRow(tr("skylite.config.alerts.keywords"), keywordsBox, ctrlW,
                tipFor("skylite.config.alerts.keywords"));
    }

    private void phrasesRow() {
        chatFilterBox = new SkyLiteUi.Field(this.font, 0, 0, ctrlW, 20);
        chatFilterBox.setMaxLength(400);
        chatFilterBox.setValue(joinList(cfg.chatFilterPhrases));
        addRow(tr("skylite.config.chat.phrases"), chatFilterBox, ctrlW,
                tipFor("skylite.config.chat.phrases"));
    }

    /** Comma-joined non-blank entries of a list, for display in a text field. */
    private static String joinList(List<String> values) {
        StringBuilder out = new StringBuilder();
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    if (out.length() > 0) {
                        out.append(", ");
                    }
                    out.append(value.trim());
                }
            }
        }
        return out.toString();
    }

    /** Splits a comma-separated field value into trimmed non-empty entries. */
    private static List<String> splitList(String value) {
        List<String> list = new ArrayList<>();
        for (String part : value.split(",")) {
            String entry = part.trim();
            if (!entry.isEmpty()) {
                list.add(entry);
            }
        }
        return list;
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
        tickBench();
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
            if (row.widget == null) {
                continue; // note rows are text only
            }
            row.widget.setX(conX + conW - SkyLiteUi.PAD - row.width);
            row.widget.setY(y + 1);
            row.widget.visible = y >= rowsTop && y + SkyLiteUi.ROW_H <= rowsBottom;
        }

        drawChrome(g);
        drawRows(g, y0, areaH, contentH, maxScroll);
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        // Expanded dropdown lists are drawn last so neither a row nor a button covers them.
        for (Row row : rows) {
            if (row.widget instanceof SkyLiteUi.Dropdown dropdown && row.widget.visible && dropdown.isOpen()) {
                dropdown.renderList(g, mouseX, mouseY);
            }
        }

        // The hover explanation of the setting label sits above everything else.
        drawTip(g, mouseX, mouseY, y0);
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
            g.text(font, row.label, conX + SkyLiteUi.PAD, SkyLiteUi.textY(y + 1, 20),
                    row.note ? SkyLiteUi.TEXT_MUTED : SkyLiteUi.TEXT_DIM, true);
        }

        if (maxScroll > 0) {
            int barX = conX + conW - 6;
            g.fill(barX, rowsTop, barX + 2, rowsBottom, SkyLiteUi.SCROLL_TRACK);
            int thumbH = Math.max(16, areaH * areaH / Math.max(1, contentH));
            int thumbY = rowsTop + (int) ((areaH - thumbH) * (scroll / maxScroll));
            g.fill(barX, thumbY, barX + 2, thumbY + thumbH, SkyLiteUi.SCROLL_THUMB);
        }
    }

    /**
     * The explanation of the setting label under the cursor. Skipped while an option list is open
     * (the list owns the pointer) and outside the scroll area; drawn above rows, widgets and lists.
     */
    private void drawTip(GuiGraphicsExtractor g, int mouseX, int mouseY, int y0) {
        if (openDropdown() != null) {
            return;
        }
        if (mouseX < conX || mouseX > conX + conW || mouseY < rowsTop || mouseY > rowsBottom) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.tip == null) {
                continue;
            }
            int y = y0 + i * SkyLiteUi.ROW_H;
            if (y < rowsTop || y + SkyLiteUi.ROW_H > rowsBottom) {
                continue;
            }
            int labelX = conX + SkyLiteUi.PAD;
            int labelY = SkyLiteUi.textY(y + 1, 20);
            if (mouseX >= labelX - 2 && mouseX <= labelX + font.width(row.label) + 2
                    && mouseY >= labelY - 3 && mouseY <= labelY + SkyLiteUi.TEXT_H + 3) {
                renderTip(g, row.tip, mouseX, mouseY);
                return;
            }
        }
    }

    /** The hover box: wrapped text in a bordered dark panel next to the cursor, kept on screen. */
    private void renderTip(GuiGraphicsExtractor g, String text, int mouseX, int mouseY) {
        List<String> lines = wrapTip(text, Math.min(190, Math.max(120, conW - 24)));
        int lineH = 11;
        int boxW = 12;
        for (String line : lines) {
            boxW = Math.max(boxW, font.width(line) + 12);
        }
        int boxH = lines.size() * lineH + 9;
        int x = mouseX + 12;
        if (x + boxW > this.width - 4) {
            x = mouseX - 12 - boxW; // flip to the left near the screen edge
        }
        x = Math.max(4, Math.min(x, this.width - 4 - boxW));
        int y = mouseY + 12;
        if (y + boxH > this.height - 4) {
            y = mouseY - 12 - boxH; // flip above near the bottom edge
        }
        y = Math.max(4, Math.min(y, this.height - 4 - boxH));
        SkyLiteUi.box(g, x, y, boxW, boxH, SkyLiteUi.PANEL_BG_SOLID, SkyLiteUi.PANEL_BORDER);
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, lines.get(i), x + 6, y + 5 + i * lineH, SkyLiteUi.TEXT, true);
        }
    }

    /** Greedy word wrap of the tip text to about {@code maxW} pixels per line. */
    private List<String> wrapTip(String text, int maxW) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && font.width(line + " " + word) > maxW) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        lines.add(line.toString());
        return lines;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        SkyLiteUi.Dropdown open = openDropdown();
        if (open == null) {
            return super.mouseClicked(event, doubleClick);
        }
        // The expanded option list swallows the click before any widget under it.
        if (open.clickedInList(event.x(), event.y(), this.height)) {
            return true;
        }
        if (open.isMouseOver(event.x(), event.y())) {
            return super.mouseClicked(event, doubleClick); // the control itself toggles the list closed
        }
        closeDropdowns();
        if (overDropdownControl(event.x(), event.y())) {
            return super.mouseClicked(event, doubleClick); // another dropdown opens its own list
        }
        return true; // a click anywhere else only collapses the list
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape() && closeDropdowns()) {
            return true; // the first Esc only collapses an open option list
        }
        return super.keyPressed(event);
    }

    /** The expanded dropdown, if any — at most one list can be open at a time. */
    private SkyLiteUi.Dropdown openDropdown() {
        for (Row row : rows) {
            if (row.widget instanceof SkyLiteUi.Dropdown dropdown && row.widget.visible && dropdown.isOpen()) {
                return dropdown;
            }
        }
        return null;
    }

    /** True when the point is over a visible dropdown control. */
    private boolean overDropdownControl(double mx, double my) {
        for (Row row : rows) {
            if (row.widget instanceof SkyLiteUi.Dropdown dropdown && row.widget.visible && dropdown.isMouseOver(mx, my)) {
                return true;
            }
        }
        return false;
    }

    /** Collapses every expanded option list; true when at least one was open. */
    private boolean closeDropdowns() {
        boolean any = false;
        for (Row row : rows) {
            if (row.widget instanceof SkyLiteUi.Dropdown dropdown && dropdown.isOpen()) {
                dropdown.close();
                any = true;
            }
        }
        return any;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        closeDropdowns(); // an open list would otherwise stay glued to a scrolled-away row
        if (mouseX >= conX && mouseX <= conX + conW && mouseY >= rowsTop && mouseY <= rowsBottom) {
            scroll -= scrollY * SkyLiteUi.ROW_H;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        benchRunning = false; // an unfinished measurement dies with the screen
        saveAll();
        Minecraft mc = Minecraft.getInstance();
        if (parent == null) {
            mc.gui.setScreen(null); // no parent (opened by command) — back to the game
        } else {
            mc.setScreenAndShow(parent);
        }
    }

    /** Applies the text fields and writes everything to skylite.json. */
    private void saveAll() {
        stashFields();
        cfg.applyColors();
        cfg.save();
        SkyLiteClient.stats.rebuild();
    }

    /** Copies the text fields into the config (called before row rebuilding and on save). */
    private void stashFields() {
        if (keywordsBox != null) {
            cfg.alertKeywords = splitList(keywordsBox.getValue());
        }
        if (chatFilterBox != null) {
            cfg.chatFilterPhrases = splitList(chatFilterBox.getValue());
        }
        if (areaRulesBox != null) {
            cfg.optAreaRules = splitList(areaRulesBox.getValue());
        }
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

    /** One content row: a label plus its control; note rows have no widget at all. */
    private static final class Row {
        final String label;
        final AbstractWidget widget; // null on note rows
        final int width;
        final String tip;            // hover explanation; null when the row has none
        final boolean note;

        Row(String label, AbstractWidget widget, int width) {
            this(label, widget, width, null);
        }

        Row(String label, AbstractWidget widget, int width, String tip) {
            this(label, widget, width, tip, false);
        }

        Row(String label, AbstractWidget widget, int width, String tip, boolean note) {
            this.label = label;
            this.widget = widget;
            this.width = width;
            this.tip = tip;
            this.note = note;
        }
    }
}
