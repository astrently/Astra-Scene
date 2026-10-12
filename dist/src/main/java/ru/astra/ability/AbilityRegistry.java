package ru.astra.ability;

import ru.astra.AstraPlugin;
import ru.astra.ability.impl.AeonAppearanceAbility;
import ru.astra.ability.impl.AnomalyAbility;
import ru.astra.ability.impl.ApplauseAbility;
import ru.astra.ability.impl.InvertedWorldAbility;
import ru.astra.ability.impl.MasqueradeAbility;
import ru.astra.ability.impl.MirrorAbility;
import ru.astra.ability.impl.OutOfTimeAbility;
import ru.astra.ability.impl.PuppeteerAbility;
import ru.astra.ability.impl.SceneChangeAbility;
import ru.astra.ability.impl.SpotlightAbility;
import ru.astra.ability.impl.ThousandFacesAbility;
import ru.astra.ability.impl.ThreadsAbility;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Реестр способностей Astra: регистрация и получение по идентификатору. */
public final class AbilityRegistry {

    private final Map<String, Ability> abilities = new LinkedHashMap<>();

    public AbilityRegistry(AstraPlugin plugin) {
        register(new SpotlightAbility(plugin));
        register(new ApplauseAbility(plugin));
        register(new AeonAppearanceAbility(plugin));
        register(new MasqueradeAbility(plugin));
        register(new MirrorAbility(plugin));
        register(new ThousandFacesAbility(plugin));
        register(new PuppeteerAbility(plugin));
        register(new SceneChangeAbility(plugin));
        register(new ThreadsAbility(plugin));
        register(new InvertedWorldAbility(plugin));
        register(new OutOfTimeAbility(plugin));
        register(new AnomalyAbility(plugin));
    }

    private void register(Ability ability) {
        abilities.put(ability.id().toLowerCase(Locale.ROOT), ability);
    }

    public Optional<Ability> byId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(abilities.get(id.toLowerCase(Locale.ROOT)));
    }

    public Collection<Ability> all() {
        return abilities.values();
    }

    /** Остановить все способности (при stopall / выключении). */
    public void stopAll(String reason) {
        for (Ability a : abilities.values()) {
            try {
                a.stopAll(reason);
            } catch (RuntimeException ex) {
                // Ошибка одной способности не должна мешать остановке остальных.
                java.util.logging.Logger log = java.util.logging.Logger.getLogger("Astra");
                log.warning("Ошибка остановки способности '" + a.id() + "': " + ex.getMessage());
            }
        }
    }
}
