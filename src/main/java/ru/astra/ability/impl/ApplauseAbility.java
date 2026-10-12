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
 * Аплодисменты: синхронизированная короткая сцена из четырёх этапов —
 * прелюдия (2с), развитие (3с), кульминация (1с), завершение (2с).
 * Поддерживает досрочную остановку; глобальный звук только при явном режиме.
 */
public final class ApplauseAbility extends TaskAbility {

    public ApplauseAbility(AstraPlugin plugin) {
        super(plugin, "applause", "Аплодисменты",
                "Синхронизированная сцена с нарастанием напряжения и кульминацией.",
                RepeatPolicy.RESTART_CURRENT);
    }

    @Override
    protected void doStart(AbilityContext ctx) {
        var cfg = plugin().config();
        var sec = cfg.ability(id());
        int maxViewers = cfg.sectionInt(sec, "max-viewers", 50, 1, 200);
        double radius = cfg.sectionInt(sec, "radius", 32, 4, 64);
        int prelude = cfg.sectionInt(sec, "stage-prelude", 2, 1, 10);
        int development = cfg.sectionInt(sec, "stage-development", 3, 1, 10);
        int climax = cfg.sectionInt(sec, "stage-climax", 1, 1, 5);
        int finale = cfg.sectionInt(sec, "stage-finale", 2, 1, 10);
        Particle particles = Text.particleOrDefault(cfg.sectionString(sec, "particles", "END_ROD"),
                Particle.END_ROD);
        Particle climaxParticles = Text.particleOrDefault(
                cfg.sectionString(sec, "climax-particles", "END_ROD"), Particle.END_ROD);
        Sound sound = Text.soundOrDefault(cfg.sectionString(sec, "sound", "ENTITY_FIREWORK_ROCKET_BLAST"),
                Sound.ENTITY_FIREWORK_ROCKET_BLAST);
        float volume = (float) cfg.sectionDouble(sec, "volume", 1.0, 0.0, 4.0);
        float pitch = (float) cfg.sectionDouble(sec, "pitch", 1.0, 0.5, 2.0);
        boolean globalSound = cfg.sectionBool(sec, "global-sound", false);

        Location anchor = ctx.point != null ? ctx.point
                : (ctx.target != null ? ctx.target.getLocation()
                : (ctx.caster != null ? ctx.caster.getLocation() : null));
        if (anchor == null) {
            return;
        }
        List<Player> viewers = ctx.viewers.isEmpty()
                ? plugin().targetSelector().playersNear(anchor, radius, maxViewers)
                : ctx.viewers.stream().limit(maxViewers).toList();

        int totalTicks = (prelude + development + climax + finale) * 20;
        launch(2, totalTicks, new StageBody(anchor.clone(), viewers, prelude * 20,
                development * 20, climax * 20, finale * 20, particles, climaxParticles,
                sound, volume, pitch, globalSound));
    }

    private final class StageBody implements Body {
        private final Location center;
        private final List<Player> audience;
        private final int preludeTicks, devTicks, climaxTicks, finaleTicks;
        private final Particle baseParticle, climaxParticle;
        private final Sound climaxSound;
        private final float volume, pitch;
        private final boolean globalSound;
        private int elapsed = 0;
        private boolean climaxDone = false;

        StageBody(Location center, List<Player> audience, int preludeTicks, int devTicks,
                  int climaxTicks, int finaleTicks, Particle baseParticle, Particle climaxParticle,
                  Sound climaxSound, float volume, float pitch, boolean globalSound) {
            this.center = center;
            this.audience = audience;
            this.preludeTicks = preludeTicks;
            this.devTicks = devTicks;
            this.climaxTicks = climaxTicks;
            this.finaleTicks = finaleTicks;
            this.baseParticle = baseParticle;
            this.climaxParticle = climaxParticle;
            this.climaxSound = climaxSound;
            this.volume = volume;
            this.pitch = pitch;
            this.globalSound = globalSound;
        }

        @Override
        public boolean step(Instance instance) {
            elapsed += Math.max(1, instance.task.periodTicks());
            int period = Math.max(1, instance.task.periodTicks());
            double total = preludeTicks + devTicks + climaxTicks + finaleTicks;

            if (elapsed <= preludeTicks) {
                // I. Прелюдия: редкие визуальные эффекты + приглушённый звук подготовки.
                if (elapsed % 20 < period) {
                    plugin().particles().burst(center.clone().add(0, 1.5, 0), baseParticle, 4, 1.2, 0.0);
                }
            } else if (elapsed <= preludeTicks + devTicks) {
                // II. Развитие: плотность растёт линейно.
                double progress = (double) (elapsed - preludeTicks) / devTicks;
                int density = (int) Text.clamp(4 + progress * 24, 1,
                        plugin().config().maxParticlesPerBurst);
                double ringR = 3 + progress * 3;
                plugin().particles().ring(center.clone().add(0, 0.1, 0), ringR, baseParticle,
                        density, ParticleService.AEON_WHITE);
                plugin().particles().sphere(center.clone().add(0, 1.2, 0), ringR * 0.6,
                        baseParticle, (int) (density / 2), ParticleService.AEON_RED);
            } else if (elapsed <= preludeTicks + devTicks + climaxTicks) {
                // III. Кульминация: синхронный акцент — вспышка, частицы, звук.
                if (!climaxDone) {
                    climaxDone = true;
                    for (Player v : audience) {
                        v.playSound(center, climaxSound, volume, pitch);
                    }
                    if (globalSound) {
                        plugin().sounds().playGlobal(climaxSound, volume, pitch);
                    }
                    if (climaxParticle == Particle.DUST || climaxParticle == Particle.DUST_COLOR_TRANSITION) {
                        plugin().particles().colorBurst(center.clone().add(0, 1.5, 0), climaxParticle,
                                80, 2.5, 0.0, ParticleService.AEON_RED);
                    } else {
                        plugin().particles().burst(center.clone().add(0, 1.5, 0), climaxParticle,
                                80, 2.5, 0.15);
                    }
                    plugin().particles().colorBurst(center.clone().add(0, 1.5, 0), Particle.DUST,
                            40, 1.5, 0.0, ParticleService.AEON_RED);
                    List<Player> snapshot = new ArrayList<>(audience);
                    plugin().scheduler().scheduleOnce(6, () -> {
                        for (Player v : snapshot) {
                            if (v.isOnline()) {
                                if (climaxParticle == Particle.DUST || climaxParticle == Particle.DUST_COLOR_TRANSITION) {
                                    v.spawnParticle(climaxParticle, center.clone().add(0, 1.5, 0),
                                            40, 2.0, 1.0, 2.0, ParticleService.dustOptions(ParticleService.AEON_RED));
                                } else {
                                    v.spawnParticle(climaxParticle, center.clone().add(0, 1.5, 0),
                                            40, 2.0, 1.0, 2.0, 0.2);
                                }
                            }
                        }
                    });
                }
            } else if (elapsed <= total) {
                // IV. Завершение: постепенное исчезновение.
                double fade = (double) (elapsed - preludeTicks - devTicks - climaxTicks) / finaleTicks;
                int count = (int) Text.clamp(14 * (1 - fade), 1, 14);
                plugin().particles().ring(center.clone().add(0, 0.1, 0), 4 + fade * 4,
                        baseParticle, count, ParticleService.AEON_WHITE);
            }
            return elapsed < total;
        }
    }
}
