package dev.asswa.skylite;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The rare-drop journal of the current session ({@link SessionTracker}): what, when and where,
 * newest first. Opened from the Alerts tab, {@code /skylite drops} or a future keybind; closed
 * back to its parent screen (or the game when opened by command).
 */
public final class SkyLiteDropsScreen extends Screen {
    private static final int PAD = 12;
    private static final int ENTRY_H = 22; // two text lines (message + time/area) per entry

    private final Screen parent;
    private double scroll;
    private SkyLiteUi.Btn clearButton;

    private int panelX, panelY, panelW, panelH;
    private int listTop, listBottom;

    public SkyLiteDropsScreen(Screen parent) {
        super(Component.translatable("skylite.drops.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panelW = Math.min(380, this.width - 40);
        panelH = Math.min(260, this.height - 40);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;
        listTop = panelY + 34;
        listBottom = panelY + panelH - 40;

        int buttonW = Math.max(80, (panelW - 3 * PAD) / 2);
        int footY = panelY + panelH - 30;
        clearButton = new SkyLiteUi.Btn(panelX + PAD, footY, buttonW, 20,
                tr("skylite.drops.clear"), SkyLiteUi.Btn.DANGER, SkyLiteClient.session::clearDrops);
        clearButton.active = !SkyLiteClient.session.drops().isEmpty();
        addRenderableWidget(clearButton);
        addRenderableWidget(new SkyLiteUi.Btn(panelX + panelW - PAD - buttonW, footY, buttonW, 20,
                tr("skylite.drops.done"), SkyLiteUi.Btn.PRIMARY, this::onClose));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xCC000000);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        SkyLiteUi.panel(g, panelX, panelY, panelW, panelH);
        g.text(font, this.title.getString(), panelX + PAD, panelY + 11, SkyLiteUi.TEXT, true);
        List<SessionTracker.Drop> drops = SkyLiteClient.session.drops();
        if (clearButton != null) {
            clearButton.active = !drops.isEmpty(); // new drops may arrive while the journal is open
        }
        String count = Component.translatable("skylite.drops.count", drops.size()).getString();
        g.text(font, count, panelX + panelW - PAD - font.width(count), panelY + 11,
                SkyLiteUi.TEXT_MUTED, true);
        g.fill(panelX + 1, panelY + 28, panelX + panelW - 1, panelY + 29, SkyLiteUi.SEP);

        int areaH = Math.max(1, listBottom - listTop);
        int contentH = drops.size() * ENTRY_H;
        double maxScroll = Math.max(0, contentH - areaH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        if (drops.isEmpty()) {
            String empty = tr("skylite.drops.empty");
            g.text(font, empty, panelX + (panelW - font.width(empty)) / 2,
                    panelY + panelH / 2 - 4, SkyLiteUi.TEXT_MUTED, true);
        } else {
            int textW = panelW - 2 * PAD - 8; // leave room for the scrollbar
            int y0 = listTop - (int) Math.round(scroll);
            for (int i = 0; i < drops.size(); i++) {
                int y = y0 + i * ENTRY_H;
                if (y + ENTRY_H <= listTop || y >= listBottom) {
                    continue;
                }
                SessionTracker.Drop drop = drops.get(i);
                g.text(font, clip(drop.text(), textW), panelX + PAD, y + 2, SkyLiteUi.TEXT, true);
                String meta = drop.area() == null || drop.area().isBlank()
                        ? drop.when()
                        : drop.when() + " · " + drop.area();
                g.text(font, clip(meta, textW), panelX + PAD, y + 12, SkyLiteUi.TEXT_MUTED, true);
            }
            if (maxScroll > 0) {
                int barX = panelX + panelW - 7;
                g.fill(barX, listTop, barX + 2, listBottom, SkyLiteUi.SCROLL_TRACK);
                int thumbH = Math.max(16, areaH * areaH / Math.max(1, contentH));
                int thumbY = listTop + (int) ((areaH - thumbH) * (scroll / maxScroll));
                g.fill(barX, thumbY, barX + 2, thumbY + thumbH, SkyLiteUi.SCROLL_THUMB);
            }
        }

        int footSep = panelY + panelH - 40;
        g.fill(panelX + 1, footSep, panelX + panelW - 1, footSep + 1, SkyLiteUi.SEP);
        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    /** Trims a too-long line with ".." so it never runs under the scrollbar. */
    private String clip(String text, int maxW) {
        Font font = Minecraft.getInstance().font;
        if (font.width(text) <= maxW) {
            return text;
        }
        String cut = text;
        while (cut.length() > 3 && font.width(cut + "..") > maxW) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "..";
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= listTop && mouseY <= listBottom) {
            scroll -= scrollY * ENTRY_H;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        if (parent == null) {
            mc.gui.setScreen(null); // opened by command — back to the game
        } else {
            mc.setScreenAndShow(parent);
        }
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }
}
