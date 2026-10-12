package ru.astra.menu;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import ru.astra.AstraPlugin;
import ru.astra.scene.Scene;
import ru.astra.scene.SceneStep;
import ru.astra.util.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Инвентарный редактор пользовательской сцены без Java-кода:
 * просмотр шагов, добавление типовых шагов, перемещение и удаление, сохранение.
 * Сложные параметры шагов доступны командами; здесь — базовый конструктор.
 */
public final class SceneEditorMenu extends MenuHolder implements Listener {

    private static final int STEP_SLOT_FROM = 10;
    private static final int STEP_SLOT_TO = 34; // 25 отображаемых шагов на страницу (одна страница)

    private String sceneId;
    private final List<Integer> stepSlots = new ArrayList<>();

    public SceneEditorMenu(AstraPlugin plugin) {
        super(plugin, "astra_scene_editor", 45);
    }

    public void open(Player player, String sceneId) {
        Scene scene = plugin.sceneManager().get(sceneId);
        if (scene == null) {
            player.sendMessage(Text.error("Сцена '" + sceneId + "' не найдена."));
            return;
        }
        this.sceneId = scene.id();
        rebuild(player);
        player.openInventory(getInventory());
    }

    Scene scene() {
        return plugin.sceneManager().get(sceneId);
    }

    private void rebuild(Player viewer) {
        Scene scene = scene();
        if (scene == null) {
            return;
        }
        java.util.Arrays.fill(actions, null);
        stepSlots.clear();

        fill(0, 8, pane());
        set(4, item(Material.PAINTING, "§fРедактор: " + scene.id(),
                "§7" + scene.title(),
                "§7Шагов: " + scene.steps().size()), null);

        int slot = STEP_SLOT_FROM;
        for (int i = 0; i < scene.steps().size() && slot <= STEP_SLOT_TO; i++) {
            SceneStep st = scene.steps().get(i);
            set(slot, item(materialFor(st.type()),
                    "§f#" + (i + 1) + " " + st.serialize(),
                    "§eЛКМ — вниз (переместить)",
                    "§eПКМ — удалить шаг"), p -> {});
            stepSlots.add(slot);
            slot++;
        }

        // Панель добавления типовых шагов
        set(36, item(Material.CLOCK, "§a+ WAIT 1с", "§eЛКМ — добавить шаг ожидания"),
                p -> addStep(p, new SceneStep(SceneStep.Type.WAIT, List.of("1"), List.of())));
        set(37, item(Material.GLOWSTONE_DUST, "§a+ PARTICLE DUST (белый)",
                "§eЛКМ — добавить частицы"),
                p -> addStep(p, new SceneStep(SceneStep.Type.PARTICLE,
                        List.of("DUST", "20", "2", "#FFFFFF"), List.of())));
        set(38, item(Material.REDSTONE, "§c+ PARTICLE DUST (красный)",
                "§eЛКМ — добавить частицы"),
                p -> addStep(p, new SceneStep(SceneStep.Type.PARTICLE,
                        List.of("DUST", "20", "2", "#FF2A2A"), List.of())));
        set(39, item(Material.NOTE_BLOCK, "§a+ SOUND", "§eЛКМ — добавить звук"),
                p -> addStep(p, new SceneStep(SceneStep.Type.SOUND,
                        List.of("ENTITY_FIREWORK_ROCKET_BLAST", "1", "1"), List.of())));
        set(40, item(Material.TARGET, "§a+ TARGET caster", "§eЛКМ — выбрать цель"),
                p -> addStep(p, new SceneStep(SceneStep.Type.TARGET,
                        List.of("caster"), List.of())));
        set(41, item(Material.BOOK, "§a+ MESSAGE", "§eЛКМ — добавить сообщение"),
                p -> addStep(p, new SceneStep(SceneStep.Type.MESSAGE,
                        List.of("\"Astra\""), List.of())));
        set(42, item(Material.OAK_DOOR, "§a+ FINISH", "§eЛКМ — завершающий шаг"),
                p -> addStep(p, new SceneStep(SceneStep.Type.FINISH, List.of(), List.of())));
        set(44, item(Material.LIME_DYE, "§aСохранить сцену",
                "§eЛКМ — записать в scenes.yml"),
                p -> {
                    plugin.sceneManager().saveAll();
                    var issues = plugin.sceneValidator().validate(scene);
                    if (issues.isEmpty()) {
                        p.sendMessage(Text.ok("Сцена '" + scene.id() + "' сохранена; проверка пройдена."));
                    } else {
                        for (var issue : issues) {
                            p.sendMessage(Text.error("Проверка: " + issue));
                        }
                    }
                });

        build("Редактор: " + scene.id());
    }

    private Material materialFor(SceneStep.Type type) {
        return switch (type) {
            case WAIT -> Material.CLOCK;
            case PARTICLE -> Material.GLOWSTONE_DUST;
            case SOUND -> Material.NOTE_BLOCK;
            case TARGET -> Material.TARGET;
            case TELEPORT -> Material.ENDER_EYE;
            case VELOCITY -> Material.SNOWBALL;
            case POTION_EFFECT -> Material.POTION;
            case ABILITY -> Material.NETHER_STAR;
            case CONDITION -> Material.COMPARATOR;
            case BRANCH -> Material.OAK_SIGN;
            case REPEAT -> Material.REPEATING_COMMAND_BLOCK;
            case MESSAGE -> Material.BOOK;
            case FINISH -> Material.OAK_DOOR;
        };
    }

    private void addStep(Player p, SceneStep step) {
        Scene scene = scene();
        if (scene == null) {
            return;
        }
        if (scene.steps().size() >= 64) {
            p.sendMessage(Text.error("Достигнут лимит шагов сцены (64)."));
            return;
        }
        scene.addStep(step);
        rebuild(p);
        p.openInventory(getInventory());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof SceneEditorMenu editor)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int raw = event.getRawSlot();
        int displayIndex = stepSlots.indexOf(raw);
        Scene scene = editor.scene();
        if (scene == null) {
            return;
        }
        if (displayIndex >= 0) {
            if (event.isRightClick()) {
                scene.removeStep(displayIndex);
                editor.rebuild(player);
                player.openInventory(editor.getInventory());
            } else if (displayIndex + 1 < scene.steps().size()) {
                scene.moveStep(displayIndex, displayIndex + 1);
                editor.rebuild(player);
                player.openInventory(editor.getInventory());
            }
            return;
        }
        editor.handleClick(event, player);
    }
}
