package ru.astra.ability.impl;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import ru.astra.AstraPlugin;
import ru.astra.ability.Ability;
import ru.astra.util.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Смена декораций: театральное перемещение выбранной группы участников.
 * Эффект отправления -> проверка конечной точки и безопасного места для каждого
 * участника -> распределение по отдельным позициям -> эффект прибытия.
 * Перемещение между мирами — отдельная настройка (по умолчанию выключена).
 * Отмена возможна до момента фактической телепортации.
 */
public final class SceneChangeAbility extends Ability {

    /** Ожидающее выполнение телепортации (можно отменить до исполнения). */
    private static final class PendingMove {
        Player participant;
        Location destination;
    }

    private final List<PendingMove> queue = new ArrayList<>();
    private boolean running = false;

    public SceneChangeAbility(AstraPlugin plugin) {
        super(plugin, "scene_change", "Смена декораций",
                "Театральное перемещение группы участников в другую точку.",
                RepeatPolicy.REJECT);
    }

    @Override
    public StartCheck canStart(AbilityContext ctx) {
        StartCheck base = super.canStart(ctx);
        if (!base.allowed()) {
            return base;
        }
        if (ctx.point == null) {
            return StartCheck.deny("Укажите конечную точку: /astra cast scene_change point <x y z>.");
        }
        if (ctx.viewers.isEmpty() && ctx.target == null) {
            return StartCheck.deny("Нет участников для перемещения.");
        }
        return StartCheck.ok();
    }

    @Override
    protected void doStart(AbilityContext ctx) {
        var cfg = plugin().config();
        var sec = cfg.ability(id());
        int maxParticipants = cfg.sectionInt(sec, "max-participants", 20, 1, 64);
        int delayTicks = cfg.sectionInt(sec, "delay-seconds", 1, 0, 10) * 20;
        boolean crossWorld = cfg.sectionBool(sec, "cross-world-enabled", false);
        var departureSound = Text.soundOrDefault(
                cfg.sectionString(sec, "departure-sound", "ENTITY_ENDERMAN_TELEPORT"),
                org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT);
        var arrivalSound = Text.soundOrDefault(
                cfg.sectionString(sec, "arrival-sound", "ENTITY_ENDERMAN_TELEPORT"),
                org.bukkit.Sound.ENTITY_ENDERMAN_TELEPORT);

        List<Player> participants = new ArrayList<>();
        if (ctx.target != null) {
            for (var e : List.of(ctx.target)) {
                if (e instanceof Player p) {
                    participants.add(p);
                }
            }
        }
        for (Player p : ctx.viewers) {
            if (participants.size() >= maxParticipants) {
                break;
            }
            if (!participants.contains(p)) {
                participants.add(p);
            }
        }
        if (participants.size() > maxParticipants) {
            participants = new ArrayList<>(participants.subList(0, maxParticipants));
        }

        Location destCenter = ctx.point.clone();
        running = true;
        queue.clear();

        // Эффект отправления для всех участников.
        for (Player p : participants) {
            plugin().particles().burst(p.getLocation().clone().add(0, 1, 0),
                    Particle.PORTAL, 25, 0.5, 0.05);
            p.playSound(p.getLocation(), departureSound, 1.0f, 0.9f);
        }

        // Планируем телепортацию с задержкой; до неё можно отменить через stopAll.
        final List<Player> finalParticipants = participants;
        plugin().scheduler().scheduleOnce(delayTicks, () -> {
            if (!running) {
                return; // отменено
            }
            int index = 0;
            List<String> failed = new ArrayList<>();
            for (Player p : finalParticipants) {
                if (!p.isOnline()) {
                    failed.add(p.getName() + " (не в сети)");
                    continue;
                }
                Location dest = spreadPosition(destCenter, index++, finalParticipants.size());
                if (!crossWorld && !dest.getWorld().equals(p.getWorld())) {
                    failed.add(p.getName() + " (другой мир отключён)");
                    continue;
                }
                if (!isSafeLanding(dest)) {
                    // Ищем безопасное место рядом: поднимаемся до 3 блоков.
                    Location probe = dest.clone();
                    boolean found = false;
                    for (int dy = 0; dy <= 3; dy++) {
                        probe.setY(dest.getY() + dy);
                        if (isSafeLanding(probe)) {
                            dest = probe.clone();
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        failed.add(p.getName() + " (нет безопасного места)");
                        continue;
                    }
                }
                p.teleport(dest);
                plugin().particles().burst(dest.clone().add(0, 1, 0), Particle.END_ROD,
                        20, 0.5, 0.0);
                p.playSound(dest, arrivalSound, 1.0f, 1.1f);
            }
            running = false;
            queue.clear();
            if (!failed.isEmpty()) {
                String report = "Смена декораций: не перемещены — " + String.join(", ", failed);
                plugin().getLogger().warning(report);
                if (ctx.caster != null) {
                    ctx.caster.sendMessage(Text.error(report));
                }
                for (Player p : plugin().getServer().getOnlinePlayers()) {
                    if (p.hasPermission("astra.admin")) {
                        p.sendMessage(Text.error(report));
                    }
                }
            }
        });
    }

    /** Распределение участников по отдельным конечным позициям (круг радиуса 1.5). */
    private Location spreadPosition(Location center, int index, int total) {
        if (total <= 1) {
            return center.clone();
        }
        double angle = (2 * Math.PI * index) / total;
        return center.clone().add(Math.cos(angle) * 1.5, 0, Math.sin(angle) * 1.5);
    }

    private boolean isSafeLanding(Location dest) {
        var w = dest.getWorld();
        if (w == null) {
            return false;
        }
        var below = dest.clone().subtract(0, 0.5, 0).getBlock();
        if (below.getType().isAir() || below.isLiquid()) {
            return false;
        }
        return dest.getBlock().getType().isAir() && dest.clone().add(0, 1, 0).getBlock().getType().isAir();
    }

    @Override
    public void stopAll(String reason) {
        // Отмена до выполнения: снимаем флаг — запланированная задача ничего не сделает.
        running = false;
        queue.clear();
        plugin().logDebug("Смена декораций остановлена до телепортации: " + reason);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
