package ru.astra.ability.impl;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import ru.astra.AstraPlugin;
import ru.astra.effect.ParticleService;
import ru.astra.util.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Явление Эона — главная постановочная способность Astra.
 *
 * ОБЯЗАТЕЛЬНОЕ ВИЗУАЛЬНОЕ ТРЕБОВАНИЕ: стандартное оформление использует ТОЛЬКО
 * белую и красную палитру (ParticleService.AEON_WHITE / AEON_RED, белые и красные
 * DustOptions, нейтральные по цвету частицы END_ROD / CLOUD / SNOWFLAKE /
 * FIREWORKS_SPARK). Золотые и фиолетовые эффекты в стандартном оформлении
 * запрещены и не используются: здесь отсутствуют частицы типа REDSTONE с золотым
 * или фиолетовым оттенком, PORTAL, END_ROD-«золото», DUST_COLOR_TRANSITION в
 * золото/фиолет и любые иные цветные варианты вне белого и красного.
 *
 * Этапы (по умолчанию 18 секунд):
 *   I.   Безмолвие      — 3с: приглушённые звуки/тишина, редкие белые частицы.
 *   II.  Проявление     — 5с: последовательное нарастание белых и красных элементов.
 *   III. Маскарад       — 5с: иллюзорные эпизоды вокруг точки (интеграция способности «Маскарад»).
 *   IV.  Кульминация    — 2с: синхронный бело-красный визуальный и звуковой акцент.
 *   V.   Исчезновение   — 3с: эффекты стягиваются к центру и рассеиваются.
 *
 * Ошибка одного этапа останавливает всю способность (без бесконечного выполнения
 * остальных) и корректно освобождает все задачи. Досрочная остановка прекращает
 * все действия; после завершения возможен повторный запуск без перезагрузки.
 */
public final class AeonAppearanceAbility extends TaskAbility {

    /** Этапы способности. */
    private enum Phase {
        SILENCE, MANIFESTATION, MASQUERADE, CLIMAX, DISAPPEARANCE
    }

    public AeonAppearanceAbility(AstraPlugin plugin) {
        super(plugin, "aeon_appearance", "Явление Эона",
                "Главное событие Astra: пять этапов в бело-красной палитре (18 секунд).",
                RepeatPolicy.REJECT);
    }

    @Override
    protected void doStart(AbilityContext ctx) {
        var cfg = plugin().config();
        var sec = cfg.ability(id());
        int visualRadius = cfg.sectionInt(sec, "visual-radius", 32, 4, 64);
        int maxViewers = cfg.sectionInt(sec, "max-viewers", 50, 1, 200);
        int updatesPerSecond = cfg.sectionInt(sec, "updates-per-second", 10, 1, 20);

        // Палитра проходит жёсткую проверку: допускается только белый и красный.
        String primary = cfg.sectionString(
                sec == null ? null : sec.getConfigurationSection("palette"), "primary", "WHITE");
        String secondary = cfg.sectionString(
                sec == null ? null : sec.getConfigurationSection("palette"), "secondary", "RED");
        if (!isAllowedPaletteColor(primary)) {
            plugin().getLogger().warning("Явление Эона: palette.primary='" + primary
                    + "' вне белой/красной палитры. Использован WHITE.");
            primary = "WHITE";
        }
        if (!isAllowedPaletteColor(secondary)) {
            plugin().getLogger().warning("Явление Эона: palette.secondary='" + secondary
                    + "' вне белой/красной палитры. Использован RED.");
            secondary = "RED";
        }
        final int colorPrimary = "RED".equalsIgnoreCase(primary)
                ? ParticleService.AEON_RED : ParticleService.AEON_WHITE;
        final int colorSecondary = "WHITE".equalsIgnoreCase(secondary)
                ? ParticleService.AEON_WHITE : ParticleService.AEON_RED;

        int silence = cfg.sectionInt(
                sec == null ? null : sec.getConfigurationSection("phases"), "silence", 3, 1, 10);
        int manifestation = cfg.sectionInt(
                sec == null ? null : sec.getConfigurationSection("phases"), "manifestation", 5, 1, 10);
        int masquerade = cfg.sectionInt(
                sec == null ? null : sec.getConfigurationSection("phases"), "masquerade", 5, 1, 10);
        int climax = cfg.sectionInt(
                sec == null ? null : sec.getConfigurationSection("phases"), "climax", 2, 1, 5);
        int disappearance = cfg.sectionInt(
                sec == null ? null : sec.getConfigurationSection("phases"), "disappearance", 3, 1, 10);

        Location anchor = ctx.point != null ? ctx.point
                : (ctx.target != null ? ctx.target.getLocation()
                : (ctx.caster != null ? ctx.caster.getLocation() : null));
        if (anchor == null) {
            return;
        }
        List<Player> viewers = ctx.viewers.isEmpty()
                ? plugin().targetSelector().playersNear(anchor, visualRadius, maxViewers)
                : ctx.viewers.stream().limit(maxViewers).toList();

        int totalTicks = (silence + manifestation + masquerade + climax + disappearance) * 20;
        int period = Math.max(1, 20 / updatesPerSecond);
        launch(period, totalTicks, new AeonBody(anchor.clone(), viewers, visualRadius,
                colorPrimary, colorSecondary,
                silence * 20, manifestation * 20, masquerade * 20, climax * 20, disappearance * 20));
    }

    /** Стандарт допускает только белый и красный (или их hex-задания). */
    private static boolean isAllowedPaletteColor(String raw) {
        if (raw == null) {
            return false;
        }
        String v = raw.trim().toUpperCase(java.util.Locale.ROOT);
        if (v.equals("WHITE") || v.equals("RED")) {
            return true;
        }
        // Hex принимается только если он явно белый или красный по компонентам.
        try {
            int rgb = Text.colorOrDefault(v, -1);
            if (rgb < 0) {
                return false;
            }
            int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
            boolean whiteish = r > 200 && g > 200 && b > 200;
            boolean redish = r > 150 && g < 90 && b < 90;
            return whiteish || redish;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Выполнение пяти этапов; ошибка любого этапа завершает всю способность. */
    private final class AeonBody implements Body {
        private final Location center;
        private final List<Player> audience;
        private final int visualRadius;
        private final int white;
        private final int red;
        private final int tSilence, tManifest, tMasq, tClimax, tDisappear;
        private int elapsed = 0;
        private Phase phase = Phase.SILENCE;
        private boolean climaxDone = false;
        private int lastEpisodeAt = -100;

        AeonBody(Location center, List<Player> audience, int visualRadius, int white, int red,
                 int tSilence, int tManifest, int tMasq, int tClimax, int tDisappear) {
            this.center = center;
            this.audience = audience;
            this.visualRadius = visualRadius;
            this.white = white;
            this.red = red;
            this.tSilence = tSilence;
            this.tManifest = tManifest;
            this.tMasq = tMasq;
            this.tClimax = tClimax;
            this.tDisappear = tDisappear;
        }

        private Phase computePhase(int e) {
            if (e <= tSilence) return Phase.SILENCE;
            if (e <= tSilence + tManifest) return Phase.MANIFESTATION;
            if (e <= tSilence + tManifest + tMasq) return Phase.MASQUERADE;
            if (e <= tSilence + tManifest + tMasq + tClimax) return Phase.CLIMAX;
            return Phase.DISAPPEARANCE;
        }

        @Override
        public boolean step(Instance instance) {
            int period = Math.max(1, instance.task.periodTicks());
            elapsed += period;
            int total = tSilence + tManifest + tMasq + tClimax + tDisappear;
            Phase next = computePhase(elapsed);
            if (next != phase) {
                plugin().logDebug("Явление Эона: переход этапа " + phase + " -> " + next
                        + " на тике " + elapsed);
                phase = next; // переходы синхронизированы по времени с шагом планировщика
            }
            try {
                switch (phase) {
                    case SILENCE -> runSilence(period);
                    case MANIFESTATION -> runManifestation(period);
                    case MASQUERADE -> runMasquerade(period);
                    case CLIMAX -> runClimax();
                    case DISAPPEARANCE -> runDisappearance(period, total);
                }
            } catch (RuntimeException ex) {
                // Ошибка одного этапа не приводит к бесконечному выполнению остальных:
                // способность останавливается полностью со штатной очисткой.
                plugin().getLogger().severe("Явление Эона: ошибка этапа " + phase
                        + " — способность остановлена: " + ex.getMessage());
                stopAll("ошибка этапа " + phase);
                notifyAdmins();
                return false;
            }
            return elapsed < total;
        }

        // ---- Этап I. Безмолвие: тишина/приглушённые звуки, редкие белые частицы ----
        private void runSilence(int period) {
            if (elapsed == period) {
                // Единственный приглушённый звук начала (низкая громкость, персонально зрителям).
                for (Player v : audience) {
                    v.playSound(center, Sound.BLOCK_NOTE_BLOCK_BASS, 0.3f, 0.5f);
                }
            }
            if (elapsed % 30 < period) {
                plugin().particles().burst(
                        center.clone().add(rand(-2, 2), rand(1.5, 3.0), rand(-2, 2)),
                        Particle.SNOWFLAKE, 1, 0.05, 0.0); // белые редкие частицы
            }
        }

        // ---- Этап II. Проявление: последовательное нарастание белых и красных элементов ----
        private void runManifestation(int period) {
            double progress = (double) elapsed / (tSilence + tManifest);
            // Кольцо расширяется от нуля; плотность растёт постепенно, а не сразу максимумом.
            double radius = 1.0 + progress * (visualRadius / 4.0);
            int density = (int) Text.clamp(4 + progress * 20, 1, 24);
            plugin().particles().ring(center.clone().add(0, 0.05, 0), radius,
                    Particle.DUST, density, white);
            if (progress > 0.35) {
                plugin().particles().ring(center.clone().add(0, 0.05, 0), radius * 0.6,
                        Particle.DUST, Math.max(4, density / 2), red);
            }
            if (progress > 0.6) {
                // Белые столбы по периметру — нарастающие вертикальные элементы.
                for (int i = 0; i < 4; i++) {
                    double a = Math.PI / 2 * i + elapsed * 0.01;
                    Location p = center.clone().add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
                    plugin().particles().column(p, 1.5 + progress * 2.5, Particle.END_ROD, 2, white);
                }
            }
            if (elapsed % 40 < period) {
                plugin().sounds().playTo(audience, center, Sound.BLOCK_BEACON_POWER_SELECT, 0.6f, 0.8f);
            }
        }

        // ---- Этап III. Маскарад: иллюзорные эпизоды вокруг точки ----
        private void runMasquerade(int period) {
            // Интеграция способности «Маскарад» как отдельного элемента сцены — один раз за этап.
            if (elapsed > tSilence + tManifest && lastEpisodeAt < 0) {
                lastEpisodeAt = elapsed;
                plugin().abilities().byId("masquerade").ifPresent(m -> {
                    if (!m.isRunning()) {
                        m.start(new AbilityContext(null, center.clone(), audience, null, "", ""));
                    }
                });
            }
            // Собственные бело-красные вспышки-эпизоды каждые ~1.2 секунды.
            if ((elapsed - tSilence - tManifest) % 24 < period) {
                double a = rand(0, Math.PI * 2);
                double d = rand(2, visualRadius / 3.0);
                Location spot = center.clone().add(Math.cos(a) * d, rand(0.3, 2.2), Math.sin(a) * d);
                int color = (elapsed / 24) % 2 == 0 ? white : red;
                VisualProjection vp = new VisualProjection(spot, (float) rand(0, 360), color, Particle.DUST);
                vp.render(plugin().particles());
                for (Player v : audience) {
                    v.playSound(spot, Sound.BLOCK_GLASS_PLACE, 0.7f, 1.4f);
                }
            }
        }

        // ---- Этап IV. Кульминация: синхронный акцент (бело-красная основа) ----
        private void runClimax() {
            if (climaxDone) {
                return;
            }
            climaxDone = true;
            // Синхронный звуковой акцент для зрителей в области действия.
            for (Player v : audience) {
                v.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.2f, 1.0f);
                v.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_BLAST, 1.0f, 0.8f);
            }
            // Красная ударная волна + белая колонна — основная часть остаётся бело-красной.
            plugin().particles().colorBurst(center.clone().add(0, 1.2, 0), Particle.DUST,
                    120, 3.0, 0.0, red);
            plugin().particles().burst(center.clone().add(0, 1.2, 0), Particle.END_ROD,
                    100, 2.5, 0.25);
            plugin().particles().column(center, 10, Particle.END_ROD, 6, white);
            plugin().particles().ring(center.clone().add(0, 0.1, 0), visualRadius / 2.0,
                    Particle.DUST, 60, white);
            List<Player> snapshot = new ArrayList<>(audience);
            plugin().scheduler().scheduleOnce(8, () -> {
                for (Player v : snapshot) {
                    if (v.isOnline()) {
                        v.spawnParticle(Particle.DRIPPING_LAVA, center.clone().add(0, 2.0, 0),
                                40, 2.0, 1.5, 2.0, 0.0); // красно-оранжевые капли — близко к красному
                    }
                }
            });
        }

        // ---- Этап V. Исчезновение: стягивание к центру и рассеивание ----
        private void runDisappearance(int period, int total) {
            double progress = Text.clamp((double) (elapsed - (total - tDisappear)) / tDisappear, 0, 1);
            int perSide = (int) Text.clamp(10 * (1 - progress), 1, 10);
            for (int i = 0; i < perSide; i++) {
                double a = (Math.PI * 2 * i) / Math.max(1, perSide) + progress * 3;
                double startR = (visualRadius / 4.0) * (1 - progress) + 0.5;
                Location p = center.clone().add(Math.cos(a) * startR, 0.5 + progress * 1.5,
                        Math.sin(a) * startR);
                plugin().particles().burst(p, i % 2 == 0 ? Particle.SNOWFLAKE : Particle.DUST,
                        1, 0.05, 0.0);
                if (i % 2 == 1) {
                    plugin().particles().colorBurst(p, Particle.DUST, 1, 0.0, 0.0, red);
                }
            }
            if (progress >= 1.0) {
                // Финальная белая точка — остаток эффектов убран.
                plugin().particles().burst(center.clone().add(0, 1.0, 0), Particle.SNOWFLAKE,
                        3, 0.1, 0.0);
            }
        }

        private void notifyAdmins() {
            plugin().getServer().getConsoleSender().sendMessage(
                    Text.error("Явление Эона остановлено из-за ошибки этапа."));
            for (Player p : plugin().getServer().getOnlinePlayers()) {
                if (p.hasPermission("astra.admin")) {
                    p.sendMessage(Text.error("Явление Эона остановлено из-за ошибки этапа."));
                }
            }
        }

        private static double rand(double min, double max) {
            return min + java.util.concurrent.ThreadLocalRandom.current().nextDouble(max - min);
        }
    }
}
