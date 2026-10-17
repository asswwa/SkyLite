package dev.asswa.skylite;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * SkyLite — a deliberately tiny client-side SkyBlock companion.
 *
 * <p>Design rules (performance first, for weak laptops):
 * <ul>
 *   <li>zero mixins — only public Fabric API events;</li>
 *   <li>no textures, models or custom sounds — vanilla assets only;</li>
 *   <li>work happens on events (chat, action bar, sidebar poll), never per frame
 *       beyond drawing a handful of cached strings;</li>
 *   <li>stats panel strings are re-composed only when the numbers actually change.</li>
 * </ul>
 */
public final class SkyLiteClient implements ClientModInitializer {
    public static final String MOD_ID = "skylite";
    public static final Logger LOGGER = LoggerFactory.getLogger("SkyLite");

    public static SkyLiteConfig config;
    public static final StatsState stats = new StatsState();
    public static final Alerts alerts = new Alerts();
    public static final PingMeter pingMeter = new PingMeter();

    private KeyMapping toggleHudKey;
    private KeyMapping toggleAlertsKey;
    private KeyMapping moveBarsKey;
    private int sidebarTick;
    private int pingTick;
    // Screen to open on the next client tick: 1 = config, 2 = HUD editor. Commands execute
    // while the chat screen is still closing; opening a screen right away would be clobbered.
    private static int pendingScreen;

    // Temporary diagnostics for the real action bar / sidebar formats (bounded per session).
    private static final Set<String> DIAG_SEEN = new HashSet<>();
    private static int diagBudget = 20;
    private static final Set<String> DIAG_SIDEBAR_SEEN = new HashSet<>();
    private static int diagSidebarBudget = 40;

    @Override
    public void onInitializeClient() {
        config = SkyLiteConfig.load();
        stats.rebuild();

        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        toggleHudKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.skylite.toggle_hud", InputConstants.Type.KEYBOARD, defaultKey("key.keyboard.f6"), category));
        toggleAlertsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.skylite.toggle_alerts", InputConstants.Type.KEYBOARD, defaultKey("key.keyboard.f7"), category));
        moveBarsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.skylite.move_bars", InputConstants.Type.KEYBOARD, defaultKey("key.keyboard.f8"), category));

        // /skylite [menu|edit|hud|alerts] — client-side only. Screens are opened on the next
        // client tick: commands run while the chat screen is still closing, and a screen opened
        // right away would be clobbered by that close.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) ->
                dispatcher.register(ClientCommands.literal("skylite")
                        .executes(ctx -> {
                            pendingScreen = 1;
                            return 1;
                        })
                        .then(ClientCommands.literal("menu").executes(ctx -> {
                            pendingScreen = 1;
                            return 1;
                        }))
                        .then(ClientCommands.literal("edit").executes(ctx -> {
                            pendingScreen = 2;
                            return 1;
                        }))
                        .then(ClientCommands.literal("hud").executes(ctx -> {
                            config.hudEnabled = !config.hudEnabled;
                            config.save();
                            alerts.toast(Component.translatable(
                                    config.hudEnabled ? "skylite.toast.hud_on" : "skylite.toast.hud_off"));
                            return 1;
                        }))
                        .then(ClientCommands.literal("alerts").executes(ctx -> {
                            config.alertsEnabled = !config.alertsEnabled;
                            config.save();
                            alerts.toast(Component.translatable(
                                    config.alertsEnabled ? "skylite.toast.alerts_on" : "skylite.toast.alerts_off"));
                            return 1;
                        }))));

        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "hud"), new HudPanel());

        // Replace the default HUD with SkyLite's versions while on SkyBlock (optional, F6-aware).
        replaceVanillaElement(VanillaHudElements.HEALTH_BAR, () -> {
            SkyLiteConfig cfg = config;
            return cfg != null && cfg.hudEnabled && cfg.hideVanillaHearts && cfg.showHealthBar
                    && stats.isOnSkyBlock();
        });
        replaceVanillaElement(VanillaHudElements.INFO_BAR, () -> {
            SkyLiteConfig cfg = config;
            return cfg != null && cfg.hudEnabled && cfg.hideVanillaXpBar && cfg.showXpBar
                    && stats.isOnSkyBlock();
        });
        replaceVanillaElement(VanillaHudElements.EXPERIENCE_LEVEL, () -> {
            SkyLiteConfig cfg = config;
            return cfg != null && cfg.hudEnabled && cfg.hideVanillaXpBar && cfg.showXpBar
                    && stats.isOnSkyBlock();
        });
        replaceVanillaElement(VanillaHudElements.FOOD_BAR, () -> {
            SkyLiteConfig cfg = config;
            return cfg != null && cfg.hudEnabled && cfg.hideVanillaFoodBar && cfg.showManaBar
                    && stats.isOnSkyBlock();
        });

        // Action bar (overlay) texts: parse them, and optionally hide Hypixel's stats line —
        // the SkyLite panel shows the same values.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            if (!overlay) {
                return true;
            }
            String plain = message.getString();
            diagLog(true, plain);
            stats.parseActionBar(plain);
            SkyLiteConfig cfg = config;
            return !(cfg != null && cfg.hudEnabled && cfg.hideStatsActionBar
                    && cfg.showHealthBar && cfg.showManaBar
                    && stats.isOnSkyBlock() && stats.isStatsLineParsed());
        });

        // Normal chat: rare-drop / keyword alerts.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay) {
                return; // overlay messages are handled above
            }
            String plain = message.getString();
            diagLog(false, plain);
            alerts.onGameMessage(plain);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingScreen != 0) {
                int what = pendingScreen;
                pendingScreen = 0;
                if (what == 1) {
                    openConfigScreen();
                } else {
                    client.setScreenAndShow(new SkyLiteHudEditor());
                }
            }
            while (toggleHudKey.consumeClick()) {
                config.hudEnabled = !config.hudEnabled;
                config.save();
                alerts.toast(Component.translatable(
                        config.hudEnabled ? "skylite.toast.hud_on" : "skylite.toast.hud_off"));
            }
            while (toggleAlertsKey.consumeClick()) {
                config.alertsEnabled = !config.alertsEnabled;
                config.save();
                alerts.toast(Component.translatable(
                        config.alertsEnabled ? "skylite.toast.alerts_on" : "skylite.toast.alerts_off"));
            }
            while (moveBarsKey.consumeClick()) {
                client.setScreenAndShow(new SkyLiteHudEditor());
            }
            if ((config.hudEnabled || config.alertsEnabled) && ++sidebarTick % 10 == 0) {
                stats.updateSidebar(client);
            }
            if (++pingTick % 20 == 0) {
                if (config.hudEnabled && config.showPingFps) {
                    updatePingTarget(client);
                } else {
                    pingMeter.updateTarget(null); // widget hidden: stop the background pings
                }
            }
        });

        LOGGER.info("SkyLite initialized (hud={}, alerts={})", config.hudEnabled, config.alertsEnabled);
    }

    /** Keeps the latency meter pointed at the server the player is connected to. */
    private static void updatePingTarget(Minecraft mc) {
        ServerData server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            pingMeter.updateTarget(server.ip);
            return;
        }
        ClientPacketListener connection = mc.getConnection();
        if (connection != null && connection.getConnection() != null
                && connection.getConnection().getRemoteAddress() instanceof InetSocketAddress address) {
            pingMeter.updateTarget(address.getHostString() + ":" + address.getPort());
            return;
        }
        pingMeter.updateTarget(null);
    }

    /** Opens the config screen on top of whatever the player is looking at. */
    private static void openConfigScreen() {
        Minecraft mc = Minecraft.getInstance();
        mc.setScreenAndShow(SkyLiteConfigScreen.create(mc.gui.screen()));
    }

    /**
     * Wraps a vanilla HUD element so it can be skipped while SkyLite draws its own version.
     * Still zero mixins — only the public Fabric element registry is used. Never fatal:
     * if the vanilla element cannot be found, a warning is logged and the default HUD stays.
     */
    private static void replaceVanillaElement(Identifier id, BooleanSupplier hide) {
        try {
            HudElementRegistry.replaceElement(id, original -> new HudElement() {
                @Override
                public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
                    if (hide.getAsBoolean()) {
                        return;
                    }
                    original.extractRenderState(graphics, delta);
                }
            });
        } catch (Exception e) {
            LOGGER.warn("SkyLite: could not replace vanilla HUD element {}", id, e);
        }
    }

    /**
     * Temporary diagnostics: logs the first few interesting action-bar/chat texts with non-ASCII
     * escaped as backslash-uXXXX codes so the parsers can be matched against the real server format.
     */
    private static void diagLog(boolean overlay, String plain) {
        if (diagBudget <= 0 || plain == null || plain.isEmpty()) {
            return;
        }
        if (!overlay && plain.indexOf('/') < 0 && plain.indexOf('❤') < 0 && plain.indexOf('❈') < 0
                && plain.indexOf('✦') < 0 && !plain.contains("Mana") && !plain.contains("Defense")) {
            return;
        }
        if (!DIAG_SEEN.add((overlay ? "O:" : "C:") + plain)) {
            return;
        }
        diagBudget--;
        LOGGER.info("[SkyLite diag] overlay={} text={}", overlay, describe(plain));
    }

    /** Bounded diagnostics for the sidebar format (area / Cookie / God Pot labels). */
    static void diagSidebar(String line) {
        if (line == null || line.isEmpty() || diagSidebarBudget <= 0) {
            return;
        }
        if (!DIAG_SIDEBAR_SEEN.add(line)) {
            return;
        }
        diagSidebarBudget--;
        LOGGER.info("[SkyLite diag] sidebar={}", describe(line));
    }

    private static String describe(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && c < 127) {
                sb.append(c);
            } else {
                sb.append(String.format("\\u%04X", (int) c));
            }
        }
        return sb.toString();
    }

    /** Key code by vanilla name, e.g. "key.keyboard.f6" (no compile-time LWJGL dependency). */
    private static int defaultKey(String name) {
        return InputConstants.getKey(name).getValue();
    }
}
