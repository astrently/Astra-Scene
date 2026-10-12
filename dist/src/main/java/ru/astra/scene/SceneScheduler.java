package ru.astra.scene;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Централизованный планировщик сцен.
 * Все повторяющиеся операции способностей и сцен обрабатываются здесь,
 * а не отдельными таймерами для каждого визуального элемента.
 * Поддерживает темп (speed multiplier) без создания лишних отложенных задач:
 * при изменении темпа пересчитывается расписание одной активной задачи.
 */
public final class SceneScheduler {

    /** Единица планирования: период в тиках и обработчик на каждый тик периода. */
    public static final class PeriodicTask {
        private final String name;
        private int periodTicks;
        private final Runnable perTick;
        private Object bukkitTask; // BukkitTask или RepeatingTask (Paper)
        private boolean cancelled;

        PeriodicTask(String name, int periodTicks, Runnable perTick) {
            this.name = name;
            this.periodTicks = Math.max(1, periodTicks);
            this.perTick = perTick;
        }

        public String name() {
            return name;
        }

        public int periodTicks() {
            return periodTicks;
        }
    }

    private final org.bukkit.plugin.Plugin owner;
    private final AtomicInteger taskCounter = new AtomicInteger();

    public SceneScheduler(org.bukkit.plugin.Plugin owner) {
        this.owner = owner;
    }

    /**
     * Запускает повторяющуюся задачу с указанным периодом (тики).
     * Возвращаемый объект позволяет безопасно остановить и перестроить расписание.
     */
    public PeriodicTask scheduleRepeating(int periodTicks, Runnable perTick) {
        PeriodicTask task = new PeriodicTask("astra-task-" + taskCounter.incrementAndGet(), periodTicks, perTick);
        start(task);
        return task;
    }

    private void start(PeriodicTask task) {
        if (task.cancelled) {
            return;
        }
        // Одна задача на период; внутренний счётчик тиков реализует интервал обновления.
        int[] tickCursor = {0};
        int period = task.periodTicks;
        task.bukkitTask = owner.getServer().getScheduler().runTaskTimer(owner, () -> {
            if (task.cancelled) {
                return;
            }
            if (tickCursor[0] % period == 0) {
                try {
                    task.perTick.run();
                } catch (RuntimeException ex) {
                    cancel(task);
                    throw ex;
                }
            }
            tickCursor[0]++;
        }, 0L, 1L);
    }

    /**
     * Изменяет темп выполнения задачи (умножение периода).
     * Не создаёт новых отложенных задач — пересоздаётся единственная активная.
     * @param tempo 1.0 = обычная скорость, 2.0 = вдвое быстрее, 0.5 = вдвое медленнее.
     */
    public void setTempo(PeriodicTask task, double tempo) {
        if (task.cancelled) {
            return;
        }
        cancelBukkit(task);
        task.periodTicks = Math.max(1, (int) Math.round(task.periodTicks / Math.max(0.05, tempo)));
        start(task);
    }

    /** Выполнить действие через N тиков (одноразово). */
    public void scheduleOnce(int delayTicks, Runnable action) {
        owner.getServer().getScheduler().runTaskLater(owner, () -> {
            try {
                action.run();
            } catch (RuntimeException ex) {
                owner.getLogger().severe("Ошибка одноразовой задачи Astra: " + ex.getMessage());
            }
        }, Math.max(0, delayTicks));
    }

    public void cancel(PeriodicTask task) {
        task.cancelled = true;
        cancelBukkit(task);
    }

    private void cancelBukkit(PeriodicTask task) {
        if (task.bukkitTask instanceof org.bukkit.scheduler.BukkitTask bt) {
            try {
                bt.cancel();
            } catch (Exception ignored) {
            }
        }
        task.bukkitTask = null;
    }

    /** Остановить всё (выключение плагина). */
    public void shutdown() {
        owner.getServer().getScheduler().cancelTasks(owner);
    }
}
