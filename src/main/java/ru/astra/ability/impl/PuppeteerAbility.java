package ru.astra.ability.impl;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import ru.astra.AstraPlugin;
import ru.astra.effect.ParticleService;
import ru.astra.util.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Кукловод: постановочное движение существа по маршруту из точек.
 * Маршрут строится администратором командами /astra route ... (см. AstraCommand).
 * Принудительное управление игроком — отдельный явно включаемый режим
 * (player-control-enabled); по умолчанию выключен из-за конфликтов с античитом.
 * Если цель застряла или не может достичь точки, маршрут завершается с
 * фиксацией причины (бесконечные повторы недопустимы).
 */
public final class PuppeteerAbility extends TaskAbility {

    /** Точка маршрута. */
    public record RoutePoint(Location location, int pauseTicks, String effect) {
    }

    private final List<List<RoutePoint>> routes = new ArrayList<>();

    public PuppeteerAbility(AstraPlugin plugin) {
        super(plugin, "puppeteer", "Кукловод",
                "Проводит существо по заданному маршруту точек.", RepeatPolicy.ALLOW_MULTIPLE);
    }

    // ==================== Конструктор маршрута ====================

    public void beginRoute() {
        routes.add(new ArrayList<>());
    }

    public boolean addPoint(Location loc, int pauseTicks, String effect) {
        if (routes.isEmpty()) {
            beginRoute();
        }
        List<RoutePoint> current = routes.get(routes.size() - 1);
        var cfg = plugin().config();
        int maxPoints = cfg.sectionInt(cfg.ability(id()), "max-route-points", 20, 1, 64);
        if (current.size() >= maxPoints) {
            return false;
        }
        current.add(new RoutePoint(loc.clone(), Text.clamp(pauseTicks, 0, 100),
                effect == null ? "" : effect));
        return true;
    }

    public List<RoutePoint> previewCurrent() {
        return routes.isEmpty() ? List.of() : routes.get(routes.size() - 1);
    }

    public void clearRoutes() {
        routes.clear();
    }

    // ==================== Запуск ====================

    @Override
    public StartCheck canStart(AbilityContext ctx) {
        StartCheck base = super.canStart(ctx);
        if (!base.allowed()) {
            return base;
        }
        if (routes.isEmpty() || routes.get(routes.size() - 1).isEmpty()) {
            return StartCheck.deny("Маршрут пуст: добавьте точки командами /astra route add/cursor.");
        }
        LivingEntity target = ctx.target;
        if (target == null) {
            return StartCheck.deny("Укажите существо: /astra cast puppeteer <игрок|моб>.");
        }
        if (target instanceof Player && !allowPlayerControl()) {
            return StartCheck.deny("Управление игроком отключено (puppeteer.player-control-enabled=false)."
                    + " Включите настройку явно, если уверены.");
        }
        return StartCheck.ok();
    }

    private boolean allowPlayerControl() {
        var cfg = plugin().config();
        return cfg.sectionBool(cfg.ability(id()), "player-control-enabled", false);
    }

    @Override
    protected void doStart(AbilityContext ctx) {
        var cfg = plugin().config();
        var sec = cfg.ability(id());
        double speed = cfg.sectionDouble(sec, "max-speed-blocks-per-tick", 0.5,
                cfg.sectionDouble(sec, "min-speed-blocks-per-tick", 0.2, 0.05, 2), 2.0);
        int maxDurationTicks = cfg.sectionInt(sec, "max-duration-seconds", 60, 1, 300) * 20;
        int stuckTimeoutTicks = cfg.sectionInt(sec, "stuck-timeout-seconds", 10, 2, 60) * 20;

        List<RoutePoint> route = new ArrayList<>(routes.remove(routes.size() - 1));
        LivingEntity entity = ctx.target;
        RouteBody body = new RouteBody(entity, route, speed, stuckTimeoutTicks);
        Instance inst = launch(1, maxDurationTicks, body::step);
        inst.extra = body;
    }

    /** Прогресс маршрута для статуса. */
    public String statusLine() {
        StringBuilder sb = new StringBuilder();
        for (Instance inst : instances) {
            if (inst.extra instanceof RouteBody body) {
                sb.append(body.entity.getName()).append(" [")
                        .append(body.pointIndex).append('/').append(body.route.size()).append("] ");
            }
        }
        return sb.toString();
    }

    private final class RouteBody implements Body {
        final LivingEntity entity;
        final List<RoutePoint> route;
        final double speed;
        final int stuckTimeoutTicks;
        int pointIndex = 0;
        int pauseLeft = 0;
        int stuckTicks = 0;
        Location lastPosition;

        RouteBody(LivingEntity entity, List<RoutePoint> route, double speed, int stuckTimeoutTicks) {
            this.entity = entity;
            this.route = route;
            this.speed = speed;
            this.stuckTimeoutTicks = stuckTimeoutTicks;
            this.lastPosition = entity.getLocation().clone();
        }

        @Override
        public boolean step(Instance instance) {
            if (entity.isDead() || !entity.isValid()) {
                report(instance, "цель недоступна (мертва или удалена)");
                return false;
            }
            if (pointIndex >= route.size()) {
                report(instance, "маршрут пройден полностью");
                return false;
            }
            RoutePoint rp = route.get(pointIndex);
            Location here = entity.getLocation();

            // Пауза на точке.
            if (pauseLeft > 0) {
                pauseLeft--;
                applyPointEffect(rp);
                return true;
            }

            double dist = here.distance(rp.location());
            if (dist < 0.7) {
                // Достигли точки: пауза и эффект.
                pointIndex++;
                pauseLeft = rp.pauseTicks();
                applyPointEffect(rp);
                return true;
            }

            // Проверка «застревания»: если позиция почти не менялась слишком долго — стоп.
            if (here.toVector().distanceSquared(lastPosition.toVector()) < 0.0025) {
                stuckTicks += Math.max(1, instance.task.periodTicks());
                if (stuckTicks >= stuckTimeoutTicks) {
                    report(instance, "цель застряла у точки " + (pointIndex + 1)
                            + " — маршрут прерван (повторы недопустимы)");
                    return false;
                }
            } else {
                stuckTicks = 0;
            }
            lastPosition = here.clone();

            // Управление через velocity без разрушений; для мобов дополнительно
            // задаём направление взгляда путём телепортации поворота (без перемещения).
            Vector dir = rp.location().toVector().subtract(here.toVector()).normalize();
            double vertical = rp.location().getY() > here.getY() + 0.6 ? 0.28 : 0.0;
            entity.setVelocity(new Vector(dir.getX() * speed, vertical, dir.getZ() * speed));
            if (!(entity instanceof Player)) {
                float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
                try {
                    entity.teleport(here.clone().setDirection(dir));
                    ((Mob) entity).setTarget(null);
                    double ignored = yaw; // направление взгляда задаётся setDirection
                } catch (IllegalStateException ignoredEx) {
                    // мир выгружен и т.п. — следующая проверка isValid остановит маршрут
                }
            }
            return true;
        }

        private void applyPointEffect(RoutePoint rp) {
            if (rp.effect() == null || rp.effect().isBlank()) {
                return;
            }
            switch (rp.effect().toLowerCase(java.util.Locale.ROOT)) {
                case "spark" -> plugin().particles().burst(rp.location().clone().add(0, 1, 0),
                        Particle.END_ROD, 20, 0.4, 0.05);
                case "flash" -> plugin().particles().colorBurst(rp.location().clone().add(0, 1, 0),
                        Particle.DUST, 30, 0.5, 0.0, ParticleService.AEON_WHITE);
                case "sound" -> plugin().sounds().play(rp.location(),
                        org.bukkit.Sound.BLOCK_NOTE_BLOCK_HARP, 1.0f, 1.0f);
                default -> {
                }
            }
        }

        private void report(Instance instance, String reason) {
            plugin().logDebug("Кукловод: маршрут для " + entity.getName() + " завершён: " + reason);
            if (instance.extra != null) {
                // extra используется как флаг отчёта, чтобы не спамить
            }
        }
    }
}
