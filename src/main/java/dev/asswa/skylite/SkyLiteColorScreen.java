package dev.asswa.skylite;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * Tiny built-in RGB colour picker (no Cloth Config). Opened by right-clicking a bar in the
 * HUD editor, or from the colour rows of the config screen. Changes are applied live and
 * saved when the screen closes.
 */
public final class SkyLiteColorScreen extends Screen {
    private final Screen parent;
    private final SkyLiteConfig cfg;
    private final int which; // 0 HP, 1 mana, 2 XP, 3 numbers

    private int red;
    private int green;
    private int blue;

    // Title resolved on init, not on every frame.
    private String titleText;
    private int titleW;

    public SkyLiteColorScreen(Screen parent, SkyLiteConfig cfg, int which) {
        super(Component.translatable(labelKey(which)));
        this.parent = parent;
        this.cfg = cfg;
        this.which = which;
        int rgb = currentRgb();
        this.red = (rgb >> 16) & 0xFF;
        this.green = (rgb >> 8) & 0xFF;
        this.blue = rgb & 0xFF;
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

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 - 46;
        addRenderableWidget(new ChannelSlider(cx - 100, y, 200, 20, "R", red, v -> {
            red = v;
            apply();
        }));
        addRenderableWidget(new ChannelSlider(cx - 100, y + 26, 200, 20, "G", green, v -> {
            green = v;
            apply();
        }));
        addRenderableWidget(new ChannelSlider(cx - 100, y + 52, 200, 20, "B", blue, v -> {
            blue = v;
            apply();
        }));
        addRenderableWidget(Button.builder(Component.translatable("skylite.config.done"), b -> onClose())
                .bounds(cx - 50, y + 102, 100, 20).build());

        titleText = this.title.getString();
        titleW = font.width(titleText);
    }

    /** Writes the picked colour into the config (live preview on the HUD behind). */
    private void apply() {
        String hex = SkyLiteConfig.toHex((red << 16) | (green << 8) | blue);
        switch (which) {
            case 0 -> cfg.healthColor = hex;
            case 1 -> cfg.manaColor = hex;
            case 2 -> cfg.xpColor = hex;
            default -> cfg.textColor = hex;
        }
        cfg.applyColors();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xCC000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);

        g.text(font, titleText, (this.width - titleW) / 2, 12, 0xFFFFFFFF, true);

        // Preview swatch: white border, black inner edge, the colour itself.
        int cx = this.width / 2;
        int py = this.height / 2 + 34;
        int rgb = (red << 16) | (green << 8) | blue;
        g.fill(cx - 71, py - 1, cx - 29, py + 21, 0xFFFFFFFF);
        g.fill(cx - 70, py, cx - 30, py + 20, 0xFF000000);
        g.fill(cx - 69, py + 1, cx - 31, py + 19, 0xFF000000 | rgb);

        String hex = String.format("#%06X", rgb);
        g.text(font, hex, cx - 18, py + 6, 0xFFFFFFFF, true);
    }

    @Override
    public void onClose() {
        cfg.applyColors();
        cfg.save();
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    /** 0..255 slider for one colour channel. */
    private static final class ChannelSlider extends AbstractSliderButton {
        private final String name;
        private final IntConsumer onValue;

        ChannelSlider(int x, int y, int w, int h, String name, int value, IntConsumer onValue) {
            super(x, y, w, h, Component.literal(name), value / 255.0);
            this.name = name;
            this.onValue = onValue;
            updateMessage();
        }

        private int current() {
            return (int) Math.round(value * 255.0);
        }

        @Override
        protected void updateMessage() {
            if (name == null) {
                return; // defensive: the super constructor must not hit unset fields
            }
            setMessage(Component.literal(name + ": " + current()));
        }

        @Override
        protected void applyValue() {
            onValue.accept(current());
        }
    }
}
