package ru.astra.ability.impl;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import ru.astra.AstraPlugin;
import ru.astra.effect.ParticleService;
import ru.astra.util.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Аномалия: локальная зона с изменёнными правилами восприятия и поведения.
 * Внутри зоны — визуальные искажения, персональные звуки «фоновой аномалии»,
 * мягкое изменение темпа движения (замедление/ускорение) через временные
 * эффекты зельями (SLOWNESS/SPEED), которые корректно снимаются при выходе.
 * Зоны не сливаются; лимит активных зон задан конфигурацией.
 */
public final class AnomalyAbility extends TaskAbility {

    /** Активная зона аномалии. */
    private static final class Zone {
        final Location center;
        final double radius;
        final int durationTicks;
        int elapsed = 0;
        final List<java.util.UUID> inside = new ArrayList<>();

        Zone(Location center, double radius, int durationTicks) {
            this.center = center;
            this.radius = radius;
            this.durationTicks = durationTicks;
        }

        boolean contains(Location loc) {
            return loc.getWorld() == center.getWorld()
                    && loc.distanceSquared(center) <= radius * radius;
        }
    }

    public AnomalyAbility(AstraPlugin plugin) {
        super(plugin, "anomaly", "Аномалия",
                "Локальная зона с изменёнными правилами восприятия.", RepeatPolicy.ALLOW_MULTIPLE);
    }

    @Override
    public StartCheck canStart(AbilityContext ctx) {
        StartCheck base = super.canStart(ctx);
        if (!base.allowed()) {
            return base;
        }
        var cfg = plugin().config();
        var sec = cfg.ability(id());
        int maxZones = cfg.sectionInt(sec, "max-active-zones", 10, 1, 32);
        if (instances.size() >= maxZones) {
            return StartCheck.deny("Достигнут лимит активных зон аномалии (" + maxZones + ").");
        }
        if (ctx.point == null && ctx.target == null) {
            return StartCheck.deny("Укажите точку: /astra cast anomaly point <x y z>.");
        }
        return StartCheck.ok();
    }

    @Override
    protected void doStart(AbilityContext ctx) {
        var cfg = plugin().config();
        var sec = cfg.ability(id());
        double radius = cfg.sectionDouble(sec, "radius", 8, 2, 32);
        int durationSeconds = cfg.sectionInt(sec, "duration-seconds", 30, 5, 300);
        int checkInterval = cfg.sectionInt(sec, "check-interval-ticks", 5, 1, 40);
        int maxTargets = cfg.sectionInt(sec, "max-targets-per-zone", 20, 1, 64);

        Location center = ctx.point != null ? ctx.point : ctx.target.getLocation();
        Zone zone = new Zone(center.clone(), radius, durationSeconds * 20);
        Instance inst = launch(checkInterval, durationSeconds * 20, new ZoneBody(zone, maxTargets));
        inst.extra = zone;
    }

    private final class ZoneBody implements Body {
        private final Zone zone;
        private final int maxTargets;

        ZoneBody(Zone zone, int maxTargets) {
            this.zone = zone;
            this.maxTargets = maxTargets;
        }

        @Override
        public boolean step(Instance instance) {
            zone.elapsed += Math.max(1, instance.task.periodTicks());
            if (zone.elapsed >= zone.durationTicks) {
                releaseAll(zone);
                return false;
            }
            // Визуальные искажения внутри радиуса.
            double pulse = 0.5 + 0.5 * Math.sin(zone.elapsed * 0.1);
            plugin().particles().ring(zone.center.clone().add(0, 0.1, 0),
                    zone.radius * pulse, Particle.DUST, 24, ParticleService.AEON_RED);
            plugin().particles().ring(zone.center.clone().add(0, 2.0 * pulse, 0),
                    zone.radius * (1 - pulse * 0.7), Particle.SNOWFLAKE, 16, ParticleService.AEON_WHITE);
            plugin().particles().sphere(zone.center.clone().add(0, 1, 0), zone.radius * 0.8,
                    Particle.PORTAL, 10, 0);

            // Цели внутри зоны.
            List<Player> insideNow = new ArrayList<>();
            for (Player p : plugin().getServer().getOnlinePlayers()) {
                if (insideNow.size() >= maxTargets) {
                    break;
                }
                if (zone.contains(p.getLocation())) {
                    insideNow.add(p);
                }
            }
            // Вошедшие: temporary speed effect + персональный фоновый звук.
            for (Player p : insideNow) {
                String key = "anomaly|" + zone.center.getBlockX() + "," + zone.center.getBlockZ()
                        + "|" + p.getUniqueId();
                if (!zone.inside.contains(p.getUniqueId())) {
                    zone.inside.add(p.getUniqueId());
                    plugin().tempState().applyPotion(key, p,
                            org.bukkit.potion.PotionEffectType.SLOWNESS,
                            40, 0, true, false, false);
                    p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                            0.8f, 0.6f);
                } else {
                    // Продлеваем эффект, пока игрок в зоне (короткими дозами).
                    plugin().tempState().applyPotion(key, p,
                            org.bukkit.potion.PotionEffectType.SLOWNESS,
                            40, 0, true, false, false);
                }
            }
            // Вышедшие: снимаем эффект корректно.
            zone.inside.removeIf(id -> {
                Player p = org.bukkit.Bukkit.getPlayer(id);
                boolean stillInside = p != null && p.isOnline() && zone.contains(p.getLocation());
                if (!stillInside) {
                    String key = "anomaly|" + zone.center.getBlockX() + "," + zone.center.getBlockZ()
                            + "|" + id;
                    plugin().tempState().removePotion(key);
                    if (p != null && p.isOnline()) {
                        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                                0.8f, 1.4f);
                    }
                }
                return !stillInside;
            });
            return true;
        }

        private void releaseAll(Zone z) {
            for (java.util.UUID id : z.inside) {
                String key = "anomaly|" + z.center.getBlockX() + "," + z.center.getBlockZ() + "|" + id;
                plugin().tempState().removePotion(key);
            }
            z.inside.clear();
            plugin().particles().burst(z.center.clone().add(0, 1, 0), Particle.CLOUD,
                    30, 1.0, 0.05);
        }
    }

    @Override
    protected void onInstanceEnd(Instance instance) {
        if (instance.extra instanceof Zone z) {
            for (java.util.UUID id : new ArrayList<>(z.inside)) {
                String key = "anomaly|" + z.center.getBlockX() + "," + z.center.getBlockZ() + "|" + id;
                try {
                    plugin().tempState().removePotion(key);
                } catch (RuntimeException ignored) {
                }
            }
            z.inside.clear();
        }
    }
}
