package ru.astra.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.astra.AstraPlugin;

import java.util.function.Consumer;

/**
 * Общий holder для всех инвентарных меню Astra: позволяет отличить собственные
 * меню от чужих инвентарей и безопасно обрабатывать клики без провала действий
 * в игровой мир.
 */
public class MenuHolder implements InventoryHolder {

    /** Действие по слоту. */
    public record SlotAction(ItemStack item, Consumer<Player> onClick) {
    }

    protected final AstraPlugin plugin;
    protected final String titleId;
    protected final SlotAction[] actions;
    protected Inventory inventory;

    protected MenuHolder(AstraPlugin plugin, String titleId, int size) {
        this.plugin = plugin;
        this.titleId = titleId;
        this.actions = new SlotAction[size];
    }

    protected void set(int slot, ItemStack item, Consumer<Player> onClick) {
        if (slot < 0 || slot >= actions.length) {
            return;
        }
        actions[slot] = new SlotAction(item, onClick);
    }

    protected void fill(int from, int to, ItemStack item) {
        for (int i = from; i <= to && i < actions.length; i++) {
            if (i >= 0) {
                actions[i] = new SlotAction(item, null);
            }
        }
    }

    /** Построить инвентарь из текущих действий. */
    protected Inventory build(String displayTitle) {
        inventory = Bukkit.createInventory(this, actions.length, Component.text(displayTitle));
        for (int i = 0; i < actions.length; i++) {
            if (actions[i] != null && actions[i].item() != null) {
                inventory.setItem(i, actions[i].item());
            }
        }
        return inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory == null ? Bukkit.createInventory(this, actions.length,
                Component.text(titleId)) : inventory;
    }

    /** Обработка клика: отмена крафта и вызов действия слота. */
    public void handleClick(InventoryClickEvent event, Player who) {
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= actions.length) {
            return;
        }
        SlotAction action = actions[slot];
        if (action != null && action.onClick() != null) {
            try {
                action.onClick().accept(who);
            } catch (RuntimeException ex) {
                who.sendMessage(net.kyori.adventure.text.Component.text(
                        "Ошибка действия меню: " + ex.getMessage()));
                plugin.getLogger().warning("Действие меню '" + titleId + "' слот " + slot
                        + ": " + ex.getMessage());
            }
        }
    }

    // ==================== Конструкторы элементов ====================

    public static ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(name));
            var loreList = new java.util.ArrayList<Component>();
            for (String l : lore) {
                loreList.add(Component.text(l));
            }
            meta.lore(loreList);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static ItemStack pane() {
        return item(Material.GRAY_STAINED_GLASS_PANE, " ");
    }
}
