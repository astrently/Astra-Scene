package ru.astra.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Sound;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;

import java.util.Locale;

/** Утилиты форматирования сообщений и безопасного разбора enum-значений. */
public final class Text {

    public static final NamedTextColor ACCENT = NamedTextColor.RED;
    public static final NamedTextColor LIGHT = NamedTextColor.WHITE;

    private Text() {
    }

    public static Component prefix() {
        return Component.text()
                .append(Component.text("[", NamedTextColor.GRAY))
                .append(Component.text("Астра", NamedTextColor.RED, TextDecoration.BOLD))
                .append(Component.text("] ", NamedTextColor.GRAY))
                .build();
    }

    public static Component msg(String text) {
        return Component.text().append(prefix()).append(Component.text(text, NamedTextColor.WHITE)).build();
    }

    public static Component error(String text) {
        return Component.text().append(prefix()).append(Component.text(text, NamedTextColor.RED)).build();
    }

    public static Component ok(String text) {
        return Component.text().append(prefix()).append(Component.text(text, NamedTextColor.GREEN)).build();
    }

    public static Component info(String label, String value) {
        return Component.text().append(prefix())
                .append(Component.text(label + ": ", NamedTextColor.GRAY))
                .append(Component.text(value, NamedTextColor.WHITE))
                .build();
    }

    public static void send(CommandSender sender, Component component) {
        sender.sendMessage(component);
    }

    /** Безопасный разбор значения enum; при ошибке возвращает значение по умолчанию. */
    public static <E extends Enum<E>> E enumOrDefault(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    /** Безопасный разбор значения enum; null при ошибке. */
    public static <E extends Enum<E>> E valueOfSafe(Class<E> type, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public static Particle particleOrDefault(String raw, Particle fallback) {
        return enumOrDefault(Particle.class, raw, fallback);
    }

    /** В Paper 1.21.x Sound — интерфейс (Keyed), а не enum; разбор через Registry.SOUNDS. */
    public static Sound soundOrDefault(String raw, Sound fallback) {
        Sound s = parseSound(raw);
        return s != null ? s : fallback;
    }

    private static Sound parseSound(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String name = raw.trim().toUpperCase(Locale.ROOT);
        // Имена звуков в реестре — namespaced (minecraft:block.note_block.pling)
        Sound s = org.bukkit.Registry.SOUNDS.get(net.kyori.adventure.key.Key.key(
                "minecraft:" + name.toLowerCase(Locale.ROOT).replace('_', '.')));
        return s;
    }

    /** Проверка существования звука по имени. */
    public static boolean isKnownSound(String raw) {
        return parseSound(raw) != null;
    }

    /** Разбор цвета "#RRGGBB" в int (RGB). При ошибке — fallback. */
    public static int colorOrDefault(String raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        String s = raw.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() != 6) {
            return fallback;
        }
        try {
            return Integer.parseInt(s, 16);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
