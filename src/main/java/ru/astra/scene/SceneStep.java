package ru.astra.scene;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import ru.astra.AstraPlugin;
import ru.astra.ability.Ability;
import ru.astra.util.Text;

import java.util.List;
import java.util.Locale;

/**
 * Шаг пользовательской сцены. Все типы шагов из ТЗ:
 * WAIT, PARTICLE, SOUND, TARGET, TELEPORT, VELOCITY, POTION_EFFECT,
 * ABILITY, CONDITION, BRANCH, REPEAT, MESSAGE, FINISH.
 */
public final class SceneStep {

    public enum Type {
        WAIT, PARTICLE, SOUND, TARGET, TELEPORT, VELOCITY, POTION_EFFECT,
        ABILITY, CONDITION, BRANCH, REPEAT, MESSAGE, FINISH;

        public static Type parse(String raw) {
            if (raw == null) {
                return null;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                return null;
            }
        }
    }

    private final Type type;
    private final List<String> args;
    /** Для REPEAT: повторяемая последовательность вложенных шагов. */
    private final List<SceneStep> children;

    public SceneStep(Type type, List<String> args, List<SceneStep> children) {
        this.type = type;
        this.args = args == null ? List.of() : args;
        this.children = children == null ? List.of() : children;
    }

    public Type type() {
        return type;
    }

    public List<String> args() {
        return args;
    }

    public List<SceneStep> children() {
        return children;
    }

    public String arg(int i) {
        return i >= 0 && i < args.size() ? args.get(i) : "";
    }

    public double num(int i, double def) {
        try {
            return Double.parseDouble(arg(i));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** Сериализация шага в строку YAML-списка. */
    public String serialize() {
        StringBuilder sb = new StringBuilder(type.name());
        for (String a : args) {
            sb.append(' ').append(a);
        }
        return sb.toString();
    }

    /**
     * Выполнение шага внутри запущенной сцены.
     * @return special outcome flags via runner methods; throws StepException on error.
     */
    public void execute(SceneRunner runner) {
        AstraPlugin plugin = runner.plugin();
        switch (type) {
            case WAIT -> {
                double seconds = Math.max(0, num(0, 1));
                runner.waitTicks((int) Math.round(seconds * 20));
            }
            case PARTICLE -> {
                Particle p = Text.particleOrDefault(arg(0), Particle.DUST);
                int count = (int) Text.clamp(num(1, 20), 1, plugin.config().maxParticlesPerBurst);
                double radius = Text.clamp(num(2, 1.0), 0.1, 32);
                int color = Text.colorOrDefault(arg(3), isRedWhite(p) ? 0xFFFFFF : 0xFF2A2A);
                Location at = runner.resolveAnchor();
                if (at != null) {
                    if (p == Particle.DUST || p == Particle.DUST_COLOR_TRANSITION) {
                        plugin.particles().colorBurst(at, p, count, radius / 4, 0.0, color);
                    } else {
                        plugin.particles().burst(at, p, count, radius / 4, 0.0);
                    }
                }
            }
            case SOUND -> {
                Sound s = Text.soundOrDefault(arg(0), Sound.BLOCK_NOTE_BLOCK_PLING);
                float vol = (float) Text.clamp(num(1, 1.0), 0, 4);
                float pitch = (float) Text.clamp(num(2, 1.0), 0.5, 2);
                Location at = runner.resolveAnchor();
                if (at != null) {
                    plugin.sounds().play(at, s, vol, pitch);
                }
            }
            case TARGET -> {
                String selector = arg(0);
                switch (selector.toLowerCase(Locale.ROOT)) {
                    case "bearer" -> runner.setTargetBearer();
                    case "self", "caster" -> runner.setTargetCaster();
                    case "point" -> runner.setTargetPoint(arg(1), arg(2), arg(3), arg(4));
                    case "nearest" -> runner.setTargetNearest(num(1, 16));
                    default -> {
                        Player named = Bukkit.getPlayerExact(selector);
                        if (named != null) {
                            runner.setTargetEntity(named);
                        } else {
                            throw new StepException("TARGET: неизвестный селектор '" + selector + "'");
                        }
                    }
                }
            }
            case TELEPORT -> {
                LivingEntity t = runner.currentTarget();
                if (t == null) {
                    throw new StepException("TELEPORT: цель не выбрана");
                }
                Location dest = runner.parseLocation(arg(0), arg(1), arg(2), arg(3));
                if (dest == null || dest.getWorld() == null) {
                    throw new StepException("TELEPORT: некорректная конечная точка");
                }
                if (!runner.isSafeLanding(dest)) {
                    throw new StepException("TELEPORT: конечная точка небезопасна (" + formatLoc(dest) + ")");
                }
                if (!(t instanceof Player) && !plugin.config().sectionBool(
                        plugin.config().ability("scene_change"), "cross-world-enabled", false)
                        && !t.getWorld().equals(dest.getWorld())) {
                    throw new StepException("TELEPORT: перемещение между мирами отключено");
                }
                t.teleport(dest);
            }
            case VELOCITY -> {
                LivingEntity t = runner.currentTarget();
                if (t == null) {
                    throw new StepException("VELOCITY: цель не выбрана");
                }
                double x = num(0, 0);
                double y = num(1, 0);
                double z = num(2, 0);
                double max = 2.0; // ограниченный импульс
                x = Text.clamp(x, -max, max);
                y = Text.clamp(y, -max, max);
                z = Text.clamp(z, -max, max);
                t.setVelocity(t.getVelocity().add(new org.bukkit.util.Vector(x, y, z)));
            }
            case POTION_EFFECT -> {
                LivingEntity t = runner.currentTarget();
                if (t == null) {
                    throw new StepException("POTION_EFFECT: цель не выбрана");
                }
                PotionEffectType effect = PotionEffectType.getByName(arg(0).toUpperCase(Locale.ROOT));
                if (effect == null) {
                    throw new StepException("POTION_EFFECT: неизвестный эффект '" + arg(0) + "'");
                }
                int durationTicks = (int) Text.clamp(num(1, 5) * 20, 20, 6000);
                int amplifier = (int) Text.clamp(num(2, 0), 0, 4);
                String key = runner.stateKey("potion-" + runner.stepIndex());
                plugin.tempState().applyPotion(key, t, effect, durationTicks, amplifier,
                        true, false, false);
            }
            case ABILITY -> {
                String abilityId = arg(0);
                Ability ability = plugin.abilities().byId(abilityId)
                        .orElseThrow(() -> new StepException("ABILITY: неизвестная способность '" + abilityId + "'"));
                Ability.StartCheck check = ability.canStart(runner.buildAbilityContext(arg(1)));
                if (!check.allowed()) {
                    throw new StepException("ABILITY: способность '" + abilityId + "' не может быть запущена");
                }
                ability.start(runner.buildAbilityContext(arg(1)));
            }
            case CONDITION -> runner.evaluateCondition(this);
            case BRANCH -> runner.evaluateBranch(this);
            case REPEAT -> runner.executeRepeat(this);
            case MESSAGE -> {
                String text = String.join(" ", args);
                for (Player viewer : runner.viewers()) {
                    viewer.sendMessage(Text.msg(text));
                }
            }
            case FINISH -> runner.finishScene();
        }
    }

    private static boolean isRedWhite(Particle p) {
        return true; // цвет подставляется только для DUST-частиц
    }

    private static String formatLoc(Location l) {
        return String.format(Locale.ROOT, "%s %.1f %.1f %.1f",
                l.getWorld() == null ? "?" : l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
    }

    /** Ошибка выполнения шага. */
    public static final class StepException extends RuntimeException {
        public StepException(String message) {
            super(message);
        }
    }
}
