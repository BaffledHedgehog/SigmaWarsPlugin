package me.luckywars.item;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
//import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public class MetalPipe implements Listener {
    //private final JavaPlugin plugin;
    private final NamespacedKey itemKey, typeKey;
    private final Map<UUID, Boolean> trackedItems = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> throwers     = new ConcurrentHashMap<>();

    public MetalPipe(JavaPlugin plugin) {
        //this.plugin  = plugin;
        this.itemKey = new NamespacedKey(plugin, "item");
        this.typeKey = new NamespacedKey(plugin, "type");

        //plugin.getLogger().info("MetalPipe listener registered; watching for tag: "
        //    + itemKey + " → " + typeKey);

        // Per‐tick ground‐check
        new BukkitRunnable() {
            @Override
            public void run() {
                if (trackedItems.isEmpty()) return;
                for (Iterator<Map.Entry<UUID, Boolean>> it = trackedItems.entrySet().iterator(); it.hasNext();) {
                    var entry = it.next();
                    UUID id = entry.getKey();
                    boolean wasOnGround = entry.getValue();

                    Entity e = plugin.getServer().getEntity(id);
                    if (!(e instanceof Item item) || !item.isValid()) {
                        //plugin.getLogger().info("Removing invalid/traded‐away item " + id);
                        it.remove();
                        throwers.remove(id);
                        continue;
                    }

                    boolean nowOnGround = item.isOnGround();
                    if (!wasOnGround && nowOnGround) {
                        //plugin.getLogger().info("MetalPipe landed: " + id);
                        handleLanding(item);
                        it.remove();
                        throwers.remove(id);
                    } else {
                        entry.setValue(nowOnGround);
                    }
                }
            }
        }.runTaskTimer(plugin, 1, 1);
    }

    @EventHandler
    public void onPlayerDrop(PlayerDropItemEvent e) {
        Item drop = e.getItemDrop();
        UUID id = drop.getUniqueId();
        //plugin.getLogger().info("onPlayerDrop: " + id);

        // *** Read the stack’s PDC, not the entity’s! ***
        PersistentDataContainer stackPdc =
            drop.getItemStack().getItemMeta().getPersistentDataContainer();

        if (!stackPdc.has(itemKey, PersistentDataType.TAG_CONTAINER)) {
            //plugin.getLogger().info("  → no TAG_CONTAINER under " + itemKey);
            return;
        }

        var nested = stackPdc.get(itemKey, PersistentDataType.TAG_CONTAINER);
        String type = nested.get(typeKey, PersistentDataType.STRING);
        //plugin.getLogger().info("  → found nested type=" + type);

        if (!"metal_pipe".equals(type)) {
            //plugin.getLogger().info("  → not a metal_pipe, skipping");
            return;
        }

        drop.setPickupDelay(Integer.MAX_VALUE);
        trackedItems.put(id, false);
        throwers.put(id, e.getPlayer().getUniqueId());
        //plugin.getLogger().info("  → now tracking metal_pipe drop=" + id
        //    + " thrower=" + e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onSpawn(EntitySpawnEvent e) {
        if (!(e.getEntity() instanceof Item item)) return;
        UUID id = item.getUniqueId();
        //plugin.getLogger().info("onSpawn: " + id);

        PersistentDataContainer stackPdc =
            item.getItemStack().getItemMeta().getPersistentDataContainer();

        if (!stackPdc.has(itemKey, PersistentDataType.TAG_CONTAINER)) {
            //plugin.getLogger().info("  → no TAG_CONTAINER under " + itemKey);
            return;
        }

        var nested = stackPdc.get(itemKey, PersistentDataType.TAG_CONTAINER);
        String type = nested.get(typeKey, PersistentDataType.STRING);
        //plugin.getLogger().info("  → found nested type=" + type);

        if (!"metal_pipe".equals(type)) {
            //plugin.getLogger().info("  → not a metal_pipe, skipping");
            return;
        }

        item.setPickupDelay(Integer.MAX_VALUE);
        trackedItems.put(id, false);
        throwers.remove(id);
        //plugin.getLogger().info("  → now tracking metal_pipe spawn=" + id);
    }

    private void handleLanding(Item item) {
        //plugin.getLogger().info("handleLanding: " + item.getUniqueId());
        item.setPickupDelay(20);
        item.getWorld().playSound(
            item.getLocation(),
            "lbcsounds.metal_pipe",
            4.0f, 1.0f
        );

        UUID shooterId = throwers.get(item.getUniqueId());
        for (Entity near : item.getNearbyEntities(2.5, 2.5, 2.5)) {
            if (!(near instanceof LivingEntity victim)) continue;

            if (shooterId != null) {
                Player shooter = Bukkit.getPlayer(shooterId);
                victim.damage(25.0, shooter != null ? shooter : item);
            } else {
                victim.damage(25.0, item);
            }
        }
    }
}
