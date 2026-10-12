package ru.astra.role;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ru.astra.AstraPlugin;
import ru.astra.util.Text;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Менеджер носителя Astra.
 * Носитель определяется по UUID и сохраняется между перезапусками сервера.
 * Факт назначения НЕ предоставляет административных прав — права проверяются
 * отдельно через систему разрешений сервера.
 */
public final class AstraRoleManager implements Listener {

    private static final String KEY = "bearer-uuid";

    private final AstraPlugin plugin;
    private final File store;
    private UUID bearerUuid;

    public AstraRoleManager(AstraPlugin plugin) {
        this.plugin = plugin;
        this.store = new File(plugin.getDataFolder(), "role.yml");
    }

    /** Загрузка сохранённого носителя при старте плагина. */
    public void load() {
        if (!store.exists()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(store);
        String raw = yml.getString(KEY, null);
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            bearerUuid = UUID.fromString(raw);
            Player online = Bukkit.getPlayer(bearerUuid);
            if (online != null) {
                applyVisuals(online);
            }
            plugin.getLogger().info("Восстановлен носитель Astra: " + raw);
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Некорректный сохранённый UUID носителя: " + raw);
            bearerUuid = null;
        }
    }

    public UUID getBearerUuid() {
        return bearerUuid;
    }

    public boolean isBearer(Player player) {
        return bearerUuid != null && player.getUniqueId().equals(bearerUuid);
    }

    public Player getBearerPlayer() {
        return bearerUuid == null ? null : Bukkit.getPlayer(bearerUuid);
    }

    /** Назначение носителя. Повторное назначение не создаёт дубликатов эффектов. */
    public void setBearer(Player player) {
        // Сначала снимаем предыдущего носителя без побочных задач.
        clearBearer(false);
        bearerUuid = player.getUniqueId();
        save();
        applyVisuals(player);
        plugin.getServer().getConsoleSender().sendMessage(
                Text.ok("Носителем Astra назначен игрок " + player.getName()));
    }

    /** Снятие носителя. Не оставляет временных эффектов и активных задач. */
    public void removeBearer() {
        clearBearer(true);
        save();
    }

    private void clearBearer(boolean notify) {
        Player old = getBearerPlayer();
        if (old != null) {
            old.setGlowing(false);
            old.removePotionEffect(org.bukkit.potion.PotionEffectType.GLOWING);
        }
        bearerUuid = null;
        if (notify) {
            plugin.getServer().getConsoleSender().sendMessage(Text.msg("Статус носителя Astra снят."));
        }
    }

    private void applyVisuals(Player player) {
        // Минимальный опознавательный знак; не изменяет инвентарь и постоянные данные.
        player.setGlowing(true);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (bearerUuid != null && event.getPlayer().getUniqueId().equals(bearerUuid)) {
            applyVisuals(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (!isBearer(event.getPlayer())) {
            return;
        }
        // Обработка связанных сцен в соответствии с настройкой.
        if ("stop".equals(plugin.config().onBearerQuit)) {
            plugin.sceneManager().stopAllBy("выход носителя Astra");
        } else {
            plugin.logDebug("Носитель вышел; сцены продолжают выполнение согласно настройке on-bearer-quit=keep.");
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (!isBearer(event.getPlayer())) {
            return;
        }
        if ("stop".equals(plugin.config().onBearerWorldChange)) {
            plugin.sceneManager().stopAllBy("смена мира носителем Astra");
            plugin.getLogger().info("Сцены остановлены: носитель сменил мир ("
                    + event.getFrom().getName() + " -> " + event.getPlayer().getWorld().getName() + ")");
        } else {
            // continue: эффекты персональны и привязаны к цели/точкам, а не к миру носителя.
            plugin.logDebug("Носитель сменил мир; сцены продолжаются согласно настройке on-bearer-world-change=continue.");
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        if (bearerUuid != null) {
            yml.set(KEY, bearerUuid.toString());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(store);
        } catch (IOException ex) {
            plugin.getLogger().severe("Не удалось сохранить статус носителя: " + ex.getMessage());
        }
    }
}
