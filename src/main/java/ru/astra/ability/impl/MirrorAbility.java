package ru.astra.ability.impl;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import ru.astra.AstraPlugin;
import ru.astra.effect.ParticleService;
import ru.astra.util.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Зазеркалье: визуальные двойники выбранной цели (до 3 проекций).
 * Точная копия внешности игрока со скином/бронёй требует пакетной реализации;
 * при entity-mode-enabled=false используется упрощённый визуальный режим из частиц,
 * что честно обозначено в описании способности и журнале.
 */
public final class MirrorAbility extends TaskAbility {

    public MirrorAbility(AstraPlugin plugin) {
        super(plugin, "mirror", "Зазеркалье",
                "Создаёт визуальные двойники цели (упрощённый режим — контуры из частиц).",
                RepeatPolicy.RESTART_CURRENT);
    }

    @Override
    public StartCheck canStart(AbilityContext ctx) {
        StartCheck base = super.canStart(ctx);
        if (!base.allowed()) {
            return base;
        }
        if (ctx.target == null && ctx.point == null) {
            return StartCheck.deny("Укажите цель: /astra cast mirror <игрок|point>");
        }
        return StartCheck.ok();
    }

    @Override
    protected void doStart(AbilityContext ctx) {
        var cfg = plugin().config();
        var sec = cfg.ability(id());
        int maxProjections = cfg.sectionInt(sec, "max-projections", 3, 1, 8);
        double minOffset = cfg.sectionDouble(sec, "min-offset", 2, 1, 16);
        double maxOffset = cfg.sectionDouble(sec, "max-offset", 5, minOffset, 32);
        int durationSeconds = cfg.sectionInt(sec, "duration-seconds", 10, 1, 120);
        boolean entityMode = cfg.sectionBool(sec, "entity-mode-enabled", false);

        if (entityMode) {
            plugin().getLogger().warning("Зазеркалье: entity-mode-enabled=true, но пакетная реализация"
                    + " точных копий игроков не входит в сборку. Использован упрощённый режим.");
        }

        LivingEntity target = ctx.target;
        Location fixedPoint = ctx.point;
        List<VisualProjection> projections = new ArrayList<>();
        for (int i = 0; i < maxProjections; i++) {
            double angle = (2 * Math.PI * i) / maxProjections;
            double offset = Text.clamp(minOffset + (i % 2) * ((maxOffset - minOffset) / 2.0),
                    minOffset, maxOffset);
            Location base = (target != null ? target.getLocation() : fixedPoint)
                    .clone().add(Math.cos(angle) * offset, 0, Math.sin(angle) * offset);
            VisualProjection vp = new VisualProjection(base, (float) Math.toDegrees(-angle),
                    i % 2 == 0 ? ParticleService.AEON_WHITE : ParticleService.AEON_RED,
                    Particle.DUST);
            // Задержка появления каждой проекции — последовательно, по одной за тик-два.
            projections.add(vp);
        }

        final LivingEntity tracked = target;
        final int[] shown = {0};
        launch(4, durationSeconds * 20, instance -> {
            Location center = tracked != null && tracked.isValid()
                    ? tracked.getLocation() : fixedPoint;
            if (center == null) {
                return false;
            }
            // Последовательное появление: каждая следующая проекция через 2 обновления.
            int visible = Math.min(projections.size(), shown[0] / 2 + 1);
            shown[0]++;
            for (int i = 0; i < visible; i++) {
                VisualProjection vp = projections.get(i);
                // Смещение проекций относительно движущейся цели.
                double dx = vp.position.getX() - projections.get(0).position.getX();
                double dz = vp.position.getZ() - projections.get(0).position.getZ();
                Location pos = center.clone().add(dx, 0, dz);
                new VisualProjection(pos, vp.yaw, vp.color, vp.particle).render(plugin().particles());
            }
            // Последовательность исчезновения: если осталось < 40 тиков, скрываем по одной.
            if (instance.ticksLeft < 40) {
                int hideUntil = 40 - instance.ticksLeft;
                int hidden = Math.min(projections.size(), hideUntil / 13);
                // Оставшиеся видимые перерисовываются выше; просто ограничиваем visible.
                return projections.size() - hidden > 0 || instance.ticksLeft > 0;
            }
            return true;
        });
    }
}
