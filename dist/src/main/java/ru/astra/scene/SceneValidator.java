package ru.astra.scene;

import ru.astra.AstraPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * Проверка сцен и параметров до запуска.
 * Бесконечные циклы запрещены; телепортация требует корректной конечной точки.
 */
public final class SceneValidator {

    public record Issue(int stepIndex, String message) {
        @Override
        public String toString() {
            return "шаг " + (stepIndex + 1) + ": " + message;
        }
    }

    private final AstraPlugin plugin;

    public SceneValidator(AstraPlugin plugin) {
        this.plugin = plugin;
    }

    /** Полный список ошибок; пустой список означает, что сцена прошла проверку. */
    public List<Issue> validate(Scene scene) {
        List<Issue> issues = new ArrayList<>();
        if (scene.steps().isEmpty()) {
            issues.add(new Issue(-1, "сцена не содержит шагов"));
        }
        for (int i = 0; i < scene.steps().size(); i++) {
            validateStep(scene.steps().get(i), i, issues, 0);
        }
        double estimated = scene.estimatedDurationSeconds();
        if (estimated > plugin.config().maxSceneDurationSeconds) {
            issues.add(new Issue(-1, String.format(
                    "прогнозируемая длительность %.0f с превышает лимит %d с",
                    estimated, plugin.config().maxSceneDurationSeconds)));
        }
        return issues;
    }

    private void validateStep(SceneStep step, int index, List<Issue> issues, int depth) {
        if (depth > 4) {
            issues.add(new Issue(index, "превышена допустимая глубина вложенности шагов"));
            return;
        }
        switch (step.type()) {
            case WAIT -> {
                double seconds = parseOr(step.arg(0), -1);
                if (seconds < 0 || seconds > 300) {
                    issues.add(new Issue(index, "WAIT: требуется время от 0 до 300 секунд"));
                }
            }
            case REPEAT -> {
                int times = (int) parseOr(step.arg(0), -1);
                if (times < 1 || times > 64) {
                    issues.add(new Issue(index, "REPEAT: количество повторов должно быть 1..64"));
                }
                if (step.children().isEmpty()) {
                    issues.add(new Issue(index, "REPEAT: отсутствуют повторяемые шаги"));
                }
                for (SceneStep child : step.children()) {
                    validateStep(child, index, issues, depth + 1);
                }
            }
            case BRANCH -> {
                for (SceneStep child : step.children()) {
                    validateStep(child, index, issues, depth + 1);
                }
            }
            case CONDITION -> {
                String check = step.arg(0);
                if (!List.of("target_alive", "bearer_online", "caster_online",
                        "viewers_at_least", "has_target_point").contains(check.toLowerCase())) {
                    issues.add(new Issue(index, "CONDITION: неизвестная проверка '" + check + "'"));
                }
            }
            case TELEPORT -> {
                if (step.args().size() < 4) {
                    issues.add(new Issue(index, "TELEPORT: нужны world x y z"));
                } else if (org.bukkit.Bukkit.getWorld(step.arg(0)) == null) {
                    issues.add(new Issue(index, "TELEPORT: мир '" + step.arg(0) + "' не существует"));
                } else {
                    try {
                        Double.parseDouble(step.arg(1));
                        Double.parseDouble(step.arg(2));
                        Double.parseDouble(step.arg(3));
                    } catch (NumberFormatException ex) {
                        issues.add(new Issue(index, "TELEPORT: некорректные координаты"));
                    }
                }
            }
            case ABILITY -> {
                String id = step.arg(0);
                if (plugin.abilities().byId(id).isEmpty()) {
                    issues.add(new Issue(index, "ABILITY: неизвестная способность '" + id + "'"));
                }
            }
            case PARTICLE -> {
                if (step.args().isEmpty()) {
                    issues.add(new Issue(index, "PARTICLE: не указан тип частиц"));
                } else if (ru.astra.util.Text.valueOfSafe(org.bukkit.Particle.class, step.arg(0)) == null) {
                    issues.add(new Issue(index, "PARTICLE: неизвестный тип '" + step.arg(0) + "'"));
                }
            }
            case SOUND -> {
                if (step.args().isEmpty()) {
                    issues.add(new Issue(index, "SOUND: не указан звук"));
                } else if (!ru.astra.util.Text.isKnownSound(step.arg(0))) {
                    issues.add(new Issue(index, "SOUND: неизвестный звук '" + step.arg(0) + "'"));
                }
            }
            case POTION_EFFECT -> {
                if (step.args().isEmpty()
                        || org.bukkit.potion.PotionEffectType.getByName(step.arg(0).toUpperCase()) == null) {
                    issues.add(new Issue(index, "POTION_EFFECT: неизвестный эффект"));
                }
            }
            case TARGET -> {
                String sel = step.arg(0).toLowerCase();
                if (sel.equals("point") && step.args().size() < 4) {
                    issues.add(new Issue(index, "TARGET point: нужны world x y z"));
                }
            }
            case VELOCITY -> {
                if (step.args().size() < 3) {
                    issues.add(new Issue(index, "VELOCITY: нужны x y z"));
                } else {
                    for (int a = 0; a < 3; a++) {
                        double v = parseOr(step.arg(a), Double.NaN);
                        if (Double.isNaN(v) || Math.abs(v) > 2.0) {
                            issues.add(new Issue(index, "VELOCITY: импульс ограничен значением ±2.0"));
                        }
                    }
                }
            }
            case MESSAGE -> {
                if (step.args().isEmpty()) {
                    issues.add(new Issue(index, "MESSAGE: пустое сообщение"));
                }
            }
            default -> {
            }
        }
    }

    private static double parseOr(String raw, double def) {
        try {
            return Double.parseDouble(raw);
        } catch (Exception ex) {
            return def;
        }
    }
}
