package ru.astra.target;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import ru.astra.AstraPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Единый выбор целей: игроки, мобы, координаты и группы.
 * Радиус поиска целей и радиус отображения эффекта — разные параметры;
 * здесь используется только радиус поиска.
 */
public final class TargetSelector {

    private final AstraPlugin plugin;

    public TargetSelector(AstraPlugin plugin) {
        this.plugin = plugin;
    }

    /** Игроки в радиусе от точки (без самой точки-источника, если это игрок). */
    public List<Player> playersNear(Location center, double radius, int maxCount) {
        if (center.getWorld() == null) {
            return List.of();
        }
        List<Player> result = new ArrayList<>();
        for (Player p : center.getWorld().getPlayers()) {
            if (p.getLocation().getWorld() != center.getWorld()) {
                continue;
            }
            if (center.distanceSquared(p.getLocation()) <= radius * radius) {
                result.add(p);
            }
        }
        result.sort(Comparator.comparingDouble(p -> center.distanceSquared(p.getLocation())));
        if (result.size() > maxCount) {
            return new ArrayList<>(result.subList(0, maxCount));
        }
        return result;
    }

    /** Живые сущности в радиусе от точки. */
    public List<LivingEntity> livingNear(Location center, double radius, int maxCount) {
        if (center.getWorld() == null) {
            return List.of();
        }
        List<LivingEntity> result = new ArrayList<>();
        for (Entity e : center.getWorld().getNearbyEntities(center, radius, radius, radius)) {
            if (e instanceof LivingEntity le && !le.isDead()) {
                result.add(le);
            }
        }
        result.sort(Comparator.comparingDouble(e -> center.distanceSquared(e.getLocation())));
        if (result.size() > maxCount) {
            return new ArrayList<>(result.subList(0, maxCount));
        }
        return result;
    }

    /** Ближайшая живая сущность к точке взгляда игрока (для /astra cast ... на цель). */
    public LivingEntity lookTarget(Player viewer, double maxDistance) {
        Entity target = viewer.getTargetEntity((int) Math.min(200, Math.max(1, maxDistance)));
        if (target instanceof LivingEntity le && !le.isDead()) {
            if (viewer.getLocation().distanceSquared(le.getLocation()) <= maxDistance * maxDistance) {
                return le;
            }
        }
        return null;
    }

    /** Точка, на которую смотрит игрок (block ray trace), с ограничением дистанции. */
    public Location lookPoint(Player viewer, double maxDistance) {
        Location eye = viewer.getEyeLocation();
        var result = eye.getWorld() == null ? null
                : eye.getWorld().rayTraceBlocks(eye, eye.getDirection(), maxDistance,
                org.bukkit.FluidCollisionMode.NEVER, true);
        if (result != null && result.getHitPosition() != null) {
            return new Location(eye.getWorld(),
                    result.getHitPosition().getX(),
                    result.getHitPosition().getY(),
                    result.getHitPosition().getZ());
        }
        // Если блок не найден — точка на линии взгляда на полной дистанции.
        return eye.clone().add(eye.getDirection().clone().multiply(maxDistance));
    }
}
