package ru.astra.ability.impl;

import ru.astra.AstraPlugin;
import ru.astra.ability.Ability;
import ru.astra.scene.SceneScheduler;

import java.util.ArrayList;
import java.util.List;

/**
 * Базовый класс способности, состоящей из повторяющихся задач-экземпляров.
 * Все задачи регистрируются в централизованном планировщике Astra;
 * остановка способности штатно отменяет все её задачи и не оставляет эффектов.
 */
public abstract class TaskAbility extends Ability {

    /** Экземпляр выполнения: периодическая задача + счётчик оставшихся тиков. */
    protected final class Instance {
        public SceneScheduler.PeriodicTask task;
        public int ticksLeft;
        /** Дополнительный ресурс (например, список сущностей для удаления). */
        public Object extra;

        void end() {
            if (task != null) {
                plugin().scheduler().cancel(task);
                task = null;
            }
            onInstanceEnd(this);
            instances.remove(this);
        }
    }

    protected final List<Instance> instances = new ArrayList<>();

    protected TaskAbility(AstraPlugin plugin, String id, String displayName, String description,
                          RepeatPolicy repeatPolicy) {
        super(plugin, id, displayName, description, repeatPolicy);
    }

    /** Создание экземпляра и запуск периодической задачи через тело выполнения. */
    protected Instance launch(int periodTicks, int durationTicks, Body body) {
        if (repeatPolicy() == RepeatPolicy.RESTART_CURRENT && isRunning()) {
            stopAll("перезапуск");
        }
        Instance inst = new Instance();
        inst.ticksLeft = durationTicks;
        int period = Math.max(1, periodTicks);
        inst.task = plugin().scheduler().scheduleRepeating(period, () -> {
            if (inst.ticksLeft <= 0 || !body.step(inst)) {
                inst.end();
                return;
            }
            inst.ticksLeft -= period;
        });
        instances.add(inst);
        return inst;
    }

    /** Тело выполнения: вызывается на каждом обновлении; false — завершить экземпляр. */
    protected interface Body {
        boolean step(Instance instance);
    }

    /** Освобождение дополнительных ресурсов экземпляра (сущности, состояния). */
    protected void onInstanceEnd(Instance instance) {
    }

    @Override
    public void stopAll(String reason) {
        for (Instance inst : new ArrayList<>(instances)) {
            inst.end();
        }
        instances.clear();
    }

    @Override
    public boolean isRunning() {
        return !instances.isEmpty();
    }

    @Override
    public int activeCount() {
        return instances.size();
    }
}
