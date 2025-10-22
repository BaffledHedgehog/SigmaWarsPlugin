package me.luckywars.bedwars;

import me.luckywars.bedwars.util.DyeColorOfTeam;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Team;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Тимовые баффы от «маяков», действуют пока у команды есть тэг have_bed. */
public final class BeaconEffectsManager {

    public enum Buff {
        SPEED2, JUMP2, FIRE_RES, STRENGTH2, HASTE2, REGEN2, RESISTANCE1
    }

    // team -> (buff -> secondsLeft)
    private static final Map<String, EnumMap<Buff, Integer>> teamTimers = new HashMap<>();

    private BeaconEffectsManager() {
    }

    public static void start(JavaPlugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            // тик таймеров (раз в секунду)
            for (var e : teamTimers.entrySet()) {
                var m = e.getValue();
                for (var b : Buff.values()) {
                    m.put(b, Math.max(0, m.getOrDefault(b, 0) - 1));
                }
            }
            // раздать эффекты игрокам их команд (если есть кровать)
            for (Player p : Bukkit.getOnlinePlayers()) {
                Team t = DyeColorOfTeam.teamOf(p);
                if (t == null)
                    continue;
                if (!teamHasBed(t))
                    continue;

                var m = teamTimers.get(t.getName());
                if (m == null)
                    continue;

                applyIf(p, m, Buff.SPEED2, PotionEffectType.SPEED, 1);
                applyIf(p, m, Buff.JUMP2, PotionEffectType.JUMP_BOOST, 2);
                applyIf(p, m, Buff.FIRE_RES, PotionEffectType.FIRE_RESISTANCE, 0);
                applyIf(p, m, Buff.STRENGTH2, PotionEffectType.STRENGTH, 1);
                applyIf(p, m, Buff.HASTE2, PotionEffectType.HASTE, 1);
                applyIf(p, m, Buff.REGEN2, PotionEffectType.REGENERATION, 1);
                applyIf(p, m, Buff.RESISTANCE1, PotionEffectType.RESISTANCE, 0);
            }
        }, 20L, 20L);
    }

    /**
     * Полный сброс всех «маяков» и их эффектов. Вызывайте при /bedwars init и
     * /bedwars stop.
     */
    public static void resetAll() {
        teamTimers.clear();
        // мгновенно снимем возможные активные эффекты от маяков
        for (Player p : Bukkit.getOnlinePlayers()) {
            clearBeaconEffects(p);
        }
        Bukkit.getLogger().info("[beacon] resetAll: timers cleared and effects removed from all players");
    }

    private static void clearBeaconEffects(Player p) {
        p.removePotionEffect(PotionEffectType.SPEED);
        p.removePotionEffect(PotionEffectType.JUMP_BOOST);
        p.removePotionEffect(PotionEffectType.FIRE_RESISTANCE);
        p.removePotionEffect(PotionEffectType.STRENGTH);
        p.removePotionEffect(PotionEffectType.HASTE);
        p.removePotionEffect(PotionEffectType.REGENERATION);
        p.removePotionEffect(PotionEffectType.RESISTANCE);
    }

    private static boolean teamHasBed(Team t) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Team pt = DyeColorOfTeam.teamOf(p);
            if (t.equals(pt) && p.getScoreboardTags().contains("have_bed")) {
                return true;
            }
        }
        return false;
    }

    private static void applyIf(Player p, EnumMap<Buff, Integer> timers, Buff key, PotionEffectType type,
            int amplifier) {
        int secondsLeft = timers.getOrDefault(key, 0);
        if (secondsLeft > 0) {
            p.addPotionEffect(new PotionEffect(type, secondsLeft * 20, amplifier, true, false, true));
        }
    }

    public static void addTimeForTeam(Player p, Buff buff, int seconds) {
        Team t = DyeColorOfTeam.teamOf(p);
        if (t == null)
            return;

        var map = teamTimers.computeIfAbsent(t.getName(), k -> new EnumMap<>(Buff.class));
        int prev = map.getOrDefault(buff, 0);
        int add = Math.max(0, seconds);
        int now = prev + add;
        map.put(buff, now);

        Bukkit.getLogger().info("[beacon] +" + add + "s " + buff + " for team " + t.getName() + " -> now " + now + "s");
    }
}
