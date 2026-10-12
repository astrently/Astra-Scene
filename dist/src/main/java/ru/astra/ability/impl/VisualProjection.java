package ru.astra.ability.impl;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import ru.astra.effect.ParticleService;

import java.util.ArrayList;
import java.util.List;

/**
 * Визуальная проекция (двойник) — упрощённый режим из частиц.
 * Полноценная фальшивая сущность, видимая отдельным зрителям, требует
 * отдельной пакетной реализации (см. раздел 10 ТЗ); здесь используется
 * только декоративный эффект, который честно обозначен как упрощённый.
 */
public final class VisualProjection {

    public Location position;
    public float yaw;
    public float pitch;
    public int color;
    public Particle particle;
    /** Истинно — видна всем; иначе персонально списку зрителей. */
    public final List<Player> personalViewers = new ArrayList<>();

    public VisualProjection(Location position, float yaw, int color, Particle particle) {
        this.position = position;
        this.yaw = yaw;
        this.pitch = 0f;
        this.color = color;
        this.particle = particle;
    }

    /** Отрисовка контура «фигуры»: основание, туловище, голова, направление взгляда. */
    public void render(ParticleService particles) {
        double x = position.getX();
        double y = position.getY();
        double z = position.getZ();
        Location world = position;
        // Основание (ноги)
        particles.ring(world.clone(), 0.35, particle, 8, color);
        // Туловище — вертикальные линии-рёбра
        for (int level = 0; level < 3; level++) {
            double ly = y + 0.4 + level * 0.45;
            particles.ring(new Location(world.getWorld(), x, ly, z), 0.32 - level * 0.03,
                    particle, 10, color);
        }
        // Голова
        particles.ring(new Location(world.getWorld(), x, y + 1.75, z), 0.18, particle, 8, color);
        particles.burst(new Location(world.getWorld(), x, y + 1.75, z), particle, 2, 0.1, 0.0);
        // Направление взгляда — короткая «линия лица»
        double rad = Math.toRadians(yaw);
        double fx = x - Math.sin(rad) * 0.6;
        double fz = z + Math.cos(rad) * 0.6;
        particles.line(new Location(world.getWorld(), x, y + 1.75, z),
                new Location(world.getWorld(), fx, y + 1.75, fz), particle, 3, color);
    }
}
