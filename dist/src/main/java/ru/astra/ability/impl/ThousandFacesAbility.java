package ru.astra.ability.impl;

import org.bukkit.Location;
import org.bukkit.Particle;
import ru.astra.AstraPlugin;
import ru.astra.effect.ParticleService;
import ru.astra.util.Text;

/**
 * Тысяча лиц: цель окружается множеством визуальных отражений (до 12 проекций),
 * появляющихся группами по 3 и меняющихся каждые 2 секунды.
 * Упрощённый визуальный режим из частиц; полноценные копии игроков/мобов
 * требуют отдельной пакетной реализации и не входят в сборку.
 */
public final class ThousandFacesAbility extends TaskAbility {

    public ThousandFacesAbility(AstraPlugin plugin) {
        super(plugin, "thousand_faces", "Тысяча лиц",
                "Окружает цель множеством визуальных отражений (упрощённый режим).",
                RepeatPolicy.RESTART_CURRENT);
    }

    @Override
    public StartCheck canStart(AbilityContext ctx) {
        StartCheck base = super.canStart(ctx);
        if (!base.allowed()) {
            return base;
        }
        if (ctx.target == null && ctx.point == null) {
            return StartCheck.deny("Укажите цель: /astra cast thousand_faces <игрок|point>");
        }
        return StartCheck.ok();
    }

    @Override
    protected void doStart(AbilityContext ctx) {
        var cfg = plugin().config();
        var sec = cfg.ability(id());
        int maxProjections = cfg.sectionInt(sec, "max-projections", 12, 3, 24);
        double radius = cfg.sectionDouble(sec, "placement-radius", 6, 1, 16);
        int durationSeconds = cfg.sectionInt(sec, "duration-seconds", 12, 2, 60);
        int groupSize = cfg.sectionInt(sec, "group-size", 3, 1, maxProjections);
        int changePeriodTicks = cfg.sectionInt(sec, "change-period-seconds", 2, 1, 10) * 20;

        if (cfg.sectionBool(sec, "entity-mode-enabled", false)) {
            plugin().getLogger().warning("Тысяча лиц: пакетная реализация копий не входит в сборку;"
                    + " используется упрощённый визуальный режим.");
        }

        final Location fixedPoint = ctx.point;
        launch(4, durationSeconds * 20, new FaceBody(ctx.target, fixedPoint,
                maxProjections, radius, groupSize, changePeriodTicks));
    }

    private final class FaceBody implements Body {
        private final org.bukkit.entity.LivingEntity target;
        private final Location fixedPoint;
        private final int maxProjections;
        private final double radius;
        private final int groupSize;
        private final int changePeriodTicks;
        private int elapsed = 0;
        private long seed = System.nanoTime();

        FaceBody(org.bukkit.entity.LivingEntity target, Location fixedPoint, int maxProjections,
                 double radius, int groupSize, int changePeriodTicks) {
            this.target = target;
            this.fixedPoint = fixedPoint;
            this.maxProjections = maxProjections;
            this.radius = radius;
            this.groupSize = groupSize;
            this.changePeriodTicks = changePeriodTicks;
        }

        @Override
        public boolean step(Instance instance) {
            Location center = target != null && target.isValid() ? target.getLocation() : fixedPoint;
            if (center == null) {
                return false;
            }
            elapsed += Math.max(1, instance.task.periodTicks());
            // Смена конфигурации проекций каждые change-period секунд.
            long epoch = elapsed / changePeriodTicks;
            java.util.Random rnd = new java.util.Random(seed + epoch * 31);

            int shownGroups = (int) Text.clamp(Math.ceil((double) elapsed / changePeriodTicks),
                    1, Math.ceil((double) maxProjections / groupSize));
            int visible = Math.min(maxProjections, shownGroups * groupSize);
            // Последнее изменение — исчезновение: сокращаем число проекций.
            if (instance.ticksLeft <= changePeriodTicks) {
                visible = Math.max(1, visible * Math.max(1, instance.ticksLeft) / changePeriodTicks);
            }

            for (int i = 0; i < visible; i++) {
                double angle = rnd.nextDouble(0, Math.PI * 2);
                double r = radius * (0.55 + rnd.nextDouble(0.45));
                double yOff = rnd.nextDouble(0, 1.4);
                Location pos = center.clone().add(Math.cos(angle) * r, yOff, Math.sin(angle) * r);
                int color = i % 3 == 0 ? ParticleService.AEON_RED : ParticleService.AEON_WHITE;
                VisualProjection vp = new VisualProjection(pos, (float) Math.toDegrees(-angle),
                        color, Particle.DUST);
                // Редкая «вспышка» вместо полного контура для экономии частиц.
                plugin().particles().burst(vp.position, Particle.END_ROD, 3, 0.12, 0.0);
                plugin().particles().colorBurst(vp.position.clone().add(0, 0.9, 0),
                        Particle.DUST, 1, 0.05, 0.0, vp.color);
            }
            return true;
        }
    }
}
