package ru.astra;

import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ru.astra.ability.AbilityRegistry;
import ru.astra.command.AstraCommand;
import ru.astra.command.TabCompleter;
import ru.astra.config.ConfigManager;
import ru.astra.effect.ParticleService;
import ru.astra.effect.SoundService;
import ru.astra.menu.MainMenu;
import ru.astra.menu.SceneEditorMenu;
import ru.astra.role.AstraRoleManager;
import ru.astra.scene.SceneManager;
import ru.astra.scene.SceneScheduler;
import ru.astra.scene.SceneValidator;
import ru.astra.state.TemporaryStateManager;
import ru.astra.target.TargetSelector;

/**
 * Astra — серверный плагин-инструментарий постановщика для Paper 1.21.11.
 * Способности, иллюзии, управление существами и пользовательские сцены.
 */
public class AstraPlugin extends JavaPlugin {

    private static AstraPlugin instance;

    private ConfigManager configManager;
    private SceneScheduler sceneScheduler;
    private ParticleService particleService;
    private SoundService soundService;
    private TargetSelector targetSelector;
    private TemporaryStateManager tempState;
    private AstraRoleManager roleManager;
    private AbilityRegistry abilityRegistry;
    private SceneManager sceneManager;
    private SceneValidator sceneValidator;
    private MainMenu mainMenu;
    private SceneEditorMenu sceneEditorMenu;

    @Override
    public void onEnable() {
        instance = this;

        configManager = new ConfigManager(this);
        configManager.load();

        sceneScheduler = new SceneScheduler(this);
        particleService = new ParticleService(this);
        soundService = new SoundService(this);
        targetSelector = new TargetSelector(this);
        tempState = new TemporaryStateManager(this);

        roleManager = new AstraRoleManager(this);
        getServer().getPluginManager().registerEvents(roleManager, this);
        roleManager.load();

        abilityRegistry = new AbilityRegistry(this);

        sceneValidator = new SceneValidator(this);
        sceneManager = new SceneManager(this);
        sceneManager.init();

        mainMenu = new MainMenu(this);
        sceneEditorMenu = new SceneEditorMenu(this);
        getServer().getPluginManager().registerEvents(mainMenu, this);
        getServer().getPluginManager().registerEvents(sceneEditorMenu, this);

        AstraCommand command = new AstraCommand(this);
        PluginCommand pc = getCommand("astra");
        if (pc != null) {
            pc.setExecutor(command);
            pc.setTabCompleter(new TabCompleter(this));
        } else {
            getLogger().severe("Команда /astra не зарегистрирована в plugin.yml!");
        }

        getLogger().info("Astra активна: " + abilityRegistry.all().size()
                + " способностей, сохранённых сцен: " + sceneManager.saved().size());
    }

    @Override
    public void onDisable() {
        // Корректное выключение: останавливаем сцены, способности, задачи, очищаем временные ресурсы.
        try {
            if (sceneManager != null && configManager != null && configManager.stopScenesOnDisable) {
                sceneManager.shutdown();
            } else if (sceneManager != null) {
                sceneManager.shutdown();
            }
        } catch (RuntimeException ex) {
            getLogger().warning("Ошибка при остановке сцен: " + ex.getMessage());
        }
        try {
            if (abilityRegistry != null) {
                abilityRegistry.stopAll("выключение плагина");
            }
        } catch (RuntimeException ex) {
            getLogger().warning("Ошибка при остановке способностей: " + ex.getMessage());
        }
        try {
            if (tempState != null) {
                tempState.clearAll();
            }
        } catch (RuntimeException ex) {
            getLogger().warning("Ошибка при очистке временных состояний: " + ex.getMessage());
        }
        try {
            if (mainMenu != null) {
                for (Player p : getServer().getOnlinePlayers()) {
                    if (p.getOpenInventory().getTopInventory().getHolder() instanceof ru.astra.menu.MenuHolder) {
                        p.closeInventory();
                    }
                }
            }
        } catch (RuntimeException ignored) {
        }
        if (sceneScheduler != null) {
            sceneScheduler.shutdown();
        }
        getLogger().info("Astra выключена; все задачи остановлены.");
    }

    /** Перезагрузка конфигурации с безопасным поведением активных сцен. */
    public void reloadSafely() {
        configManager.load();
        getLogger().info("Конфигурация Astra перезагружена; активные сцены ("
                + sceneManager.active().size() + ") продолжают выполнение без сброса состояния.");
    }

    // ==================== Геттеры сервисов ====================

    public static AstraPlugin get() {
        return instance;
    }

    public ConfigManager config() {
        return configManager;
    }

    public SceneScheduler scheduler() {
        return sceneScheduler;
    }

    public ParticleService particles() {
        return particleService;
    }

    public SoundService sounds() {
        return soundService;
    }

    public TargetSelector targetSelector() {
        return targetSelector;
    }

    public TemporaryStateManager tempState() {
        return tempState;
    }

    public AstraRoleManager roleManager() {
        return roleManager;
    }

    public AbilityRegistry abilities() {
        return abilityRegistry;
    }

    public SceneManager sceneManager() {
        return sceneManager;
    }

    public SceneValidator sceneValidator() {
        return sceneValidator;
    }

    public MainMenu mainMenu() {
        return mainMenu;
    }

    public SceneEditorMenu sceneEditor() {
        return sceneEditorMenu;
    }

    public void logDebug(String message) {
        if (configManager != null && configManager.debug) {
            getLogger().info("[debug] " + message);
        }
    }
}
