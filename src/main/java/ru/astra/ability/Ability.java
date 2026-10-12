package ru.astra.ability;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import ru.astra.AstraPlugin;

import java.util.List;

/**
 * Базовая способность Astra.
 * Каждая способность имеет уникальный идентификатор, название, описание,
 * параметры запуска (AbilityContext), выбор целей, проверку условий,
 * состояние выполнения, штатную остановку и очистку временных эффектов.
 */
public abstract class Ability {

    /** Режим повторного запуска, если способность уже выполняется. */
    public enum RepeatPolicy {
        ALLOW_MULTIPLE,   // разрешить несколько экземпляров
        RESTART_CURRENT,  // перезапустить текущий
        REJECT            // отклонить повторный запуск
    }

    /** Контекст запуска способности. */
    public static final class AbilityContext {
        public final Player caster;              // кто запускает (может быть null для консоли)
        public final Location point;             // точка привязки эффекта
        public final List<Player> viewers;       // зрители/участники (могут быть пустыми)
        public final org.bukkit.entity.LivingEntity target; // основная цель (может быть null)
        public final String mode;                // дополнительный режим строкой
        public final String arguments;           // сырая строка аргументов команды

        public AbilityContext(Player caster, Location point, List<Player> viewers,
                              org.bukkit.entity.LivingEntity target, String mode, String arguments) {
            this.caster = caster;
            this.point = point;
            this.viewers = viewers == null ? List.of() : viewers;
            this.target = target;
            this.mode = mode == null ? "" : mode;
            this.arguments = arguments == null ? "" : arguments;
        }
    }

    /** Результат проверки условий запуска. */
    public record StartCheck(boolean allowed, Component reason) {
        public static StartCheck ok() {
            return new StartCheck(true, null);
        }

        public static StartCheck deny(String reason) {
            return new StartCheck(false, ru.astra.util.Text.error(reason));
        }
    }

    private final AstraPlugin plugin;
    private final String id;
    private final String displayName;
    private final String description;
    private final RepeatPolicy repeatPolicy;

    protected Ability(AstraPlugin plugin, String id, String displayName, String description,
                      RepeatPolicy repeatPolicy) {
        this.plugin = plugin;
        this.id = id;
        this.displayName = displayName;
        this.description = description;
        this.repeatPolicy = repeatPolicy;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String description() {
        return description;
    }

    public RepeatPolicy repeatPolicy() {
        return repeatPolicy;
    }

    public boolean isEnabledByConfig() {
        var sec = plugin.config().ability(id);
        return sec == null || sec.getBoolean("enabled", true);
    }

    protected AstraPlugin plugin() {
        return plugin;
    }

    /** Проверка условий запуска до начала выполнения. */
    public StartCheck canStart(AbilityContext ctx) {
        if (!isEnabledByConfig()) {
            return StartCheck.deny("Способность «" + displayName + "» отключена в конфигурации.");
        }
        return StartCheck.ok();
    }

    /** Запуск способности. Вызывается только из главного потока. */
    public final void start(AbilityContext ctx) {
        doStart(ctx);
    }

    protected abstract void doStart(AbilityContext ctx);

    /** Штатная остановка всех экземпляров способности с очисткой ресурсов. */
    public abstract void stopAll(String reason);

    /** Есть ли выполняющиеся экземпляры. */
    public abstract boolean isRunning();

    /** Количество активных экземпляров. */
    public int activeCount() {
        return isRunning() ? 1 : 0;
    }
}
