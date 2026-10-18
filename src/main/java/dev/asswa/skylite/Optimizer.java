package dev.asswa.skylite;

import com.mojang.blaze3d.Blaze3D;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ParticleStatus;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The engine of the "Optimization" tab. Everything here stays inside the public vanilla
 * {@link Options} API — no mixins, no renderer hooks: SkyLite can only tune what vanilla
 * exposes, and the tab honestly points at Sodium/EntityCulling for the heavier wins.
 *
 * <ul>
 *   <li>profiles — one-click bundles ({@link Profile});</li>
 *   <li>advisor — the checklist of remaining wins ({@link Advice});</li>
 *   <li>location rules — SkyBlock areas pick a profile ({@link #tickArea});</li>
 *   <li>snapshot — vanilla values captured before the first change, used by "Restore".</li>
 * </ul>
 */
public final class Optimizer {
    private Optimizer() {
    }

    // ---------- profiles ----------

    /** One-click bundles; {@link #id} is what the config and the location rules store. */
    public enum Profile {
        BALANCED("bal", "skylite.opt.profile.balanced"),
        PERFORMANCE("perf", "skylite.opt.profile.performance"),
        MAXIMUM("max", "skylite.opt.profile.maximum");

        public final String id;
        public final String nameKey;

        Profile(String id, String nameKey) {
            this.id = id;
            this.nameKey = nameKey;
        }

        public Component displayName() {
            return Component.translatable(nameKey);
        }
    }

    /** Rule/profile id -> profile; accepts ids, English words and the Russian names. */
    public static Profile byId(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "bal", "balanced", "баланс", "сбалансированный" -> Profile.BALANCED;
            case "perf", "performance", "произв", "производительность" -> Profile.PERFORMANCE;
            case "max", "maximum", "макс", "максимум" -> Profile.MAXIMUM;
            default -> null;
        };
    }

    /**
     * Applies a profile bundle: particles, clouds, entity shadows/distance, biome blend,
     * opaque leaves and — for "Maximum" — the render/simulation distances as well.
     */
    private static void applyValues(Options o, Profile profile) {
        switch (profile) {
            case BALANCED -> {
                o.particles().set(ParticleStatus.DECREASED);
                o.cloudStatus().set(CloudStatus.FAST);
                o.entityShadows().set(false);
                o.entityDistanceScaling().set(1.0);
                o.biomeBlendRadius().set(1);
            }
            case PERFORMANCE -> {
                o.particles().set(ParticleStatus.MINIMAL);
                o.cloudStatus().set(CloudStatus.FAST);
                o.entityShadows().set(false);
                o.entityDistanceScaling().set(0.5);
                o.biomeBlendRadius().set(0);
                o.cutoutLeaves().set(false);
            }
            case MAXIMUM -> {
                applyValues(o, Profile.PERFORMANCE);
                o.cloudStatus().set(CloudStatus.OFF);
                o.ambientOcclusion().set(false);
                o.renderDistance().set(Math.min(o.renderDistance().get(), 8));
                o.simulationDistance().set(Math.min(o.simulationDistance().get(), 6));
            }
        }
    }

    /** Applies a profile from the config screen; the snapshot is kept for "Restore". */
    public static void applyProfile(Minecraft mc, SkyLiteConfig cfg, Profile profile) {
        captureOnce(cfg, mc.options);
        applyValues(mc.options, profile);
        persist(cfg, mc.options);
    }

    // ---------- snapshot / restore ----------

    /** True while "Restore" has something to return to. */
    public static boolean canRestore(SkyLiteConfig cfg) {
        return cfg.optRestore != null;
    }

    /** Remembers the vanilla values once, so "Restore" always returns to the original state. */
    public static void captureOnce(SkyLiteConfig cfg, Options o) {
        if (cfg.optRestore != null) {
            return;
        }
        SkyLiteConfig.OptSnapshot snapshot = new SkyLiteConfig.OptSnapshot();
        snapshot.renderDistance = o.renderDistance().get();
        snapshot.simulationDistance = o.simulationDistance().get();
        snapshot.entityDistance = o.entityDistanceScaling().get();
        snapshot.particles = o.particles().get().name();
        snapshot.clouds = o.cloudStatus().get().name();
        snapshot.entityShadows = o.entityShadows().get();
        snapshot.biomeBlend = o.biomeBlendRadius().get();
        snapshot.ambientOcclusion = o.ambientOcclusion().get();
        snapshot.cutoutLeaves = o.cutoutLeaves().get();
        cfg.optRestore = snapshot;
        cfg.save();
    }

    /** Puts the captured values back and switches the location rules off. */
    public static boolean restore(Minecraft mc, SkyLiteConfig cfg) {
        SkyLiteConfig.OptSnapshot snapshot = cfg.optRestore;
        if (snapshot == null) {
            return false;
        }
        Options o = mc.options;
        o.renderDistance().set(snapshot.renderDistance);
        o.simulationDistance().set(snapshot.simulationDistance);
        o.entityDistanceScaling().set(snapshot.entityDistance);
        o.particles().set(particle(snapshot.particles));
        o.cloudStatus().set(cloud(snapshot.clouds));
        o.entityShadows().set(snapshot.entityShadows);
        o.biomeBlendRadius().set(snapshot.biomeBlend);
        o.ambientOcclusion().set(snapshot.ambientOcclusion);
        o.cutoutLeaves().set(snapshot.cutoutLeaves);
        cfg.optRestore = null;
        cfg.optAreaProfiles = false; // otherwise the rules would re-apply on the next area change
        areaState = null;
        persist(cfg, o);
        return true;
    }

    private static ParticleStatus particle(String name) {
        try {
            return ParticleStatus.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return ParticleStatus.ALL;
        }
    }

    private static CloudStatus cloud(String name) {
        try {
            return CloudStatus.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return CloudStatus.FANCY;
        }
    }

    // ---------- advisor ----------

    /** One advisor line: what to change and the runnable that changes it. */
    public record Advice(String label, Runnable apply) {
    }

    /** Everything the advisor still sees as worth changing, best-value rows first. */
    public static List<Advice> advices(Minecraft mc) {
        Options o = mc.options;
        List<Advice> advices = new ArrayList<>();
        if (o.particles().get() == ParticleStatus.ALL) {
            advices.add(new Advice(advice("particles",
                    Component.translatable("options.particles.all"),
                    Component.translatable("options.particles.decreased")),
                    () -> o.particles().set(ParticleStatus.DECREASED)));
        }
        if (o.cloudStatus().get() == CloudStatus.FANCY) {
            advices.add(new Advice(advice("clouds",
                    Component.translatable("options.clouds.fancy"),
                    Component.translatable("options.clouds.fast")),
                    () -> o.cloudStatus().set(CloudStatus.FAST)));
        }
        if (o.entityShadows().get()) {
            advices.add(new Advice(advice("shadows", on(), off()),
                    () -> o.entityShadows().set(false)));
        }
        if (o.cutoutLeaves().get()) {
            advices.add(new Advice(advice("leaves", on(), off()),
                    () -> o.cutoutLeaves().set(false)));
        }
        if (o.biomeBlendRadius().get() > 1) {
            advices.add(new Advice(advice("blend", blend(o.biomeBlendRadius().get()), blend(1)),
                    () -> o.biomeBlendRadius().set(1)));
        }
        if (o.entityDistanceScaling().get() > 1.0) {
            advices.add(new Advice(advice("entity_distance",
                    Component.literal(String.format(Locale.ROOT, "%.2f", o.entityDistanceScaling().get())),
                    Component.literal("1.0")),
                    () -> o.entityDistanceScaling().set(1.0)));
        }
        return advices;
    }

    private static String advice(String what, Component... values) {
        return Component.translatable("skylite.opt.advice." + what, (Object[]) values).getString();
    }

    /** The vanilla "3x3"-style label of a biome-blend radius (stored 0..7, shown 2r+1). */
    private static Component blend(int radius) {
        return Component.translatable("options.biomeBlendRadius." + (radius * 2 + 1));
    }

    private static Component on() {
        return Component.translatable("options.on");
    }

    private static Component off() {
        return Component.translatable("options.off");
    }

    // ---------- mods ----------

    /** Perf mods that go further than a config-only mod can; checked against the mod list. */
    public record ModTip(String nameKey, String modId, String url) {
    }

    public static final List<ModTip> MOD_TIPS = List.of(
            new ModTip("skylite.opt.mod.sodium", "sodium", "https://modrinth.com/mod/sodium"),
            new ModTip("skylite.opt.mod.entityculling", "entityculling", "https://modrinth.com/mod/entityculling"),
            new ModTip("skylite.opt.mod.immediatelyfast", "immediatelyfast", "https://modrinth.com/mod/immediatelyfast"),
            new ModTip("skylite.opt.mod.ferritecore", "ferritecore", "https://modrinth.com/mod/ferritecore"),
            new ModTip("skylite.opt.mod.modernfix", "modernfix", "https://modrinth.com/mod/modernfix"),
            new ModTip("skylite.opt.mod.krypton", "krypton", "https://modrinth.com/mod/krypton"));

    public static boolean installed(String modId) {
        return FabricLoader.getInstance().getModContainer(modId).isPresent();
    }

    /** Opens a link in the player's browser — the same call vanilla uses for chat links. */
    public static void openUrl(String url) {
        try {
            Blaze3D.openUri(URI.create(url));
        } catch (Exception e) {
            SkyLiteClient.LOGGER.warn("SkyLite: could not open {}", url, e);
        }
    }

    // ---------- location rules ----------

    /** "area profileId" of the last applied rule set; null = evaluate again on the next poll. */
    private static String areaState;

    /**
     * SkyBlock location rules, called from the sidebar poll every half a second: the profile
     * re-applies only when the area or the profile chosen for it actually changes.
     */
    public static void tickArea(Minecraft mc, SkyLiteConfig cfg, String area) {
        if (!cfg.optAreaProfiles || area == null) {
            areaState = null;
            return;
        }
        Profile profile = profileForArea(cfg, area);
        String state = area + '\u0000' + (profile == null ? "none" : profile.id);
        if (state.equals(areaState)) {
            return;
        }
        areaState = state;
        if (profile == null) {
            return; // "none": leave the current values alone
        }
        captureOnce(cfg, mc.options);
        applyValues(mc.options, profile);
        persist(cfg, mc.options);
        SkyLiteClient.alerts.toast(Component.translatable("skylite.opt.toast.profile", profile.displayName()));
    }

    /** Profile of an area: the first matching rule, else {@link SkyLiteConfig#optAreaDefault}. */
    private static Profile profileForArea(SkyLiteConfig cfg, String area) {
        String lower = area.toLowerCase(Locale.ROOT);
        for (String rule : cfg.optAreaRules) {
            if (rule == null) {
                continue;
            }
            int equals = rule.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            String place = rule.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            if (!place.isEmpty() && lower.contains(place)) {
                Profile profile = byId(rule.substring(equals + 1));
                if (profile != null) {
                    return profile;
                }
            }
        }
        return byId(cfg.optAreaDefault);
    }

    // ---------- shared ----------

    /** Writes both the vanilla options and skylite.json after a change. */
    public static void persist(SkyLiteConfig cfg, Options options) {
        options.save();
        cfg.save();
    }
}
