package ru.astra.effect;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import ru.astra.AstraPlugin;

import java.util.Collection;

/**
 * Единый слой звуковых эффектов.
 * Звук действует в заданном радиусе; глобальное воспроизведение
 * допускается только при явном включённом режиме.
 */
public final class SoundService {

    private final AstraPlugin plugin;

    public SoundService(AstraPlugin plugin) {
        this.plugin = plugin;
    }

    /** Звук в точке для всех слушателей поблизости (стандартное затухание Minecraft). */
    public void play(Location loc, Sound sound, float volume, float pitch) {
        World w = loc.getWorld();
        if (w == null) {
            return;
        }
        w.playSound(loc, sound, volume, pitch);
    }

    /** Персональный звук только выбранным зрителям. */
    public void playTo(Collection<? extends Player> viewers, Location loc, Sound sound, float volume, float pitch) {
        for (Player p : viewers) {
            p.playSound(p.getLocation(), sound, volume, pitch);
        }
    }

    /** Глобальный звук: только при явно включённом режиме. */
    public void playGlobal(Sound sound, float volume, float pitch) {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            p.playSound(p.getLocation(), sound, volume, pitch);
        }
    }
}
