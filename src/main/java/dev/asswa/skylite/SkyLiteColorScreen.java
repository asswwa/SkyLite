package dev.asswa.skylite;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Flat RGB colour picker (Athen-like modal, {@link SkyLiteUi}). Opened by right-clicking a
 * bar in the HUD editor or from the colour rows of the config screen. Changes are applied
 * live to the HUD; "Save" (or Esc) keeps them, "Cancel" restores the colour the picker was
 * opened with. The file is written on close.
 */
public final class SkyLiteColorScreen extends Screen {
    private static final String[] CHANNELS = {"R", "G", "B"};
    // Channel track gradients (dark end -> full-intensity end), matching the mock-up.
    private static final int[] GRAD_FROM = {0xFF1E0C0C, 0xFF0C1E0E, 0xFF0C0E1E};
    private static final int[] GRAD_TO = {0xFFFF4646, 0xFF5AFF5A, 0xFF5A6EFF};

    private final Screen parent;
    private final SkyLiteConfig cfg;
    private final int which; // 0 HP, 1 mana, 2 XP, 3 numbers

    private int red;
    private int green;
    private int blue;
    private final int initialRgb; // "Cancel" restores this

    private int modalX, modalY, modalW, modalH, innerX, innerW;
    private int shownRgb = Integer.MIN_VALUE;
    private String shownHex = "#000000";

    public SkyLiteColorScreen(Screen parent, SkyLiteConfig cfg, int which) {
        super(Component.translatable(labelKey(which)));
        this.parent = parent;
        this.cfg = cfg;
        this.which = which;
        int rgb = currentRgb();
        this.red = (rgb >> 16) & 0xFF;
        this.green = (rgb >> 8) & 0xFF;
        this.blue = rgb & 0xFF;
        this.initialRgb = rgb;
    }

    private static String labelKey(int which) {
        return switch (which) {
            case 0 -> "skylite.config.bars.color_hp";
            case 1 -> "skylite.config.bars.color_mana";
            case 2 -> "skylite.config.bars.color_xp";
            default -> "skylite.config.bars.color_text";
        };
    }

    private int currentRgb() {
        return switch (which) {
            case 0 -> cfg.healthRgb();
            case 1 -> cfg.manaRgb();
            case 2 -> cfg.xpRgb();
            default -> cfg.textRgb();
        };
    }

    private int pickedRgb() {
        return (red << 16) | (green << 8) | blue;
    }

    @Override
    protected void init() {
        modalW = Math.min(304, this.width - 32);
        modalH = 222;
        modalX = (this.width - modalW) / 2;
        modalY = (this.height - modalH) / 2;
        innerX = modalX + 12;
        innerW = modalW - 24;

        int sliderX = innerX + 40;
        int sliderW = Math.max(80, innerW - 40 - 48);
        int firstRow = modalY + 82; // "R" label row; each row is 36px apart
        addRenderableWidget(new SkyLiteUi.Slider(sliderX, firstRow + 10, sliderW, 20, 0, 255, red,
                GRAD_FROM[0], GRAD_TO[0], v -> {
            red = v;
            apply();
        }));
        addRenderableWidget(new SkyLiteUi.Slider(sliderX, firstRow + 46, sliderW, 20, 0, 255, green,
                GRAD_FROM[1], GRAD_TO[1], v -> {
            green = v;
            apply();
        }));
        addRenderableWidget(new SkyLiteUi.Slider(sliderX, firstRow + 82, sliderW, 20, 0, 255, blue,
                GRAD_FROM[2], GRAD_TO[2], v -> {
            blue = v;
            apply();
        }));

        int buttonW = Math.max(60, (innerW - 12) / 2);
        int footY = modalY + modalH - 36 + 8;
        addRenderableWidget(new SkyLiteUi.Btn(innerX, footY, buttonW, 20,
                tr("skylite.config.cancel"), SkyLiteUi.Btn.DANGER, this::cancel));
        addRenderableWidget(new SkyLiteUi.Btn(innerX + buttonW + 12, footY, buttonW, 20,
                tr("skylite.config.save"), SkyLiteUi.Btn.PRIMARY, this::onClose));
    }

    /** Writes the picked colour into the config (live preview on the HUD behind). */
    private void apply() {
        String hex = SkyLiteConfig.toHex(pickedRgb());
        switch (which) {
            case 0 -> cfg.healthColor = hex;
            case 1 -> cfg.manaColor = hex;
            case 2 -> cfg.xpColor = hex;
            default -> cfg.textColor = hex;
        }
        cfg.applyColors();
    }

    /** "Cancel": restores the colour the picker was opened with, then closes. */
    private void cancel() {
        red = (initialRgb >> 16) & 0xFF;
        green = (initialRgb >> 8) & 0xFF;
        blue = initialRgb & 0xFF;
        apply();
        cfg.save();
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xCC000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int rgb = pickedRgb();
        if (rgb != shownRgb) {
            shownRgb = rgb;
            shownHex = String.format("#%06X", rgb);
        }

        // modal frame
        SkyLiteUi.box(g, modalX, modalY, modalW, modalH, SkyLiteUi.PANEL_BG_SOLID, SkyLiteUi.PANEL_BORDER);
        g.text(font, this.title.getString(), modalX + 12, modalY + 10, SkyLiteUi.TEXT, true);
        g.fill(modalX + 1, modalY + 28, modalX + modalW - 1, modalY + 29, SkyLiteUi.SEP);

        // preview: the picked colour + its hex
        int py = modalY + 38;
        int hexW = 96;
        int swatchW = innerW - 8 - hexW;
        g.fill(innerX, py, innerX + swatchW, py + 32, 0xFF000000 | rgb);
        SkyLiteUi.border(g, innerX, py, swatchW, 32, 0x5A000000);
        SkyLiteUi.box(g, innerX + swatchW + 8, py, hexW, 32, SkyLiteUi.CONTROL_BG, SkyLiteUi.CONTROL_BORDER);
        g.text(font, shownHex, innerX + swatchW + 8 + (hexW - font.width(shownHex)) / 2,
                py + 12, SkyLiteUi.TEXT, true);

        // channel labels (the sliders themselves are widgets)
        for (int i = 0; i < CHANNELS.length; i++) {
            g.text(font, CHANNELS[i], innerX, modalY + 82 + i * 36, SkyLiteUi.TEXT_DIM, true);
        }

        // footer separator
        int footSep = modalY + modalH - 36;
        g.fill(modalX + 1, footSep, modalX + modalW - 1, footSep + 1, SkyLiteUi.SEP);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        cfg.applyColors();
        cfg.save();
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }
}
