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
 * Вне времени: локальное изменение темпа выполнения для выбранной цели или точки.
 * Реализация без изменения глобального TPS сервера и без создания лишних задач:
 * «замедление» — дискретные импульсы поддержки и редкие тики смещения, дающие
 * субъективно замедленное, плавное движение; визуальный фон — персональные частицы.
 * Темп меняется только у целевой области; остальные игроки не затронуты.
 */
public final class OutOfTimeAbility extends TaskAbility {

    public OutOfTimeAbility(AstraPlugin plugin) {
        super(plugin, "out_of_time", "Вне времени",
                "Локально меняет темп выполнения для цели или точки.", RepeatPolicy.RESTART_CURRENT);
    }

    @Override
    public StartCheck canStart(AbilityContext ctx) {
        StartCheck base = super.canStart(ctx);
        if (!base.allowed()) {
            return base;
        }
        if (ctx.point == null && ctx.target == null) {
            return StartCheck.deny("Укажите цель или точку: /astra cast out_of_time <игрок|point>.");
        }
        var cfg = plugin().config();
        double min = cfg.sectionDouble(cfg.ability(id()), "min-tempo-multiplier", 0.25, 0.1, 1);
        if (ctx.mode != null && !ctx.mode.isBlank()) {
            try {
                double requested = Double.parseDouble(ctx.mode);
                if (requested < min || requested > 4.0) {
                    return StartCheck.deny("Темп должен быть в диапазоне " + min + "..4.0.");
                }
            } catch (NumberFormatException ex) {
                return StartCheck.deny("Темп — число: /astra cast out_of_time <tempo> ...");
            }
        }
        return StartCheck.ok();
    }

    @Override
    protected void doStart(AbilityContext ctx) {
        var cfg = plugin().config();
        var sec = cfg.ability(id());
        int maxDuration = cfg.sectionInt(sec, "max-duration-seconds", 30, 1, 120);
        double radius = cfg.sectionInt(sec, "radius", 8, 2, 32);
        double defaultTempo = cfg.sectionDouble(sec, "default-tempo", 0.5,
                cfg.sectionDouble(sec, "min-tempo-multiplier", 0.25, 0.1, 1), 4.0);

        double tempo = defaultTempo;
        if (ctx.mode != null && !ctx.mode.isBlank()) {
            try {
                tempo = Double.parseDouble(ctx.mode.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        Location fixedPoint = ctx.point;
        org.bukkit.entity.LivingEntity followedTarget = ctx.target;
        int durationSeconds = (int) Text.clamp(maxDuration, 1, maxDuration);

        launch(2, durationSeconds * 20, new TimeBody(followedTarget, fixedPoint, radius, tempo));
    }

    private final class TimeBody implements Body {
        private final org.bukkit.entity.LivingEntity target;
        private Location point;
        private final double radius;
        private final double tempo; // <1 замедление, >1 ускорение
        private int elapsed = 0;
        private final List<Player> affected = new ArrayList<>();

        TimeBody(org.bukkit.entity.LivingEntity target, Location point, double radius, double tempo) {
            this.target = target;
            this.point = point;
            this.radius = radius;
            this.tempo = Text.clamp(tempo, 0.1, 4.0);
        }

        private Location center() {
            if (target != null && target.isValid() && target.isValid()) {
                return target.getLocation();
            }
            return point;
        }

        @Override
        public boolean step(Instance instance) {
            Location c = center();
            if (c == null) {
                return false;
            }
            elapsed += Math.max(1, instance.task.periodTicks());

            // Обновляем список целей в области действия.
            affected.clear();
            for (Player p : plugin().getServer().getOnlinePlayers()) {
                if (p.getWorld().equals(c.getWorld())
                        && p.getLocation().distanceSquared(c) <= radius * radius) {
                    affected.add(p);
                }
            }

            // Замедление: поддерживающий импульс против гравитации — движения становятся
            // «вязкими»; ускорение: лёгкая поддержка текущего вектора движения.
            boolean slow = tempo < 1.0;
            for (Player p : affected) {
                var vel = p.getVelocity();
                if (slow) {
                    if (vel.getY() < -0.08) {
                        p.setVelocity(new org.bukkit.util.Vector(vel.getX() * 0.9,
                                vel.getY() * Math.max(0.3, tempo), vel.getZ() * 0.9));
                    }
                } else if (elapsed % 4 == 0) {
                    var dir = p.getLocation().getDirection();
                    p.setVelocity(vel.add(dir.multiply((tempo - 1.0) * 0.06)));
                }
                // Персональные визуальные эффекты: часы/песок только у затронутых игроков.
                p.spawnParticle(slow ? Particle.SNOWFLAKE : Particle.END_ROD,
                        p.getLocation().clone().add(rand(-0.8, 0.8), rand(1.5, 2.4), rand(-0.8, 0.8)),
                        1, 0.02, 0.02, 0.02, 0.0);
            }

            // Контур области влияния (бело-красная палитра Astra).
            if (elapsed % 10 == 0) {
                plugin().particles().ring(c.clone().add(0, 0.05, 0), radius,
                        Particle.DUST, 32, slow ? ParticleService.AEON_WHITE : ParticleService.AEON_RED);
            }
            return true;
        }

        private static double rand(double min, double max) {
            return min + Math.random() * (max - min);
        }
    }
}
