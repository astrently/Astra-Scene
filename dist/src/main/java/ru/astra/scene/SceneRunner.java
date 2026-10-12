package ru.astra.scene;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import ru.astra.AstraPlugin;
import ru.astra.ability.Ability;
import ru.astra.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Выполнение шагов сцены по расписанию.
 * Реализует WAIT/REPEAT/CONDITION/BRANCH, темп («Вне времени»), паузу,
 * корректную остановку и очистку временных ресурсов.
 */
public final class SceneRunner {

    public enum State {
        RUNNING, PAUSED, FINISHED, STOPPED, FAILED
    }

    private static final int HARD_MAX_REPEATS = 64;
    private static final int MAX_NESTING = 8;

    private final AstraPlugin plugin;
    private final Scene scene;
    private final Player caster;
    private final List<Player> viewers;
    private final String runId;

    private State state = State.RUNNING;
    private double tempo = 1.0;
    private long startedAtTick;
    private long pausedAccumTicks;
    private long pauseStartTick = -1;

    // Курсор выполнения: стек (для REPEAT) и текущий индекс на каждом уровне.
    private final List<List<SceneStep>> stack = new ArrayList<>();
    private final List<int[]> cursors = new ArrayList<>();
    private int waitRemainingTicks = 0;

    private LivingEntity currentTarget;
    private Location targetPoint;
    private boolean conditionResult = true;
    private final List<String> stateKeys = new ArrayList<>();

    public SceneRunner(AstraPlugin plugin, Scene scene, Player caster, List<Player> viewers, String runId) {
        this.plugin = plugin;
        this.scene = scene;
        this.caster = caster;
        this.viewers = viewers == null ? new ArrayList<>() : new ArrayList<>(viewers);
        this.runId = runId;
        this.startedAtTick = Bukkit.getCurrentTick();
        this.stack.add(scene.steps());
        this.cursors.add(new int[]{0});
    }

    /** Вызывается из планировщика каждый тик. */
    public void tick() {
        if (state != State.RUNNING) {
            return;
        }
        if (waitRemainingTicks > 0) {
            waitRemainingTicks--;
            return;
        }
        // Проверка общей длительности.
        long elapsed = (Bukkit.getCurrentTick() - startedAtTick - pausedAccumTicks)
                + (pauseStartTick >= 0 ? 0 : 0);
        if (elapsed > plugin.config().maxSceneDurationSeconds * 20L) {
            stop("превышение максимальной длительности сцены");
            return;
        }
        if (stack.isEmpty()) {
            finish("последний шаг выполнен");
            return;
        }
        List<SceneStep> level = stack.get(stack.size() - 1);
        int[] cursor = cursors.get(cursors.size() - 1);
        if (cursor[0] >= level.size()) {
            popLevel();
            return;
        }
        SceneStep step = level.get(cursor[0]);
        cursor[0]++;
        try {
            step.execute(this);
        } catch (SceneStep.StepException ex) {
            fail("шаг " + step.type() + ": " + ex.getMessage());
        } catch (RuntimeException ex) {
            fail("шаг " + step.type() + ": внутренняя ошибка " + ex.getMessage());
            plugin.getLogger().severe("[сцена " + runId + "] ошибка шага '" + step.type() + "': " + ex);
        }
    }

    // ================= API для SceneStep =================

    public AstraPlugin plugin() {
        return plugin;
    }

    public String runId() {
        return runId;
    }

    public Scene scene() {
        return scene;
    }

    public State state() {
        return state;
    }

    public Player caster() {
        return caster;
    }

    public List<Player> viewers() {
        List<Player> live = new ArrayList<>();
        for (Player p : viewers) {
            if (p.isOnline()) {
                live.add(p);
            }
        }
        return live;
    }

    public int stepIndex() {
        if (cursors.isEmpty()) {
            return -1;
        }
        return cursors.get(cursors.size() - 1)[0];
    }

    public void waitTicks(int ticks) {
        waitRemainingTicks = (int) Math.max(0, Math.round(ticks / Math.max(0.05, tempo)));
    }

    public double tempo() {
        return tempo;
    }

    /** Изменение темпа во время выполнения — пересчитывает только оставшиеся задержки. */
    public void setTempo(double newTempo) {
        this.tempo = Text.clamp(newTempo, 0.25, 4.0);
    }

    public void pause() {
        if (state == State.RUNNING) {
            state = State.PAUSED;
            pauseStartTick = Bukkit.getCurrentTick();
        }
    }

    public void resume() {
        if (state == State.PAUSED) {
            pausedAccumTicks += Bukkit.getCurrentTick() - pauseStartTick;
            pauseStartTick = -1;
            state = State.RUNNING;
        }
    }

    public LivingEntity currentTarget() {
        if (currentTarget != null && !currentTarget.isDead() && currentTarget.isValid()) {
            return currentTarget;
        }
        return null;
    }

    public Location resolveAnchor() {
        if (currentTarget != null && currentTarget.isValid()) {
            return currentTarget.getLocation().clone().add(0, 1, 0);
        }
        if (targetPoint != null) {
            return targetPoint.clone();
        }
        if (caster != null && caster.isOnline()) {
            return caster.getLocation().clone().add(0, 1, 0);
        }
        return null;
    }

    // --- TARGET селекторы ---

    public void setTargetBearer() {
        Player bearer = plugin.roleManager().getBearerPlayer();
        if (bearer == null) {
            throw new SceneStep.StepException("TARGET: носитель Astra не назначен или не в сети");
        }
        currentTarget = bearer;
        targetPoint = null;
    }

    public void setTargetCaster() {
        if (caster == null || !caster.isOnline()) {
            throw new SceneStep.StepException("TARGET: инициатор сцены недоступен");
        }
        currentTarget = caster;
        targetPoint = null;
    }

    public void setTargetEntity(Player player) {
        currentTarget = player;
        targetPoint = null;
    }

    public void setTargetPoint(String worldName, String x, String y, String z) {
        Location loc = parseLocation(worldName, x, y, z);
        if (loc == null) {
            throw new SceneStep.StepException("TARGET point: некорректные координаты");
        }
        targetPoint = loc;
        currentTarget = null;
    }

    public void setTargetNearest(double radius) {
        Location anchor = targetPoint != null ? targetPoint
                : (caster != null && caster.isOnline() ? caster.getLocation() : null);
        if (anchor == null) {
            throw new SceneStep.StepException("TARGET nearest: нет точки отсчёта");
        }
        List<LivingEntity> near = plugin.targetSelector().livingNear(anchor, radius, 1);
        if (near.isEmpty()) {
            throw new SceneStep.StepException("TARGET nearest: цель не найдена в радиусе " + radius);
        }
        currentTarget = near.get(0);
    }

    public Location parseLocation(String worldName, String xs, String ys, String zs) {
        World w = Bukkit.getWorld(worldName);
        if (w == null) {
            return null;
        }
        try {
            return new Location(w, Double.parseDouble(xs), Double.parseDouble(ys), Double.parseDouble(zs));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Безопасное место приземления: твёрдый блок под ногами и два воздушных блока сверху. */
    public boolean isSafeLanding(Location dest) {
        World w = dest.getWorld();
        if (w == null) {
            return false;
        }
        Location below = dest.clone().subtract(0, 0.5, 0);
        if (!below.getBlock().isSolid() && below.getBlock().getType().isAir()) {
            return false;
        }
        Location feet = dest.clone();
        Location head = dest.clone().add(0, 1, 0);
        return feet.getBlock().getType().isAir() && head.getBlock().getType().isAir();
    }

    public String stateKey(String suffix) {
        String key = runId + "|" + suffix;
        stateKeys.add(key);
        return key;
    }

    public Ability.AbilityContext buildAbilityContext(String extra) {
        Location anchor = resolveAnchor();
        List<Player> vs = viewers.isEmpty() && anchor != null
                ? plugin.targetSelector().playersNear(anchor, 32, plugin.config().maxViewersPerScene)
                : viewers;
        return new Ability.AbilityContext(caster, anchor, vs, currentTarget, extra, extra);
    }

    // --- CONDITION / BRANCH / REPEAT ---

    /** CONDITION <check> [args...] — сохраняет результат для следующего BRANCH. */
    public void evaluateCondition(SceneStep step) {
        String check = step.arg(0).toLowerCase(Locale.ROOT);
        switch (check) {
            case "target_alive" -> conditionResult = currentTarget() != null;
            case "bearer_online" -> conditionResult = plugin.roleManager().getBearerPlayer() != null;
            case "caster_online" -> conditionResult = caster != null && caster.isOnline();
            case "viewers_at_least" -> conditionResult = viewers().size() >= (int) step.num(1, 1);
            case "has_target_point" -> conditionResult = targetPoint != null;
            default -> throw new SceneStep.StepException("CONDITION: неизвестная проверка '" + check + "'");
        }
    }

    /** BRANCH <steps-if-true> <steps-if-false> — форматируется как индексы уровней REPEAT-подобной структуры.
     *  Упрощённая семантика: аргументы — имена ярлыков шагов внутри children. */
    public void evaluateBranch(SceneStep step) {
        boolean takeTrue = conditionResult;
        String mode = step.arg(0).toLowerCase(Locale.ROOT);
        boolean useTrue = !"invert".equals(mode) ? takeTrue : !takeTrue;
        List<SceneStep> children = step.children();
        if (children.isEmpty()) {
            // Без вложенных шагов BRANCH просто пропускается.
            return;
        }
        // Аргумент arg(1) — индекс разделения: первые N детей выполняются при истине, остальные — при лжи.
        int split = (int) Text.clamp(step.num(1, children.size()), 0, children.size());
        List<SceneStep> branch = useTrue
                ? new ArrayList<>(children.subList(0, split))
                : new ArrayList<>(children.subList(split, children.size()));
        pushLevel(branch);
    }

    /** REPEAT <times> [ шаги ] — ограниченный повтор без бесконечных циклов. */
    public void executeRepeat(SceneStep step) {
        int times = (int) Text.clamp(step.num(0, 1), 0, HARD_MAX_REPEATS);
        if (times <= 0 || step.children().isEmpty()) {
            return;
        }
        if (stack.size() >= MAX_NESTING) {
            throw new SceneStep.StepException("REPEAT: превышена глубина вложенности (" + MAX_NESTING + ")");
        }
        // Разворачиваем повторение как последовательное копирование детей.
        List<SceneStep> expanded = new ArrayList<>(times * step.children().size());
        for (int i = 0; i < times; i++) {
            for (SceneStep child : step.children()) {
                expanded.add(new SceneStep(child.type(), new ArrayList<>(child.args()),
                        new ArrayList<>(child.children())));
            }
        }
        pushLevel(expanded);
    }

    private void pushLevel(List<SceneStep> steps) {
        stack.add(steps);
        cursors.add(new int[]{0});
    }

    private void popLevel() {
        if (stack.size() > 1) {
            stack.remove(stack.size() - 1);
            cursors.remove(cursors.size() - 1);
        } else {
            finish("все шаги выполнены");
        }
    }

    // --- завершение ---

    public void finishScene() {
        finish("шаг FINISH");
    }

    public void finish(String reason) {
        if (state == State.FINISHED || state == State.STOPPED || state == State.FAILED) {
            return;
        }
        cleanup();
        state = State.FINISHED;
        plugin.logDebug("Сцена " + runId + " завершена: " + reason);
    }

    public void stop(String reason) {
        if (state == State.FINISHED || state == State.STOPPED || state == State.FAILED) {
            return;
        }
        cleanup();
        state = State.STOPPED;
        plugin.logDebug("Сцена " + runId + " остановлена: " + reason);
        notifyAdmin("Сцена «" + scene.title() + "» (" + runId + ") остановлена: " + reason);
    }

    public void fail(String reason) {
        if (state == State.FINISHED || state == State.STOPPED || state == State.FAILED) {
            return;
        }
        cleanup();
        state = State.FAILED;
        plugin.getLogger().warning("[сцена " + runId + ", шаг " + stepIndex() + "] ошибка: " + reason);
        notifyAdmin("Ошибка сцены «" + scene.title() + "» (" + runId + "): " + reason);
    }

    /** Освобождение задач и временных ресурсов сцен. */
    private void cleanup() {
        for (String key : stateKeys) {
            try {
                plugin.tempState().removePotion(key);
                plugin.tempState().restoreGlow(key);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Не удалось очистить ресурс '" + key + "': " + ex.getMessage());
            }
        }
        stateKeys.clear();
    }

    private void notifyAdmin(String message) {
        ComponentUtil.broadcastAdmin(plugin, message);
    }

    /** Ход выполнения для /astra status. */
    public String progressLine() {
        int totalSteps = scene.steps().size();
        int done = cursors.isEmpty() ? 0 : cursors.get(0)[0];
        return String.format(Locale.ROOT, "%d/%d шагов, темп %.2fx", done, totalSteps, tempo);
    }

    /** Вспомогательный класс рассылки сообщений администраторам. */
    static final class ComponentUtil {
        static void broadcastAdmin(AstraPlugin plugin, String message) {
            var comp = Text.error(message);
            plugin.getServer().getConsoleSender().sendMessage(comp);
            for (Player p : plugin.getServer().getOnlinePlayers()) {
                if (p.hasPermission("astra.admin")) {
                    p.sendMessage(comp);
                }
            }
        }
    }
}
