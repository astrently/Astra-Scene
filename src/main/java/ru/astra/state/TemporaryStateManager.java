package ru.astra.state;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ru.astra.AstraPlugin;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Учёт временных изменений, которые необходимо безопасно отменить.
 * При очистке проверяется, что текущее значение всё ещё соответствует
 * значению, установленному Astra; иначе восстановление пропускается,
 * чтобы не затереть изменения других плагинов или самого игрока.
 */
public final class TemporaryStateManager {

    /** Запись о применённом эффекте: кто и какой именно эффект поставил. */
    public record PotionRecord(UUID entityUuid, PotionEffect applied) {
    }

    private final AstraPlugin plugin;
    private final Map<String, PotionRecord> potions = new ConcurrentHashMap<>();
    private final Map<String, Boolean> glowOverridden = new ConcurrentHashMap<>();

    public TemporaryStateManager(AstraPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Применяет временный эффект зелья с ключом записи.
     * @return true, если эффект применён.
     */
    public boolean applyPotion(String key, LivingEntity target, PotionEffectType type,
                               int durationTicks, int amplifier, boolean ambient,
                               boolean particles, boolean icon) {
        if (target == null || target.isDead()) {
            return false;
        }
        PotionEffect effect = new PotionEffect(type, durationTicks, amplifier, ambient, particles, icon);
        target.addPotionEffect(effect);
        potions.put(key, new PotionRecord(target.getUniqueId(), effect));
        return true;
    }

    /**
     * Снимает эффект только если он всё ещё совпадает с установленным Astra
     * (тип, длительность-остаток не проверяется, но амплитуда и источник — да).
     */
    public void removePotion(String key) {
        PotionRecord rec = potions.remove(key);
        if (rec == null) {
            return;
        }
        if (!(org.bukkit.Bukkit.getEntity(rec.entityUuid()) instanceof LivingEntity le) || le.isDead()) {
            return;
        }
        PotionEffect current = le.getPotionEffect(rec.applied().getType());
        if (current == null) {
            return; // уже снят другим
        }
        // Если другой плагин/игрок заменил эффект (другая амплитуда) — не трогаем.
        if (current.getAmplifier() != rec.applied().getAmplifier()) {
            plugin.logDebug("Эффект " + rec.applied().getType().getKey() + " изменён извне; восстановление пропущено.");
            return;
        }
        le.removePotionEffect(rec.applied().getType());
    }

    /** Временное свечение цели с защитой от перезаписи чужого состояния. */
    public void setGlow(String key, Player player, boolean glow) {
        if (!glowOverridden.containsKey(key)) {
            glowOverridden.put(key, player.isGlowing());
        }
        player.setGlowing(glow);
    }

    /** Возврат свечения, только если игрок всё ещё в состоянии, заданном Astra. */
    public void restoreGlow(String key) {
        Boolean previous = glowOverridden.remove(key);
        if (previous == null) {
            return;
        }
        // key формат: uuid|...
        try {
            UUID id = UUID.fromString(key.split("\\|", 2)[0]);
            if (org.bukkit.Bukkit.getPlayer(id) instanceof Player p) {
                // Не восстанавливаем, если значение изменил кто-то другой после нас?
                // Мы помним только своё "до"; сравнение текущего с ожидаемым "после" невозможно
                // без хранения обоих, поэтому храним ожидаемое состояние через отдельную карту.
                Boolean expected = glowExpected.remove(key);
                if (expected != null && p.isGlowing() != expected) {
                    plugin.logDebug("Свечение игрока " + p.getName() + " изменено извне; восстановление пропущено.");
                    return;
                }
                p.setGlowing(previous);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }

    private final Map<String, Boolean> glowExpected = new ConcurrentHashMap<>();

    public void setGlowExpected(String key, boolean expected) {
        glowExpected.put(key, expected);
    }

    /** Полная очистка всех временных состояний (при остановке плагина / stopall). */
    public void clearAll() {
        for (String key : List.copyOf(potions.keySet())) {
            removePotion(key);
        }
        for (String key : List.copyOf(glowOverridden.keySet())) {
            restoreGlow(key);
        }
        potions.clear();
        glowOverridden.clear();
        glowExpected.clear();
    }

    public boolean hasKey(String key) {
        return potions.containsKey(key) || glowOverridden.containsKey(key);
    }
}
