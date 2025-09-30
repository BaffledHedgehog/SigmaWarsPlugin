package me.luckywars.bedwars;

import org.bukkit.entity.LightningStrike;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.EntityTransformEvent;

public final class ShopMobIgnoreListener implements Listener {

    private static boolean isShopVillager(LivingEntity e) {
        return (e instanceof Villager) && e.getScoreboardTags().contains("bedwars_shop");
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        LivingEntity tgt = e.getTarget();
        if (tgt != null && isShopVillager(tgt)) {
            e.setCancelled(true);
            e.setTarget(null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (e.getEntity() instanceof Villager v && isShopVillager(v)) {
            // Мобы и сама сущность молнии как "дамежер"
            if (e.getDamager() instanceof Monster || e.getDamager() instanceof LightningStrike) {
                e.setCancelled(true);
            }
        }
    }

    // Урон от молнии (случай, когда "дамежера" нет, только причина LIGHTNING)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onLightningDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Villager v && isShopVillager(v)
                && e.getCause() == EntityDamageEvent.DamageCause.LIGHTNING) {
            e.setCancelled(true);
            v.setFireTicks(0);
        }
    }

    // Поджог молнией (зажигающим "дамежером" будет LightningStrike)
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onCombustByLightning(EntityCombustByEntityEvent e) {
        if (e.getEntity() instanceof Villager v && isShopVillager(v)
                && e.getCombuster() instanceof LightningStrike) {
            e.setCancelled(true);
            v.setFireTicks(0);
        }
    }

    // Блокируем превращение Villager -> Witch из-за удара молнии
    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onTransform(EntityTransformEvent e) {
        if (e.getEntity() instanceof Villager v && isShopVillager(v)
                && e.getTransformReason() == EntityTransformEvent.TransformReason.LIGHTNING) {
            e.setCancelled(true);
        }
    }
}
