package ru.astra.effect;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import ru.astra.AstraPlugin;

import java.util.Collection;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Единый слой создания визуальных эффектов.
 * Все методы потокобезопасно вызываются только из главного потока Bukkit.
 * Количество частиц ограничено конфигурацией (защита производительности).
 */
public final class ParticleService {

    /** Стандартная палитра «Явления Эона»: только белый и красный. */
    public static final int AEON_WHITE = 0xFFFFFF;
    public static final int AEON_RED = 0xFF2A2A;

    private final AstraPlugin plugin;

    public ParticleService(AstraPlugin plugin) {
        this.plugin = plugin;
    }

    private int clampCount(int count) {
        return Math.max(1, Math.min(count, plugin.config().maxParticlesPerBurst));
    }

    /** Публичная партия частиц всем поблизости. */
    public void burst(Location loc, Particle particle, int count, double spread, double speed) {
        World w = loc.getWorld();
        if (w == null) {
            return;
        }
        // Цветные Dust-частицы требуют DustOptions: без них Paper бросает
        // IllegalArgumentException ("particle requires data") и обрывает этап сцены.
        if (isColored(particle)) {
            colorBurst(loc, particle, count, spread, speed, AEON_WHITE);
            return;
        }
        w.spawnParticle(particle, loc, clampCount(count), spread, spread, spread, speed);
    }

    /** Персональные частицы только указанным зрителям. */
    public void burstTo(Collection<? extends Player> viewers, Location loc, Particle particle,
                        int count, double spread, double speed) {
        if (isColored(particle)) {
            colorBurstTo(viewers, loc, particle, count, spread, speed, AEON_WHITE);
            return;
        }
        int c = clampCount(count);
        for (Player p : viewers) {
            p.spawnParticle(particle, loc, c, spread, spread, spread, speed);
        }
    }

    /** Цветная партия (DUST / DUST_COLOR_TRANSITION). */
    public void colorBurst(Location loc, Particle particle, int count, double spread, double speed, int rgb) {
        World w = loc.getWorld();
        if (w == null) {
            return;
        }
        w.spawnParticle(particle, loc, clampCount(count), spread, spread, spread,
                dustOptions(rgb));
    }

    /** Цвет Dust-частиц из RGB-int (бумага 1.21.x: DustOptions(Color, size)). */
    public static Particle.DustOptions dustOptions(int rgb) {
        return new Particle.DustOptions(org.bukkit.Color.fromRGB(rgb & 0xFFFFFF), 1.0f);
    }

    public void colorBurstTo(Collection<? extends Player> viewers, Location loc, Particle particle,
                             int count, double spread, double speed, int rgb) {
        Particle.DustOptions options = dustOptions(rgb);
        int c = clampCount(count);
        for (Player p : viewers) {
            p.spawnParticle(particle, loc, c, spread, spread, spread, options);
        }
    }

    /** Линия из частиц между двумя точками (плотность ограничена). */
    public void line(Location from, Location to, Particle particle, double densityPerBlock, int rgb) {
        World w = to.getWorld();
        if (w == null || !w.equals(from.getWorld())) {
            return;
        }
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length < 0.01) {
            return;
        }
        int steps = clampCount((int) Math.ceil(length * Math.max(0.5, densityPerBlock)));
        boolean colored = isColored(particle);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Location point = new Location(w,
                    from.getX() + dx * t,
                    from.getY() + dy * t,
                    from.getZ() + dz * t);
            if (colored) {
                colorBurst(point, particle, 1, 0.0, 0.0, rgb);
            } else {
                w.spawnParticle(particle, point, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /** Окружность из частиц в горизонтальной плоскости. */
    public void ring(Location center, double radius, Particle particle, int points, int rgb) {
        World w = center.getWorld();
        if (w == null) {
            return;
        }
        int n = clampCount(points);
        boolean colored = isColored(particle);
        for (int i = 0; i < n; i++) {
            double angle = (2 * Math.PI * i) / n;
            Location p = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
            if (colored) {
                colorBurst(p, particle, 1, 0.0, 0.0, rgb);
            } else {
                w.spawnParticle(particle, p, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /** Вертикальный столб из частиц над точкой. */
    public void column(Location base, double height, Particle particle, int perLevel, int rgb) {
        World w = base.getWorld();
        if (w == null) {
            return;
        }
        int levels = clampCount((int) (height * 2));
        boolean colored = isColored(particle);
        for (int i = 0; i <= levels; i++) {
            double y = base.getY() + (height * i) / Math.max(1, levels);
            Location p = new Location(w, base.getX(), y, base.getZ());
            int count = clampCount(perLevel);
            if (colored) {
                colorBurst(p, particle, count, 0.15, 0.0, rgb);
            } else {
                w.spawnParticle(particle, p, count, 0.15, 0.0, 0.15, 0.0);
            }
        }
    }

    /** Случайная сфера из частиц вокруг точки. */
    public void sphere(Location center, double radius, Particle particle, int count, int rgb) {
        World w = center.getWorld();
        if (w == null) {
            return;
        }
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        int n = clampCount(count);
        boolean colored = isColored(particle);
        for (int i = 0; i < n; i++) {
            double x = center.getX() + (rnd.nextDouble(-1, 1) * radius);
            double y = center.getY() + (rnd.nextDouble(-1, 1) * radius);
            double z = center.getZ() + (rnd.nextDouble(-1, 1) * radius);
            Location p = new Location(w, x, y, z);
            if (colored) {
                colorBurst(p, particle, 1, 0.0, 0.0, rgb);
            } else {
                w.spawnParticle(particle, p, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    private static boolean isColored(Particle particle) {
        return particle == Particle.DUST || particle == Particle.DUST_COLOR_TRANSITION;
    }
}
