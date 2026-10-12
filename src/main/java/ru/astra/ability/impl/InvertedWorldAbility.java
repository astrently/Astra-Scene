package ru.astra.ability.impl;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import ru.astra.AstraPlugin;
import ru.astra.util.Text;

/**
 * Перевёрнутый мир: локальное изменение правил движения для целей.
 * Мягкое «парение» (замедленное падение) и усиленный прыжок реализованы
 * через ограниченные импульсы без изменения постоянных данных игрока.
 * Эффекты персональны: каждый игрок видит собственную партию частиц.
 */
public final class InvertedWorldAbility extends TaskAbility {

    public InvertedWorldAbility(AstraPlugin plugin) {
        super(plugin, "inverted_world", "Перевёрнутый мир",
                "Локально меняет правила движения для целей (мягкое падение, прыжки).",
                RepeatPolicy.RESTART_CURRENT);
    }

    @Override
    protected void doStart(AbilityContext ctx) {
        var cfg = plugin().config();
        var sec = cfg.ability(id());
        int searchRadius = cfg.sectionInt(sec, "search-radius", 32, 4, 64);
        int maxTargets = cfg.sectionInt(sec, "max-targets", 10, 1, 32);
        int minDuration = cfg.sectionInt(sec, "min-duration-seconds", 5, 1, 60);
        int maxDuration = cfg.sectionInt(sec, "max-duration-seconds", 15, minDuration, 120);
        int pulsesMin = cfg.sectionInt(sec, "pulses-per-second-min", 2, 1, 10);
        int pulsesMax = cfg.sectionInt(sec, "pulses-per-second-max", 10, pulsesMin, 20);
        double softFall = cfg.sectionDouble(sec, "soft-fall-multiplier", 0.35, 0.05, 0.9);
        double jumpBoost = cfg.sectionDouble(sec, "jump-boost", 0.42, 0.1, 0.8);

        Location anchor = ctx.point != null ? ctx.point
                : (ctx.caster != null ? ctx.caster.getLocation() : null);
        if (anchor == null) {
            return;
        }
        java.util.List<Player> targets = new java.util.ArrayList<>(
                plugin().targetSelector().playersNear(anchor, searchRadius, maxTargets));
        if (!ctx.viewers.isEmpty()) {
            for (Player p : ctx.viewers) {
                if (targets.size() >= maxTargets) {
                    break;
                }
                if (!targets.contains(p)) {
                    targets.add(p);
                }
            }
        }
        int durationSeconds = java.util.concurrent.ThreadLocalRandom.current()
                .nextInt(minDuration, maxDuration + 1);
        int period = Math.max(1, 20 / pulsesMax); // обновление до pulsesMax раз в секунду

        launch(period, durationSeconds * 20,
                new InvertBody(targets, anchor.clone(), searchRadius, softFall, jumpBoost, pulsesMax));
    }

    private final class InvertBody implements Body {
        private final java.util.List<Player> initialTargets;
        private final Location anchor;
        private final double radius;
        private final double softFallMultiplier;
        private final double jumpBoost;
        private final int pulsesPerSecond;
        private int elapsed = 0;

        InvertBody(java.util.List<Player> initialTargets, Location anchor, double radius,
                   double softFallMultiplier, double jumpBoost, int pulsesPerSecond) {
            this.initialTargets = initialTargets;
            this.anchor = anchor;
            this.radius = radius;
            this.softFallMultiplier = softFallMultiplier;
            this.jumpBoost = jumpBoost;
            this.pulsesPerSecond = pulsesPerSecond;
        }

        @Override
        public boolean step(Instance instance) {
            elapsed += Math.max(1, instance.task.periodTicks());
            // Обновление состава целей: кто вошёл/вышел из области действия.
            java.util.List<Player> active = new java.util.ArrayList<>();
            for (Player p : initialTargets) {
                if (p.isOnline() && p.getWorld().equals(anchor.getWorld())
                        && p.getLocation().distanceSquared(anchor) <= radius * radius) {
                    active.add(p);
                }
            }
            if (active.isEmpty()) {
                return false;
            }

            for (Player p : active) {
                Vector vel = p.getVelocity();
                // Мягкое падение: гасим нисходящую составляющую.
                if (vel.getY() < -0.15) {
                    p.setVelocity(new Vector(vel.getX(), vel.getY() * softFallMultiplier, vel.getZ()));
                }
                // Усиленный прыжок при контакте с землёй.
                if (p.isOnGround() && vel.getY() > 0.05) {
                    p.setVelocity(vel.setY(Math.min(vel.getY() + jumpBoost, 0.9)));
                }
                // Персональные визуальные эффекты (только этому игроку).
                double pulse = (elapsed % (20 / Math.max(1, pulsesPerSecond) * 4)) == 0 ? 1 : 0;
                if (pulse > 0) {
                    p.spawnParticle(Particle.SNOWFLAKE,
                            p.getLocation().clone().add(rand(-1), rand(0.5, 2.5), rand(-1)),
                            2, 0.1, 0.1, 0.1, 0.0);
                    p.spawnParticle(Particle.END_ROD,
                            p.getLocation().clone().add(rand(-1.5), rand(0, 3), rand(-1.5)),
                            1, 0.05, 0.05, 0.05, 0.0);
                }
            }
            return true;
        }

        private static double rand(double spread) {
            return (Math.random() * 2 - 1) * spread;
        }

        private static double rand(double min, double max) {
            return min + Math.random() * (max - min);
        }
    }
}
