package ru.astra.scene;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Сохранённая пользовательская сцена: идентификатор, название, описание, шаги. */
public final class Scene {

    private final String id;
    private String title;
    private String description;
    private final List<SceneStep> steps = new ArrayList<>();

    public Scene(String id, String title, String description) {
        this.id = id;
        this.title = title == null || title.isBlank() ? id : title;
        this.description = description == null ? "" : description;
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String description() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<SceneStep> steps() {
        return steps;
    }

    public void addStep(SceneStep step) {
        steps.add(step);
    }

    public void removeStep(int index) {
        if (index >= 0 && index < steps.size()) {
            steps.remove(index);
        }
    }

    public void moveStep(int from, int to) {
        if (from < 0 || from >= steps.size() || to < 0 || to >= steps.size()) {
            return;
        }
        SceneStep s = steps.remove(from);
        steps.add(to, s);
    }

    /** Копия сцены под новым идентификатором. */
    public Scene copyAs(String newId, String newTitle) {
        Scene copy = new Scene(newId, newTitle, description);
        for (SceneStep st : steps) {
            copy.addStep(new SceneStep(st.type(), new ArrayList<>(st.args()),
                    new ArrayList<>(st.children())));
        }
        return copy;
    }

    /** Простейший прогноз длительности в секундах (для валидации лимита). */
    public double estimatedDurationSeconds() {
        double total = 0;
        for (SceneStep st : steps) {
            total += estimateStep(st);
        }
        return total;
    }

    private static double estimateStep(SceneStep st) {
        switch (st.type()) {
            case WAIT -> {
                try {
                    return Math.max(0, Double.parseDouble(st.arg(0)));
                } catch (NumberFormatException ex) {
                    return 1;
                }
            }
            case REPEAT -> {
                int times = (int) Math.max(0, parseOr(st.arg(0), 1));
                double inner = 0;
                for (SceneStep child : st.children()) {
                    inner += estimateStep(child);
                }
                return times * inner;
            }
            default -> {
                return 0;
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

    /** Сериализация шагов для YAML. */
    public List<String> serializeSteps() {
        List<String> out = new ArrayList<>();
        for (SceneStep st : steps) {
            StringBuilder sb = new StringBuilder(st.serialize());
            if (st.type() == SceneStep.Type.REPEAT) {
                sb.append(" [");
                boolean first = true;
                for (SceneStep child : st.children()) {
                    if (!first) {
                        sb.append(" ;; ");
                    }
                    sb.append(child.serialize());
                    first = false;
                }
                sb.append(" ]");
            }
            out.add(sb.toString());
        }
        return out;
    }

    /** Разбор строки шага формата "TYPE arg1 arg2 ..." (и "REPEAT n [ a ;; b ]"). */
    public static SceneStep parseStepLine(String line) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        List<SceneStep> children = new ArrayList<>();
        String main = trimmed;
        int bracket = trimmed.indexOf('[');
        if (bracket >= 0 && trimmed.endsWith("]")) {
            main = trimmed.substring(0, bracket).trim();
            String inner = trimmed.substring(bracket + 1, trimmed.length() - 1).trim();
            if (!inner.isEmpty()) {
                for (String part : inner.split(";;")) {
                    SceneStep child = parseStepLine(part);
                    if (child != null) {
                        children.add(child);
                    }
                }
            }
        }
        String[] tokens = main.split("\\s+");
        SceneStep.Type type = SceneStep.Type.parse(tokens[0]);
        if (type == null) {
            return null;
        }
        List<String> args = new ArrayList<>();
        for (int i = 1; i < tokens.length; i++) {
            args.add(tokens[i]);
        }
        return new SceneStep(type, args, children);
    }
}
