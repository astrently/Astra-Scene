package ru.astra.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import ru.astra.AstraPlugin;
import ru.astra.util.Text;

/**
 * Централизованная конфигурация с валидацией.
 * При ошибочном значении используется безопасное значение по умолчанию,
 * о чём сообщается в журнал.
 */
public final class ConfigManager {

    private static final int HARD_MAX_PARTICLES = 2000;

    // settings
    public boolean debug;
    public int maxActiveScenes;
    public int maxSceneDurationSeconds;
    public int maxViewersPerScene;
    public boolean stopScenesOnDisable;
    public String onBearerQuit;      // keep | stop
    public String onBearerWorldChange; // continue | stop
    public int maxParticlesPerBurst;
    public int maxPuppetRoutes;
    public String defaultRolePermission;

    private final AstraPlugin plugin;

    public ConfigManager(AstraPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        debug = c.getBoolean("settings.debug", false);
        maxActiveScenes = positiveInt(c, "settings.max-active-scenes", 20, 1, 200);
        maxSceneDurationSeconds = positiveInt(c, "settings.max-scene-duration-seconds", 300, 5, 3600);
        maxViewersPerScene = positiveInt(c, "settings.max-viewers-per-scene", 50, 1, 500);
        stopScenesOnDisable = c.getBoolean("settings.stop-scenes-on-plugin-disable", true);
        onBearerQuit = normalizeChoice(c.getString("settings.on-bearer-quit", "keep"), "stop", "keep");
        onBearerWorldChange = normalizeChoice(c.getString("settings.on-bearer-world-change", "continue"), "stop", "continue");
        maxParticlesPerBurst = positiveInt(c, "settings.max-particles-per-burst", 200, 1, HARD_MAX_PARTICLES);
        maxPuppetRoutes = positiveInt(c, "settings.max-puppet-routes", 16, 1, 128);
        defaultRolePermission = c.getString("permissions.default-role-permission", "astra.use");
    }

    /** Безопасное ограничение числа зрителей (защита от 0/отрицательных значений). */
    public int maxViewersPerSceneSafe() {
        return Math.max(1, maxViewersPerScene);
    }

    /** Доступ к произвольному разделу способностей. */
    public ConfigurationSection ability(String id) {
        return plugin.getConfig().getConfigurationSection("abilities." + id);
    }

    /** Включена ли способность (по умолчанию — включена). */
    public boolean isEnabled(String abilityId) {
        ConfigurationSection sec = ability(abilityId);
        return sec == null || sec.getBoolean("enabled", true);
    }

    /** Безопасный целочисленный параметр раздела с ограничением диапазона. */
    public int sectionInt(ConfigurationSection sec, String path, int def, int min, int max) {
        if (sec == null) {
            return def;
        }
        int raw = sec.getInt(path, def);
        if (raw < min || raw > max) {
            plugin.getLogger().warning("Некорректное значение abilities.'" + path + "' = " + raw
                    + ". Использовано безопасное значение по умолчанию: " + def);
            return def;
        }
        return raw;
    }

    /** Безопасный вещественный параметр раздела с ограничением диапазона. */
    public double sectionDouble(ConfigurationSection sec, String path, double def, double min, double max) {
        if (sec == null) {
            return def;
        }
        double raw = sec.getDouble(path, def);
        if (Double.isNaN(raw) || raw < min || raw > max) {
            plugin.getLogger().warning("Некорректное значение abilities.'" + path + "' = " + raw
                    + ". Использовано безопасное значение по умолчанию: " + def);
            return def;
        }
        return raw;
    }

    public boolean sectionBool(ConfigurationSection sec, String path, boolean def) {
        return sec == null ? def : sec.getBoolean(path, def);
    }

    public String sectionString(ConfigurationSection sec, String path, String def) {
        if (sec == null) {
            return def;
        }
        String v = sec.getString(path, def);
        return v == null || v.isBlank() ? def : v.trim();
    }

    private int positiveInt(FileConfiguration c, String path, int def, int min, int max) {
        int raw = c.getInt(path, def);
        if (raw < min || raw > max) {
            plugin.getLogger().warning("Некорректное значение '" + path + "' = " + raw
                    + ". Использовано безопасное значение по умолчанию: " + def);
            return def;
        }
        return raw;
    }

    private String normalizeChoice(String raw, String... allowed) {
        String v = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
        for (String a : allowed) {
            if (a.equals(v)) {
                return v;
            }
        }
        plugin.getLogger().warning("Некорректный выбор значения '" + raw + "'. Использовано: " + allowed[allowed.length - 1]);
        return allowed[allowed.length - 1];
    }
}
