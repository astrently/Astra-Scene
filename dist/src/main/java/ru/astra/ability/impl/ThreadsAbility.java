package ru.astra.ability.impl;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import ru.astra.AstraPlugin;
import ru.astra.effect.ParticleService;
import ru.astra.util.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Нити судьбы: визуальная и функциональная связь нескольких целей.
 * Режимы: visual (по умолчанию), point <x y z> (общая точка),
 * effects (синхронные эффекты), attraction (притяжение — отключён по умолчанию),
 * mirror (зеркальное движение — отключено по умолчанию).
 * Нити не наносят урон, не изменяют инвентари и постоянные данные.
 */
public final class ThreadsAbility extends TaskAbility {

    public ThreadsAbility(AstraPlugin plugin) {
        super(plugin, "threads", "Нити судьбы",
                "Связывает цели визуальными нитями; режимы движения выключены по умолчанию.",
                RepeatPolicy.RESTART_CURRENT);
    }

    @Override
    public StartCheck canStart(AbilityContext ctx) {
        StartCheck base = super.canStart(ctx);
        if (!base.allowed()) {
            return base;
        }
        var cfg = plugin().config();
        var sec = cfg.ability(id());
        int minTargets = cfg.sectionInt(sec, "min-targets", 2, 1, 8);
        if (ctx.viewers.size() + (ctx.target != null ? 1 : 0) < minTargets) {
            return StartCheck.deny("Требуется минимум " + minTargets + " цели(ей):"
                    + " /astra cast threads <mode> [players...]");
        }
        return StartCheck.ok();
    }

    @Override
    protected void doStart(AbilityContext ctx) {
        var cfg = plugin().config();
        var sec = cfg.ability(id());
        int maxTargets = cfg.sectionInt(sec, "max-targets", 8, 2, 16);
        double maxLinkLength = cfg.sectionDouble(sec, "max-link-length", 64, 4, 256);
        int durationSeconds = cfg.sectionInt(sec, "duration-seconds", 15, 1, 120);
        int ups = cfg.sectionInt(sec, "updates-per-second", 5, 1, 20);
        Particle particle = Text.particleOrDefault(cfg.sectionString(sec, "particle", "REDSTONE"),
                Particle.DUST);
        int lineColor = Text.colorOrDefault(cfg.sectionString(sec, "line-color", "#FF3030"),
                ParticleService.AEON_RED);
        boolean attractionEnabled = cfg.sectionBool(sec, "attraction-enabled", false);
        double attractionStrength = cfg.sectionDouble(sec, "attraction-strength", 0.05, 0.01, 0.3);
        boolean mirrorEnabled = cfg.sectionBool(sec, "mirror-motion-enabled", false);

        List<LivingEntity> targets = new ArrayList<>();
        if (ctx.target != null) {
            targets.add(ctx.target);
        }
        for (Player p : ctx.viewers) {
            if (targets.size() >= maxTargets) {
                break;
            }
            if (!targets.contains(p)) {
                targets.add(p);
            }
        }

        String mode = ctx.mode == null || ctx.mode.isBlank() ? "visual" : ctx.mode.toLowerCase();
        Location sharedPoint = parseSharedPoint(mode, ctx.arguments);
        boolean useEffects = mode.contains("effect") || mode.equals("sync");
        boolean useAttraction = attractionEnabled && mode.contains("attract");
        boolean useMirror = mirrorEnabled && mode.contains("mirror");

        if ((mode.contains("attract")) && !attractionEnabled) {
            plugin().getLogger().warning("Нити судьбы: режим притяжения запрошен,"
                    + " но threads.attraction-enabled=false — включите настройку явно.");
        }
        if (mode.contains("mirror") && !mirrorEnabled) {
            plugin().getLogger().warning("Нити судьбы: зеркальное движение запрошено,"
                    + " но threads.mirror-motion-enabled=false — включите настройку явно.");
        }

        launch(Math.max(1, 20 / ups), durationSeconds * 20,
                new ThreadBody(new ArrayList<>(targets), particle, lineColor, maxLinkLength,
                        sharedPoint, useEffects, useAttraction, attractionStrength, useMirror));
    }

    private static Location parseSharedPoint(String mode, String args) {
        if (mode.startsWith("point") && args != null) {
            String[] parts = args.trim().split("\\s+");
            // Ожидаем x y z в аргументах после режима.
            if (parts.length >= 3) {
                try {
                    return new Location(org.bukkit.Bukkit.getWorlds().get(0),
                            Double.parseDouble(parts[parts.length - 3]),
                            Double.parseDouble(parts[parts.length - 2]),
                            Double.parseDouble(parts[parts.length - 1]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return null;
    }

    private final class ThreadBody implements Body {
        private final List<LivingEntity> targets;
        private final Particle particle;
        private final int color;
        private final double maxLinkLength;
        private Location sharedPoint;
        private final boolean useEffects;
        private final boolean useAttraction;
        private final double attractionStrength;
        private final boolean useMirror;
        private int elapsed = 0;

        ThreadBody(List<LivingEntity> targets, Particle particle, int color, double maxLinkLength,
                   Location sharedPoint, boolean useEffects, boolean useAttraction,
                   double attractionStrength, boolean useMirror) {
            this.targets = targets;
            this.particle = particle;
            this.color = color;
            this.maxLinkLength = maxLinkLength;
            this.sharedPoint = sharedPoint;
            this.useEffects = useEffects;
            this.useAttraction = useAttraction;
            this.attractionStrength = attractionStrength;
            this.useMirror = useMirror;
        }

        @Override
        public boolean step(Instance instance) {
            elapsed += Math.max(1, instance.task.periodTicks());
            List<LivingEntity> alive = new ArrayList<>();
            for (LivingEntity le : targets) {
                if (le.isValid() && le.isValid()) {
                    alive.add(le);
                }
            }
            if (alive.size() < 2) {
                return false; // цели исчезли — штатное завершение
            }

            // Визуальные нити: последовательные пары или общая точка.
            if (sharedPoint != null) {
                for (LivingEntity le : alive) {
                    drawThread(le.getLocation().clone().add(0, 1.2, 0), sharedPoint.clone());
                }
            } else {
                for (int i = 0; i < alive.size() - 1; i++) {
                    Location a = alive.get(i).getLocation().clone().add(0, 1.2, 0);
                    Location b = alive.get(i + 1).getLocation().clone().add(0, 1.2, 0);
                    if (a.distance(b) <= maxLinkLength) {
                        drawThread(a, b);
                    }
                }
            }

            // Синхронные эффекты на связанных целях.
            if (useEffects && elapsed % 20 == 0) {
                for (LivingEntity le : alive) {
                    plugin().particles().burst(le.getLocation().clone().add(0, 1.6, 0),
                            Particle.GLOW, 6, 0.3, 0.0);
                    if (le instanceof Player p) {
                        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT,
                                0.6f, 1.6f);
                    }
                }
            }

            // Притяжение к общей точке (ограниченный импульс, без урона).
            if (useAttraction && sharedPoint != null) {
                for (LivingEntity le : alive) {
                    Vector toPoint = sharedPoint.toVector().subtract(le.getLocation().toVector());
                    if (toPoint.length() > 1.0) {
                        le.setVelocity(le.getVelocity().multiply(0.6)
                                .add(toPoint.normalize().multiply(attractionStrength)));
                    }
                }
            }

            // Зеркальное движение: заданная последовательность импульсов синхронно всем целям.
            if (useMirror) {
                int phase = (elapsed / 10) % 4;
                Vector impulse = switch (phase) {
                    case 0 -> new Vector(0.12, 0, 0);
                    case 1 -> new Vector(0, 0.1, 0);
                    case 2 -> new Vector(-0.12, 0, 0);
                    default -> new Vector(0, 0, 0.12);
                };
                for (LivingEntity le : alive) {
                    le.setVelocity(le.getVelocity().add(impulse));
                }
            }
            return true;
        }

        private void drawThread(Location from, Location to) {
            // Плотность ограничена: максимум ~24 частицы на нить за обновление.
            plugin().particles().line(from, to, particle, 1.5, color);
        }
    }
}
