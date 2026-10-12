package ru.astra.ability.impl;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import ru.astra.AstraPlugin;
import ru.astra.effect.ParticleService;
import ru.astra.util.Text;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Маскарад: управляемая иллюзия, отвлекающая внимание выбранных зрителей.
 * Последовательность иллюзорных эпизодов: звук в заданной точке, вспышки частиц,
 * кратковременные визуальные проекции. Полноценная фальшивая сущность, видимая
 * только зрителям, требует пакетной реализации (fake-entity-enabled=false —
 * используется упрощённый визуальный режим из частиц).
 */
public final class MasqueradeAbility extends TaskAbility {

    public MasqueradeAbility(AstraPlugin plugin) {
        super(plugin, "masquerade", "Маскарад",
                "Управляемая иллюзия, отвлекающая внимание зрителей.",
                RepeatPolicy.RESTART_CURRENT);
    }

    @Override
    protected void doStart(AbilityContext ctx) {
        var cfg = plugin().config();
        var sec = cfg.ability(id());
        int searchRadius = cfg.sectionInt(sec, "search-radius", 64, 4, 128);
        int maxViewers = cfg.sectionInt(sec, "max-viewers", 10, 1, 50);
        int soundRadius = cfg.sectionInt(sec, "sound-radius", 8, 1, 32);
        int minDelay = cfg.sectionInt(sec, "min-delay-seconds", 1, 0, 10);
        int maxDelay = cfg.sectionInt(sec, "max-delay-seconds", 3, minDelay, 15);
        int minDuration = cfg.sectionInt(sec, "min-duration-seconds", 3, 1, 30);
        int maxDuration = cfg.sectionInt(sec, "max-duration-seconds", 8, minDuration, 60);
        int episodes = cfg.sectionInt(sec, "episodes", 2, 1, 8);
        boolean fakeEntity = cfg.sectionBool(sec, "fake-entity-enabled", false);

        Location anchor = ctx.point != null ? ctx.point
                : (ctx.target != null ? ctx.target.getLocation() : null);
        if (anchor == null && ctx.caster != null) {
            anchor = ctx.caster.getLocation();
        }
        if (anchor == null) {
            return;
        }
        List<Player> viewers = ctx.viewers.isEmpty()
                ? plugin().targetSelector().playersNear(anchor, searchRadius, maxViewers)
                : ctx.viewers.stream().limit(maxViewers).toList();

        if (fakeEntity) {
            // Пакетная реализация фальшивой сущности не включена в сборку:
            // способность честно работает в упрощённом визуальном режиме.
            plugin().getLogger().warning("Маскарад: fake-entity-enabled=true, но пакетная реализация"
                    + " фальшивых сущностей не входит в сборку. Использован упрощённый режим.");
        }
        if (!viewers.isEmpty()) {
            viewers.get(0).sendMessage(Text.msg("Маскарад: иллюзорные эпизоды запущены ("
                    + episodes + " шт., упрощённый визуальный режим)."));
        }

        int totalTicks = ThreadLocalRandom.current().nextInt(minDuration, maxDuration + 1) * 20;
        final Location center = anchor.clone();
        final List<Player> audience = viewers;
        final int episodeTicks = Math.max(20, totalTicks / Math.max(1, episodes));

        launch(episodeTicks / 4, totalTicks, new EpisodeBody(center, audience, episodes,
                episodeTicks, soundRadius, minDelay, maxDelay));
    }

    /** Тело: серия эпизодов со звуком, вспышками и проекциями-миганиями. */
    private final class EpisodeBody implements Body {
        private final Location center;
        private final List<Player> audience;
        private final int episodes;
        private final int episodeTicks;
        private final int soundRadius;
        private final int minDelay;
        private final int maxDelay;
        private int elapsed = 0;
        private int nextEpisodeAt;
        private int episodeIndex = 0;

        EpisodeBody(Location center, List<Player> audience, int episodes, int episodeTicks,
                    int soundRadius, int minDelay, int maxDelay) {
            this.center = center;
            this.audience = audience;
            this.episodes = episodes;
            this.episodeTicks = episodeTicks;
            this.soundRadius = soundRadius;
            this.minDelay = minDelay;
            this.maxDelay = maxDelay;
            this.nextEpisodeAt = randomDelay();
        }

        private int randomDelay() {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            return rnd.nextInt(minDelay * 20, Math.max(minDelay * 20 + 1, maxDelay * 20 + 1));
        }

        @Override
        public boolean step(Instance instance) {
            elapsed += Math.max(1, instance.task.periodTicks());
            if (elapsed >= nextEpisodeAt && episodeIndex < episodes) {
                playEpisode();
                episodeIndex++;
                nextEpisodeAt = elapsed + episodeTicks + randomDelay();
            }
            return elapsed < instance.ticksLeft + episodeTicks;
        }

        private void playEpisode() {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            double angle = rnd.nextDouble(0, Math.PI * 2);
            double dist = rnd.nextDouble(1.5, 4.5);
            Location spot = center.clone().add(Math.cos(angle) * dist, rnd.nextDouble(0.2, 1.2),
                    Math.sin(angle) * dist);

            Sound[] sounds = {Sound.BLOCK_NOTE_BLOCK_BELL, Sound.ENTITY_PLAYER_HURT,
                    Sound.BLOCK_AMETHYST_BLOCK_RESONATE};
            Sound s = sounds[rnd.nextInt(sounds.length)];
            // Звук действует в заданном радиусе: персонально зрителям рядом с точкой.
            for (Player v : audience) {
                if (v.getWorld().equals(spot.getWorld())
                        && v.getLocation().distance(spot) <= soundRadius * 4) {
                    v.playSound(spot, s, 1.0f, 0.9f + rnd.nextFloat(0.3f));
                }
            }
            // Вспышка + контур «фигуры» на короткое время (упрощённый визуальный режим).
            VisualProjection projection = new VisualProjection(spot, (float) rnd.nextDouble(0, 360),
                    ParticleService.AEON_WHITE, Particle.END_ROD);
            projection.render(plugin().particles());
            plugin().particles().burst(spot.clone().add(0, 1, 0), Particle.CLOUD, 15, 0.4, 0.05);
            plugin().scheduler().scheduleOnce(10, () ->
                    plugin().particles().burst(spot.clone().add(0, 1, 0), Particle.POOF, 12, 0.3, 0.0));
        }
    }
}
