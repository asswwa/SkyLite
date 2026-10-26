package dev.asswa.skylite;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.scores.DisplaySlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Locale;
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
    public static final SessionTracker session = new SessionTracker();
    public static final Alerts alerts = new Alerts();
    public static final ChatFilter chat = new ChatFilter();
    public static final PingMeter pingMeter = new PingMeter();

    private KeyMapping toggleHudKey;
    private KeyMapping toggleAlertsKey;
    private KeyMapping moveBarsKey;
    private int sidebarTick;
    private int pingTick;
    private int vitalTick;
    private int kickTick;

    // Low HP/mana alerts: armed per threshold, re-armed only after the value recovers.
    private static final long MIN_VITAL_GAP_MS = 5_000L;
    private boolean lowHpArmed = true;
    private boolean lowManaArmed = true;
    private long lastLowHpAt;
    private long lastLowManaAt;

    // Cookie / God Pot expiry warnings (v13): fire once under five minutes, re-arm on a refresh.
    private static final int BUFF_WARN_SECONDS = 300;
    private static final int BUFF_REARM_SECONDS = 360;
    private boolean cookieWarnArmed = true;
    private boolean godPotWarnArmed = true;
    /** Effects already warned about under the threshold; re-armed once they expire or recover (v15). */
    private final Set<String> warnedEffects = new HashSet<>();

    // Kick watchdog: "away from SkyBlock" episodes on the current connection.
    private static final String[] TRAVEL_COMMANDS = {"lobby", "play", "pt", "swaplobby", "leave"};
    private static final long TRAVEL_SUPPRESS_MS = 15_000L;
    private static final long KICK_DWELL_MS = 12_000L;
    private static final long KICK_COOLDOWN_MS = 30_000L;
    private boolean sawSkyblock;
    private boolean awayFromSkyblock;
    private boolean awaySuppressed;
    private long awaySince;
    private boolean kickAlerted;
    private long travelCommandAt;
    private long lastKickAlertAt;

    // v18: inventory-full warning, nickname mentions and rare-drop screenshots.
    private static final long MENTION_GAP_MS = 3_000L;
    private static final long DROP_SHOT_GAP_MS = 2_000L;
    private boolean invWarnArmed = true;
    private long lastMentionAt;
    private long lastDropShotAt;
    /** The last chat line the player sent, so its echo is not read as a mention (v18). */
    private String lastOwnChatText = "";
    private long lastOwnChatAt;

    // Screen to open on the next client tick: 1 = config, 2 = HUD editor, 3 = drop journal.
    // Commands execute while the chat screen is still closing; opening a screen right away
    // would be clobbered.
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
                        }))
                        .then(ClientCommands.literal("session")
                                .then(ClientCommands.literal("reset").executes(ctx -> {
                                    session.reset();
                                    alerts.toast(Component.translatable("skylite.toast.session_reset"));
                                    return 1;
                                })))
                        .then(ClientCommands.literal("drops").executes(ctx -> {
                            pendingScreen = 3;
                            return 1;
                        }))));

        // Travel commands mark the upcoming "left SkyBlock" episode as deliberate, so the kick
        // watchdog stays quiet when the player leaves on purpose (/lobby, /play, …).
        ClientSendMessageEvents.COMMAND.register(command -> {
            String c = command == null ? "" : command.trim().toLowerCase(Locale.ROOT);
            int start = 0;
            while (start < c.length() && c.charAt(start) == '/') {
                start++;
            }
            c = c.substring(start);
            for (String name : TRAVEL_COMMANDS) {
                if (c.equals(name) || c.startsWith(name + " ")) {
                    travelCommandAt = System.currentTimeMillis();
                    return;
                }
            }
        });

        // Remember the last line the player sent, so the server echo of it is not treated as a
        // nickname mention (v18). Commands are not chat and are ignored on purpose.
        ClientSendMessageEvents.CHAT.register(message -> {
            if (message != null && !message.isBlank()) {
                lastOwnChatText = message.trim();
                lastOwnChatAt = System.currentTimeMillis();
            }
        });

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
        // the SkyLite panel shows the same values. Normal chat: the alerts run first (a message
        // hidden by the chat filter below still alerts), then the spam filter decides.
        ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
            String plain = message.getString();
            SkyLiteConfig cfg = config;
            if (overlay) {
                diagLog(true, plain);
                stats.parseActionBar(plain);
                return !(cfg != null && cfg.hudEnabled && cfg.hideStatsActionBar
                        && cfg.showHealthBar && cfg.showManaBar
                        && stats.isOnSkyBlock() && stats.isStatsLineParsed());
            }
            diagLog(false, plain);
            alerts.onGameMessage(plain);
            kickOnChat(plain);
            if (session.onChat(plain, stats.area())) {
                dropScreenshot(); // v18: a rare drop just landed in the chat
            }
            checkMention(plain); // v18: someone mentioned the nickname
            return cfg == null || chat.allow(plain);
        });

        // Nickname highlight: a rewrite of the message, so it goes through MODIFY_GAME.
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) ->
                overlay ? message : chat.highlight(message));

        // Player-chat packets (Hypixel sends everything as system chat; other servers may not):
        // the spam filter applies, but there is no MODIFY_CHAT event, so no highlight here.
        // Mentions still alert — that is a notification, not a rewrite (v18).
        ClientReceiveMessageEvents.ALLOW_CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            SkyLiteConfig cfg = config;
            String plain = message.getString();
            checkMention(plain);
            return cfg == null || chat.allow(plain);
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (pendingScreen != 0) {
                int what = pendingScreen;
                pendingScreen = 0;
                if (what == 1) {
                    openConfigScreen();
                } else if (what == 2) {
                    client.setScreenAndShow(new SkyLiteHudEditor());
                } else {
                    client.setScreenAndShow(new SkyLiteDropsScreen(null));
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
            alerts.tick(); // v14: plays the chimes of overlapping alerts, spaced out
            if (config.hudEnabled) {
                stats.tickActivity(client); // v13: the AFK line is HUD-only, so it follows the HUD
            }
            // The sidebar is also polled for the optimizer's location rules and the session
            // coin messages, even with the HUD and alerts off.
            if (++sidebarTick % 10 == 0
                    && (config.hudEnabled || config.alertsEnabled || config.optAreaProfiles
                    || config.sessionCoinsChat)) {
                stats.updateSidebar(client);
                session.onPurse(stats.isOnSkyBlock() ? stats.purse() : null);
                checkBuffWarnings();
                checkEffectWarnings();
                if (config.optAreaProfiles) {
                    Optimizer.tickArea(client, config, stats.area());
                }
            }
            if (++vitalTick % 5 == 0) {
                checkVitals(client);
                checkInventory(client); // v18
            }
            if (++kickTick % 20 == 0) {
                checkKick(client);
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
     * Low HP / low mana alerts: each one is armed below its threshold and re-arms only after the
     * value recovers a few percent above it, so sitting at the threshold cannot spam the sound.
     */
    private void checkVitals(Minecraft client) {
        SkyLiteConfig cfg = config;
        if (cfg == null || !cfg.alertsEnabled || client.player == null || client.level == null
                || !stats.isOnSkyBlock()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (cfg.lowHpAlert && client.player.isAlive()) {
            float max = client.player.getMaxHealth();
            float ratio = max > 0 ? client.player.getHealth() / max : 1.0F;
            int percent = Math.round(ratio * 100.0F);
            if (percent < cfg.lowHpPercent) {
                if (lowHpArmed && now - lastLowHpAt >= MIN_VITAL_GAP_MS) {
                    lowHpArmed = false;
                    lastLowHpAt = now;
                    alerts.onEvent(Component.translatable("skylite.alert.low_hp", percent).getString(), false);
                }
            } else if (percent > cfg.lowHpPercent + 5) {
                lowHpArmed = true;
            }
        }
        if (cfg.lowManaAlert) {
            float ratio = stats.manaRatio();
            if (!Float.isNaN(ratio)) {
                int percent = Math.round(ratio * 100.0F);
                if (percent < cfg.lowManaPercent) {
                    if (lowManaArmed && now - lastLowManaAt >= MIN_VITAL_GAP_MS) {
                        lowManaArmed = false;
                        lastLowManaAt = now;
                        alerts.onEvent(Component.translatable("skylite.alert.low_mana", percent).getString(), false);
                    }
                } else if (percent > cfg.lowManaPercent + 5) {
                    lowManaArmed = true;
                }
            }
        }
    }

    /**
     * Inventory-full warning (v18): counts the free main-inventory slots and fires once when they
     * drop to the configured threshold. Freeing a few slots re-arms it, so a full inventory that
     * stays full warns exactly once. Only the 36 main slots count — armour and the offhand are
     * not what Hypixel means by a full inventory.
     */
    private void checkInventory(Minecraft client) {
        SkyLiteConfig cfg = config;
        if (cfg == null || !cfg.alertsEnabled || !cfg.invWarn || !stats.isOnSkyBlock()
                || client.player == null) {
            invWarnArmed = true;
            return;
        }
        int free = 0;
        for (ItemStack stack : client.player.getInventory().getNonEquipmentItems()) {
            if (stack.isEmpty()) {
                free++;
            }
        }
        int threshold = Math.max(0, Math.min(10, cfg.invWarnSlots));
        if (free <= threshold) {
            if (invWarnArmed) {
                invWarnArmed = false;
                alerts.onEvent(Component.translatable("skylite.alert.inv_soon", free).getString(), false);
            }
        } else if (free > threshold + 2) {
            invWarnArmed = true; // hysteresis: a couple of freed slots count as "recovered"
        }
    }

    /**
     * Rare-drop screenshot (v18, off by default): saves the current frame into
     * {@code screenshots/} as {@code skylite_drop_<date>.png} and confirms with a toast.
     * Hypixel sometimes doubles a drop line, so two shots within two seconds collapse to one.
     */
    private void dropScreenshot() {
        SkyLiteConfig cfg = config;
        if (cfg == null || !cfg.dropScreenshot) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastDropShotAt < DROP_SHOT_GAP_MS) {
            return;
        }
        lastDropShotAt = now;
        Minecraft mc = Minecraft.getInstance();
        try {
            String name = "skylite_drop_" + Util.getFilenameFormattedDateTime() + ".png";
            Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1,
                    message -> mc.execute(() ->
                            alerts.toast(Component.translatable("skylite.toast.screenshot"))));
        } catch (Throwable t) {
            LOGGER.warn("SkyLite: could not take the rare-drop screenshot", t);
        }
    }

    /**
     * Nickname-mention alert (v18): someone says the player's name in chat. Lines authored by the
     * player themselves are skipped — first by shape ("[rank] Name: …", "Party > Name: …"), and
     * as a second guard by remembering what was just sent and ignoring the server's echo of it.
     */
    private void checkMention(String plain) {
        SkyLiteConfig cfg = config;
        if (cfg == null || !cfg.alertsEnabled || !cfg.mentionAlert
                || plain == null || plain.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        String name = mc.player.getName().getString();
        if (name.isEmpty()) {
            return;
        }
        int pos = mentionPos(plain, name);
        if (pos < 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (authoredBy(plain, name, pos)) {
            return; // a line authored by the player themselves
        }
        if (!lastOwnChatText.isEmpty() && now - lastOwnChatAt < 5_000L
                && plain.endsWith(lastOwnChatText)) {
            return; // the echo of a message just sent
        }
        if (now - lastMentionAt < MENTION_GAP_MS) {
            return; // a mention storm still alerts at most every few seconds
        }
        lastMentionAt = now;
        alerts.onEvent(plain, false);
    }

    /** First whole-word occurrence of the nickname (case-insensitive), or -1. */
    private static int mentionPos(String plain, String name) {
        int from = 0;
        while (true) {
            int pos = ChatFilter.indexOfIgnoreCase(plain, name, from);
            if (pos < 0) {
                return -1;
            }
            boolean left = pos == 0 || !isWordChar(plain.charAt(pos - 1));
            int after = pos + name.length();
            boolean right = after >= plain.length() || !isWordChar(plain.charAt(after));
            if (left && right) {
                return pos;
            }
            from = pos + 1;
        }
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /**
     * True when the nickname at {@code pos} reads as the author of the line: followed by ":" or
     * "»" (or wrapped in "&lt;…&gt;"), preceded only by rank/level tags, spaces and party/guild
     * prefixes like "Party &gt;". Own messages on Hypixel always have this shape.
     */
    private static boolean authoredBy(String plain, String name, int pos) {
        int after = pos + name.length();
        if (after >= plain.length()) {
            return false;
        }
        char next = plain.charAt(after);
        boolean angled = next == '>' && pos > 0 && plain.charAt(pos - 1) == '<';
        if (next != ':' && next != '»' && !angled) {
            return false;
        }
        String before = plain.substring(0, angled ? pos - 1 : pos);
        int i = 0;
        while (i < before.length()) {
            char c = before.charAt(i);
            if (c == ' ') {
                i++;
                continue;
            }
            if (c == '[' || c == '(' || c == '<') {
                char close = c == '[' ? ']' : c == '(' ? ')' : '>';
                int end = before.indexOf(close, i);
                if (end < 0) {
                    return false;
                }
                i = end + 1;
                continue;
            }
            // A bare word is tolerated only as a channel prefix: "Party >", "Guild >", "Co-op >".
            int j = i;
            while (j < before.length() && (Character.isLetterOrDigit(before.charAt(j))
                    || before.charAt(j) == '-' || before.charAt(j) == '_')) {
                j++;
            }
            if (j == i) {
                return false;
            }
            int k = j;
            while (k < before.length() && before.charAt(k) == ' ') {
                k++;
            }
            if (k < before.length() && before.charAt(k) == '>') {
                i = k + 1;
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * Cookie Buff / God Pot expiry warnings (v13): one alert per buff when under five minutes
     * are left; refilling the buff (or losing the sidebar line) re-arms it.
     */
    private void checkBuffWarnings() {
        SkyLiteConfig cfg = config;
        if (cfg == null || !cfg.alertsEnabled || !cfg.buffWarn || !stats.isOnSkyBlock()) {
            cookieWarnArmed = true;
            godPotWarnArmed = true;
            return;
        }        int cookie = stats.cookieSeconds();
        if (cookie < 0) {
            cookieWarnArmed = true;
        } else if (cookie <= BUFF_WARN_SECONDS) {
            if (cookieWarnArmed) {
                cookieWarnArmed = false;
                alerts.onEvent(Component.translatable("skylite.alert.cookie_soon", buffMinutes(cookie))
                        .getString(), false);
            }
        } else if (cookie > BUFF_REARM_SECONDS) {
            cookieWarnArmed = true;
        }
        int godPot = stats.godPotSeconds();
        if (godPot < 0) {
            godPotWarnArmed = true;
        } else if (godPot <= BUFF_WARN_SECONDS) {
            if (godPotWarnArmed) {
                godPotWarnArmed = false;
                alerts.onEvent(Component.translatable("skylite.alert.godpot_soon", buffMinutes(godPot))
                        .getString(), false);
            }
        } else if (godPot > BUFF_REARM_SECONDS) {
            godPotWarnArmed = true;
        }
    }

    private static int buffMinutes(int seconds) {
        return Math.max(1, seconds / 60);
    }

    /**
     * Potion-expiry warnings (v15): an effect that drops under the configured threshold fires
     * once — flash, toast and chime. The mark clears when the effect ends or gets refreshed
     * well above the threshold, so re-drinking arms it again; one warning per poll at most,
     * so two potions ending together cannot fill the screen at once.
     */
    private void checkEffectWarnings() {
        SkyLiteConfig cfg = config;
        if (cfg == null || !cfg.alertsEnabled || !cfg.effectWarn || !stats.isOnSkyBlock()) {
            warnedEffects.clear();
            return;
        }
        String[] names = stats.effectNames();
        int[] seconds = stats.effectSeconds();
        int threshold = Math.max(10, Math.min(60, cfg.effectWarnSeconds));
        if (!warnedEffects.isEmpty()) {
            warnedEffects.removeIf(name -> {
                for (int i = 0; i < names.length; i++) {
                    if (names[i].equals(name)) {
                        return seconds[i] > threshold + 50 || seconds[i] < 0; // refreshed or infinite
                    }
                }
                return true; // the effect is gone
            });
        }
        for (int i = 0; i < names.length; i++) {
            int left = seconds[i];
            if (left >= 0 && left <= threshold && !warnedEffects.contains(names[i])) {
                warnedEffects.add(names[i]);
                alerts.onEvent(Component.translatable("skylite.alert.effect_soon", names[i])
                        .getString(), false);
                break; // the next poll warns about the next effect, if it is still urgent
            }
        }
    }

    /**
     * The kick watchdog: an alert when the player was on SkyBlock and then stays off it (Limbo or
     * the main lobby) for long enough. Ordinary island transfers last seconds and pass unnoticed;
     * leaving deliberately (/lobby, /play) and a chat line mentioning Limbo mark the episode handled.
     */
    private void checkKick(Minecraft client) {
        SkyLiteConfig cfg = config;
        if (cfg == null || !cfg.alertsEnabled || !cfg.kickAlert) {
            awayFromSkyblock = false;
            kickAlerted = false;
            return;
        }
        if (client.level == null || client.player == null || client.getConnection() == null) {
            sawSkyblock = false; // title screen / no world: a fresh connection starts clean
            awayFromSkyblock = false;
            kickAlerted = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (stats.isOnSkyBlock()) {
            sawSkyblock = true;
            awayFromSkyblock = false;
            awaySuppressed = false;
            kickAlerted = false;
            return;
        }
        if (!sawSkyblock) {
            return; // never seen SkyBlock on this connection: nothing to alert about
        }
        if (!awayFromSkyblock) {
            awayFromSkyblock = true;
            awaySince = now;
            awaySuppressed = now - travelCommandAt < TRAVEL_SUPPRESS_MS;
            return;
        }
        if (awaySuppressed || kickAlerted || now - awaySince < KICK_DWELL_MS) {
            return;
        }
        kickAlerted = true;
        fireKickAlert(currentlyLimbo(client) ? "skylite.alert.kick_limbo" : "skylite.alert.kick_lobby");
    }

    /** A chat line that mentions Limbo is the clearest kick signal the server itself sends. */
    private void kickOnChat(String plain) {
        SkyLiteConfig cfg = config;
        if (cfg == null || !cfg.alertsEnabled || !cfg.kickAlert
                || plain == null || plain.isEmpty()) {
            return;
        }
        if (ChatFilter.indexOfIgnoreCase(plain, "limbo", 0) < 0) {
            return;
        }
        travelCommandAt = System.currentTimeMillis(); // suppress the dwell detector for this episode
        fireKickAlert("skylite.alert.kick_limbo");
    }

    private void fireKickAlert(String key) {
        long now = System.currentTimeMillis();
        if (now - lastKickAlertAt < KICK_COOLDOWN_MS) {
            return;
        }
        lastKickAlertAt = now;
        alerts.onEvent(Component.translatable(key).getString(), true);
    }

    /** Limbo has no sidebar at all; the main lobby has one, just not a SkyBlock one. */
    private static boolean currentlyLimbo(Minecraft client) {
        return client.level.getScoreboard().getDisplayObjective(DisplaySlot.SIDEBAR) == null;
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
