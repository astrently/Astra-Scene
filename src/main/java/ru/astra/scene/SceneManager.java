package ru.astra.scene;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ru.astra.AstraPlugin;
import ru.astra.util.Text;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Хранение сохранённых сцен, запуск и остановка активных выполнений.
 * Выполнения сцен обрабатываются единой повторяющейся задачей планировщика
 * (централизованный планировщик), а не отдельным таймером на каждую сцену.
 */
public final class SceneManager {

    private final AstraPlugin plugin;
    private final Map<String, Scene> savedScenes = new LinkedHashMap<>();
    private final Map<String, SceneRunner> activeRuns = new LinkedHashMap<>();
    private final AtomicInteger runCounter = new AtomicInteger();
    private SceneScheduler.PeriodicTask heartbeat;
    private File storeFile;

    public SceneManager(AstraPlugin plugin) {
        this.plugin = plugin;
    }

    /** Инициализация: загрузка сохранённых сцен и создание эталонной red_revelation. */
    public void init() {
        storeFile = new File(plugin.getDataFolder(), "scenes.yml");
        load();
        startHeartbeat();
    }

    private void startHeartbeat() {
        if (heartbeat != null) {
            return;
        }
        heartbeat = plugin.scheduler().scheduleRepeating(1, () -> {
            List<String> finished = new ArrayList<>();
            for (Map.Entry<String, SceneRunner> e : new ArrayList<>(activeRuns.entrySet())) {
                SceneRunner runner = e.getValue();
                switch (runner.state()) {
                    case RUNNING -> runner.tick();
                    case FINISHED, STOPPED, FAILED -> finished.add(e.getKey());
                    default -> {
                    }
                }
            }
            for (String id : finished) {
                activeRuns.remove(id);
            }
        });
    }

    // ==================== Сохранение/загрузка ====================

    public void load() {
        savedScenes.clear();
        if (!storeFile.exists()) {
            createDefaultExample();
            saveAll();
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(storeFile);
        ConfigurationSection scenes = yml.getConfigurationSection("scenes");
        if (scenes != null) {
            for (String id : scenes.getKeys(false)) {
                ConfigurationSection sec = scenes.getConfigurationSection(id);
                if (sec == null) {
                    continue;
                }
                Scene scene = new Scene(id, sec.getString("title", id), sec.getString("description", ""));
                for (String line : sec.getStringList("steps")) {
                    SceneStep step = Scene.parseStepLine(line);
                    if (step != null) {
                        scene.addStep(step);
                    } else {
                        plugin.getLogger().warning("Сцена '" + id + "': пропущен неразбираемый шаг '" + line + "'");
                    }
                }
                savedScenes.put(id, scene);
            }
        }
    }

    public void saveAll() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Scene scene : savedScenes.values()) {
            String base = "scenes." + scene.id();
            yml.set(base + ".title", scene.title());
            yml.set(base + ".description", scene.description());
            yml.set(base + ".steps", scene.serializeSteps());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(storeFile);
        } catch (IOException ex) {
            plugin.getLogger().severe("Не удалось сохранить сцены: " + ex.getMessage());
        }
    }

    /** Эталонная сцена из ТЗ (пример структуры). */
    private void createDefaultExample() {
        Scene example = new Scene("red_revelation", "Красное откровение",
                "Пример пользовательской сцены Astra: напряжённое появление в бело-красной палитре.");
        example.steps().add(new SceneStep(SceneStep.Type.TARGET,
                List.of("point", "world", "~", "~", "~"), List.of()));
        example.steps().add(new SceneStep(SceneStep.Type.WAIT, List.of("2"), List.of()));
        example.steps().add(new SceneStep(SceneStep.Type.PARTICLE,
                List.of("DUST", "20", "2", "#FFFFFF"), List.of()));
        example.steps().add(new SceneStep(SceneStep.Type.WAIT, List.of("1"), List.of()));
        example.steps().add(new SceneStep(SceneStep.Type.PARTICLE,
                List.of("DUST", "60", "3", "#FF2A2A"), List.of()));
        example.steps().add(new SceneStep(SceneStep.Type.SOUND,
                List.of("ENTITY_FIREWORK_ROCKET_BLAST", "1", "0.8"), List.of()));
        example.steps().add(new SceneStep(SceneStep.Type.ABILITY, List.of("masquerade"), List.of()));
        example.steps().add(new SceneStep(SceneStep.Type.WAIT, List.of("3"), List.of()));
        example.steps().add(new SceneStep(SceneStep.Type.REPEAT, List.of("4"),
                List.of(new SceneStep(SceneStep.Type.PARTICLE, List.of("END_ROD", "30", "4", ""), List.of()),
                        new SceneStep(SceneStep.Type.PARTICLE, List.of("DUST", "30", "4", "#FF2A2A"), List.of()),
                        new SceneStep(SceneStep.Type.WAIT, List.of("0.5"), List.of()))));
        example.steps().add(new SceneStep(SceneStep.Type.FINISH, List.of(), List.of()));
        savedScenes.put(example.id(), example);
    }

    // ==================== Управление ====================

    public Map<String, Scene> saved() {
        return savedScenes;
    }

    public Scene get(String id) {
        return savedScenes.get(id.toLowerCase(Locale.ROOT));
    }

    public boolean exists(String id) {
        return savedScenes.containsKey(id.toLowerCase(Locale.ROOT));
    }

    public static boolean validId(String id) {
        if (id == null || id.length() < 3 || id.length() > 32) {
            return false;
        }
        for (char c : id.toCharArray()) {
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return false;
            }
        }
        return true;
    }

    public Scene create(String id, String title, String description) {
        String key = id.toLowerCase(Locale.ROOT);
        Scene scene = new Scene(key, title, description);
        savedScenes.put(key, scene);
        return scene;
    }

    public boolean delete(String id) {
        return savedScenes.remove(id.toLowerCase(Locale.ROOT)) != null;
    }

    /** Результат попытки запуска сцены. */
    public record StartResult(boolean ok, String runId, String message) {
    }

    /** Запуск сохранённой сцены (удобная обёртка для меню). */
    public StartResult start(Scene scene, org.bukkit.Location anchor, Player caster,
                             List<Player> viewers, Object ignoredTarget,
                             String mode, Player feedbackTo) {
        String runId = start(scene, caster, viewers, CommandFeedback.to(
                feedbackTo != null ? feedbackTo : caster));
        return runId == null
                ? new StartResult(false, null, "Сцена не запущена (см. сообщения проверки).")
                : new StartResult(true, runId, "Сцена запущена: " + runId);
    }

    /** Запуск сохранённой сцены. Возвращает идентификатор выполнения или null при отказе. */
    public String start(Scene scene, Player caster, List<Player> viewers, CommandFeedback feedback) {
        List<SceneValidator.Issue> issues = plugin.sceneValidator().validate(scene);
        if (!issues.isEmpty()) {
            for (SceneValidator.Issue issue : issues) {
                feedback.error("Проверка сцены не пройдена — " + issue);
            }
            return null;
        }
        if (activeRuns.size() >= plugin.config().maxActiveScenes) {
            feedback.error("Достигнут лимит активных сцен (" + plugin.config().maxActiveScenes + ").");
            return null;
        }
        String runId = scene.id() + "#" + runCounter.incrementAndGet();
        SceneRunner runner = new SceneRunner(plugin, scene, caster, viewers, runId);
        activeRuns.put(runId, runner);
        plugin.logDebug("Запущена сцена " + runId);
        return runId;
    }

    public boolean stopRun(String runId, String reason) {
        SceneRunner runner = activeRuns.get(runId);
        if (runner == null) {
            return false;
        }
        runner.stop(reason);
        activeRuns.remove(runId);
        return true;
    }

    /** Остановить все активные сцены и выполнить очистку временных ресурсов. */
    public int stopAll(String reason) {
        int count = 0;
        for (SceneRunner runner : new ArrayList<>(activeRuns.values())) {
            runner.stop(reason);
            count++;
        }
        activeRuns.clear();
        plugin.tempState().clearAll();
        return count;
    }

    public int stopAllBy(String reason) {
        return stopAll(reason);
    }

    public Map<String, SceneRunner> active() {
        return activeRuns;
    }

    public void shutdown() {
        stopAll("выключение плагина");
        if (heartbeat != null) {
            plugin.scheduler().cancel(heartbeat);
            heartbeat = null;
        }
    }

    /** Обратная связь команд (чтобы не тянуть зависимость от отправителя вглубь). */
    public interface CommandFeedback {
        void error(String message);

        static CommandFeedback to(Player player) {
            return message -> Text.send(player, Text.error(message));
        }

        static CommandFeedback console() {
            return message -> Bukkit.getConsoleSender().sendMessage(Text.error(message));
        }
    }
}
