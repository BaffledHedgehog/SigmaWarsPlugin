package me.luckywars.bedwars;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scoreboard.Team;

import java.util.EnumSet;
import java.util.Set;

public final class BedwarsDropsListener implements Listener {
    private final BedwarsRegionManager region;

    // Разрешённые предметы, всё остальное удаляем из дропа
    private static final Set<Material> WHITELIST = EnumSet.of(
            Material.COPPER_INGOT, Material.COPPER_BLOCK,
            Material.IRON_INGOT, Material.IRON_BLOCK,
            Material.GOLD_INGOT, Material.GOLD_BLOCK,
            Material.DIAMOND, Material.DIAMOND_BLOCK,
            Material.NETHER_STAR
    );

    public BedwarsDropsListener(BedwarsRegionManager region) {
        this.region = region;
    }

    /* ========== DROP FILTER ========== */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // Работает ТОЛЬКО во время активной игры
        if (region == null || !region.isEnabled()) return;

        // Фильтруем дроп: оставляем только whitelisted
        event.getDrops().removeIf(stack -> {
            if (stack == null) return true;
            Material m = stack.getType();
            if (m.isAir()) return true;
            return !WHITELIST.contains(m);
        });
    }

    /* ========== FRIENDLY-FIRE BLOCK ========== */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFriendlyFire(EntityDamageByEntityEvent e) {
        if (region == null || !region.isEnabled()) return;

        if (!(e.getEntity() instanceof Player victim)) return;

        Player attacker = null;

        // прямой урон от игрока
        if (e.getDamager() instanceof Player p) {
            attacker = p;
        }
        // стрелы/снежки/триндент/зелья и т.п.
        else if (e.getDamager() instanceof Projectile proj) {
            ProjectileSource src = proj.getShooter();
            if (src instanceof Player p) attacker = p;
        }
        // TNT, где источником был игрок
        else if (e.getDamager() instanceof TNTPrimed tnt) {
            var src = tnt.getSource();
            if (src instanceof Player p) attacker = p;
        }
        // облако эффектов (последствия взрывных/зельев)
        else if (e.getDamager() instanceof AreaEffectCloud cloud) {
            ProjectileSource src = cloud.getSource();
            if (src instanceof Player p) attacker = p;
        }

        if (attacker == null) return;

        if (sameTeam(attacker, victim)) {
            e.setCancelled(true);
            e.setDamage(0.0);
        }
    }

    private static boolean sameTeam(Player a, Player b) {
        String ta = teamNameOf(a);
        String tb = teamNameOf(b);
        return ta != null && ta.equals(tb);
    }

    private static String teamNameOf(Player p) {
        var mgr = Bukkit.getScoreboardManager();
        if (mgr == null) return null;
        org.bukkit.scoreboard.Scoreboard sb = mgr.getMainScoreboard();
        Team t = sb.getEntryTeam(p.getName());
        return (t != null) ? t.getName() : null;
    }
}
