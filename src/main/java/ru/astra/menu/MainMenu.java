package ru.astra.menu;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import ru.astra.AstraPlugin;
import ru.astra.ability.Ability;
import ru.astra.scene.Scene;
import ru.astra.util.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Главное меню постановщика: способности, сохранённые сцены, управление носителем.
 * ЛКМ — быстрое действие (запуск), ПКМ — вспомогательное (вкл/выкл, редактор).
 */
public final class MainMenu extends MenuHolder implements Listener {

    private static final String ABILITY_MARK = "\u00A7#AB\u00A7"; // не используется; слоты типизированы ниже

    private enum SlotKind { NONE, ABILITY, SCENE, BEARER_SET, BEARER_REMOVE }

    private final SlotKind[] kinds;

    public MainMenu(AstraPlugin plugin) {
        super(plugin, "astra_main", 54);
        this.kinds = new SlotKind[size()];
    }

    private int size() {
        return actions.length;
    }

    /** Открыть меню игроку. */
    public void open(Player player) {
        rebuild(player);
        player.openInventory(getInventory());
    }

    private void rebuild(Player viewer) {
        java.util.Arrays.fill(actions, null);
        java.util.Arrays.fill(kinds, SlotKind.NONE);

        fill(0, 8, pane());
        set(4, item(Material.NETHER_STAR, "§cАСТРА §7— инструментарий постановщика",
                "§7Способности, сцены и роли."), null);

        // Ряд способностей (слоты 10..25, максимум 12)
        List<Ability> abilities = new ArrayList<>(plugin.abilities().all());
        int slot = 10;
        for (Ability a : abilities) {
            if (slot > 25) {
                break;
            }
            boolean enabled = plugin.config().isEnabled(a.id());
            Material mat = enabled ? Material.REDSTONE_BLOCK : Material.GRAY_DYE;
            String status = enabled ? "§aвключена" : "§cвыключена";
            ItemStack stack = item(mat, "§f" + a.displayName(),
                    "§7" + a.description(),
                    "§7Статус: " + status + (a.isRunning() ? " §6(активна)" : ""),
                    "§eЛКМ — запустить на себе",
                    "§eПКМ — включить/выключить");
            set(slot, stack, null);
            kinds[slot] = SlotKind.ABILITY;
            slot++;
        }

        fill(27, 35, pane());
        set(31, item(Material.PAINTING, "§fСцены", "§7Сохранённые пользовательские сцены."), null);

        // Ряд сцен (слоты 37..43, максимум 7)
        int sceneSlot = 37;
        for (Scene s : plugin.sceneManager().saved().values()) {
            if (sceneSlot > 43) {
                break;
            }
            ItemStack stack = item(Material.PAPER, "§fСцена: " + s.id(),
                    "§7" + s.title(),
                    "§7Шагов: " + s.steps().size(),
                    "§eЛКМ — запустить на себе",
                    "§eПКМ — открыть редактор (команда)");
            set(sceneSlot, stack, null);
            kinds[sceneSlot] = SlotKind.SCENE;
            sceneSlot++;
        }

        // Носитель Astra (не даёт прав автоматически)
        Player bearer = plugin.roleManager().getBearerPlayer();
        set(48, item(Material.PLAYER_HEAD,
                        bearer == null ? "§fНазначить себя носителем" : "§fНоситель: " + bearer.getName(),
                        "§7UUID носителя сохраняется между перезапусками.",
                        "§7Назначение НЕ выдаёт админ-права.",
                        "§eЛКМ — назначить/переназначить"),
                p -> {
                    if (!p.hasPermission("astra.assign")) {
                        p.sendMessage(Text.error("Нужно право astra.assign."));
                        return;
                    }
                    plugin.roleManager().setBearer(p);
                    p.sendMessage(Text.ok("Вы назначены носителем Astra."));
                    rebuild(p);
                    p.openInventory(getInventory());
                });
        set(50, item(Material.BARRIER, "§fСнять носителя",
                        "§eЛКМ — снять статус носителя"),
                p -> {
                    if (!p.hasPermission("astra.assign")) {
                        p.sendMessage(Text.error("Нужно право astra.assign."));
                        return;
                    }
                    plugin.roleManager().removeBearer();
                    p.sendMessage(Text.msg("Статус носителя снят."));
                    rebuild(p);
                    p.openInventory(getInventory());
                });

        build("Astra");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MainMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= menu.kinds.length) {
            return;
        }
        switch (menu.kinds[slot]) {
            case ABILITY -> {
                Ability a = abilityAt(slot);
                if (a == null) {
                    return;
                }
                if (event.isRightClick()) {
                    menu.toggleAbility(player, a);
                } else {
                    menu.quickCast(player, a);
                }
            }
            case SCENE -> {
                Scene s = sceneAt(slot);
                if (s == null) {
                    return;
                }
                if (event.isRightClick()) {
                    player.closeInventory();
                    player.performCommand("astra scene edit " + s.id());
                } else {
                    if (!player.hasPermission(plugin.config().defaultRolePermission)) {
                        player.sendMessage(Text.error("Недостаточно прав для запуска."));
                        return;
                    }
                    var res = plugin.sceneManager().start(s, player.getLocation(), player,
                            List.of(player), null, "", player);
                    player.sendMessage(res.ok()
                            ? Text.ok("Сцена «" + s.title() + "» запущена (" + res.runId() + ")")
                            : Text.error(res.message()));
                }
            }
            default -> {
                var action = menu.actions[slot];
                if (action != null && action.onClick() != null) {
                    action.onClick().accept(player);
                }
            }
        }
    }

    /** Способность по индексу слота (порядок совпадает с rebuild). */
    private Ability abilityAt(int slot) {
        if (slot < 10 || slot > 25) {
            return null;
        }
        List<Ability> list = new ArrayList<>(plugin.abilities().all());
        int index = slot - 10;
        return index < list.size() ? list.get(index) : null;
    }

    private Scene sceneAt(int slot) {
        if (slot < 37 || slot > 43) {
            return null;
        }
        List<Scene> list = new ArrayList<>(plugin.sceneManager().saved().values());
        int index = slot - 37;
        return index < list.size() ? list.get(index) : null;
    }

    private void quickCast(Player p, Ability a) {
        if (!p.hasPermission(plugin.config().defaultRolePermission)) {
            p.sendMessage(Text.error("Недостаточно прав: нужно " + plugin.config().defaultRolePermission + "."));
            return;
        }
        Ability.AbilityContext ctx = new Ability.AbilityContext(p, p.getLocation(), List.of(p),
                null, "", "");
        Ability.StartCheck check = a.canStart(ctx);
        if (!check.allowed()) {
            p.sendMessage(check.reason() == null ? Text.error("Запуск отклонён.") : check.reason());
            return;
        }
        a.start(ctx);
        p.sendMessage(Text.ok("Способность «" + a.displayName() + "» запущена."));
    }

    private void toggleAbility(Player p, Ability a) {
        if (!p.hasPermission("astra.admin")) {
            p.sendMessage(Text.error("Нужно право astra.admin."));
            return;
        }
        boolean now = !plugin.config().isEnabled(a.id());
        plugin.getConfig().set("abilities." + a.id() + ".enabled", now);
        plugin.saveConfig();
        plugin.reloadSafely();
        p.sendMessage(Text.ok("Способность «" + a.displayName() + "» "
                + (now ? "включена" : "выключена") + "."));
        rebuild(p);
        p.openInventory(getInventory());
    }

    private static String plainName(ItemStack stack) {
        if (stack == null || stack.getItemMeta() == null) {
            return "";
        }
        var display = stack.getItemMeta().displayName();
        return display == null ? "" : PlainTextComponentSerializer.plainText().serialize(display);
    }
}
