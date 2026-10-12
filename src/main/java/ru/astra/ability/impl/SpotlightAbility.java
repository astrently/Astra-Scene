package ru.astra.ability.impl;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import ru.astra.AstraPlugin;
import ru.astra.ability.Ability;
import ru.astra.util.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Прожектор: визуальное выделение цели или точки пространства.
 * Частицы сами по себе не освещают окружение — это ограничение Paper API,
 * зафиксированное в ТЗ (настоящее изменение освещения требует отдельного решения).
 */
public final class SpotlightAbility extends Ability {

    private final List<Object[]> instances = new ArrayList<>(); // {task, ...}

    public SpotlightAbility(AstraPlugin plugin) {
        super(plugin, "spotlight", "Прожектор",
                "Визуально выделяет персонажа, объект или точку.", RepeatPolicy.ALLOW_MULTIPLE);
    }

    @Override
    public StartCheck canStart(AbilityContext ctx) {
        StartCheck base = super.canStart(ctx);
        if (!base.allowed()) {
            return base;
        }
        if (ctx.point == null && ctx.target == null) {
            return StartCheck.deny("Укажите цель или точку: /astra cast spotlight <игрок|point>");
        }
        return StartCheck.ok();
    }

    @Override
    protected void doStart(AbilityContext ctx) {
        var cfg = plugin().config();
        var sec = cfg.ability(id());
        double radius = cfg.sectionInt(sec, "visual-radius", 3, 1, 16);
        int durationSeconds = cfg.sectionInt(sec, "duration-seconds", 5, 1, 60);
        int ups = cfg.sectionInt(sec, "updates-per-second", 5, 1, 20);
        Particle particle = Text.particleOrDefault(cfg.sectionString(sec, "particle", "DUST"), Particle.DUST);
        int color = Text.colorOrDefault(cfg.sectionString(sec, "dust-color", "#FFFFFF"), 0xFFFFFF);
        Sound sound = Text.soundOrDefault(cfg.sectionString(sec, "sound", "BLOCK_NOTE_BLOCK_PLING"),
                Sound.BLOCK_NOTE_BLOCK_PLING);

        Location anchor = ctx.target != null ? ctx.target.getLocation().add(0, 1, 0) : ctx.point;
        List<Player> viewers = ctx.viewers.isEmpty()
                ? plugin().targetSelector().playersNear(anchor, cfg.sectionInt(sec, "search-radius", 64, 4, 128),
                cfg.maxViewersPerSceneSafe())
                : ctx.viewers;

        plugin().sounds().play(anchor, sound, 1.0f, 1.2f);

        int period = Math.max(1, 20 / ups);
        final int[] ticksLeft = {durationSeconds * 20};
        Object[] inst = new Object[1];
        var task = plugin().scheduler().scheduleRepeating(period, () -> {
            if (ticksLeft[0] <= 0) {
                plugin().scheduler().cancel((ru.astra.scene.SceneScheduler.PeriodicTask) inst[0]);
                instances.remove(inst);
                return;
            }
            ticksLeft[0] -= period;
            Location center = ctx.target != null && ctx.target.isValid()
                    ? ctx.target.getLocation().add(0, 1, 0)
                    : anchor;
            // Столб света над целью + кольцо у основания (белый цвет по умолчанию).
            plugin().particles().column(center.clone().add(0, 0.2, 0), radius + 2.0, particle, 3, color);
            plugin().particles().ring(center.clone().add(0, 0.05, 0), radius, particle, 24, color);
        });
        inst[0] = task;
        instances.add(inst);
    }

    @Override
    public void stopAll(String reason) {
        for (Object[] inst : new ArrayList<>(instances)) {
            plugin().scheduler().cancel((ru.astra.scene.SceneScheduler.PeriodicTask) inst[0]);
        }
        instances.clear();
    }

    @Override
    public boolean isRunning() {
        return !instances.isEmpty();
    }

    @Override
    public int activeCount() {
        return instances.size();
    }
}
